package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.memorydb.MemoryDbService;
import io.github.hectorvent.floci.services.memorydb.model.Acl;
import io.github.hectorvent.floci.services.memorydb.model.AuthMode;
import io.github.hectorvent.floci.services.memorydb.model.Cluster;
import io.github.hectorvent.floci.services.memorydb.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Objects;
import java.util.Set;

/**
 * CloudFormation provisioning for MemoryDB, backed by {@link MemoryDbService}: {@code AWS::MemoryDB::User},
 * {@code ACL}, {@code SubnetGroup}, {@code ParameterGroup} and {@code Cluster}. {@code Ref} is the name
 * of each, matching the schemas' primary identifiers.
 *
 * <p>A cluster is created exactly as the native {@code CreateCluster} creates one, so it gets the same
 * endpoint (and, with Docker, the same backing container and auth proxy); its endpoint is exposed as
 * the dotted {@code ClusterEndpoint.Address} and {@code ClusterEndpoint.Port} attributes.
 *
 * <p>The service has no subnet groups or parameter groups, so those two types are recorded rather than
 * backed: their name and ARN resolve, and a cluster referencing them is created as if they were absent.
 * Properties the service does not model (security groups, KMS key, windows, parameters, ...) are
 * accepted and ignored. The service cannot update a user, an ACL's membership or a cluster beyond its
 * description, so an in-place update keeps the existing entity and applies only the description;
 * renaming any of these is a replacement, which is refused.
 */
