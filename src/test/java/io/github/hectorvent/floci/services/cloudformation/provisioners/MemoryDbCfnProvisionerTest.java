package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.memorydb.MemoryDbService;
import io.github.hectorvent.floci.services.memorydb.model.Acl;
import io.github.hectorvent.floci.services.memorydb.model.AuthMode;
import io.github.hectorvent.floci.services.memorydb.model.Cluster;
import io.github.hectorvent.floci.services.memorydb.model.ClusterStatus;
import io.github.hectorvent.floci.services.memorydb.model.Endpoint;
import io.github.hectorvent.floci.services.memorydb.model.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The MemoryDB CFN provisioner in isolation, against a mocked {@link MemoryDbService}. */
class MemoryDbCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final MemoryDbService memoryDb = mock(MemoryDbService.class);
    private final MemoryDbCfnProvisioner provisioner = new MemoryDbCfnProvisioner(memoryDb);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        return ctx(null);
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveStringList(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            List<String> values = new ArrayList<>();
            if (node != null) {
                node.forEach(n -> values.add(n.asText()));
            }
            return values;
        });
        return new ProvisionContext(engine, REGION, ACCOUNT, "my-stack", priorPhysicalId);
    }

    private static StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static Cluster cluster(String name, String description) {
        Cluster c = new Cluster();
        c.setName(name);
        c.setDescription(description);
        c.setStatus(ClusterStatus.AVAILABLE);
        c.setArn("arn:aws:memorydb:us-east-1:000000000000:cluster/" + name);
        c.setClusterEndpoint(new Endpoint("localhost", 6400));
        return c;
    }

    @Test
    void userIsCreatedWithPasswordAuthenticationAndExposesArnAndStatus() {
        when(memoryDb.createUser(any(), eq(REGION))).thenAnswer(inv -> {
            User spec = inv.getArgument(0);
            spec.setArn("arn:aws:memorydb:us-east-1:000000000000:user/" + spec.getName());
            spec.setStatus("active");
            return spec;
        });
        StackResource r = resource("AWS::MemoryDB::User", "AppUser");
        ObjectNode props = mapper.createObjectNode()
                .put("UserName", "app-user")
                .put("AccessString", "on ~* &* +@all");
        props.putObject("AuthenticationMode").put("Type", "password")
                .putArray("Passwords").add("a-long-enough-password");

        provisioner.provision(r, props, ctx());

        ArgumentCaptor<User> spec = ArgumentCaptor.forClass(User.class);
        verify(memoryDb).createUser(spec.capture(), eq(REGION));
        assertEquals("app-user", spec.getValue().getName());
        assertEquals("on ~* &* +@all", spec.getValue().getAccessString());
        assertEquals(AuthMode.PASSWORD, spec.getValue().getAuthMode());
        assertEquals(List.of("a-long-enough-password"), spec.getValue().getPasswords());
        assertEquals("app-user", r.getPhysicalId());
        assertEquals("arn:aws:memorydb:us-east-1:000000000000:user/app-user", r.getAttributes().get("Arn"));
        assertEquals("active", r.getAttributes().get("Status"));
    }

    @Test
    void aclIsCreatedWithItsUsersAndExposesArnAndStatus() {
        when(memoryDb.createAcl(any(), eq(REGION))).thenAnswer(inv -> {
            Acl spec = inv.getArgument(0);
            spec.setArn("arn:aws:memorydb:us-east-1:000000000000:acl/" + spec.getName());
            spec.setStatus("active");
            return spec;
        });
        StackResource r = resource("AWS::MemoryDB::ACL", "AppAcl");
        ObjectNode props = mapper.createObjectNode().put("ACLName", "app-acl");
        props.putArray("UserNames").add("default").add("app-user");

        provisioner.provision(r, props, ctx());

        ArgumentCaptor<Acl> spec = ArgumentCaptor.forClass(Acl.class);
        verify(memoryDb).createAcl(spec.capture(), eq(REGION));
        assertEquals(List.of("default", "app-user"), spec.getValue().getUserNames());
        assertEquals("app-acl", r.getPhysicalId());
        assertEquals("arn:aws:memorydb:us-east-1:000000000000:acl/app-acl", r.getAttributes().get("Arn"));
        assertEquals("active", r.getAttributes().get("Status"));
    }

    @Test
    void clusterIsCreatedThroughTheServiceAndExposesEndpointAttributes() {
        when(memoryDb.createCluster(any(), eq(REGION))).thenReturn(cluster("app-cache", "cache"));
        StackResource r = resource("AWS::MemoryDB::Cluster", "Cache");
        ObjectNode props = mapper.createObjectNode()
                .put("ClusterName", "app-cache")
                .put("ACLName", "app-acl")
                .put("NodeType", "db.t4g.small")
                .put("Engine", "valkey")
                .put("EngineVersion", "7.2")
                .put("NumShards", 2)
                .put("NumReplicasPerShard", 1)
                .put("TLSEnabled", true)
                .put("Port", 6379)
                .put("KmsKeyId", "alias/aws/memorydb")
                .put("ParameterGroupName", "default.memorydb-valkey7")
                .put("SubnetGroupName", "app-subnets")
                .put("MaintenanceWindow", "sun:23:00-mon:01:30")
                .put("SnapshotWindow", "04:00-05:00")
                .put("SnapshotRetentionLimit", 1)
                .put("AutoMinorVersionUpgrade", true)
                .put("Description", "cache");
        props.putArray("SecurityGroupIds").add("sg-123");
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "core");

        provisioner.provision(r, props, ctx());

        ArgumentCaptor<Cluster> spec = ArgumentCaptor.forClass(Cluster.class);
        verify(memoryDb).createCluster(spec.capture(), eq(REGION));
        Cluster sent = spec.getValue();
        assertEquals("app-cache", sent.getName());
        assertEquals("app-acl", sent.getAclName());
        assertEquals("db.t4g.small", sent.getNodeType());
        assertEquals("valkey", sent.getEngine());
        assertEquals("7.2", sent.getEngineVersion());
        assertEquals(2, sent.getNumberOfShards());
        assertTrue(sent.isTlsEnabled());
        assertEquals("cache", sent.getDescription());
        assertEquals(Map.of("team", "core"), sent.getTags());

        assertEquals("app-cache", r.getPhysicalId());
        assertEquals("arn:aws:memorydb:us-east-1:000000000000:cluster/app-cache", r.getAttributes().get("ARN"));
        assertEquals("available", r.getAttributes().get("Status"));
        assertEquals("localhost", r.getAttributes().get("ClusterEndpoint.Address"));
        assertEquals("6400", r.getAttributes().get("ClusterEndpoint.Port"));
        assertEquals("in-sync", r.getAttributes().get("ParameterGroupStatus"));
    }

    @Test
    void clusterUpdateKeepsTheClusterAndAppliesTheDescription() {
        when(memoryDb.getCluster("app-cache", REGION)).thenReturn(cluster("app-cache", "old"));
        when(memoryDb.updateCluster("app-cache", "new", REGION)).thenReturn(cluster("app-cache", "new"));
        StackResource r = resource("AWS::MemoryDB::Cluster", "Cache");
        ObjectNode props = mapper.createObjectNode()
                .put("ClusterName", "app-cache")
                .put("ACLName", "open-access")
                .put("NodeType", "db.t4g.small")
                .put("Description", "new");

        provisioner.provision(r, props, ctx("app-cache"));

        verify(memoryDb).updateCluster("app-cache", "new", REGION);
        verify(memoryDb, never()).createCluster(any(), anyString());
        assertEquals("app-cache", r.getPhysicalId());
        assertEquals("6400", r.getAttributes().get("ClusterEndpoint.Port"));
    }

    @Test
    void renamingAClusterOnUpdateIsRefusedAsAReplacement() {
        StackResource r = resource("AWS::MemoryDB::Cluster", "Cache");
        ObjectNode props = mapper.createObjectNode()
                .put("ClusterName", "renamed")
                .put("ACLName", "open-access")
                .put("NodeType", "db.t4g.small");

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props, ctx("app-cache")));

        assertEquals("ValidationError", e.getErrorCode());
        verify(memoryDb, never()).createCluster(any(), anyString());
    }

    @Test
    void subnetAndParameterGroupsAreRecordedWithTheirArns() {
        StackResource subnets = resource("AWS::MemoryDB::SubnetGroup", "Subnets");
        ObjectNode subnetProps = mapper.createObjectNode()
                .put("SubnetGroupName", "app-subnets")
                .put("Description", "subnets");
        subnetProps.putArray("SubnetIds").add("subnet-1");
        StackResource params = resource("AWS::MemoryDB::ParameterGroup", "Params");
        ObjectNode paramProps = mapper.createObjectNode()
                .put("ParameterGroupName", "app-params")
                .put("Family", "memorydb_valkey7");
        paramProps.putObject("Parameters").put("maxmemory-policy", "allkeys-lru");

        provisioner.provision(subnets, subnetProps, ctx());
        provisioner.provision(params, paramProps, ctx());

        assertEquals("app-subnets", subnets.getPhysicalId());
        assertEquals("arn:aws:memorydb:us-east-1:000000000000:subnetgroup/app-subnets",
                subnets.getAttributes().get("ARN"));
        assertEquals("ipv4", subnets.getAttributes().get("SupportedNetworkTypes"));
        assertEquals("app-params", params.getPhysicalId());
        assertEquals("arn:aws:memorydb:us-east-1:000000000000:parametergroup/app-params",
                params.getAttributes().get("ARN"));
        verifyNoInteractions(memoryDb);
    }

    @Test
    void deleteToleratesOnlyAnAlreadyDeletedResource() {
        when(memoryDb.deleteCluster("gone", REGION))
                .thenThrow(new AwsException("ClusterNotFoundFault", "Cluster not found.", 404));
        when(memoryDb.deleteAcl("in-use", REGION))
                .thenThrow(new AwsException("InvalidACLStateFault", "in use", 400));

        provisioner.delete("AWS::MemoryDB::Cluster", "gone", REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::MemoryDB::ACL", "in-use", REGION));
        assertEquals("InvalidACLStateFault", e.getErrorCode());

        provisioner.delete("AWS::MemoryDB::User", "app-user", REGION);
        verify(memoryDb).deleteUser("app-user", REGION);
        provisioner.delete("AWS::MemoryDB::SubnetGroup", "app-subnets", REGION);
    }
}
