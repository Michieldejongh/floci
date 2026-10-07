package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.ManagedLoginBranding;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provisions {@code AWS::Cognito::ManagedLoginBranding}. {@code Ref} and {@code Fn::GetAtt
 * ManagedLoginBrandingId} are the branding style id, as on AWS. The schema's primary identifier also
 * holds the pool, so the pool and client of each style are recorded per physical id: every branding
 * call is addressed by pool and id. {@code UserPoolId} and {@code ClientId} are create-only: an update
 * that keeps both changes the style in place, one that changes either creates the new style and leaves
 * the displaced one to the {@link ReplacementCleanup} record. {@code ReturnMergedResources} only shapes
 * AWS's response and is accepted and ignored.
 */
@ApplicationScoped
public class CognitoManagedLoginBrandingCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(CognitoManagedLoginBrandingCfnProvisioner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TYPE = "AWS::Cognito::ManagedLoginBranding";
    private static final String NOT_FOUND = "ResourceNotFoundException";

    /**
     * The current style and every one still owed a delete, mapped to its [UserPoolId, ClientId]. Kept
     * under a private key, which a replacement rollback leaves as it is, so the restored style is
     * still found by its own pool and client.
     */
    private static final String BRANDINGS_ATTR = "__FlociBrandings";
    /** The style before the in-place update in flight, so a failed stack update can put it back. */
    private static final String UPDATE_SNAPSHOT_ATTR = "__FlociBrandingUpdateSnapshot";

    private final CognitoService cognitoService;

    @Inject
    public CognitoManagedLoginBrandingCfnProvisioner(CognitoService cognitoService) {
        this.cognitoService = cognitoService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String userPoolId = require(props, "UserPoolId", ctx);
        // Optional in the schema, but CreateManagedLoginBranding needs it, so AWS fails without it too.
        String clientId = require(props, "ClientId", ctx);
        Boolean useCognitoProvidedValues = resolveBoolean(props, "UseCognitoProvidedValues", ctx);
        Map<String, Object> settings = resolveSettings(props, ctx);
        List<Map<String, Object>> assets = resolveAssets(props, ctx);

        r.getAttributes().remove(UPDATE_SNAPSHOT_ATTR);
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        ManagedLoginBranding prior = ctx.isUpdate()
                ? findPrior(ctx.priorPhysicalId(), brandings(attributesBefore), userPoolId, clientId) : null;

        ManagedLoginBranding branding;
        if (prior != null) {
            snapshot(r, prior);
            // The template states the whole style: an omitted flag selects no Cognito defaults and
            // omitted assets are removed, where the API's own update would keep what was there.
            branding = cognitoService.updateManagedLoginBranding(userPoolId, prior.getManagedLoginBrandingId(),
                    useCognitoProvidedValues == null ? Boolean.FALSE : useCognitoProvidedValues,
                    settings, assets == null ? List.of() : assets);
        } else {
            branding = cognitoService.createManagedLoginBranding(userPoolId, clientId,
                    useCognitoProvidedValues, settings, assets);
        }

        r.setPhysicalId(branding.getManagedLoginBrandingId());
        r.getAttributes().put("ManagedLoginBrandingId", branding.getManagedLoginBrandingId());
        ReplacementCleanup.record(r, ctx, attributesBefore);
        recordBranding(r, attributesBefore, userPoolId, clientId);
    }

    /** The style this resource names when it can be updated in place; null when it must be created. */
    private ManagedLoginBranding findPrior(String priorPhysicalId, ObjectNode brandings,
                                           String userPoolId, String clientId) {
        JsonNode recorded = priorPhysicalId == null ? null : brandings.get(priorPhysicalId);
        if (recorded == null || !userPoolId.equals(recorded.path(0).asText(null))
                || !clientId.equals(recorded.path(1).asText(null))) {
            return null;
        }
        try {
            return cognitoService.describeManagedLoginBranding(userPoolId, priorPhysicalId);
        } catch (AwsException e) {
            if (!NOT_FOUND.equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Managed login branding {0} is gone; creating it again", priorPhysicalId);
            return null;
        }
    }

    @Override
    public void delete(StackResource resource, String region) {
        deleteBranding(resource.getAttributes(), resource.getPhysicalId());
    }

    /**
     * Without the resource's record only an id in the schema's primary identifier form,
     * {@code <UserPoolId>|<ManagedLoginBrandingId>}, says which pool holds the style.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        deleteBranding(Map.of(), physicalId);
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
        resource.getAttributes().remove(UPDATE_SNAPSHOT_ATTR);
        return ReplacementCleanup.complete(resource, (type, physicalId, region) ->
                deleteBranding(resource.getAttributes(), physicalId));
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(UPDATE_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement is undone through the cleanup record, an in-place update from the snapshot taken
     * before it. With neither, the provision failed before it changed anything.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, (type, physicalId, region) ->
                deleteBranding(resource.getAttributes(), physicalId))) {
            return true;
        }
        String raw = resource.getAttributes().get(UPDATE_SNAPSHOT_ATTR);
        if (raw == null) {
            return true;
        }
        ManagedLoginBranding before;
        try {
            before = MAPPER.readValue(raw, ManagedLoginBranding.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the branding snapshot for " + resource.getLogicalId(), e);
        }
        boolean cognitoDefaults = before.isUseCognitoProvidedValues();
        cognitoService.updateManagedLoginBranding(before.getUserPoolId(), before.getManagedLoginBrandingId(),
                cognitoDefaults, cognitoDefaults ? null : before.getSettings(), before.getAssets());
        // Spent only once the restore succeeded, so a failed restore can be attempted again.
        resource.getAttributes().remove(UPDATE_SNAPSHOT_ATTR);
        return true;
    }

    private void deleteBranding(Map<String, String> attributes, String physicalId) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        String brandingId = physicalId;
        String userPoolId = brandings(attributes).path(physicalId).path(0).asText(null);
        int separator = physicalId.lastIndexOf('|');
        if (userPoolId == null && separator > 0) {
            userPoolId = physicalId.substring(0, separator);
            brandingId = physicalId.substring(separator + 1);
        }
        if (userPoolId == null) {
            LOG.warnv("No user pool recorded for managed login branding {0}; nothing to delete", physicalId);
            return;
        }
        String pool = userPoolId;
        String id = brandingId;
        CfnDeletes.safeDelete("managed login branding", physicalId,
                () -> cognitoService.deleteManagedLoginBranding(pool, id), NOT_FOUND);
    }

    private static void snapshot(StackResource r, ManagedLoginBranding prior) {
        try {
            r.getAttributes().put(UPDATE_SNAPSHOT_ATTR, MAPPER.writeValueAsString(prior));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not snapshot " + r.getLogicalId() + " before its update", e);
        }
    }

    /**
     * Keeps the record of the style the resource now names and of every style still owed a delete,
     * dropping the rest so it stays bounded. Call after {@link ReplacementCleanup#record}, so the
     * style this update displaced is already owed.
     */
    private static void recordBranding(StackResource r, Map<String, String> attributesBefore,
                                       String userPoolId, String clientId) {
        ObjectNode prior = brandings(attributesBefore);
        ObjectNode brandings = MAPPER.createObjectNode();
        for (String owed : ReplacementCleanup.owedPhysicalIds(r)) {
            JsonNode parts = prior.get(owed);
            if (parts != null) {
                brandings.set(owed, parts);
            }
        }
        brandings.set(r.getPhysicalId(), MAPPER.createArrayNode().add(userPoolId).add(clientId));
        r.getAttributes().put(BRANDINGS_ATTR, brandings.toString());
    }

    private static ObjectNode brandings(Map<String, String> attributes) {
        String recorded = attributes == null ? null : attributes.get(BRANDINGS_ATTR);
        if (recorded != null) {
            try {
                if (MAPPER.readTree(recorded) instanceof ObjectNode brandings) {
                    return brandings;
                }
            } catch (JsonProcessingException e) {
                LOG.warnv("Unreadable managed login branding record, ignoring it: {0}", e.getMessage());
            }
        }
        return MAPPER.createObjectNode();
    }

    private static String require(JsonNode props, String name, ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, name);
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", TYPE + " requires " + name, 400);
        }
        return value;
    }

    private static Boolean resolveBoolean(JsonNode props, String name, ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, name);
        return value == null || value.isBlank() ? null : Boolean.valueOf(value.trim());
    }

    private static Map<String, Object> resolveSettings(JsonNode props, ProvisionContext ctx) {
        JsonNode resolved = resolveNode(props, "Settings", ctx);
        if (resolved == null) {
            return null;
        }
        if (!resolved.isObject()) {
            throw new AwsException("ValidationError", TYPE + " Settings must be a JSON object", 400);
        }
        return MAPPER.convertValue(resolved, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private static List<Map<String, Object>> resolveAssets(JsonNode props, ProvisionContext ctx) {
        JsonNode resolved = resolveNode(props, "Assets", ctx);
        if (resolved == null) {
            return null;
        }
        if (!resolved.isArray()) {
            throw new AwsException("ValidationError", TYPE + " Assets must be a list", 400);
        }
        List<Map<String, Object>> assets = new ArrayList<>();
        for (JsonNode asset : resolved) {
            if (!asset.isObject()) {
                throw new AwsException("ValidationError", TYPE + " Assets entries must be objects", 400);
            }
            assets.add(MAPPER.convertValue(asset, new TypeReference<LinkedHashMap<String, Object>>() { }));
        }
        return assets;
    }

    private static JsonNode resolveNode(JsonNode props, String name, ProvisionContext ctx) {
        if (props == null || !props.hasNonNull(name)) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get(name));
        return resolved == null || resolved.isNull() ? null : resolved;
    }
}
