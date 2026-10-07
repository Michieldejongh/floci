package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.efs.EfsException;
import io.github.hectorvent.floci.services.efs.EfsService;
import io.github.hectorvent.floci.services.efs.model.AccessPointDescription;
import io.github.hectorvent.floci.services.efs.model.CreateAccessPointRequest;
import io.github.hectorvent.floci.services.efs.model.CreateFileSystemRequest;
import io.github.hectorvent.floci.services.efs.model.CreateMountTargetRequest;
import io.github.hectorvent.floci.services.efs.model.DescribeMountTargetsResponse;
import io.github.hectorvent.floci.services.efs.model.FileSystem;
import io.github.hectorvent.floci.services.efs.model.LifecyclePolicy;
import io.github.hectorvent.floci.services.efs.model.ModifyMountTargetSecurityGroupsRequest;
import io.github.hectorvent.floci.services.efs.model.MountTarget;
import io.github.hectorvent.floci.services.efs.model.PerformanceMode;
import io.github.hectorvent.floci.services.efs.model.ThroughputMode;
import io.github.hectorvent.floci.services.efs.model.UpdateFileSystemRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EfsCfnProvisionerTest {

    private static final String FS_ID = "fs-0123456789abcdef0";
    private static final String FS_ARN = "arn:aws:elasticfilesystem:us-east-1:000000000000:file-system/" + FS_ID;

    private final EfsService efs = mock(EfsService.class);
    private final EfsCfnProvisioner provisioner = new EfsCfnProvisioner(efs);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        return updateCtx(null);
    }

    private ProvisionContext updateCtx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveStringList(any())).thenAnswer(inv -> {
            List<String> out = new ArrayList<>();
            ((JsonNode) inv.getArgument(0)).forEach(n -> out.add(n.asText()));
            return out;
        });
        when(engine.resolveJsonAttributeStrict(any())).thenAnswer(inv -> inv.getArgument(0).toString());
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    private StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    private static FileSystem fileSystem() {
        FileSystem fs = new FileSystem();
        fs.setFileSystemId(FS_ID);
        fs.setFileSystemArn(FS_ARN);
        fs.setPerformanceMode("generalPurpose");
        fs.setThroughputMode("bursting");
        fs.setEncrypted(true);
        fs.setTags(new ArrayList<>());
        return fs;
    }

    @Test
    void fileSystemSetsPhysicalIdAndGetAttAttributes() throws Exception {
        when(efs.createFileSystem(any(), eq("us-east-1"))).thenReturn(fileSystem());
        StackResource r = resource("AWS::EFS::FileSystem", "Fs");

        provisioner.provision(r, json("""
                {"PerformanceMode": "maxIO", "ThroughputMode": "elastic", "Encrypted": true,
                 "KmsKeyId": "alias/efs", "BackupPolicy": {"Status": "ENABLED"},
                 "FileSystemTags": [{"Key": "Name", "Value": "shared"}],
                 "LifecyclePolicies": [{"TransitionToIA": "AFTER_30_DAYS"}],
                 "FileSystemPolicy": {"Version": "2012-10-17", "Statement": []}}
                """), ctx());

        assertEquals(FS_ID, r.getPhysicalId());
        assertEquals(FS_ID, r.getAttributes().get("FileSystemId"));
        assertEquals(FS_ARN, r.getAttributes().get("Arn"));

        ArgumentCaptor<CreateFileSystemRequest> request = ArgumentCaptor.forClass(CreateFileSystemRequest.class);
        verify(efs).createFileSystem(request.capture(), eq("us-east-1"));
        assertEquals(PerformanceMode.maxIO, request.getValue().getPerformanceMode());
        assertEquals(ThroughputMode.elastic, request.getValue().getThroughputMode());
        assertEquals(Boolean.TRUE, request.getValue().getEncrypted());
        assertEquals("alias/efs", request.getValue().getKmsKeyId());
        assertEquals(Boolean.TRUE, request.getValue().getBackup());
        assertEquals("shared", request.getValue().getTags().getFirst().getValue());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LifecyclePolicy>> lifecycle = ArgumentCaptor.forClass(List.class);
        verify(efs).putLifecycleConfiguration(eq("us-east-1"), eq(FS_ID), lifecycle.capture());
        assertEquals("AFTER_30_DAYS", lifecycle.getValue().getFirst().getTransitionToIA());
        verify(efs).putFileSystemPolicy(eq("us-east-1"), eq(FS_ID), eq("{\"Version\":\"2012-10-17\",\"Statement\":[]}"));
    }

    @Test
    void fileSystemWithoutBackupPolicyDefaultsToBackupsOff() throws Exception {
        when(efs.createFileSystem(any(), eq("us-east-1"))).thenReturn(fileSystem());

        provisioner.provision(resource("AWS::EFS::FileSystem", "Fs"), json("{}"), ctx());

        ArgumentCaptor<CreateFileSystemRequest> request = ArgumentCaptor.forClass(CreateFileSystemRequest.class);
        verify(efs).createFileSystem(request.capture(), eq("us-east-1"));
        assertEquals(Boolean.FALSE, request.getValue().getBackup());
        verify(efs, never()).putLifecycleConfiguration(anyString(), anyString(), anyList());
        verify(efs, never()).putFileSystemPolicy(anyString(), anyString(), anyString());
    }

    @Test
    void fileSystemUpdateChangesThroughputInPlace() throws Exception {
        when(efs.getFileSystem("us-east-1", FS_ID)).thenReturn(fileSystem());
        when(efs.updateFileSystem(eq("us-east-1"), eq(FS_ID), any())).thenReturn(fileSystem());
        StackResource r = resource("AWS::EFS::FileSystem", "Fs");

        provisioner.provision(r, json("{\"Encrypted\": true, \"ThroughputMode\": \"elastic\"}"), updateCtx(FS_ID));

        verify(efs, never()).createFileSystem(any(), anyString());
        ArgumentCaptor<UpdateFileSystemRequest> update = ArgumentCaptor.forClass(UpdateFileSystemRequest.class);
        verify(efs).updateFileSystem(eq("us-east-1"), eq(FS_ID), update.capture());
        assertEquals(ThroughputMode.elastic, update.getValue().getThroughputMode());
        assertEquals(FS_ID, r.getPhysicalId());
        assertEquals(FS_ARN, r.getAttributes().get("Arn"));
    }

    @Test
    void fileSystemUpdateRefusesCreateOnlyChange() throws Exception {
        when(efs.getFileSystem("us-east-1", FS_ID)).thenReturn(fileSystem());

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::EFS::FileSystem", "Fs"),
                json("{\"Encrypted\": true, \"PerformanceMode\": \"maxIO\"}"), updateCtx(FS_ID)));

        assertTrue(e.getMessage().contains("PerformanceMode"));
        verify(efs, never()).updateFileSystem(anyString(), anyString(), any());
    }

    @Test
    void fileSystemRejectsUnknownThroughputMode() throws Exception {
        assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::EFS::FileSystem", "Fs"), json("{\"ThroughputMode\": \"turbo\"}"), ctx()));
        verify(efs, never()).createFileSystem(any(), anyString());
    }

    @Test
    void mountTargetSetsIdAndIpAddress() throws Exception {
        MountTarget mt = new MountTarget();
        mt.setMountTargetId("fsmt-0123456789abcdef0");
        mt.setIpAddress("10.0.1.25");
        when(efs.createMountTarget(any(), eq("us-east-1"))).thenReturn(mt);
        StackResource r = resource("AWS::EFS::MountTarget", "Mt");

        provisioner.provision(r, json("""
                {"FileSystemId": "%s", "SubnetId": "subnet-1", "SecurityGroups": ["sg-1", "sg-2"],
                 "IpAddress": "10.0.1.25"}
                """.formatted(FS_ID)), ctx());

        assertEquals("fsmt-0123456789abcdef0", r.getPhysicalId());
        assertEquals("fsmt-0123456789abcdef0", r.getAttributes().get("Id"));
        assertEquals("10.0.1.25", r.getAttributes().get("IpAddress"));
        ArgumentCaptor<CreateMountTargetRequest> request = ArgumentCaptor.forClass(CreateMountTargetRequest.class);
        verify(efs).createMountTarget(request.capture(), eq("us-east-1"));
        assertEquals(FS_ID, request.getValue().getFileSystemId());
        assertEquals("subnet-1", request.getValue().getSubnetId());
        assertEquals(List.of("sg-1", "sg-2"), request.getValue().getSecurityGroups());
        assertEquals("10.0.1.25", request.getValue().getIpAddress());
    }

    @Test
    void mountTargetUpdateReplacesSecurityGroupsInPlace() throws Exception {
        MountTarget mt = new MountTarget();
        mt.setMountTargetId("fsmt-1");
        mt.setFileSystemId(FS_ID);
        mt.setSubnetId("subnet-1");
        mt.setIpAddress("10.0.0.10");
        DescribeMountTargetsResponse described = new DescribeMountTargetsResponse();
        described.setMountTargets(List.of(mt));
        when(efs.describeMountTargets(eq("us-east-1"), any())).thenReturn(described);
        StackResource r = resource("AWS::EFS::MountTarget", "Mt");

        provisioner.provision(r, json("""
                {"FileSystemId": "%s", "SubnetId": "subnet-1", "SecurityGroups": ["sg-3"]}
                """.formatted(FS_ID)), updateCtx("fsmt-1"));

        verify(efs, never()).createMountTarget(any(), anyString());
        ArgumentCaptor<ModifyMountTargetSecurityGroupsRequest> modify =
                ArgumentCaptor.forClass(ModifyMountTargetSecurityGroupsRequest.class);
        verify(efs).modifyMountTargetSecurityGroups(eq("us-east-1"), eq("fsmt-1"), modify.capture());
        assertEquals(List.of("sg-3"), modify.getValue().getSecurityGroups());
        assertEquals("fsmt-1", r.getAttributes().get("Id"));
        assertEquals("10.0.0.10", r.getAttributes().get("IpAddress"));
    }

    @Test
    void accessPointPassesPosixUserAndRootDirectory() throws Exception {
        AccessPointDescription ap = new AccessPointDescription();
        ap.setAccessPointId("fsap-0123456789abcdef0");
        ap.setAccessPointArn("arn:aws:elasticfilesystem:us-east-1:000000000000:access-point/fsap-0123456789abcdef0");
        when(efs.createAccessPoint(eq("us-east-1"), any())).thenReturn(ap);
        StackResource r = resource("AWS::EFS::AccessPoint", "Ap");

        provisioner.provision(r, json("""
                {"FileSystemId": "%s", "ClientToken": "token-1",
                 "PosixUser": {"Uid": "1000", "Gid": "1000", "SecondaryGids": ["2000"]},
                 "RootDirectory": {"Path": "/data",
                   "CreationInfo": {"OwnerUid": "1000", "OwnerGid": "1000", "Permissions": "0755"}},
                 "AccessPointTags": [{"Key": "Name", "Value": "data"}]}
                """.formatted(FS_ID)), ctx());

        assertEquals("fsap-0123456789abcdef0", r.getPhysicalId());
        assertEquals("fsap-0123456789abcdef0", r.getAttributes().get("AccessPointId"));
        assertEquals(ap.getAccessPointArn(), r.getAttributes().get("Arn"));
        ArgumentCaptor<CreateAccessPointRequest> request = ArgumentCaptor.forClass(CreateAccessPointRequest.class);
        verify(efs).createAccessPoint(eq("us-east-1"), request.capture());
        assertEquals(FS_ID, request.getValue().getFileSystemId());
        assertEquals("token-1", request.getValue().getClientToken());
        assertEquals(1000L, request.getValue().getPosixUser().getUid());
        assertEquals(List.of(2000L), request.getValue().getPosixUser().getSecondaryGids());
        assertEquals("/data", request.getValue().getRootDirectory().getPath());
        assertEquals("0755", request.getValue().getRootDirectory().getCreationInfo().getPermissions());
        assertEquals("data", request.getValue().getTags().getFirst().getValue());
    }

    @Test
    void accessPointWithoutFileSystemIdIsRejected() throws Exception {
        assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::EFS::AccessPoint", "Ap"), json("{}"), ctx()));
        verify(efs, never()).createAccessPoint(anyString(), any());
    }

    @Test
    void deleteToleratesAlreadyGoneResources() {
        doThrow(EfsException.fileSystemNotFound(FS_ID)).when(efs).deleteFileSystem("us-east-1", FS_ID);
        doThrow(EfsException.mountTargetNotFound("fsmt-1")).when(efs).deleteMountTarget("us-east-1", "fsmt-1");
        doThrow(EfsException.accessPointNotFound("fsap-1")).when(efs).deleteAccessPoint("us-east-1", "fsap-1");

        provisioner.delete("AWS::EFS::FileSystem", FS_ID, "us-east-1");
        provisioner.delete("AWS::EFS::MountTarget", "fsmt-1", "us-east-1");
        provisioner.delete("AWS::EFS::AccessPoint", "fsap-1", "us-east-1");

        verify(efs).deleteFileSystem("us-east-1", FS_ID);
        verify(efs).deleteMountTarget("us-east-1", "fsmt-1");
        verify(efs).deleteAccessPoint("us-east-1", "fsap-1");
    }

    @Test
    void deleteOfFileSystemInUsePropagates() {
        doThrow(EfsException.fileSystemInUse(FS_ID)).when(efs).deleteFileSystem("us-east-1", FS_ID);

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::EFS::FileSystem", FS_ID, "us-east-1"));
        assertEquals("FileSystemInUse", e.getErrorCode());
    }

    @Test
    void deleteWithoutPhysicalIdDoesNothing() {
        provisioner.delete("AWS::EFS::FileSystem", null, "us-east-1");
        verify(efs, never()).deleteFileSystem(anyString(), anyString());
    }
}
