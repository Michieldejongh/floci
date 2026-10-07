package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appconfig.AppConfigService;
import io.github.hectorvent.floci.services.appconfig.model.Application;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.Environment;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * CloudFormation provisioning for AppConfig: {@code AWS::AppConfig::Application},
 * {@code AWS::AppConfig::Environment} and {@code AWS::AppConfig::ConfigurationProfile}.
 *
 * <p>{@code Ref} is the resource's own id, as on AWS, so the ids feed straight into the native
 * API ({@code CreateHostedConfigurationVersion}, {@code StartDeployment}). Properties the
 * AppConfig model does not hold ({@code Monitors}, {@code Validators}, {@code RetrievalRoleArn},
 * {@code KmsKeyIdentifier}, {@code DeletionProtectionCheck}, and tags on environments and
 * profiles) are accepted and ignored.
 */
@ApplicationScoped
public class AppConfigCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(AppConfigCfnProvisioner.class);
    private static final String APPLICATION = "AWS::AppConfig::Application";
    private static final String ENVIRONMENT = "AWS::AppConfig::Environment";
    private static final String CONFIGURATION_PROFILE = "AWS::AppConfig::ConfigurationProfile";
    private static final String NOT_FOUND = "ResourceNotFoundException";
    /** What AppConfig reports for a profile created without a Type. */
    private static final String DEFAULT_PROFILE_TYPE = "AWS.Freeform";

    private final AppConfigService appConfigService;

    @Inject
    public AppConfigCfnProvisioner(AppConfigService appConfigService) {
        this.appConfigService = appConfigService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(APPLICATION, ENVIRONMENT, CONFIGURATION_PROFILE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        switch (r.getResourceType()) {
            case APPLICATION -> provisionApplication(r, props, ctx);
            case ENVIRONMENT -> provisionEnvironment(r, props, ctx);
            case CONFIGURATION_PROFILE -> provisionConfigurationProfile(r, props, ctx);
            default -> throw new IllegalStateException("AppConfigCfnProvisioner cannot handle " + r.getResourceType());
        }
        // An Environment or ConfigurationProfile whose createOnly property changed got a new id;
        // the displaced one is deleted once the stack update commits.
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private void provisionApplication(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, Object> request = nameAndDescription(props, ctx);
        // provision is also the update path: Name, Description and Tags all update in place. A prior
        // id that resolves to nothing (a dispatcher stub, or an application deleted out of band)
        // gets a fresh application instead.
        Application app = ctx.isUpdate()
                ? find(() -> appConfigService.getApplication(ctx.priorPhysicalId())).orElse(null)
                : null;
        app = app == null
                ? appConfigService.createApplication(request)
                : appConfigService.updateApplication(app.getId(), request);
        reconcileApplicationTags(app.getId(), ctx.resolveTags(props, "Tags"));
        r.setPhysicalId(app.getId());
        r.getAttributes().put("ApplicationId", app.getId());
    }

    private void provisionEnvironment(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, Object> request = nameAndDescription(props, ctx);
        String appId = applicationId(props, ctx);
        // ApplicationId is createOnly: an environment that is not under the (resolved) application
        // any more is not found here, so a changed ApplicationId creates a replacement.
        Environment env = ctx.isUpdate()
                ? find(() -> appConfigService.getEnvironment(appId, ctx.priorPhysicalId())).orElse(null)
                : null;
        env = env == null
                ? appConfigService.createEnvironment(appId, request)
                : appConfigService.updateEnvironment(appId, env.getId(), request);
        r.setPhysicalId(env.getId());
        r.getAttributes().put("EnvironmentId", env.getId());
        r.getAttributes().put("ApplicationId", appId);
    }

    private void provisionConfigurationProfile(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, Object> request = nameAndDescription(props, ctx);
        String appId = applicationId(props, ctx);
        String locationUri = required(props, "LocationUri", ctx);
        String type = Optional.ofNullable(present(props, "Type", ctx)).orElse(DEFAULT_PROFILE_TYPE);
        // ApplicationId, LocationUri and Type are createOnly: a change to any of them replaces.
        ConfigurationProfile profile = ctx.isUpdate()
                ? find(() -> appConfigService.getConfigurationProfile(appId, ctx.priorPhysicalId()))
                        .filter(p -> locationUri.equals(p.getLocationUri()) && Objects.equals(type, p.getType()))
                        .orElse(null)
                : null;
        if (profile == null) {
            request.put("LocationUri", locationUri);
            request.put("Type", type);
            profile = appConfigService.createConfigurationProfile(appId, request);
        } else {
            profile = appConfigService.updateConfigurationProfile(appId, profile.getId(), request);
        }
        r.setPhysicalId(profile.getId());
        r.getAttributes().put("ConfigurationProfileId", profile.getId());
        r.getAttributes().put("ApplicationId", appId);
        String kmsKeyArn = kmsKeyArn(present(props, "KmsKeyIdentifier", ctx), ctx);
        if (kmsKeyArn != null) {
            r.getAttributes().put("KmsKeyArn", kmsKeyArn);
        } else {
            r.getAttributes().remove("KmsKeyArn");
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case APPLICATION -> CfnDeletes.safeDelete("AppConfig application", physicalId,
                    () -> appConfigService.deleteApplication(physicalId), NOT_FOUND);
            // The physical id is the bare environment or profile id, as Ref is on AWS, while the
            // service addresses both under their application. A displaced replacement may sit under
            // an application other than the resource's current one, so look the owner up by id.
            case ENVIRONMENT -> owningApplication(appId -> appConfigService.listEnvironments(appId)
                    .stream().anyMatch(e -> physicalId.equals(e.getId())))
                    .ifPresentOrElse(appId -> CfnDeletes.safeDelete("AppConfig environment", physicalId,
                                    () -> appConfigService.deleteEnvironment(appId, physicalId), NOT_FOUND),
                            () -> LOG.debugv("AppConfig environment already gone: {0}", physicalId));
            case CONFIGURATION_PROFILE -> owningApplication(appId -> appConfigService
                    .listConfigurationProfiles(appId).stream().anyMatch(p -> physicalId.equals(p.getId())))
                    .ifPresentOrElse(appId -> CfnDeletes.safeDelete("AppConfig configuration profile", physicalId,
                                    () -> appConfigService.deleteConfigurationProfile(appId, physicalId), NOT_FOUND),
                            () -> LOG.debugv("AppConfig configuration profile already gone: {0}", physicalId));
            default -> throw new IllegalStateException("AppConfigCfnProvisioner cannot delete " + resourceType);
        }
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return ReplacementCleanup.rollback(resource, this::delete);
    }

    private Optional<String> owningApplication(Predicate<String> owns) {
        return appConfigService.listApplications().stream()
                .map(Application::getId)
                .filter(owns)
                .findFirst();
    }

    /** ApplicationId accepts the application's id or its name, as the registry schema documents. */
    private String applicationId(JsonNode props, ProvisionContext ctx) {
        return appConfigService.resolveApplication(required(props, "ApplicationId", ctx)).getId();
    }

    /**
     * The mutable Name and Description, the latter mapped to null when the template drops it, so
     * an update clears it as CloudFormation's desired-state semantics require.
     */
    private static Map<String, Object> nameAndDescription(JsonNode props, ProvisionContext ctx) {
        Map<String, Object> request = new HashMap<>();
        request.put("Name", required(props, "Name", ctx));
        request.put("Description", present(props, "Description", ctx));
        return request;
    }

    private void reconcileApplicationTags(String appId, Map<String, String> desired) {
        List<String> stale = ProvisionContext.staleTagKeys(appConfigService.getApplicationTags(appId), desired);
        if (!stale.isEmpty()) {
            appConfigService.untagApplication(appId, stale);
        }
        if (!desired.isEmpty()) {
            appConfigService.tagApplication(appId, desired);
        }
    }

    /**
     * The key ARN AWS reports for {@code KmsKeyIdentifier}, when it follows from the identifier
     * alone: a key ARN as given, a bare key id under this account and region. An alias names a key
     * only KMS can resolve, so it yields null and the attribute stays unset.
     */
    private static String kmsKeyArn(String identifier, ProvisionContext ctx) {
        if (identifier == null || identifier.startsWith("alias/")) {
            return null;
        }
        if (identifier.startsWith("arn:")) {
            return identifier.contains(":key/") ? identifier : null;
        }
        return AwsArnUtils.Arn.of("kms", ctx.region(), ctx.accountId(), "key/" + identifier).toString();
    }

    private static <T> Optional<T> find(Supplier<T> lookup) {
        try {
            return Optional.of(lookup.get());
        } catch (AwsException e) {
            if (!NOT_FOUND.equals(e.getErrorCode())) {
                throw e;
            }
            return Optional.empty();
        }
    }

    /** A property's resolved value, or null when absent or blank (how AWS::NoValue resolves). */
    private static String present(JsonNode props, String name, ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, name);
        return value == null || value.isBlank() ? null : value;
    }

    private static String required(JsonNode props, String name, ProvisionContext ctx) {
        String value = present(props, name, ctx);
        if (value == null) {
            // The registry schema's required key, checked before any call to the service.
            throw new AwsException("InvalidRequest",
                    "Model validation failed (#: required key [" + name + "] not found)", 400);
        }
        return value;
    }
}
