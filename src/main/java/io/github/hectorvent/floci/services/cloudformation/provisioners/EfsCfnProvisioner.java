package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.efs.EfsService;
import io.github.hectorvent.floci.services.efs.model.AccessPointDescription;
import io.github.hectorvent.floci.services.efs.model.BackupPolicy;
import io.github.hectorvent.floci.services.efs.model.CreateAccessPointRequest;
import io.github.hectorvent.floci.services.efs.model.CreateFileSystemRequest;
import io.github.hectorvent.floci.services.efs.model.CreateMountTargetRequest;
import io.github.hectorvent.floci.services.efs.model.CreateTagsRequest;
import io.github.hectorvent.floci.services.efs.model.CreationInfo;
import io.github.hectorvent.floci.services.efs.model.DeleteTagsRequest;
import io.github.hectorvent.floci.services.efs.model.DescribeMountTargetsRequest;
import io.github.hectorvent.floci.services.efs.model.FileSystem;
import io.github.hectorvent.floci.services.efs.model.LifecyclePolicy;
import io.github.hectorvent.floci.services.efs.model.ModifyMountTargetSecurityGroupsRequest;
import io.github.hectorvent.floci.services.efs.model.MountTarget;
import io.github.hectorvent.floci.services.efs.model.PerformanceMode;
import io.github.hectorvent.floci.services.efs.model.PosixUser;
import io.github.hectorvent.floci.services.efs.model.ReplicationOverwriteProtection;
import io.github.hectorvent.floci.services.efs.model.RootDirectory;
import io.github.hectorvent.floci.services.efs.model.Tag;
import io.github.hectorvent.floci.services.efs.model.TagResourceRequest;
import io.github.hectorvent.floci.services.efs.model.ThroughputMode;
import io.github.hectorvent.floci.services.efs.model.UpdateFileSystemProtectionRequest;
import io.github.hectorvent.floci.services.efs.model.UpdateFileSystemRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Provisions {@code AWS::EFS::FileSystem}, {@code AWS::EFS::MountTarget} and
 * {@code AWS::EFS::AccessPoint} through {@link EfsService}. {@code Ref} is the file system,
 * mount target or access point id, as on AWS.
 *
 * <p>An update changes the existing entity in place. A change to a createOnly property is refused
 * rather than replacing the entity. {@code BypassPolicyLockoutSafetyCheck},
 * {@code ReplicationConfiguration}, {@code Ipv6Address} and {@code IpAddressType} are accepted and
 * ignored.
 */
