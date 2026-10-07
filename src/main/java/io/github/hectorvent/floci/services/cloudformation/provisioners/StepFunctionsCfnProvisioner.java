package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationYamlParser;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService;
import io.github.hectorvent.floci.services.stepfunctions.model.RoutingConfiguration;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachineAlias;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachineVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Provisions {@code AWS::StepFunctions::StateMachine}, {@code AWS::StepFunctions::StateMachineVersion}
 * and {@code AWS::StepFunctions::StateMachineAlias}.
 *
 * <p>Extracted from the former CloudFormation monolith. This type carried the last
 * fall-through arm in that class's five update-cleanup hooks, so moving it here reduces every
 * one of them to plain registry delegation: a replacement's bookkeeping, its retry budget and
 * its rollback now live beside the code that creates the replacement.
 *
 * <p>A state machine replaces rather than updates in place when its name changes, when the name
 * stops being generated and becomes explicit (or the reverse), or when its type changes, since
 * {@code StateMachineName} and {@code StateMachineType} are create-only in the registry schema.
 * The name mode is remembered on the resource because a generated name is otherwise
 * indistinguishable from an explicit one on the next update.
 *
 * <p>Every property of a version is create-only, so a version is either the one already published
 * or a newly published replacement. Publishing is idempotent per revision, as on AWS: a revision
 * that is already published yields the existing version ARN. An alias updates in place while its
 * ARN (state machine plus name) stays the same and is replaced otherwise. Replaced versions and
 * aliases are deleted through the {@link ReplacementCleanup} record once the update commits.
 */
