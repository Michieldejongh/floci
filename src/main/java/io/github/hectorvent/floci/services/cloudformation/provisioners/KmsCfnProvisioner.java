package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Provisions {@code AWS::KMS::Key} and {@code AWS::KMS::Alias}. */
@ApplicationScoped
public class KmsCfnProvisioner implements CfnResourceProvisioner {

    private static final String KEY = "AWS::KMS::Key";
    private static final String ALIAS = "AWS::KMS::Alias";

    private final KmsService kmsService;

    public KmsCfnProvisioner(KmsService kmsService) {
        this.kmsService = kmsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(KEY, ALIAS);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case KEY -> provisionKey(r, props, ctx);
            case ALIAS -> provisionAlias(r, props, ctx);
            default -> throw new IllegalStateException(
                    "KmsCfnProvisioner cannot provision " + r.getResourceType());
        }
    }

    private void provisionKey(StackResource r, JsonNode props, ProvisionContext ctx) {
        String description = ctx.resolveOptional(props, "Description");
        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, ctx);
        String keyUsage = ctx.resolveOrDefault(props, "KeyUsage", "ENCRYPT_DECRYPT");
        String keySpec = ctx.resolveOrDefault(props, "KeySpec", "SYMMETRIC_DEFAULT");
        boolean multiRegion = Boolean.parseBoolean(ctx.resolveOptional(props, "MultiRegion"));
        String keyPolicy = keyPolicy(props, ctx);
        KmsKey key = kmsService.createKey(description, keyUsage, keySpec, keyPolicy, tags, null,
                multiRegion, ctx.region());
        r.setPhysicalId(key.getKeyId());
        r.getAttributes().put("Arn", key.getArn());
        r.getAttributes().put("KeyId", key.getKeyId());
    }

    /** {@code KeyPolicy} is declared as JSON, so it arrives as an object or as a JSON string. */
    private String keyPolicy(JsonNode props, ProvisionContext ctx) {
        JsonNode policy = props != null ? ctx.engine().resolveNode(props.get("KeyPolicy")) : null;
        if (policy == null || policy.isNull() || policy.isMissingNode()) {
            return null;
        }
        return policy.isTextual() ? policy.asText() : policy.toString();
    }

    private void provisionAlias(StackResource r, JsonNode props, ProvisionContext ctx) {
        String aliasName = ctx.resolveOptional(props, "AliasName");
        String targetKeyId = ctx.resolveOptional(props, "TargetKeyId");
        if (aliasName != null && targetKeyId != null) {
            kmsService.createAlias(aliasName, targetKeyId, ctx.region());
        }
        r.setPhysicalId(aliasName != null
                ? aliasName
                : "alias/cfn-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        // A KMS key cannot be deleted immediately, only scheduled, so a stack delete leaves it.
        if (ALIAS.equals(resourceType)) {
            kmsService.deleteAlias(physicalId, region);
        }
    }

    /**
     * Kept rather than delegating to {@link ProvisionContext#resolveTags}, which is not
     * equivalent: it skips a blank key this keeps and orders entries by insertion rather than hash.
     */
    private Map<String, String> parseCfnTags(JsonNode tagsNode, ProvisionContext ctx) {
        tagsNode = ctx.engine().resolveNode(tagsNode);
        Map<String, String> out = new HashMap<>();
        if (tagsNode == null || tagsNode.isNull() || !tagsNode.isArray()) {
            return out;
        }
        for (JsonNode entry : tagsNode) {
            JsonNode resolved = ctx.engine().resolveNode(entry);
            String key = resolved.path("Key").asText(null);
            String value = resolved.path("Value").asText("");
            if (key != null) {
                out.put(key, value);
            }
        }
        return out;
    }
}