@ApplicationScoped
public class EfsCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(EfsCfnProvisioner.class);

    private static final String FILE_SYSTEM = "AWS::EFS::FileSystem";
    private static final String MOUNT_TARGET = "AWS::EFS::MountTarget";
    private static final String ACCESS_POINT = "AWS::EFS::AccessPoint";

    private static final String FILE_SYSTEM_NOT_FOUND = "FileSystemNotFound";
    private static final String MOUNT_TARGET_NOT_FOUND = "MountTargetNotFound";
    private static final String ACCESS_POINT_NOT_FOUND = "AccessPointNotFound";

    private final EfsService efsService;

    @Inject
    public EfsCfnProvisioner(EfsService efsService) {
        this.efsService = efsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(FILE_SYSTEM, MOUNT_TARGET, ACCESS_POINT);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case FILE_SYSTEM -> provisionFileSystem(r, props, ctx);
            case MOUNT_TARGET -> provisionMountTarget(r, props, ctx);
            case ACCESS_POINT -> provisionAccessPoint(r, props, ctx);
            default -> throw new IllegalStateException(
                    "EfsCfnProvisioner cannot provision " + r.getResourceType());
        }
    }

    // --- FileSystem ---

    private void provisionFileSystem(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        PerformanceMode performanceMode = parseEnum(ctx.resolveOptional(props, "PerformanceMode"),
                PerformanceMode::valueOf, "PerformanceMode");
        ThroughputMode throughputMode = parseEnum(ctx.resolveOptional(props, "ThroughputMode"),
                ThroughputMode::valueOf, "ThroughputMode");
        Double provisionedThroughput = parseDouble(ctx.resolveOptional(props, "ProvisionedThroughputInMibps"),
                "ProvisionedThroughputInMibps");
        boolean encrypted = Boolean.parseBoolean(ctx.resolveOptional(props, "Encrypted"));
        String kmsKeyId = blankToNull(ctx.resolveOptional(props, "KmsKeyId"));
        String availabilityZoneName = blankToNull(ctx.resolveOptional(props, "AvailabilityZoneName"));
        List<Tag> tags = toTags(ctx.resolveTags(props, "FileSystemTags"));
        String backupStatus = nestedText(props, "BackupPolicy", "Status", ctx);
        List<LifecyclePolicy> lifecyclePolicies = lifecyclePolicies(props, ctx);
        String policy = props != null && props.hasNonNull("FileSystemPolicy")
                ? ctx.engine().resolveJsonAttributeStrict(props.get("FileSystemPolicy"))
                : null;
        ReplicationOverwriteProtection protection = parseEnum(
                nestedText(props, "FileSystemProtection", "ReplicationOverwriteProtection", ctx),
                ReplicationOverwriteProtection::valueOf, "ReplicationOverwriteProtection");

        FileSystem existing = ctx.isUpdate() ? findFileSystem(region, ctx.priorPhysicalId()) : null;
        FileSystem fs;
        if (existing != null) {
            rejectIfChanged("PerformanceMode",
                    Objects.requireNonNullElse(existing.getPerformanceMode(), PerformanceMode.generalPurpose.name()),
                    performanceMode != null ? performanceMode.name() : PerformanceMode.generalPurpose.name());
            rejectIfChanged("Encrypted", String.valueOf(Boolean.TRUE.equals(existing.getEncrypted())),
                    String.valueOf(encrypted));
            rejectIfChanged("KmsKeyId", existing.getKmsKeyId(), kmsKeyId);
            rejectIfChanged("AvailabilityZoneName", existing.getAvailabilityZoneName(), availabilityZoneName);

            UpdateFileSystemRequest update = new UpdateFileSystemRequest();
            update.setThroughputMode(throughputMode != null ? throughputMode : ThroughputMode.bursting);
            update.setProvisionedThroughputInMibps(provisionedThroughput);
            fs = efsService.updateFileSystem(region, existing.getFileSystemId(), update);
            reconcileFileSystemTags(region, existing, tags);
        } else {
            CreateFileSystemRequest request = new CreateFileSystemRequest();
            request.setPerformanceMode(performanceMode);
            request.setThroughputMode(throughputMode);
            request.setProvisionedThroughputInMibps(provisionedThroughput);
            request.setEncrypted(encrypted);
            request.setKmsKeyId(kmsKeyId);
            request.setAvailabilityZoneName(availabilityZoneName);
            request.setTags(tags);
            // The API default: backups off for a Regional file system, on for One Zone.
            request.setBackup(backupStatus != null ? "ENABLED".equals(backupStatus) : availabilityZoneName != null);
            fs = efsService.createFileSystem(request, region);
        }
        String fsId = fs.getFileSystemId();

        if (existing != null && backupStatus != null) {
            BackupPolicy backupPolicy = new BackupPolicy();
            backupPolicy.setStatus(backupStatus);
            efsService.putBackupPolicy(region, fsId, backupPolicy);
        }
        if (existing != null || !lifecyclePolicies.isEmpty()) {
            efsService.putLifecycleConfiguration(region, fsId, lifecyclePolicies);
        }
        if (policy != null) {
            efsService.putFileSystemPolicy(region, fsId, policy);
        } else if (existing != null) {
            efsService.deleteFileSystemPolicy(region, fsId);
        }
        if (protection != null) {
            UpdateFileSystemProtectionRequest protectionRequest = new UpdateFileSystemProtectionRequest();
            protectionRequest.setReplicationOverwriteProtection(protection);
            efsService.updateFileSystemProtection(region, fsId, protectionRequest);
        }

        r.setPhysicalId(fsId);
        r.getAttributes().put("FileSystemId", fsId);
        r.getAttributes().put("Arn", fs.getFileSystemArn());
    }

    private FileSystem findFileSystem(String region, String fileSystemId) {
        try {
            return efsService.getFileSystem(region, fileSystemId);
        } catch (AwsException e) {
            if (!FILE_SYSTEM_NOT_FOUND.equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("EFS file system {0} from the previous provision is gone, creating it again", fileSystemId);
            return null;
        }
    }

    /** CreateTags only adds, so a key the template dropped has to be deleted explicitly. */
    private void reconcileFileSystemTags(String region, FileSystem fs, List<Tag> desired) {
        List<String> stale = staleTagKeys(fs.getTags(), desired);
        if (!stale.isEmpty()) {
            DeleteTagsRequest deleteTags = new DeleteTagsRequest();
            deleteTags.setTagKeys(stale);
            efsService.deleteTags(region, fs.getFileSystemId(), deleteTags);
        }
        if (!desired.isEmpty()) {
            CreateTagsRequest createTags = new CreateTagsRequest();
            createTags.setTags(desired);
            efsService.createTags(region, fs.getFileSystemId(), createTags);
        }
    }

    private static List<LifecyclePolicy> lifecyclePolicies(JsonNode props, ProvisionContext ctx) {
        List<LifecyclePolicy> policies = new ArrayList<>();
        if (props == null || !props.hasNonNull("LifecyclePolicies")) {
            return policies;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get("LifecyclePolicies"));
        if (resolved == null || !resolved.isArray()) {
            return policies;
        }
        for (JsonNode entry : resolved) {
            JsonNode node = ctx.engine().resolveNode(entry);
            LifecyclePolicy policy = new LifecyclePolicy();
            policy.setTransitionToIA(text(node, "TransitionToIA", ctx));
            policy.setTransitionToPrimaryStorageClass(text(node, "TransitionToPrimaryStorageClass", ctx));
            policy.setTransitionToArchive(text(node, "TransitionToArchive", ctx));
            policies.add(policy);
        }
        return policies;
    }

    // --- MountTarget ---

    private void provisionMountTarget(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        String fileSystemId = ctx.resolveOptional(props, "FileSystemId");
        String subnetId = ctx.resolveOptional(props, "SubnetId");
        List<String> securityGroups = ctx.resolveStringList(props, "SecurityGroups");
        String ipAddress = blankToNull(ctx.resolveOptional(props, "IpAddress"));
        requireProperty(MOUNT_TARGET, "FileSystemId", fileSystemId);
        requireProperty(MOUNT_TARGET, "SubnetId", subnetId);

        MountTarget existing = ctx.isUpdate() ? findMountTarget(region, ctx.priorPhysicalId()) : null;
        MountTarget mt;
        if (existing != null) {
            rejectIfChanged("FileSystemId", existing.getFileSystemId(), fileSystemId);
            rejectIfChanged("SubnetId", existing.getSubnetId(), subnetId);
            if (ipAddress != null) {
                rejectIfChanged("IpAddress", existing.getIpAddress(), ipAddress);
            }
            ModifyMountTargetSecurityGroupsRequest modify = new ModifyMountTargetSecurityGroupsRequest();
            modify.setMountTargetId(existing.getMountTargetId());
            modify.setSecurityGroups(securityGroups);
            efsService.modifyMountTargetSecurityGroups(region, existing.getMountTargetId(), modify);
            mt = existing;
        } else {
            CreateMountTargetRequest request = new CreateMountTargetRequest();
            request.setFileSystemId(fileSystemId);
            request.setSubnetId(subnetId);
            request.setSecurityGroups(securityGroups);
            request.setIpAddress(ipAddress);
            mt = efsService.createMountTarget(request, region);
        }

        r.setPhysicalId(mt.getMountTargetId());
        r.getAttributes().put("Id", mt.getMountTargetId());
        r.getAttributes().put("IpAddress", mt.getIpAddress());
    }

    /** DescribeMountTargets by id answers an empty list, not an error, when the target is gone. */
    private MountTarget findMountTarget(String region, String mountTargetId) {
        DescribeMountTargetsRequest request = new DescribeMountTargetsRequest();
        request.setMountTargetId(mountTargetId);
        List<MountTarget> found = efsService.describeMountTargets(region, request).getMountTargets();
        if (found == null || found.isEmpty()) {
            LOG.debugv("EFS mount target {0} from the previous provision is gone, creating it again", mountTargetId);
            return null;
        }
        return found.getFirst();
    }

    // --- AccessPoint ---

    private void provisionAccessPoint(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        String fileSystemId = ctx.resolveOptional(props, "FileSystemId");
        String clientToken = blankToNull(ctx.resolveOptional(props, "ClientToken"));
        PosixUser posixUser = posixUser(props, ctx);
        RootDirectory rootDirectory = rootDirectory(props, ctx);
        List<Tag> tags = toTags(ctx.resolveTags(props, "AccessPointTags"));
        requireProperty(ACCESS_POINT, "FileSystemId", fileSystemId);

        AccessPointDescription existing = ctx.isUpdate() ? findAccessPoint(region, ctx.priorPhysicalId()) : null;
        AccessPointDescription ap;
        if (existing != null) {
            rejectIfChanged("FileSystemId", existing.getFileSystemId(), fileSystemId);
            if (clientToken != null) {
                rejectIfChanged("ClientToken", existing.getClientToken(), clientToken);
            }
            rejectIfChanged("PosixUser", describe(existing.getPosixUser()), describe(posixUser));
            rejectIfChanged("RootDirectory", describe(existing.getRootDirectory()), describe(rootDirectory));
            reconcileAccessPointTags(region, existing, tags);
            ap = existing;
        } else {
            CreateAccessPointRequest request = new CreateAccessPointRequest();
            request.setFileSystemId(fileSystemId);
            request.setClientToken(clientToken);
            request.setPosixUser(posixUser);
            request.setRootDirectory(rootDirectory);
            request.setTags(tags);
            ap = efsService.createAccessPoint(region, request);
        }

        r.setPhysicalId(ap.getAccessPointId());
        r.getAttributes().put("AccessPointId", ap.getAccessPointId());
        r.getAttributes().put("Arn", ap.getAccessPointArn());
    }

    private AccessPointDescription findAccessPoint(String region, String accessPointId) {
        List<AccessPointDescription> found = efsService.describeAccessPoints(region, null, accessPointId);
        if (found == null || found.isEmpty()) {
            LOG.debugv("EFS access point {0} from the previous provision is gone, creating it again", accessPointId);
            return null;
        }
        return found.getFirst();
    }

    private void reconcileAccessPointTags(String region, AccessPointDescription ap, List<Tag> desired) {
        List<String> stale = staleTagKeys(ap.getTags(), desired);
        if (!stale.isEmpty()) {
            efsService.untagResource(region, ap.getAccessPointId(), stale);
        }
        if (!desired.isEmpty()) {
            TagResourceRequest tagRequest = new TagResourceRequest();
            tagRequest.setResourceId(ap.getAccessPointId());
            tagRequest.setTags(desired);
            efsService.tagResource(region, tagRequest);
        }
    }

    private static PosixUser posixUser(JsonNode props, ProvisionContext ctx) {
        JsonNode node = resolvedObject(props, "PosixUser", ctx);
        if (node == null) {
            return null;
        }
        PosixUser user = new PosixUser();
        user.setUid(parseLong(text(node, "Uid", ctx), "PosixUser.Uid"));
        user.setGid(parseLong(text(node, "Gid", ctx), "PosixUser.Gid"));
        if (node.hasNonNull("SecondaryGids")) {
            List<Long> secondaryGids = new ArrayList<>();
            for (String gid : ctx.engine().resolveStringList(node.get("SecondaryGids"))) {
                secondaryGids.add(parseLong(gid, "PosixUser.SecondaryGids"));
            }
            user.setSecondaryGids(secondaryGids);
        }
        return user;
    }

    private static RootDirectory rootDirectory(JsonNode props, ProvisionContext ctx) {
        JsonNode node = resolvedObject(props, "RootDirectory", ctx);
        if (node == null) {
            return null;
        }
        RootDirectory root = new RootDirectory();
        root.setPath(text(node, "Path", ctx));
        JsonNode creationNode = node.hasNonNull("CreationInfo") ? ctx.engine().resolveNode(node.get("CreationInfo")) : null;
        if (creationNode != null && creationNode.isObject()) {
            CreationInfo creationInfo = new CreationInfo();
            creationInfo.setOwnerUid(parseLong(text(creationNode, "OwnerUid", ctx), "CreationInfo.OwnerUid"));
            creationInfo.setOwnerGid(parseLong(text(creationNode, "OwnerGid", ctx), "CreationInfo.OwnerGid"));
            creationInfo.setPermissions(text(creationNode, "Permissions", ctx));
            root.setCreationInfo(creationInfo);
        }
        return root;
    }

    /** A value-comparable rendering, since the EFS model types do not implement equals. */
    private static String describe(PosixUser user) {
        if (user == null) {
            return null;
        }
        return user.getUid() + ":" + user.getGid() + ":" + user.getSecondaryGids();
    }

    private static String describe(RootDirectory root) {
        if (root == null) {
            return null;
        }
        CreationInfo info = root.getCreationInfo();
        String creation = info == null ? "" : info.getOwnerUid() + ":" + info.getOwnerGid() + ":" + info.getPermissions();
        return root.getPath() + "|" + creation;
    }

    // --- Delete ---

    /**
     * A file system that still has mount targets refuses to go with {@code FileSystemInUse}, as on
     * AWS, and that failure is left to surface on the stack delete.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        switch (resourceType) {
            case FILE_SYSTEM -> CfnDeletes.safeDelete("EFS file system", physicalId,
                    () -> efsService.deleteFileSystem(region, physicalId), FILE_SYSTEM_NOT_FOUND);
            case MOUNT_TARGET -> CfnDeletes.safeDelete("EFS mount target", physicalId,
                    () -> efsService.deleteMountTarget(region, physicalId), MOUNT_TARGET_NOT_FOUND);
            case ACCESS_POINT -> CfnDeletes.safeDelete("EFS access point", physicalId,
                    () -> efsService.deleteAccessPoint(region, physicalId), ACCESS_POINT_NOT_FOUND);
            default -> throw new IllegalStateException("EfsCfnProvisioner cannot delete " + resourceType);
        }
    }

    // --- Helpers ---

    private static List<Tag> toTags(Map<String, String> tags) {
        List<Tag> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            Tag tag = new Tag();
            tag.setKey(entry.getKey());
            tag.setValue(entry.getValue());
            out.add(tag);
        }
        return out;
    }

    private static List<String> staleTagKeys(List<Tag> current, List<Tag> desired) {
        Map<String, String> currentMap = new HashMap<>();
        if (current != null) {
            for (Tag tag : current) {
                currentMap.put(tag.getKey(), tag.getValue());
            }
        }
        Map<String, String> desiredMap = new HashMap<>();
        for (Tag tag : desired) {
            desiredMap.put(tag.getKey(), tag.getValue());
        }
        return ProvisionContext.staleTagKeys(currentMap, desiredMap);
    }

    private static JsonNode resolvedObject(JsonNode props, String name, ProvisionContext ctx) {
        if (props == null || !props.hasNonNull(name)) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get(name));
        return resolved != null && resolved.isObject() ? resolved : null;
    }

    private static String nestedText(JsonNode props, String objectName, String field, ProvisionContext ctx) {
        JsonNode node = resolvedObject(props, objectName, ctx);
        return node == null ? null : blankToNull(text(node, field, ctx));
    }

    private static String text(JsonNode node, String field, ProvisionContext ctx) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        return ctx.engine().resolve(node.get(field));
    }

    private static <E extends Enum<E>> E parseEnum(String value, Function<String, E> valueOf, String property) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf.apply(value);
        } catch (IllegalArgumentException e) {
            throw invalid(property, value);
        }
    }

    private static Double parseDouble(String value, String property) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException e) {
            throw invalid(property, value);
        }
    }

    private static Long parseLong(String value, String property) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw invalid(property, value);
        }
    }

    private static AwsException invalid(String property, String value) {
        return new AwsException("ValidationError",
                "Invalid value '" + value + "' for property " + property + ".", 400);
    }

    private static void requireProperty(String type, String property, String value) {
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError",
                    type + " requires the " + property + " property.", 400);
        }
    }

    private static void rejectIfChanged(String property, String existing, String requested) {
        if (!Objects.equals(blankToNull(existing), blankToNull(requested))) {
            throw new AwsException("ValidationError",
                    "Updating " + property + " requires resource replacement, which is not supported.", 400);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
