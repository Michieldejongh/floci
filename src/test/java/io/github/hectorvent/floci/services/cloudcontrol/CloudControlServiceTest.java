package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudControlServiceTest {

    @Test
    void accountScopesCreateStatusLookupAndDelete() throws Exception {
        CfnResourceDispatcher provisioner = mock(CfnResourceDispatcher.class);
        StackResource resource = new StackResource();
        resource.setPhysicalId("vpc-account-a");
        resource.setAttributes(Map.of("VpcId", "vpc-account-a"));
        when(provisioner.provisionStandalone(eq("AWS::EC2::VPC"),
                any(), eq("us-east-1"),
                eq("111111111111"))).thenReturn(resource);
        CloudControlService service = new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class), provisioner,
                new ObjectMapper());

        CloudControlService.ProgressEvent pending = service.createResource(
                "us-east-1", "111111111111", "AWS::EC2::VPC", "{\"CidrBlock\":\"10.0.0.0/16\"}");
        CloudControlService.ProgressEvent completed = pending;
        for (int i = 0; i < 20 && !"SUCCESS".equals(completed.operationStatus()); i++) {
            Thread.sleep(10);
            completed = service.requestStatus("111111111111", pending.requestToken());
        }
        assertEquals("SUCCESS", completed.operationStatus());
        assertEquals("vpc-account-a", service.getResource("us-east-1", "111111111111",
                "AWS::EC2::VPC", "vpc-account-a").identifier());
        assertThrows(AwsException.class, () -> service.requestStatus("222222222222", pending.requestToken()));
        assertThrows(AwsException.class, () -> service.getResource("us-east-1", "222222222222",
                "AWS::EC2::VPC", "vpc-account-a"));

        CloudControlService.ProgressEvent deniedDelete = service.deleteResource(
                "us-east-1", "222222222222", "AWS::EC2::VPC", "vpc-account-a");
        assertEquals("FAILED", deniedDelete.operationStatus());
        verify(provisioner, never()).deleteStandalone(anyString(), anyString(), anyString(), anyMap());

        CloudControlService.ProgressEvent deleted = service.deleteResource(
                "us-east-1", "111111111111", "AWS::EC2::VPC", "vpc-account-a");
        assertEquals("SUCCESS", deleted.operationStatus());
        verify(provisioner).deleteStandalone("AWS::EC2::VPC", "vpc-account-a", "us-east-1",
                Map.of("VpcId", "vpc-account-a"));
    }

    @Test
    void restoresAccountOwnersAndRequestStateFromMetadataStores() throws Exception {
        CfnResourceDispatcher provisioner = mock(CfnResourceDispatcher.class);
        StackResource resource = new StackResource();
        resource.setPhysicalId("igw-persisted");
        when(provisioner.provisionStandalone(eq("AWS::EC2::InternetGateway"),
                any(), eq("us-east-1"),
                eq("111111111111"))).thenReturn(resource);
        AccountAwareStorageBackend<CloudControlService.PersistedRequest> requests =
                AccountAwareStorageBackend.inMemory("000000000000");
        AccountAwareStorageBackend<CloudControlService.PersistedCreatedResource> created =
                AccountAwareStorageBackend.inMemory("000000000000");
        CloudControlService first = new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class), provisioner,
                new ObjectMapper(), requests, created);
        CloudControlService.ProgressEvent pending = first.createResource("us-east-1", "111111111111",
                "AWS::EC2::InternetGateway", "{}");
        CloudControlService.ProgressEvent completed = first.requestStatus("111111111111", pending.requestToken());
        for (int i = 0; i < 20 && !"SUCCESS".equals(completed.operationStatus()); i++) {
            Thread.sleep(10);
            completed = first.requestStatus("111111111111", pending.requestToken());
        }
        CloudControlService restarted = new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class), provisioner,
                new ObjectMapper(), requests, created);

        assertEquals("SUCCESS", restarted.requestStatus("111111111111", pending.requestToken()).operationStatus());
        assertEquals("igw-persisted", restarted.getResource("us-east-1", "111111111111",
                "AWS::EC2::InternetGateway", "igw-persisted").identifier());
        assertThrows(AwsException.class, () -> restarted.requestStatus("222222222222", pending.requestToken()));
        assertThrows(AwsException.class, () -> restarted.getResource("us-east-1", "222222222222",
                "AWS::EC2::InternetGateway", "igw-persisted"));
    }

    @Test
    void emitsOnlyAwsShapedTagsForMalformedPersistedData() throws Exception {
        Ec2Service ec2Service = mock(Ec2Service.class);
        Vpc vpc = new Vpc();
        vpc.setVpcId("vpc-test");
        vpc.setTags(List.of(
                new Tag(null, "ignored-null"),
                new Tag("", "ignored-empty"),
                new Tag("  ", "ignored-blank"),
                new Tag("Name", null)));
        when(ec2Service.describeVpcs("us-east-1", List.of(), Map.of())).thenReturn(List.of(vpc));
        ObjectMapper mapper = new ObjectMapper();
        CloudControlService service = new CloudControlService(
                mock(S3Service.class), ec2Service, mock(IamService.class),
                mock(CfnResourceDispatcher.class), mapper);

        String properties = service.listResources("us-east-1", "AWS::EC2::VPC").getFirst().properties();
        JsonNode tags = mapper.readTree(properties).path("Tags");

        assertTrue(tags.isArray());
        assertEquals(1, tags.size());
        assertTrue(tags.get(0).path("Key").isTextual());
        assertEquals("Name", tags.get(0).path("Key").asText());
        assertTrue(tags.get(0).path("Value").isTextual());
        assertEquals("", tags.get(0).path("Value").asText());
        assertFalse(properties.contains("ignored-null"));
        assertFalse(properties.contains("ignored-empty"));
        assertFalse(properties.contains("ignored-blank"));
    }

    @Test
    void listResourcesRejectsARealButUnbackedTypeInsteadOfReturningEmpty() {
        CloudControlService service = new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class),
                mock(CfnResourceDispatcher.class), new ObjectMapper());

        AwsException e = assertThrows(AwsException.class,
                () -> service.listResources("us-east-1", "AWS::SQS::Queue"));

        assertEquals("UnsupportedActionException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void listResourcesRejectsATypeThatDoesNotExistInAwsAtAll() {
        CloudControlService service = new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class),
                mock(CfnResourceDispatcher.class), new ObjectMapper());

        AwsException e = assertThrows(AwsException.class,
                () -> service.listResources("us-east-1", "AWS::NoSuch::Type"));

        assertEquals("UnsupportedActionException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void listResourcesStillReturnsASupportedType() {
        S3Service s3Service = mock(S3Service.class);
        when(s3Service.listBuckets()).thenReturn(List.of());
        CloudControlService service = new CloudControlService(
                s3Service, mock(Ec2Service.class), mock(IamService.class),
                mock(CfnResourceDispatcher.class), new ObjectMapper());

        assertTrue(service.listResources("us-east-1", "AWS::S3::Bucket").isEmpty());
    }

    @Test
    void getResourceReturnsReadOnlyAttributesOfACreatedResource() throws Exception {
        CfnResourceDispatcher provisioner = mock(CfnResourceDispatcher.class);
        StackResource resource = new StackResource();
        resource.setPhysicalId("cluster-1");
        resource.setAttributes(Map.of(
                "Arn", "arn:aws:memorydb:us-east-1:111111111111:cluster/cluster-1",
                "ClusterEndpoint.Address", "localhost",
                "ClusterEndpoint.Port", "6379",
                "Description", "from-provisioner",
                "__FlociCreateOnly", "internal",
                "FlociLogGroupNameMode", "internal"));
        when(provisioner.provisionStandalone(eq("AWS::MemoryDB::Cluster"),
                any(), eq("us-east-1"),
                eq("111111111111"))).thenReturn(resource);
        ObjectMapper mapper = new ObjectMapper();
        CloudControlService service = new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class), provisioner, mapper);

        CloudControlService.ProgressEvent pending = service.createResource("us-east-1", "111111111111",
                "AWS::MemoryDB::Cluster", "{\"ClusterName\":\"cluster-1\",\"Description\":\"desired\"}");
        CloudControlService.ProgressEvent completed = pending;
        for (int i = 0; i < 20 && !"SUCCESS".equals(completed.operationStatus()); i++) {
            Thread.sleep(10);
            completed = service.requestStatus("111111111111", pending.requestToken());
        }
        assertEquals("SUCCESS", completed.operationStatus());

        JsonNode properties = mapper.readTree(service.getResource("us-east-1", "111111111111",
                "AWS::MemoryDB::Cluster", "cluster-1").properties());
        assertEquals("arn:aws:memorydb:us-east-1:111111111111:cluster/cluster-1", properties.path("Arn").asText());
        assertEquals("localhost", properties.path("ClusterEndpoint").path("Address").asText());
        assertEquals("6379", properties.path("ClusterEndpoint").path("Port").asText());
        assertEquals("desired", properties.path("Description").asText());
        assertEquals("cluster-1", properties.path("ClusterName").asText());
        assertFalse(properties.has("__FlociCreateOnly"));
        assertFalse(properties.has("FlociLogGroupNameMode"));
    }

    private static final String ACCOUNT = "111111111111";
    private static final String QUEUE_URL = "http://localhost:4566/111111111111/q";

    private static StackResource queue(String status, Map<String, String> attributes) {
        StackResource resource = new StackResource();
        resource.setPhysicalId(QUEUE_URL);
        resource.setStatus(status);
        resource.setAttributes(attributes);
        return resource;
    }

    private static CloudControlService.ProgressEvent await(CloudControlService service,
                                                           CloudControlService.ProgressEvent pending)
            throws InterruptedException {
        CloudControlService.ProgressEvent current = pending;
        for (int i = 0; i < 100 && "IN_PROGRESS".equals(current.operationStatus()); i++) {
            Thread.sleep(10);
            current = service.requestStatus(ACCOUNT, pending.requestToken());
        }
        return current;
    }

    private static CloudControlService createdQueue(CfnResourceDispatcher provisioner,
                                                    AccountAwareStorageBackend<CloudControlService.PersistedRequest> requests,
                                                    AccountAwareStorageBackend<CloudControlService.PersistedCreatedResource> created)
            throws InterruptedException {
        when(provisioner.provisionStandalone(eq("AWS::SQS::Queue"), any(), eq("us-east-1"), eq(ACCOUNT)))
                .thenReturn(queue("CREATE_COMPLETE",
                        Map.of("Arn", "arn:aws:sqs:us-east-1:111111111111:q", "QueueName", "q", "QueueUrl", QUEUE_URL)));
        CloudControlService service = new CloudControlService(mock(S3Service.class), mock(Ec2Service.class),
                mock(IamService.class), provisioner, new ObjectMapper(), requests, created);
        CloudControlService.ProgressEvent done = await(service, service.createResource("us-east-1", ACCOUNT,
                "AWS::SQS::Queue", "{\"VisibilityTimeout\":30,\"Tags\":[{\"Key\":\"k\",\"Value\":\"v\"}]}"));
        assertEquals("SUCCESS", done.operationStatus());
        return service;
    }

    @Test
    void updateResourceProvisionsThePatchedDesiredStateOverTheExistingResource() throws Exception {
        CfnResourceDispatcher provisioner = mock(CfnResourceDispatcher.class);
        AccountAwareStorageBackend<CloudControlService.PersistedRequest> requests =
                AccountAwareStorageBackend.inMemory("000000000000");
        AccountAwareStorageBackend<CloudControlService.PersistedCreatedResource> created =
                AccountAwareStorageBackend.inMemory("000000000000");
        CloudControlService service = createdQueue(provisioner, requests, created);
        Map<String, String> updatedAttributes = Map.of("Arn", "arn:aws:sqs:us-east-1:111111111111:q",
                "QueueName", "q", "QueueUrl", QUEUE_URL, "__FlociSqsUpdateSnapshot", "{}");
        when(provisioner.provisionStandalone(eq("AWS::SQS::Queue"), any(), eq("us-east-1"), eq(ACCOUNT),
                eq(QUEUE_URL), anyMap())).thenReturn(queue("CREATE_COMPLETE", updatedAttributes));

        CloudControlService.ProgressEvent pending = service.updateResource("us-east-1", ACCOUNT, "AWS::SQS::Queue",
                QUEUE_URL, "[{\"op\":\"replace\",\"path\":\"/VisibilityTimeout\",\"value\":45},"
                        + "{\"op\":\"remove\",\"path\":\"/Tags\"}]");
        assertEquals("UPDATE", pending.operation());
        assertEquals(QUEUE_URL, pending.identifier());
        CloudControlService.ProgressEvent done = await(service, pending);
        assertEquals("SUCCESS", done.operationStatus(), done.statusMessage());

        ArgumentCaptor<JsonNode> props = ArgumentCaptor.forClass(JsonNode.class);
        verify(provisioner).provisionStandalone(eq("AWS::SQS::Queue"), props.capture(), eq("us-east-1"),
                eq(ACCOUNT), eq(QUEUE_URL),
                eq(Map.of("Arn", "arn:aws:sqs:us-east-1:111111111111:q", "QueueName", "q", "QueueUrl", QUEUE_URL)));
        // Only the inputs: the model's read-only Arn, QueueUrl and Id are not handed back as properties.
        assertEquals(new ObjectMapper().readTree("{\"VisibilityTimeout\":45}"), props.getValue());
        verify(provisioner).clearUpdate(any());
        assertEquals(45, new ObjectMapper().readTree(done.resourceModel()).path("VisibilityTimeout").asInt());

        // The new model, attributes and desired state survive a restart.
        CloudControlService restarted = new CloudControlService(mock(S3Service.class), mock(Ec2Service.class),
                mock(IamService.class), provisioner, new ObjectMapper(), requests, created);
        JsonNode model = new ObjectMapper().readTree(
                restarted.getResource("us-east-1", ACCOUNT, "AWS::SQS::Queue", QUEUE_URL).properties());
        assertEquals(45, model.path("VisibilityTimeout").asInt());
        assertFalse(model.has("Tags"));
        assertEquals("SUCCESS", restarted.requestStatus(ACCOUNT, pending.requestToken()).operationStatus());
    }

    @Test
    void updateResourceKeepsInputsTheReadSideModelDoesNotReport() throws Exception {
        CfnResourceDispatcher provisioner = mock(CfnResourceDispatcher.class);
        IamService iamService = mock(IamService.class);
        IamRole role = new IamRole();
        role.setRoleName("r");
        role.setPath("/");
        role.setArn("arn:aws:iam::111111111111:role/r");
        when(iamService.listRoles("/")).thenReturn(List.of(role));
        StackResource resource = new StackResource();
        resource.setPhysicalId("r");
        resource.setStatus("CREATE_COMPLETE");
        resource.setAttributes(Map.of("Arn", "arn:aws:iam::111111111111:role/r", "RoleId", "AROA1"));
        when(provisioner.provisionStandalone(eq("AWS::IAM::Role"), any(), eq("us-east-1"), eq(ACCOUNT)))
                .thenReturn(resource);
        when(provisioner.provisionStandalone(eq("AWS::IAM::Role"), any(), eq("us-east-1"), eq(ACCOUNT),
                eq("r"), anyMap())).thenReturn(resource);
        CloudControlService service = new CloudControlService(mock(S3Service.class), mock(Ec2Service.class),
                iamService, provisioner, new ObjectMapper());
        await(service, service.createResource("us-east-1", ACCOUNT, "AWS::IAM::Role",
                "{\"RoleName\":\"r\",\"AssumeRolePolicyDocument\":{\"Version\":\"2012-10-17\"}}"));

        CloudControlService.ProgressEvent done = await(service, service.updateResource("us-east-1", ACCOUNT,
                "AWS::IAM::Role", "r", "[{\"op\":\"add\",\"path\":\"/Description\",\"value\":\"d\"}]"));

        assertEquals("SUCCESS", done.operationStatus(), done.statusMessage());
        ArgumentCaptor<JsonNode> props = ArgumentCaptor.forClass(JsonNode.class);
        verify(provisioner).provisionStandalone(eq("AWS::IAM::Role"), props.capture(), eq("us-east-1"),
                eq(ACCOUNT), eq("r"), anyMap());
        // The read side lists only Arn, RoleName and Path; the trust policy comes from the desired state.
        assertEquals("2012-10-17", props.getValue().path("AssumeRolePolicyDocument").path("Version").asText());
        assertEquals("d", props.getValue().path("Description").asText());
    }

    @Test
    void updateResourceRejectsACreateOnlyChangeBeforeProvisioning() throws Exception {
        CfnResourceDispatcher provisioner = mock(CfnResourceDispatcher.class);
        CloudControlService service = createdQueue(provisioner,
                AccountAwareStorageBackend.inMemory("000000000000"), AccountAwareStorageBackend.inMemory("000000000000"));

        AwsException e = assertThrows(AwsException.class, () -> service.updateResource("us-east-1", ACCOUNT,
                "AWS::SQS::Queue", QUEUE_URL, "[{\"op\":\"add\",\"path\":\"/FifoQueue\",\"value\":true}]"));

        assertEquals("NotUpdatableException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        verify(provisioner, never()).provisionStandalone(anyString(), any(), anyString(), anyString(),
                anyString(), anyMap());
    }

    @Test
    void updateResourceRejectsInvalidPatchesAndReadOnlyProperties() throws Exception {
        CfnResourceDispatcher provisioner = mock(CfnResourceDispatcher.class);
        CloudControlService service = createdQueue(provisioner,
                AccountAwareStorageBackend.inMemory("000000000000"), AccountAwareStorageBackend.inMemory("000000000000"));

        for (String patch : List.of("{}", "[{\"op\":\"frobnicate\",\"path\":\"/A\"}]",
                "[{\"op\":\"replace\",\"path\":\"/Missing\",\"value\":1}]",
                "[{\"op\":\"test\",\"path\":\"/VisibilityTimeout\",\"value\":31}]",
                "[{\"op\":\"replace\",\"path\":\"/Arn\",\"value\":\"arn:aws:sqs:us-east-1:111111111111:x\"}]")) {
            AwsException e = assertThrows(AwsException.class, () -> service.updateResource("us-east-1", ACCOUNT,
                    "AWS::SQS::Queue", QUEUE_URL, patch), patch);
            assertEquals("InvalidRequestException", e.getErrorCode(), patch);
        }
        verify(provisioner, never()).provisionStandalone(anyString(), any(), anyString(), anyString(),
                anyString(), anyMap());
    }

    @Test
    void updateResourceReportsNotFoundForAnUnknownIdentifier() {
        CloudControlService service = new CloudControlService(mock(S3Service.class), mock(Ec2Service.class),
                mock(IamService.class), mock(CfnResourceDispatcher.class), new ObjectMapper());

        AwsException e = assertThrows(AwsException.class, () -> service.updateResource("us-east-1", ACCOUNT,
                "AWS::SQS::Queue", "nope", "[]"));

        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void updateResourceFailsWhenTheProvisionerFails() throws Exception {
        CfnResourceDispatcher provisioner = mock(CfnResourceDispatcher.class);
        CloudControlService service = createdQueue(provisioner,
                AccountAwareStorageBackend.inMemory("000000000000"), AccountAwareStorageBackend.inMemory("000000000000"));
        StackResource failed = queue("CREATE_FAILED", Map.of());
        failed.setStatusReason("boom");
        when(provisioner.provisionStandalone(eq("AWS::SQS::Queue"), any(), eq("us-east-1"), eq(ACCOUNT),
                eq(QUEUE_URL), anyMap())).thenReturn(failed);

        CloudControlService.ProgressEvent done = await(service, service.updateResource("us-east-1", ACCOUNT,
                "AWS::SQS::Queue", QUEUE_URL, "[{\"op\":\"replace\",\"path\":\"/VisibilityTimeout\",\"value\":45}]"));

        assertEquals("FAILED", done.operationStatus());
        assertEquals("boom", done.statusMessage());
        assertEquals(30, new ObjectMapper().readTree(service.getResource("us-east-1", ACCOUNT, "AWS::SQS::Queue",
                QUEUE_URL).properties()).path("VisibilityTimeout").asInt());
    }

    @Test
    void anUpdateInterruptedByARestartIsReportedFailed() {
        AccountAwareStorageBackend<CloudControlService.PersistedRequest> requests =
                AccountAwareStorageBackend.inMemory("000000000000");
        requests.putForAccount(ACCOUNT, "tok", new CloudControlService.PersistedRequest(
                new CloudControlService.ProgressEvent("AWS::SQS::Queue", QUEUE_URL, "tok", "UPDATE", "IN_PROGRESS",
                        null, null, ACCOUNT), "us-east-1", "{}", 1L));

        CloudControlService restarted = new CloudControlService(mock(S3Service.class), mock(Ec2Service.class),
                mock(IamService.class), mock(CfnResourceDispatcher.class), new ObjectMapper(), requests,
                AccountAwareStorageBackend.inMemory("000000000000"));

        assertEquals("FAILED", restarted.requestStatus(ACCOUNT, "tok").operationStatus());
    }
}