@ApplicationScoped
public class StepFunctionsCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::StepFunctions::StateMachine";
    private static final String VERSION_TYPE = "AWS::StepFunctions::StateMachineVersion";
    private static final String ALIAS_TYPE = "AWS::StepFunctions::StateMachineAlias";

    private static final Logger LOG = Logger.getLogger(StepFunctionsCfnProvisioner.class);

    private static final String SFN_NAME_MODE_ATTR = "FlociStepFunctionsNameMode";
    private static final String SFN_UPDATE_SNAPSHOT_ATTR = "__FlociStepFunctionsUpdateSnapshot";
    private static final String NAME_MODE_EXPLICIT = "explicit";
    private static final String NAME_MODE_GENERATED = "generated";
    private static final int STEP_FUNCTIONS_NAME_MAX_LENGTH = 80;
    private static final String ALIAS_NAME_MODE_ATTR = "FlociStepFunctionsAliasNameMode";
    private static final String ALIAS_UPDATE_SNAPSHOT_ATTR = "__FlociStepFunctionsAliasUpdateSnapshot";
    /** Mirrors the suffix ProvisionContext.generatePhysicalName appends, so a generated name is recognisable. */
    private static final int GENERATED_NAME_SUFFIX_LENGTH = 12;

    /** Matches the non-ARN stub physical id the dispatcher's default arm writes, {@code <logicalId>-<8 hex>}. */
    private static final Pattern STUB_PHYSICAL_ID = Pattern.compile("^[A-Za-z0-9]+-[0-9a-f]{8}$");

    private final StepFunctionsService stepFunctionsService;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;

    @Inject
    public StepFunctionsCfnProvisioner(StepFunctionsService stepFunctionsService, S3Service s3Service,
                                       ObjectMapper objectMapper) {
        this.stepFunctionsService = stepFunctionsService;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE, VERSION_TYPE, ALIAS_TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (requireSupported(r.getResourceType())) {
            case VERSION_TYPE -> provisionVersion(r, props, ctx);
            case ALIAS_TYPE -> provisionAlias(r, props, ctx);
            default -> provisionStateMachine(r, props, ctx);
        }
    }

    @Override
    public void delete(StackResource resource, String region) {
        // A dispatcher stub of an alias created nothing, and its id is no ARN the service accepts.
        if (ALIAS_TYPE.equals(resource.getResourceType())
                && CfnResourceDispatcher.isStub(resource.getPhysicalId(), resource.getAttributes())) {
            return;
        }
        delete(resource.getResourceType(), resource.getPhysicalId(), region);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (requireSupported(resourceType)) {
            // The service treats a version that is gone, or an id that is no version ARN, as
            // deleted; a version an alias still routes to fails with ConflictException.
            case VERSION_TYPE -> stepFunctionsService.deleteStateMachineVersion(physicalId);
            case ALIAS_TYPE -> CfnDeletes.safeDelete("Step Functions alias", physicalId,
                    () -> stepFunctionsService.deleteStateMachineAlias(physicalId), "ResourceNotFound");
            default -> stepFunctionsService.deleteStateMachine(physicalId);
        }
    }

    private static String requireSupported(String resourceType) {
        if (!TYPE.equals(resourceType) && !VERSION_TYPE.equals(resourceType)
                && !ALIAS_TYPE.equals(resourceType)) {
            throw new IllegalStateException(
                    "StepFunctionsCfnProvisioner received an unsupported type: " + resourceType);
        }
        return resourceType;
    }

    /** Versions and aliases use the plain replacement record; state machines keep their own snapshot. */
    private static boolean usesReplacementCleanup(StackResource resource) {
        return VERSION_TYPE.equals(resource.getResourceType())
                || ALIAS_TYPE.equals(resource.getResourceType());
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        if (usesReplacementCleanup(resource)) {
            return ReplacementCleanup.complete(resource, this::delete);
        }
        String rawSnapshot = resource.getAttributes().get(SFN_UPDATE_SNAPSHOT_ATTR);
        if (rawSnapshot == null) {
            return UpdateCleanupResult.notApplicable();
        }
        try {
            JsonNode snapshot = objectMapper.readTree(rawSnapshot);
            String previousArn = snapshot.path("physicalId").asText(null);
            if (!snapshot.path("replacement").asBoolean(false)
                    || previousArn == null
                    || Objects.equals(previousArn, resource.getPhysicalId())) {
                return new UpdateCleanupResult(true, true, previousArn, 0, null);
            }
            if ("Retain".equals(resource.getUpdateReplacePolicy())) {
                return new UpdateCleanupResult(true, true, previousArn, 0, null);
            }

            int attempts = snapshot.path("cleanupAttempts").asInt(0);
            String failureReason = snapshot.path("cleanupFailureReason").asText(null);
            if (attempts >= 3) {
                return new UpdateCleanupResult(
                        true, false, previousArn, attempts, failureReason);
            }

            try {
                String previousRevisionId = snapshot.path("stateMachine")
                        .path("revisionId")
                        .asText(null);
                StateMachine cleanupTarget = findStateMachine(previousArn);
                if (cleanupTarget != null
                        && !stepFunctionsService.deleteStateMachineIfRevisionMatches(
                                previousArn, previousRevisionId)) {
                    throw new IllegalStateException(
                            "The old state machine no longer matches the replacement snapshot");
                }
                return new UpdateCleanupResult(
                        true, true, previousArn, attempts, null);
            } catch (Exception e) {
                attempts++;
                ((ObjectNode) snapshot).put("cleanupAttempts", attempts);
                ((ObjectNode) snapshot).put("cleanupFailureReason", e.getMessage());
                resource.getAttributes().put(
                        SFN_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
                return new UpdateCleanupResult(
                        true, false, previousArn, attempts, e.getMessage());
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not finalize Step Functions state machine "
                            + resource.getLogicalId(), e);
        }
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        if (usesReplacementCleanup(resource)) {
            return ReplacementCleanup.cleanupPhysicalId(resource);
        }
        if ("Retain".equals(resource.getUpdateReplacePolicy())) {
            return null;
        }
        String rawSnapshot = resource.getAttributes().get(SFN_UPDATE_SNAPSHOT_ATTR);
        if (rawSnapshot == null) {
            return null;
        }
        try {
            JsonNode snapshot = objectMapper.readTree(rawSnapshot);
            if (!snapshot.path("replacement").asBoolean(false)) {
                return null;
            }
            return snapshot.path("physicalId").asText(null);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not read Step Functions cleanup metadata for "
                            + resource.getLogicalId(), e);
        }
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        if (usesReplacementCleanup(resource)) {
            return ReplacementCleanup.hasReplacement(resource);
        }
        String rawSnapshot = resource.getAttributes().get(SFN_UPDATE_SNAPSHOT_ATTR);
        if (rawSnapshot == null) {
            return false;
        }
        try {
            return objectMapper.readTree(rawSnapshot).path("replacement").asBoolean(false);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not read Step Functions update metadata for "
                            + resource.getLogicalId(), e);
        }
    }

    @Override
    public void clearUpdate(StackResource resource) {
        if (usesReplacementCleanup(resource)) {
            resource.getAttributes().remove(ALIAS_UPDATE_SNAPSHOT_ATTR);
            ReplacementCleanup.clear(resource);
            return;
        }
        resource.getAttributes().remove(SFN_UPDATE_SNAPSHOT_ATTR);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (VERSION_TYPE.equals(resource.getResourceType())) {
            // A version never changes in place, so only a replacement has anything to undo.
            ReplacementCleanup.rollback(resource, this::delete);
            return true;
        }
        if (ALIAS_TYPE.equals(resource.getResourceType())) {
            return rollbackAlias(resource);
        }
        String rawSnapshot = resource.getAttributes().get(SFN_UPDATE_SNAPSHOT_ATTR);
        if (rawSnapshot == null) {
            return false;
        }
        try {
            JsonNode snapshot = objectMapper.readTree(rawSnapshot);
            String previousArn = snapshot.path("physicalId").asText(null);
            String replacementArn = snapshot.path("replacementArn").asText(
                    resource.getPhysicalId());
            String replacementRevisionId = snapshot.path("replacementRevisionId")
                    .asText(null);
            if (snapshot.path("replacement").asBoolean(false)
                    && replacementArn != null
                    && !Objects.equals(previousArn, replacementArn)) {
                stepFunctionsService.deleteStateMachineIfRevisionMatches(
                        replacementArn, replacementRevisionId);
            }

            StateMachine previous = objectMapper.treeToValue(
                    snapshot.path("stateMachine"), StateMachine.class);
            StateMachine current = findStateMachine(previousArn);
            String restoredRevisionId = null;
            if (current == null) {
                throw new IllegalStateException(
                        "The original state machine no longer exists: " + previousArn);
            }
            if (!snapshot.path("replacement").asBoolean(false)) {
                if (!stateMachineConfigurationMatches(
                        current,
                        previous.getDefinition(),
                        previous.getRoleArn(),
                        previous.getLoggingConfiguration(),
                        previous.getTracingConfiguration(),
                        previous.getEncryptionConfiguration())) {
                    stepFunctionsService.updateStateMachine(
                            previousArn,
                            new StepFunctionsService.UpdateStateMachineRequest(
                                    previous.getDefinition(),
                                    previous.getRoleArn(),
                                    previous.getLoggingConfiguration(), true,
                                    previous.getTracingConfiguration(), true,
                                    previous.getEncryptionConfiguration(), true,
                                    false,
                                    null));
                }
                StateMachine restored = stepFunctionsService.describeStateMachine(previousArn);
                restoredRevisionId = restored.getRevisionId();
                if (!Objects.equals(restored.getTags(), previous.getTags())) {
                    stepFunctionsService.replaceStateMachineTags(
                            previousArn, previous.getTags());
                }
            }

            resource.setPhysicalId(previousArn);
            resource.getAttributes().clear();
            JsonNode previousAttributes = snapshot.path("attributes");
            previousAttributes.fields().forEachRemaining(entry ->
                    resource.getAttributes().put(entry.getKey(), entry.getValue().asText()));
            if (restoredRevisionId != null) {
                resource.getAttributes().put(
                        "StateMachineRevisionId", restoredRevisionId);
            }
            resource.setStatus("UPDATE_COMPLETE");
            resource.setStatusReason(null);
            return true;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not roll back Step Functions state machine "
                            + resource.getLogicalId(), e);
        }
    }

    private void provisionStateMachine(StackResource r, JsonNode props, ProvisionContext ctx) {
        CloudFormationTemplateEngine engine = ctx.engine();
        String region = ctx.region();
        String accountId = ctx.accountId();
        String explicitName = ctx.resolveOptional(props, "StateMachineName");
        boolean hasExplicitName = explicitName != null && !explicitName.isBlank();
        String roleArn = ctx.resolveOptional(props, "RoleArn");
        if (roleArn == null || roleArn.isBlank()) {
            throw new AwsException("ValidationError", "RoleArn is required for a state machine", 400);
        }
        String type = ctx.resolveOrDefault(props, "StateMachineType", "STANDARD");
        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, engine);
        String definition = resolveStateMachineDefinition(props, ctx);
        JsonNode loggingConfiguration = resolveStateMachineLoggingConfiguration(props, engine);
        JsonNode tracingConfiguration = resolveStateMachineTracingConfiguration(props, engine);
        JsonNode encryptionConfiguration = resolveStateMachineEncryptionConfiguration(props, engine);

        StateMachine existing = findExistingOrStubbedStateMachine(r.getPhysicalId());
        String desiredNameMode = hasExplicitName ? NAME_MODE_EXPLICIT : NAME_MODE_GENERATED;
        String previousNameMode = r.getAttributes().get(SFN_NAME_MODE_ATTR);
        if (existing != null && previousNameMode == null) {
            previousNameMode = inferStepFunctionsNameMode(existing, ctx.stackName(), r.getLogicalId());
        }
        boolean nameModeReplacement = existing != null
                && !Objects.equals(previousNameMode, desiredNameMode);
        boolean typeReplacement = existing != null && !Objects.equals(existing.getType(), type);

        String name;
        if (hasExplicitName) {
            name = explicitName;
        } else if (existing != null && !nameModeReplacement && !typeReplacement) {
            name = existing.getName();
        } else {
            name = ctx.generatePhysicalName(r.getLogicalId(), STEP_FUNCTIONS_NAME_MAX_LENGTH, false);
        }
        String desiredArn = AwsArnUtils.Arn.of(
                "states", region, accountId, "stateMachine:" + name).toString();

        boolean nameReplacement = existing != null && !Objects.equals(existing.getName(), name);
        boolean replacement = nameReplacement || nameModeReplacement || typeReplacement;
        if (replacement && Objects.equals(existing.getName(), name)) {
            throw new AwsException("ValidationError",
                    "Cannot replace state machine " + existing.getName()
                            + " without a new StateMachineName", 400);
        }

        boolean configurationChanged = existing != null
                && !stateMachineConfigurationMatches(
                        existing,
                        definition,
                        roleArn,
                        loggingConfiguration,
                        tracingConfiguration,
                        encryptionConfiguration);
        boolean tagsChanged = existing != null && !Objects.equals(existing.getTags(), tags);

        StateMachine sm;
        if (existing == null) {
            sm = stepFunctionsService.createStateMachine(
                    name, definition, roleArn, type, region, tags,
                    loggingConfiguration, tracingConfiguration, encryptionConfiguration);
        } else if (!replacement && !configurationChanged && !tagsChanged) {
            sm = existing;
        } else {
            String replacementRevisionId = replacement
                    ? UUID.randomUUID().toString()
                    : null;
            beginStepFunctionsUpdate(
                    r,
                    existing,
                    replacement,
                    replacement ? desiredArn : null,
                    replacementRevisionId);
            if (replacement) {
                sm = stepFunctionsService.createStateMachineWithRevisionId(
                        name, definition, roleArn, type, region, tags,
                        loggingConfiguration,
                        tracingConfiguration,
                        encryptionConfiguration,
                        replacementRevisionId);
                markStepFunctionsReplacementCreated(r, sm);
            } else {
                sm = existing;
                if (configurationChanged) {
                    sm = stepFunctionsService.updateStateMachine(
                            existing.getStateMachineArn(),
                            new StepFunctionsService.UpdateStateMachineRequest(
                                    definition,
                                    roleArn,
                                    loggingConfiguration, true,
                                    tracingConfiguration, true,
                                    encryptionConfiguration, true,
                                    false,
                                    null)).stateMachine();
                }
                if (tagsChanged) {
                    stepFunctionsService.replaceStateMachineTags(sm.getStateMachineArn(), tags);
                    sm = stepFunctionsService.describeStateMachine(sm.getStateMachineArn());
                }
            }
        }

        r.setPhysicalId(sm.getStateMachineArn());
        r.getAttributes().put("Arn", sm.getStateMachineArn());
        r.getAttributes().put("Name", sm.getName());
        r.getAttributes().put("StateMachineRevisionId", sm.getRevisionId());
        r.getAttributes().put(SFN_NAME_MODE_ATTR, desiredNameMode);
    }

    private void provisionVersion(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        String stateMachineArn = blankToNull(ctx.resolveOptional(props, "StateMachineArn"));
        if (stateMachineArn == null) {
            throw new AwsException("ValidationError", VERSION_TYPE + " requires StateMachineArn", 400);
        }
        String revisionId = blankToNull(ctx.resolveOptional(props, "StateMachineRevisionId"));
        String description = blankToNull(ctx.resolveOptional(props, "Description"));
        StateMachineVersion version = stepFunctionsService.publishStateMachineVersion(
                stateMachineArn, revisionId, description);
        r.setPhysicalId(version.getStateMachineVersionArn());
        r.getAttributes().put("Arn", version.getStateMachineVersionArn());
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private void provisionAlias(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(ALIAS_UPDATE_SNAPSHOT_ATTR);
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        String explicitName = blankToNull(ctx.resolveOptional(props, "Name"));
        String description = blankToNull(ctx.resolveOptional(props, "Description"));
        List<RoutingConfiguration> routing = resolveAliasRouting(props, ctx.engine());
        String stateMachineArn = baseStateMachineArn(routing.get(0).getStateMachineVersionArn());
        String name = aliasName(r, ctx, explicitName);
        String aliasArn = stateMachineArn + ":" + name;

        StateMachineAlias current = ctx.isUpdate() && aliasArn.equals(ctx.priorPhysicalId())
                ? findAlias(aliasArn)
                : null;
        StateMachineAlias alias;
        if (current != null) {
            ObjectNode snapshot = objectMapper.createObjectNode();
            snapshot.put("description", current.getDescription());
            snapshot.set("routingConfiguration", objectMapper.valueToTree(current.getRoutingConfiguration()));
            r.getAttributes().put(ALIAS_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
            alias = stepFunctionsService.updateStateMachineAlias(aliasArn, description, true, routing);
        } else {
            alias = stepFunctionsService.createStateMachineAlias(name, description, routing);
        }
        r.setPhysicalId(alias.getStateMachineAliasArn());
        r.getAttributes().put("Arn", alias.getStateMachineAliasArn());
        r.getAttributes().put(ALIAS_NAME_MODE_ATTR,
                explicitName != null ? NAME_MODE_EXPLICIT : NAME_MODE_GENERATED);
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * The template's name when it gives one; otherwise the generated name the alias already has,
     * unless the previous name was explicit (dropping it replaces the alias on AWS); otherwise a
     * fresh generated name.
     */
    private static String aliasName(StackResource r, ProvisionContext ctx, String explicitName) {
        if (explicitName != null) {
            return explicitName;
        }
        String prior = ctx.priorPhysicalId();
        boolean keepsGeneratedName = ctx.isUpdate()
                && NAME_MODE_GENERATED.equals(r.getAttributes().get(ALIAS_NAME_MODE_ATTR))
                && !CfnResourceDispatcher.isStub(prior, r.getAttributes());
        if (keepsGeneratedName) {
            return prior.substring(prior.lastIndexOf(':') + 1);
        }
        return ctx.generatePhysicalName(r.getLogicalId(), STEP_FUNCTIONS_NAME_MAX_LENGTH, false);
    }

    /**
     * {@code RoutingConfiguration} as given, or the end state of a {@code DeploymentPreference}:
     * all traffic on its version. The gradual LINEAR and CANARY shifts are not emulated.
     */
    private List<RoutingConfiguration> resolveAliasRouting(JsonNode props, CloudFormationTemplateEngine engine) {
        JsonNode routingNode = props == null ? null : props.get("RoutingConfiguration");
        JsonNode preferenceNode = props == null ? null : props.get("DeploymentPreference");
        boolean hasRouting = routingNode != null && !routingNode.isNull();
        boolean hasPreference = preferenceNode != null && !preferenceNode.isNull();
        if (hasRouting == hasPreference) {
            throw new AwsException("ValidationError", ALIAS_TYPE
                    + " requires exactly one of RoutingConfiguration or DeploymentPreference", 400);
        }
        List<RoutingConfiguration> routing = new ArrayList<>();
        if (hasPreference) {
            String versionArn = engine.resolveNode(preferenceNode).path("StateMachineVersionArn").asText(null);
            if (versionArn == null || versionArn.isBlank()) {
                throw new AwsException("ValidationError",
                        "DeploymentPreference requires StateMachineVersionArn", 400);
            }
            routing.add(new RoutingConfiguration(versionArn, 100));
            return routing;
        }
        JsonNode resolved = engine.resolveNode(routingNode);
        if (resolved == null || !resolved.isArray() || resolved.isEmpty()) {
            throw new AwsException("ValidationError", "RoutingConfiguration must be a non-empty list", 400);
        }
        for (JsonNode route : resolved) {
            String versionArn = route.path("StateMachineVersionArn").asText(null);
            String weight = route.path("Weight").asText(null);
            if (versionArn == null || versionArn.isBlank() || weight == null) {
                throw new AwsException("ValidationError",
                        "Each RoutingConfiguration entry requires StateMachineVersionArn and Weight", 400);
            }
            try {
                routing.add(new RoutingConfiguration(versionArn, Integer.parseInt(weight.trim())));
            } catch (NumberFormatException e) {
                throw new AwsException("ValidationError",
                        "RoutingConfiguration Weight must be an integer, got " + weight, 400);
            }
        }
        return routing;
    }

    /** The state machine ARN a version ARN ({@code <stateMachineArn>:<version>}) belongs to. */
    private static String baseStateMachineArn(String versionArn) {
        int qualifier = versionArn.lastIndexOf(':');
        String base = qualifier > 0 ? versionArn.substring(0, qualifier) : versionArn;
        if (!base.contains(":stateMachine:")) {
            throw new AwsException("InvalidArn", "Invalid state machine version ARN: " + versionArn, 400);
        }
        return base;
    }

    private StateMachineAlias findAlias(String aliasArn) {
        try {
            return stepFunctionsService.describeStateMachineAlias(aliasArn);
        } catch (AwsException e) {
            if ("ResourceNotFound".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Undoes a replacement through the cleanup record, and an in-place update from the snapshot
     * taken before it. The snapshot is spent only once the restore succeeded.
     */
    private boolean rollbackAlias(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        String raw = resource.getAttributes().get(ALIAS_UPDATE_SNAPSHOT_ATTR);
        if (raw == null) {
            return true;
        }
        try {
            JsonNode snapshot = objectMapper.readTree(raw);
            List<RoutingConfiguration> routing = new ArrayList<>();
            for (JsonNode route : snapshot.path("routingConfiguration")) {
                routing.add(objectMapper.treeToValue(route, RoutingConfiguration.class));
            }
            stepFunctionsService.updateStateMachineAlias(resource.getPhysicalId(),
                    snapshot.path("description").asText(null), true, routing);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not read the alias update snapshot for " + resource.getLogicalId(), e);
        }
        resource.getAttributes().remove(ALIAS_UPDATE_SNAPSHOT_ATTR);
        return true;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }


    /**
     * Looks up a state machine by ARN, treating {@code StateMachineDoesNotExist} as "not found".
     * An {@code InvalidArn} error is not swallowed here: every caller of this method reads an ARN
     * this floci instance itself recorded (a cleanup or rollback snapshot), so a malformed value
     * reaching {@code describeStateMachine} is a real anomaly, not a legitimate "not found" case.
     */
    private StateMachine findStateMachine(String stateMachineArn) {
        if (stateMachineArn == null || stateMachineArn.isBlank()) {
            return null;
        }
        try {
            return stepFunctionsService.describeStateMachine(stateMachineArn);
        } catch (AwsException e) {
            if ("StateMachineDoesNotExist".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Same as {@link #findStateMachine}, but additionally treats {@code InvalidArn} as "not found"
     * when {@code physicalId} matches the stub shape a pre-SAM-expansion floci build wrote
     * ({@code <logicalId>-<8 hex>}, produced at :523). A stack whose
     * {@code AWS::Serverless::StateMachine} resource was provisioned before floci expanded SAM
     * types carries that stub value into its next update; without this, {@code describeStateMachine}
     * throws {@code InvalidArn} for it and the update fails instead of creating the real resource.
     * Used only at the provisioning entry point, where that upgrade path is legitimate: unlike
     * {@link #findStateMachine}'s other callers, a stub here is expected, not an anomaly.
     */
    private StateMachine findExistingOrStubbedStateMachine(String physicalId) {
        if (physicalId == null || physicalId.isBlank()) {
            return null;
        }
        try {
            return stepFunctionsService.describeStateMachine(physicalId);
        } catch (AwsException e) {
            if ("StateMachineDoesNotExist".equals(e.getErrorCode())
                    || ("InvalidArn".equals(e.getErrorCode()) && isStubPhysicalId(physicalId))) {
                return null;
            }
            throw e;
        }
    }
    private static boolean isStubPhysicalId(String physicalId) {
        return STUB_PHYSICAL_ID.matcher(physicalId).matches();
    }
    private String inferStepFunctionsNameMode(
            StateMachine existing, String stackName, String logicalId) {
        String base = stackName + "-" + logicalId;
        int keep = STEP_FUNCTIONS_NAME_MAX_LENGTH - GENERATED_NAME_SUFFIX_LENGTH - 1;
        String prefix = base.substring(0, Math.min(base.length(), keep));
        while (prefix.endsWith("-")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        String generatedPrefix = prefix.isEmpty() ? "" : prefix + "-";
        String name = existing.getName();
        if (name == null || !name.startsWith(generatedPrefix)) {
            return NAME_MODE_EXPLICIT;
        }
        String suffix = name.substring(generatedPrefix.length());
        boolean generatedSuffix = suffix.length() == GENERATED_NAME_SUFFIX_LENGTH
                && suffix.chars().allMatch(c -> c >= '0' && c <= '9' || c >= 'a' && c <= 'f');
        return generatedSuffix
                ? NAME_MODE_GENERATED
                : NAME_MODE_EXPLICIT;
    }
    private boolean stateMachineConfigurationMatches(
            StateMachine existing,
            String definition,
            String roleArn,
            JsonNode loggingConfiguration,
            JsonNode tracingConfiguration,
            JsonNode encryptionConfiguration) {
        return Objects.equals(existing.getDefinition(), definition)
                && Objects.equals(existing.getRoleArn(), roleArn)
                && Objects.equals(
                        effectiveLoggingConfiguration(existing.getLoggingConfiguration()),
                        loggingConfiguration)
                && Objects.equals(
                        effectiveTracingConfiguration(existing.getTracingConfiguration()),
                        tracingConfiguration)
                && Objects.equals(
                        effectiveEncryptionConfiguration(existing.getEncryptionConfiguration()),
                        encryptionConfiguration);
    }
    private JsonNode effectiveLoggingConfiguration(JsonNode configuration) {
        return configuration != null ? configuration : defaultStateMachineLoggingConfiguration();
    }
    private JsonNode effectiveTracingConfiguration(JsonNode configuration) {
        return configuration != null ? configuration : defaultStateMachineTracingConfiguration();
    }
    private JsonNode effectiveEncryptionConfiguration(JsonNode configuration) {
        return configuration != null ? configuration : defaultStateMachineEncryptionConfiguration();
    }
    private String resolveStateMachineDefinition(JsonNode props, ProvisionContext ctx) {
        CloudFormationTemplateEngine engine = ctx.engine();
        if (props == null) {
            throw new AwsException(
                    "ValidationError",
                    "A state machine definition is required",
                    400);
        }

        boolean hasDefinitionString =
                props.has("DefinitionString")
                        && !props.get("DefinitionString").isNull();
        boolean hasDefinition =
                props.has("Definition")
                        && !props.get("Definition").isNull();
        boolean hasS3Location =
                props.has("DefinitionS3Location")
                        && !props.get("DefinitionS3Location").isNull();
        int sourceCount = (hasDefinitionString ? 1 : 0)
                + (hasDefinition ? 1 : 0)
                + (hasS3Location ? 1 : 0);
        if (sourceCount != 1) {
            throw new AwsException(
                    "ValidationError",
                    "Specify exactly one of Definition, DefinitionString, or DefinitionS3Location",
                    400);
        }

        boolean definitionFromS3 = false;
        String definition;
        if (hasDefinitionString) {
            definition = ctx.resolveOptional(props, "DefinitionString");
        } else if (hasDefinition) {
            definition = engine.resolveJsonAttribute(props.get("Definition"));
        } else {
            JsonNode location = engine.resolveNode(props.get("DefinitionS3Location"));
            String bucket = location.path("Bucket").asText(null);
            String key = location.path("Key").asText(null);
            String version = location.path("Version").asText(null);
            if (bucket == null || bucket.isBlank() || key == null || key.isBlank()) {
                throw new AwsException(
                        "ValidationError",
                        "DefinitionS3Location requires Bucket and Key",
                        400);
            }
            S3Object object = s3Service.getObject(bucket, key, version);
            definition = new String(object.getData(), StandardCharsets.UTF_8);
            definitionFromS3 = true;
        }

        JsonNode subsNode = props.get("DefinitionSubstitutions");
        if (subsNode != null && !subsNode.isNull()) {
            JsonNode resolvedSubs = engine.resolveNode(subsNode);
            Iterator<Map.Entry<String, JsonNode>> entries = resolvedSubs.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                String placeholder = "${" + entry.getKey() + "}";
                String value = entry.getValue().isTextual()
                        ? entry.getValue().asText()
                        : entry.getValue().toString();
                definition = definition.replace(placeholder, value);
            }
        }

        if (definitionFromS3) {
            try {
                definition = new CloudFormationYamlParser(objectMapper)
                        .parse(definition)
                        .toString();
            } catch (Exception e) {
                throw new AwsException(
                        "ValidationError",
                        "DefinitionS3Location contains invalid JSON or YAML: "
                                + e.getMessage(),
                        400);
            }
        }

        return definition;
    }
    private JsonNode resolveStateMachineLoggingConfiguration(
            JsonNode props, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has("LoggingConfiguration")
                || props.get("LoggingConfiguration").isNull()) {
            return defaultStateMachineLoggingConfiguration();
        }
        JsonNode source = engine.resolveNode(props.get("LoggingConfiguration"));
        if (!source.isObject()) {
            throw new AwsException("ValidationError",
                    "LoggingConfiguration must be an object", 400);
        }

        ObjectNode result = objectMapper.createObjectNode();
        JsonNode level = source.get("Level");
        if (level != null && !level.isTextual()) {
            throw new AwsException("ValidationError",
                    "LoggingConfiguration.Level must be a string", 400);
        }
        result.put("level", level != null ? level.asText() : "OFF");

        JsonNode includeExecutionData = source.get("IncludeExecutionData");
        if (includeExecutionData != null && !includeExecutionData.isBoolean()) {
            throw new AwsException("ValidationError",
                    "LoggingConfiguration.IncludeExecutionData must be a boolean", 400);
        }
        result.put("includeExecutionData",
                includeExecutionData != null && includeExecutionData.asBoolean());

        ArrayNode destinations = result.putArray("destinations");
        JsonNode sourceDestinations = source.get("Destinations");
        if (sourceDestinations != null) {
            if (!sourceDestinations.isArray()) {
                throw new AwsException("ValidationError",
                        "LoggingConfiguration.Destinations must be an array", 400);
            }
            for (JsonNode destination : sourceDestinations) {
                JsonNode logGroupArn = destination.path("CloudWatchLogsLogGroup").get("LogGroupArn");
                if (logGroupArn == null || !logGroupArn.isTextual()) {
                    throw new AwsException("ValidationError",
                            "LoggingConfiguration destination LogGroupArn must be a string", 400);
                }
                destinations.addObject()
                        .putObject("cloudWatchLogsLogGroup")
                        .put("logGroupArn", logGroupArn.asText());
            }
        }
        return result;
    }
    private ObjectNode defaultStateMachineLoggingConfiguration() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("level", "OFF");
        result.put("includeExecutionData", false);
        result.putArray("destinations");
        return result;
    }
    private JsonNode resolveStateMachineTracingConfiguration(
            JsonNode props, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has("TracingConfiguration")
                || props.get("TracingConfiguration").isNull()) {
            return defaultStateMachineTracingConfiguration();
        }
        JsonNode source = engine.resolveNode(props.get("TracingConfiguration"));
        if (!source.isObject()) {
            throw new AwsException("ValidationError",
                    "TracingConfiguration must be an object", 400);
        }
        JsonNode enabled = source.get("Enabled");
        if (enabled != null && !enabled.isBoolean()) {
            throw new AwsException("ValidationError",
                    "TracingConfiguration.Enabled must be a boolean", 400);
        }
        return objectMapper.createObjectNode()
                .put("enabled", enabled != null && enabled.asBoolean());
    }
    private ObjectNode defaultStateMachineTracingConfiguration() {
        return objectMapper.createObjectNode().put("enabled", false);
    }
    private JsonNode resolveStateMachineEncryptionConfiguration(
            JsonNode props, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has("EncryptionConfiguration")
                || props.get("EncryptionConfiguration").isNull()) {
            return defaultStateMachineEncryptionConfiguration();
        }
        JsonNode source = engine.resolveNode(props.get("EncryptionConfiguration"));
        if (!source.isObject()) {
            throw new AwsException("ValidationError",
                    "EncryptionConfiguration must be an object", 400);
        }

        JsonNode type = source.get("Type");
        if (type == null || !type.isTextual() || type.asText().isBlank()) {
            throw new AwsException("ValidationError",
                    "EncryptionConfiguration.Type is required and must be a string", 400);
        }
        ObjectNode result = objectMapper.createObjectNode().put("type", type.asText());

        JsonNode keyId = source.get("KmsKeyId");
        if (keyId != null) {
            if (!keyId.isTextual()) {
                throw new AwsException("ValidationError",
                        "EncryptionConfiguration.KmsKeyId must be a string", 400);
            }
            result.put("kmsKeyId", keyId.asText());
        }

        JsonNode reusePeriod = source.get("KmsDataKeyReusePeriodSeconds");
        if (reusePeriod != null) {
            if (!reusePeriod.isIntegralNumber()) {
                throw new AwsException("ValidationError",
                        "EncryptionConfiguration.KmsDataKeyReusePeriodSeconds must be an integer", 400);
            }
            result.put("kmsDataKeyReusePeriodSeconds", reusePeriod.intValue());
        }
        return result;
    }
    private ObjectNode defaultStateMachineEncryptionConfiguration() {
        return objectMapper.createObjectNode().put("type", "AWS_OWNED_KEY");
    }
    private void beginStepFunctionsUpdate(
            StackResource resource,
            StateMachine existing,
            boolean replacement,
            String replacementArn,
            String replacementRevisionId) {
        if (resource.getAttributes().containsKey(SFN_UPDATE_SNAPSHOT_ATTR)) {
            return;
        }
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("physicalId", resource.getPhysicalId());
        snapshot.put("replacement", replacement);
        if (replacementArn != null) {
            snapshot.put("replacementArn", replacementArn);
        }
        if (replacementRevisionId != null) {
            snapshot.put("replacementRevisionId", replacementRevisionId);
        }
        snapshot.put("replacementCreated", false);
        snapshot.put("cleanupAttempts", 0);
        snapshot.set("stateMachine", objectMapper.valueToTree(existing));
        ObjectNode attributes = snapshot.putObject("attributes");
        resource.getAttributes().forEach(attributes::put);
        resource.getAttributes().put(SFN_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
    }
    private void markStepFunctionsReplacementCreated(
            StackResource resource, StateMachine replacement) {
        String rawSnapshot = resource.getAttributes().get(SFN_UPDATE_SNAPSHOT_ATTR);
        if (rawSnapshot == null) {
            throw new IllegalStateException(
                    "Step Functions replacement metadata is missing for "
                            + resource.getLogicalId());
        }
        try {
            ObjectNode snapshot = (ObjectNode) objectMapper.readTree(rawSnapshot);
            snapshot.put("replacementCreated", true);
            snapshot.put("replacementArn", replacement.getStateMachineArn());
            snapshot.put("replacementRevisionId", replacement.getRevisionId());
            resource.getAttributes().put(
                    SFN_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not record Step Functions replacement ownership for "
                            + resource.getLogicalId(), e);
        }
    }
    private Map<String, String> parseCfnTags(JsonNode tagsNode, CloudFormationTemplateEngine engine) {
        tagsNode = engine.resolveNode(tagsNode);
        Map<String, String> out = new HashMap<>();
        if (tagsNode == null || tagsNode.isNull() || !tagsNode.isArray()) {
            return out;
        }
        for (JsonNode entry : tagsNode) {
            JsonNode resolved = engine.resolveNode(entry);
            String key = resolved.path("Key").asText(null);
            String value = resolved.path("Value").asText("");
            if (key != null) {
                out.put(key, value);
            }
        }
        return out;
    }
}