@ApplicationScoped
public class MemoryDbCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(MemoryDbCfnProvisioner.class);

    private static final String USER = "AWS::MemoryDB::User";
    private static final String ACL = "AWS::MemoryDB::ACL";
    private static final String SUBNET_GROUP = "AWS::MemoryDB::SubnetGroup";
    private static final String PARAMETER_GROUP = "AWS::MemoryDB::ParameterGroup";
    private static final String CLUSTER = "AWS::MemoryDB::Cluster";

    /** MemoryDB's limit on cluster, ACL, user and group names. */
    private static final int NAME_MAX = 40;

    private final MemoryDbService memoryDbService;

    @Inject
    public MemoryDbCfnProvisioner(MemoryDbService memoryDbService) {
        this.memoryDbService = memoryDbService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(USER, ACL, SUBNET_GROUP, PARAMETER_GROUP, CLUSTER);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case USER -> provisionUser(r, props, ctx);
            case ACL -> provisionAcl(r, props, ctx);
            case SUBNET_GROUP -> provisionSubnetGroup(r, props, ctx);
            case PARAMETER_GROUP -> provisionParameterGroup(r, props, ctx);
            case CLUSTER -> provisionCluster(r, props, ctx);
            default -> throw new IllegalStateException(
                    "MemoryDbCfnProvisioner cannot provision " + r.getResourceType());
        }
    }

    private void provisionUser(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = physicalName(r, props, ctx, "UserName");
        if (reusesExisting(ctx, name, "UserName")) {
            User existing = findUser(name, ctx.region());
            if (existing != null) {
                recordUser(r, existing);
                return;
            }
        }
        User spec = new User();
        spec.setName(name);
        spec.setAccessString(ctx.resolveOptional(props, "AccessString"));
        JsonNode auth = props != null ? ctx.engine().resolveNode(props.get("AuthenticationMode")) : null;
        if (auth != null && auth.isObject()) {
            String type = ctx.engine().resolve(auth.get("Type"));
            spec.setAuthMode(parseAuthMode(type));
            spec.setPasswords(ctx.resolveStringList(auth, "Passwords"));
        }
        recordUser(r, memoryDbService.createUser(spec, ctx.region()));
    }

    private void provisionAcl(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = physicalName(r, props, ctx, "ACLName");
        if (reusesExisting(ctx, name, "ACLName")) {
            Acl existing = findAcl(name, ctx.region());
            if (existing != null) {
                recordAcl(r, existing);
                return;
            }
        }
        Acl spec = new Acl();
        spec.setName(name);
        spec.setUserNames(ctx.resolveStringList(props, "UserNames"));
        recordAcl(r, memoryDbService.createAcl(spec, ctx.region()));
    }

    private void provisionSubnetGroup(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = physicalName(r, props, ctx, "SubnetGroupName");
        reusesExisting(ctx, name, "SubnetGroupName");
        r.setPhysicalId(name);
        r.getAttributes().put("ARN", arn(ctx, "subnetgroup/" + name));
        r.getAttributes().put("SupportedNetworkTypes", "ipv4");
    }

    private void provisionParameterGroup(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = physicalName(r, props, ctx, "ParameterGroupName");
        reusesExisting(ctx, name, "ParameterGroupName");
        r.setPhysicalId(name);
        r.getAttributes().put("ARN", arn(ctx, "parametergroup/" + name));
    }

    private void provisionCluster(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = physicalName(r, props, ctx, "ClusterName");
        String description = ctx.resolveOptional(props, "Description");
        boolean tlsEnabled = Boolean.parseBoolean(ctx.resolveOptional(props, "TLSEnabled"));
        if (reusesExisting(ctx, name, "ClusterName")) {
            Cluster existing = findCluster(name, ctx.region());
            if (existing != null) {
                if (existing.isTlsEnabled() != tlsEnabled) {
                    throw replacementNotSupported("TLSEnabled");
                }
                if (!Objects.equals(existing.getDescription(), description)) {
                    existing = memoryDbService.updateCluster(name, description == null ? "" : description,
                            ctx.region());
                }
                recordCluster(r, existing);
                return;
            }
        }
        Cluster spec = new Cluster();
        spec.setName(name);
        spec.setDescription(description);
        spec.setAclName(ctx.resolveOptional(props, "ACLName"));
        spec.setNodeType(ctx.resolveOptional(props, "NodeType"));
        spec.setEngine(ctx.resolveOptional(props, "Engine"));
        spec.setEngineVersion(ctx.resolveOptional(props, "EngineVersion"));
        String numShards = ctx.resolveOptional(props, "NumShards");
        if (numShards != null && !numShards.isBlank()) {
            spec.setNumberOfShards(parseInt("NumShards", numShards));
        }
        spec.setTlsEnabled(tlsEnabled);
        spec.setTags(ctx.resolveTags(props, "Tags"));
        recordCluster(r, memoryDbService.createCluster(spec, ctx.region()));
    }

    private static void recordUser(StackResource r, User user) {
        r.setPhysicalId(user.getName());
        r.getAttributes().put("Arn", user.getArn());
        r.getAttributes().put("Status", user.getStatus());
    }

    private static void recordAcl(StackResource r, Acl acl) {
        r.setPhysicalId(acl.getName());
        r.getAttributes().put("Arn", acl.getArn());
        r.getAttributes().put("Status", acl.getStatus());
    }

    private static void recordCluster(StackResource r, Cluster cluster) {
        r.setPhysicalId(cluster.getName());
        r.getAttributes().put("ARN", cluster.getArn());
        r.getAttributes().put("Status", cluster.getStatus().wireValue());
        r.getAttributes().put("ClusterEndpoint.Address", cluster.getClusterEndpoint().address());
        r.getAttributes().put("ClusterEndpoint.Port", String.valueOf(cluster.getClusterEndpoint().port()));
        // No parameter groups are modelled, so the cluster is always in sync with its (default) group.
        r.getAttributes().put("ParameterGroupStatus", "in-sync");
    }

    private static String physicalName(StackResource r, JsonNode props, ProvisionContext ctx, String property) {
        return ctx.stablePhysicalName(ctx.resolveOptional(props, property), r.getLogicalId(), NAME_MAX, true);
    }

    /**
     * True when this provision is an in-place update of the entity the resource already names. Every
     * name here is createOnly, so an update that derives a different name is a replacement.
     */
    private static boolean reusesExisting(ProvisionContext ctx, String name, String property) {
        if (!ctx.isUpdate()) {
            return false;
        }
        if (!ctx.reusesPriorEntity(name)) {
            throw replacementNotSupported(property);
        }
        return true;
    }

    private User findUser(String name, String region) {
        try {
            return memoryDbService.describeUsers(name, region).iterator().next();
        } catch (AwsException e) {
            return goneOrThrow(e, "UserNotFoundFault", "user", name);
        }
    }

    private Acl findAcl(String name, String region) {
        try {
            return memoryDbService.describeAcls(name, region).iterator().next();
        } catch (AwsException e) {
            return goneOrThrow(e, "ACLNotFoundFault", "ACL", name);
        }
    }

    private Cluster findCluster(String name, String region) {
        try {
            return memoryDbService.getCluster(name, region);
        } catch (AwsException e) {
            return goneOrThrow(e, "ClusterNotFoundFault", "cluster", name);
        }
    }

    private static <T> T goneOrThrow(AwsException e, String notFoundCode, String what, String name) {
        if (!notFoundCode.equals(e.getErrorCode())) {
            throw e;
        }
        LOG.debugv("MemoryDB {0} {1} from the previous provision is gone, creating it again", what, name);
        return null;
    }

    private static AuthMode parseAuthMode(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return AuthMode.fromWire(type);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterValueException", e.getMessage(), 400);
        }
    }

    private static int parseInt(String property, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValueException",
                    property + " must be an integer, got '" + value + "'.", 400);
        }
    }

    private static String arn(ProvisionContext ctx, String resource) {
        return AwsArnUtils.Arn.of("memorydb", ctx.region(), ctx.accountId(), resource).toString();
    }

    private static AwsException replacementNotSupported(String property) {
        return new AwsException("ValidationError",
                "Updating " + property + " requires resource replacement, which is not supported.", 400);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        switch (resourceType) {
            case CLUSTER -> CfnDeletes.safeDelete("MemoryDB cluster", physicalId,
                    () -> memoryDbService.deleteCluster(physicalId, region), "ClusterNotFoundFault");
            case ACL -> CfnDeletes.safeDelete("MemoryDB ACL", physicalId,
                    () -> memoryDbService.deleteAcl(physicalId, region), "ACLNotFoundFault");
            case USER -> CfnDeletes.safeDelete("MemoryDB user", physicalId,
                    () -> memoryDbService.deleteUser(physicalId, region), "UserNotFoundFault");
            default -> {
                // Subnet and parameter groups are recorded only, so there is nothing to delete.
            }
        }
    }
}
