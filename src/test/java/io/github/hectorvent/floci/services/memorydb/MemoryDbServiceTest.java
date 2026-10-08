package io.github.hectorvent.floci.services.memorydb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.container.ValkeyClusterFormation;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerHandle;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerManager;
import io.github.hectorvent.floci.services.memorydb.model.Acl;
import io.github.hectorvent.floci.services.memorydb.model.AuthMode;
import io.github.hectorvent.floci.services.memorydb.model.Cluster;
import io.github.hectorvent.floci.services.memorydb.model.ClusterStatus;
import io.github.hectorvent.floci.services.memorydb.model.User;
import io.github.hectorvent.floci.services.memorydb.proxy.MemoryDbProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MemoryDbServiceTest {

    private MemoryDbService service;
    private MemoryDbContainerManager containerManager;
    private MemoryDbProxyManager proxyManager;
    private SigV4Validator sigV4Validator;
    private ValkeyClusterFormation clusterFormation;
    private EmulatorConfig.MemoryDbServiceConfig mdbConfig;
    private StorageFactory storageFactory;
    private EmulatorConfig config;
    private RegionResolver regionResolver;

    @BeforeEach
    void setUp() {
        containerManager = mock(MemoryDbContainerManager.class);
        proxyManager = mock(MemoryDbProxyManager.class);
        sigV4Validator = mock(SigV4Validator.class);
        clusterFormation = mock(ValkeyClusterFormation.class);
        storageFactory = mock(StorageFactory.class);
        config = mock(EmulatorConfig.class);
        regionResolver = mock(RegionResolver.class);

        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        mdbConfig = mock(EmulatorConfig.MemoryDbServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.memorydb()).thenReturn(mdbConfig);
        when(mdbConfig.proxyBasePort()).thenReturn(16400);
        when(mdbConfig.proxyMaxPort()).thenReturn(16419);
        when(mdbConfig.defaultImage()).thenReturn("valkey/valkey:8");
        when(config.hostname()).thenReturn(Optional.of("localhost"));
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        when(regionResolver.buildArn(anyString(), anyString(), anyString())).thenAnswer(inv ->
                AwsArnUtils.Arn.of(inv.getArgument(0), inv.getArgument(1),
                        "000000000000", inv.getArgument(2)).toString());

        // One store per file, shared by every service built from this factory, so a second
        // service stands in for Floci restarted on the same persisted state.
        Map<String, AccountAwareStorageBackend<Object>> stores = new HashMap<>();
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv -> stores.computeIfAbsent(
                inv.getArgument(1), file -> AccountAwareStorageBackend.inMemory("000000000000")));
        when(containerManager.tryStart(anyString(), anyString(), anyList()))
                .thenReturn(new MemoryDbContainerHandle("cid", "cluster", "localhost", 6379));
        doNothing().when(proxyManager).startProxy(anyString(), anyBoolean(), anyInt(), anyString(), anyInt(), any());

        service = new MemoryDbService(containerManager, proxyManager, sigV4Validator, clusterFormation,
                storageFactory, config, regionResolver);
    }

    @Test
    void createAndDescribeCluster() {
        Cluster spec = new Cluster();
        spec.setName("my-cluster");
        spec.setNodeType("db.t4g.small");
        spec.setAclName("open-access");
        Cluster created = service.createCluster(spec, "us-east-1");

        assertEquals("my-cluster", created.getName());
        assertEquals(ClusterStatus.AVAILABLE, created.getStatus());
        assertEquals("arn:aws:memorydb:us-east-1:000000000000:cluster/my-cluster", created.getArn());
        assertEquals("localhost", created.getClusterEndpoint().address());

        assertEquals(1, service.describeClusters("my-cluster").size());
        assertEquals(1, service.describeClusters(null).size());
    }

    @Test
    void duplicateClusterRejected() {
        Cluster spec = new Cluster();
        spec.setName("dupe");
        spec.setAclName("open-access");
        service.createCluster(spec, "us-east-1");

        Cluster again = new Cluster();
        again.setName("dupe");
        again.setAclName("open-access");
        assertThrows(AwsException.class, () -> service.createCluster(again, "us-east-1"));
    }

    @Test
    void deleteClusterRemovesIt() {
        Cluster spec = new Cluster();
        spec.setName("temp");
        spec.setAclName("open-access");
        service.createCluster(spec, "us-east-1");

        service.deleteCluster("temp");

        assertThrows(AwsException.class, () -> service.getCluster("temp"));
    }

    @Test
    void tagAndUntagResource() {
        Cluster spec = new Cluster();
        spec.setName("tagged");
        spec.setAclName("open-access");
        Cluster created = service.createCluster(spec, "us-east-1");
        String arn = created.getArn();

        service.tagResource(arn, Map.of("env", "dev"));
        assertEquals("dev", service.listTags(arn).get("env"));

        service.untagResource(arn, List.of("env"));
        assertFalse(service.listTags(arn).containsKey("env"));
    }

    @Test
    void openAccessClusterRequiresNoAuth() {
        Cluster spec = new Cluster();
        spec.setName("open");
        spec.setAclName("open-access");
        service.createCluster(spec, "us-east-1");

        // open-access maps to the no-auth path: any AUTH attempt is accepted
        assertTrue(service.authenticate("open", null, "anything"));
    }

    @Test
    void passwordAuthResolvesThroughAclUser() {
        User userSpec = new User();
        userSpec.setName("app-user");
        userSpec.setAuthMode(AuthMode.PASSWORD);
        userSpec.setPasswords(List.of("s3cret"));
        userSpec.setAccessString("on ~* +@all");
        service.createUser(userSpec, "us-east-1");

        Acl aclSpec = new Acl();
        aclSpec.setName("app-acl");
        aclSpec.setUserNames(List.of("default", "app-user"));
        service.createAcl(aclSpec, "us-east-1");

        Cluster spec = new Cluster();
        spec.setName("secure");
        spec.setAclName("app-acl");
        service.createCluster(spec, "us-east-1");

        assertTrue(service.authenticate("secure", "app-user", "s3cret"));
        assertFalse(service.authenticate("secure", "app-user", "wrong"));
        assertFalse(service.authenticate("secure", "unknown-user", "s3cret"));
    }

    @Test
    void iamAuthDelegatesToSigV4Validator() {
        when(sigV4Validator.validate(anyString(), anyString(), anyString())).thenReturn(true);

        User userSpec = new User();
        userSpec.setName("iam-user");
        userSpec.setAuthMode(AuthMode.IAM);
        userSpec.setAccessString("on ~* +@all");
        service.createUser(userSpec, "us-east-1");

        Acl aclSpec = new Acl();
        aclSpec.setName("iam-acl");
        aclSpec.setUserNames(List.of("default", "iam-user"));
        service.createAcl(aclSpec, "us-east-1");

        Cluster spec = new Cluster();
        spec.setName("iam-cluster");
        spec.setAclName("iam-acl");
        service.createCluster(spec, "us-east-1");

        assertTrue(service.authenticate("iam-cluster", "iam-user", "presigned-token"));
    }

    @Test
    void createClusterWithUnknownAclRejected() {
        Cluster spec = new Cluster();
        spec.setName("bad");
        spec.setAclName("does-not-exist");
        assertThrows(AwsException.class, () -> service.createCluster(spec, "us-east-1"));
    }

    @Test
    void passwordUserRequiresPassword() {
        User userSpec = new User();
        userSpec.setName("no-pass");
        userSpec.setAuthMode(AuthMode.PASSWORD);
        userSpec.setAccessString("on ~* +@all");
        assertThrows(AwsException.class, () -> service.createUser(userSpec, "us-east-1"));
    }

    @Test
    void createClusterRequiresAclName() {
        Cluster spec = new Cluster();
        spec.setName("no-acl");
        AwsException ex = assertThrows(AwsException.class, () -> service.createCluster(spec, "us-east-1"));
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void createUserRequiresAccessString() {
        User userSpec = new User();
        userSpec.setName("no-access");
        userSpec.setAuthMode(AuthMode.PASSWORD);
        userSpec.setPasswords(List.of("p"));
        assertThrows(AwsException.class, () -> service.createUser(userSpec, "us-east-1"));
    }

    @Test
    void createUserRejectsNoPasswordType() {
        // "no-password" is a valid wire enum value but is output-only: AWS rejects it on
        // CreateUser ("all newly-created users require a password" / IAM).
        User userSpec = new User();
        userSpec.setName("npr");
        userSpec.setAuthMode(AuthMode.NO_PASSWORD);
        userSpec.setAccessString("on ~* +@all");
        AwsException ex = assertThrows(AwsException.class, () -> service.createUser(userSpec, "us-east-1"));
        assertEquals("InvalidParameterValueException", ex.jsonType());
    }

    @Test
    void createUserRejectsInvalidUserName() {
        User userSpec = new User();
        userSpec.setName("1-bad-start"); // must start with a letter
        userSpec.setAuthMode(AuthMode.PASSWORD);
        userSpec.setPasswords(List.of("p"));
        userSpec.setAccessString("on ~* +@all");
        AwsException ex = assertThrows(AwsException.class, () -> service.createUser(userSpec, "us-east-1"));
        assertEquals("InvalidParameterValueException", ex.jsonType());
    }

    @Test
    void createAclRequiresDefaultUser() {
        User userSpec = new User();
        userSpec.setName("solo");
        userSpec.setAuthMode(AuthMode.PASSWORD);
        userSpec.setPasswords(List.of("p"));
        userSpec.setAccessString("on ~* +@all");
        service.createUser(userSpec, "us-east-1");

        Acl aclSpec = new Acl();
        aclSpec.setName("no-default-acl");
        aclSpec.setUserNames(List.of("solo")); // missing the required "default" user
        AwsException ex = assertThrows(AwsException.class, () -> service.createAcl(aclSpec, "us-east-1"));
        assertEquals("DefaultUserRequired", ex.jsonType());
    }

    @Test
    void createAclRejectsDuplicateUserNames() {
        User userSpec = new User();
        userSpec.setName("dup");
        userSpec.setAuthMode(AuthMode.PASSWORD);
        userSpec.setPasswords(List.of("p"));
        userSpec.setAccessString("on ~* +@all");
        service.createUser(userSpec, "us-east-1");

        Acl aclSpec = new Acl();
        aclSpec.setName("dup-acl");
        aclSpec.setUserNames(List.of("default", "dup", "dup"));
        AwsException ex = assertThrows(AwsException.class, () -> service.createAcl(aclSpec, "us-east-1"));
        assertEquals("DuplicateUserNameFault", ex.jsonType());
    }

    @Test
    void describeIncludesBuiltinDefaults() {
        assertTrue(service.describeUsers(null, "us-east-1").stream()
                .anyMatch(u -> "default".equals(u.getName())));
        assertTrue(service.describeAcls(null, "us-east-1").stream()
                .anyMatch(a -> "open-access".equals(a.getName())));
    }

    @Test
    void builtinDefaultsCannotBeDeleted() {
        assertThrows(AwsException.class, () -> service.deleteUser("default"));
        assertThrows(AwsException.class, () -> service.deleteAcl("open-access"));
    }

    @Test
    void aclInUseCannotBeDeleted() {
        User userSpec = new User();
        userSpec.setName("u1");
        userSpec.setAuthMode(AuthMode.PASSWORD);
        userSpec.setPasswords(List.of("p"));
        userSpec.setAccessString("on ~* +@all");
        service.createUser(userSpec, "us-east-1");

        Acl aclSpec = new Acl();
        aclSpec.setName("in-use");
        aclSpec.setUserNames(List.of("default", "u1"));
        service.createAcl(aclSpec, "us-east-1");

        Cluster spec = new Cluster();
        spec.setName("c");
        spec.setAclName("in-use");
        service.createCluster(spec, "us-east-1");

        assertThrows(AwsException.class, () -> service.deleteAcl("in-use"));
    }

    @Test
    void nullClusterNameYieldsValidationErrorNotNpe() {
        AwsException ex = assertThrows(AwsException.class, () -> service.getCluster(null));
        assertEquals(400, ex.getHttpStatus());
        assertThrows(AwsException.class, () -> service.deleteCluster(null));
        assertThrows(AwsException.class, () -> service.updateCluster("  ", "desc"));
    }

    @Test
    void failedProvisioningReleasesProxyPort() {
        when(mdbConfig.proxyBasePort()).thenReturn(16400);
        when(mdbConfig.proxyMaxPort()).thenReturn(16400); // exactly one port available
        when(containerManager.tryStart(anyString(), anyString(), anyList()))
                .thenThrow(new RuntimeException("docker unavailable"))
                .thenReturn(new MemoryDbContainerHandle("cid", "c2", "localhost", 6379));

        Cluster first = new Cluster();
        first.setName("c1");
        first.setAclName("open-access");
        assertThrows(RuntimeException.class, () -> service.createCluster(first, "us-east-1"));

        // The single port must have been released, so a second create still succeeds
        Cluster second = new Cluster();
        second.setName("c2");
        second.setAclName("open-access");
        Cluster created = service.createCluster(second, "us-east-1");
        assertEquals(ClusterStatus.AVAILABLE, created.getStatus());
    }

    @Test
    void failedProvisioningRollsBackContainerAndReleasesProxyPort() {
        MemoryDbContainerHandle handle = new MemoryDbContainerHandle("cid", "c1", "localhost", 6379);
        when(containerManager.tryStart(anyString(), anyString(), anyList())).thenReturn(handle);

        // Proxy startup blows up after the port is reserved and the container is started.
        doThrow(new RuntimeException("proxy boom"))
                .when(proxyManager).startProxy(eq("c1"), anyBoolean(), anyInt(), anyString(), anyInt(), any());

        Cluster spec = new Cluster();
        spec.setName("c1");
        spec.setAclName("open-access");

        // The original failure must propagate to the caller (we clean up, then rethrow).
        assertThrows(RuntimeException.class, () -> service.createCluster(spec, "us-east-1"));

        // Rollback stopped the proxy and the already-started container. Stopped by the exact
        // handle from this request, not by name: a concurrent create for the same cluster name
        // could have raced ahead and overwritten the registered container, and looking it up by
        // name here would risk stopping that other request's container instead of this one's.
        verify(proxyManager).stopProxy("c1");
        verify(containerManager).stop(handle);
        verify(containerManager, never()).stopByClusterName(anyString());

        // The reserved proxy port was released: a subsequent successful create reuses the base port
        // instead of skipping to the next one (which is what a leak would cause).
        doNothing().when(proxyManager)
                .startProxy(anyString(), anyBoolean(), anyInt(), anyString(), anyInt(), any());
        Cluster second = new Cluster();
        second.setName("c2");
        second.setAclName("open-access");
        Cluster recovered = service.createCluster(second, "us-east-1");
        assertEquals(16400, recovered.getProxyPort(),
                "Port from the failed create must be released so the next cluster reuses it");
    }

    @Test
    void failedContainerStartupCleansUpContainerByNameAndReleasesPort() {
        // containerManager.tryStart(...) throws — this models both a container that never started
        // and (crucially) a readiness timeout, where start() created + registered the container
        // before throwing, so no handle ever reaches the service.
        doThrow(new RuntimeException("readiness boom"))
                .when(containerManager).tryStart(eq("c1"), anyString(), anyList());

        Cluster spec = new Cluster();
        spec.setName("c1");
        spec.setAclName("open-access");

        // The original failure must propagate to the caller (we clean up, then rethrow).
        assertThrows(RuntimeException.class, () -> service.createCluster(spec, "us-east-1"));

        // No handle returned, so the proxy never started and must not be stopped...
        verify(proxyManager, never()).stopProxy(anyString());
        // ...but the container must still be cleaned up by name, since start() may have created
        // and registered it before failing. Cleaning up by handle here would orphan it.
        verify(containerManager).stopByClusterName("c1");

        // The reserved proxy port was still released: a subsequent successful create reuses the base port.
        when(containerManager.tryStart(anyString(), anyString(), anyList()))
                .thenReturn(new MemoryDbContainerHandle("cid", "c2", "localhost", 6379));
        Cluster second = new Cluster();
        second.setName("c2");
        second.setAclName("open-access");
        Cluster recovered = service.createCluster(second, "us-east-1");
        assertEquals(16400, recovered.getProxyPort(),
                "Port from the failed create must be released so the next cluster reuses it");
    }

    @Test
    void mockModeSkipsContainerAndReportsStandardPort() {
        when(mdbConfig.mock()).thenReturn(true);

        Cluster spec = new Cluster();
        spec.setName("mock-cluster");
        spec.setAclName("open-access");
        Cluster created = service.createCluster(spec, "us-east-1");

        assertEquals(ClusterStatus.AVAILABLE, created.getStatus());
        assertEquals("localhost", created.getClusterEndpoint().address());
        assertEquals(6379, created.getClusterEndpoint().port());
        verifyNoInteractions(containerManager);
        verifyNoInteractions(clusterFormation);
    }

    @Test
    void containerRunsInClusterModeAnnouncingTheProxyEndpoint() {
        Cluster created = service.createCluster(cluster("cm-cluster", "open-access"), "us-east-1");

        int proxyPort = created.getClusterEndpoint().port();
        assertEquals(16400, proxyPort);
        verify(containerManager).tryStart(eq("cm-cluster"), eq("valkey/valkey:8"), eq(List.of(
                "--cluster-enabled", "yes",
                "--cluster-announce-hostname", "localhost",
                "--cluster-preferred-endpoint-type", "hostname",
                "--cluster-announce-client-ipv4", "127.0.0.1",
                "--cluster-announce-port", String.valueOf(proxyPort),
                "--cluster-announce-bus-port", "16379")));
    }

    @Test
    void singleShardOwnsAllSlotsBeforeTheProxyAcceptsClients() {
        service.createCluster(cluster("cm-cluster", "open-access"), "us-east-1");

        InOrder order = inOrder(clusterFormation, proxyManager);
        order.verify(clusterFormation).form("cm-cluster", List.of(new ValkeyClusterFormation.Node(
                "localhost", 6379, "localhost", 0, true)), 1);
        order.verify(proxyManager).startProxy(eq("cm-cluster"), anyBoolean(), eq(16400),
                eq("localhost"), eq(6379), any());
        assertEquals(List.of(0, 16383), java.util.Arrays.stream(
                ValkeyClusterFormation.slotRange(0, 1)).boxed().toList());
    }

    @Test
    void failedSlotAssignmentRollsBackTheContainerAndReleasesThePort() {
        MemoryDbContainerHandle handle = new MemoryDbContainerHandle("cid", "c1", "localhost", 6379);
        when(containerManager.tryStart(anyString(), anyString(), anyList())).thenReturn(handle);
        doThrow(new RuntimeException("formation boom"))
                .when(clusterFormation).form(eq("c1"), anyList(), eq(1));

        assertThrows(RuntimeException.class,
                () -> service.createCluster(cluster("c1", "open-access"), "us-east-1"));

        verify(containerManager).stop(handle);
        verify(proxyManager, never()).startProxy(anyString(), anyBoolean(), anyInt(), anyString(), anyInt(), any());

        doNothing().when(clusterFormation).form(anyString(), anyList(), anyInt());
        assertEquals(16400, service.createCluster(cluster("c2", "open-access"), "us-east-1").getProxyPort());
    }

    @Test
    void concurrentCreateForSameNameIsRejectedWhileFirstIsProvisioning() throws InterruptedException {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        when(containerManager.tryStart(anyString(), anyString(), anyList())).thenAnswer(inv -> {
            startedLatch.countDown();
            assertTrue(releaseLatch.await(5, TimeUnit.SECONDS), "test timed out waiting for release");
            return new MemoryDbContainerHandle("cid", "c1", "localhost", 6379);
        });

        Cluster spec = new Cluster();
        spec.setName("c1");
        spec.setAclName("open-access");

        Thread firstRequest = new Thread(() -> service.createCluster(spec, "us-east-1"));
        firstRequest.start();
        assertTrue(startedLatch.await(5, TimeUnit.SECONDS), "first request never reached container start");

        Cluster spec2 = new Cluster();
        spec2.setName("c1");
        spec2.setAclName("open-access");
        AwsException ex = assertThrows(AwsException.class, () -> service.createCluster(spec2, "us-east-1"));
        assertEquals("ClusterAlreadyExistsFault", ex.jsonType());
        verify(containerManager, never()).stop(any());
        verify(containerManager, never()).stopByClusterName(anyString());

        releaseLatch.countDown();
        firstRequest.join(5000);

        assertEquals(ClusterStatus.AVAILABLE, service.getCluster("c1").getStatus());
    }

    @Test
    void sameNamesAreScopedToRegion() {
        User firstUser = passwordUser("shared-user", "one");
        User secondUser = passwordUser("shared-user", "two");
        service.createUser(firstUser, "us-east-1");
        service.createUser(secondUser, "eu-west-1");

        Acl firstAcl = acl("shared-acl", "shared-user");
        Acl secondAcl = acl("shared-acl", "shared-user");
        service.createAcl(firstAcl, "us-east-1");
        service.createAcl(secondAcl, "eu-west-1");

        Cluster first = cluster("shared-cluster", "shared-acl");
        Cluster second = cluster("shared-cluster", "shared-acl");
        service.createCluster(first, "us-east-1");
        service.createCluster(second, "eu-west-1");

        assertEquals("one", service.describeUsers("shared-user", "us-east-1").iterator().next()
                .getPasswords().get(0));
        assertEquals("two", service.describeUsers("shared-user", "eu-west-1").iterator().next()
                .getPasswords().get(0));
        assertEquals("arn:aws:memorydb:us-east-1:000000000000:cluster/shared-cluster",
                service.getCluster("shared-cluster", "us-east-1").getArn());
        assertEquals("arn:aws:memorydb:eu-west-1:000000000000:cluster/shared-cluster",
                service.getCluster("shared-cluster", "eu-west-1").getArn());
        assertThrows(AwsException.class, () -> service.getCluster("shared-cluster", "ap-southeast-1"));
    }

    private User passwordUser(String name, String password) {
        User user = new User();
        user.setName(name);
        user.setAuthMode(AuthMode.PASSWORD);
        user.setPasswords(List.of(password));
        user.setAccessString("on ~* +@all");
        return user;
    }

    private Acl acl(String name, String userName) {
        Acl acl = new Acl();
        acl.setName(name);
        acl.setUserNames(List.of("default", userName));
        return acl;
    }

    private Cluster cluster(String name, String aclName) {
        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setAclName(aclName);
        return cluster;
    }

    @Test
    void createWithoutDockerDaemonStillReachesAvailable() {
        // tryStart() returns null when no Docker daemon is reachable. The cluster record is
        // metadata, so the create still succeeds, the cluster reaches 'available' on the first
        // describe (what SDK/Terraform waiters poll), and no auth proxy is started.
        when(containerManager.tryStart(anyString(), anyString(), anyList())).thenReturn(null);

        Cluster spec = new Cluster();
        spec.setName("no-docker-cluster");
        spec.setAclName("open-access");
        Cluster created = service.createCluster(spec, "us-east-1");

        assertEquals(ClusterStatus.AVAILABLE, created.getStatus());
        assertEquals("localhost", created.getClusterEndpoint().address());
        assertEquals(16400, created.getProxyPort());
        verify(proxyManager, never()).startProxy(anyString(), anyBoolean(), anyInt(), anyString(), anyInt(), any());
        verifyNoInteractions(clusterFormation);

        assertEquals("no-docker-cluster", service.getCluster("no-docker-cluster").getName());

        // Delete must not reach for a container that was never created.
        service.deleteCluster("no-docker-cluster");
        verify(containerManager, never()).stop(any());
    }

    // ──────────────────────────── Restore after restart ────────────────────────────

    private MemoryDbService restartedService() {
        clearInvocations(containerManager, proxyManager, clusterFormation);
        return new MemoryDbService(containerManager, proxyManager, sigV4Validator, clusterFormation,
                storageFactory, config, regionResolver);
    }

    private MemoryDbContainerHandle restoredHandle() {
        return new MemoryDbContainerHandle("restored-cid", "persisted", "10.0.0.5", 6379);
    }

    @Test
    void restoreAdoptsTheBackendAndStartsTheProxyOnTheRecordedPort() {
        Cluster created = service.createCluster(cluster("persisted", "open-access"), "us-east-1");
        int port = created.getClusterEndpoint().port();
        MemoryDbService restarted = restartedService();
        when(containerManager.tryAdoptOrStart(eq("persisted"), anyString(), anyList())).thenReturn(restoredHandle());
        when(clusterFormation.isFormed("10.0.0.5", 6379)).thenReturn(true);

        restarted.restorePersistedRuntime().join();

        Cluster restored = restarted.getCluster("persisted", "us-east-1");
        assertEquals(ClusterStatus.AVAILABLE, restored.getStatus());
        assertEquals(port, restored.getClusterEndpoint().port());
        assertEquals("restored-cid", restored.getContainerId());
        verify(containerManager).tryAdoptOrStart(eq("persisted"), eq("valkey/valkey:8"),
                eq(ValkeyClusterFormation.nodeFlags("localhost",
                        ValkeyClusterFormation.resolveAnnounceIp("localhost"), port)));
        verify(clusterFormation).awaitOk("persisted", "10.0.0.5", 6379);
        verify(proxyManager).startProxy(eq("persisted"), eq(false), eq(port), eq("10.0.0.5"), eq(6379), any());
    }

    @Test
    void restoreFormsTheClusterWhenTheBackendCameBackEmpty() {
        service.createCluster(cluster("persisted", "open-access"), "us-east-1");
        MemoryDbService restarted = restartedService();
        when(containerManager.tryAdoptOrStart(anyString(), anyString(), anyList())).thenReturn(restoredHandle());
        when(clusterFormation.isFormed("10.0.0.5", 6379)).thenReturn(false);

        restarted.restorePersistedRuntime().join();

        InOrder order = inOrder(clusterFormation, proxyManager);
        order.verify(clusterFormation).form(eq("persisted"), eq(List.of(new ValkeyClusterFormation.Node(
                "10.0.0.5", 6379, "10.0.0.5", 0, true))), eq(1));
        order.verify(proxyManager).startProxy(eq("persisted"), anyBoolean(), anyInt(), eq("10.0.0.5"), eq(6379), any());
        verify(clusterFormation, never()).awaitOk(anyString(), anyString(), anyInt());
    }

    @Test
    void restoreKeepsAProtectedClusterBehindAuthentication() {
        User user = new User();
        user.setName("app-user");
        user.setAuthMode(AuthMode.PASSWORD);
        user.setPasswords(List.of("s3cret"));
        user.setAccessString("on ~* +@all");
        service.createUser(user, "us-east-1");
        Acl acl = new Acl();
        acl.setName("app-acl");
        acl.setUserNames(List.of("default", "app-user"));
        service.createAcl(acl, "us-east-1");
        service.createCluster(cluster("secure", "app-acl"), "us-east-1");
        MemoryDbService restarted = restartedService();
        when(containerManager.tryAdoptOrStart(anyString(), anyString(), anyList())).thenReturn(restoredHandle());

        restarted.restorePersistedRuntime().join();

        verify(proxyManager).startProxy(eq("secure"), eq(true), anyInt(), anyString(), anyInt(), any());
        assertTrue(restarted.authenticate("secure", "app-user", "s3cret", "us-east-1"));
    }

    @Test
    void failedRestoreReportsRestoreFailedTearsDownAndFreesThePort() {
        Cluster created = service.createCluster(cluster("broken", "open-access"), "us-east-1");
        int port = created.getClusterEndpoint().port();
        MemoryDbService restarted = restartedService();
        when(containerManager.tryAdoptOrStart(anyString(), anyString(), anyList())).thenReturn(restoredHandle());
        doThrow(new RuntimeException("Failed to start proxy for cluster broken on port " + port))
                .when(proxyManager).startProxy(eq("broken"), anyBoolean(), anyInt(), anyString(), anyInt(), any());

        restarted.restorePersistedRuntime().join();

        Cluster failed = restarted.getCluster("broken", "us-east-1");
        assertEquals(ClusterStatus.RESTORE_FAILED, failed.getStatus());
        assertEquals("restore-failed", failed.getStatus().wireValue());
        assertNull(failed.getClusterEndpoint());
        verify(containerManager).stopByClusterName("broken");
        Cluster next = restarted.createCluster(cluster("next", "open-access"), "us-east-1");
        assertEquals(port, next.getClusterEndpoint().port(), "the failed cluster's port is free again");
    }

    @Test
    void restoreFailedClusterIsRetriedOnTheNextStart() {
        service.createCluster(cluster("flaky", "open-access"), "us-east-1");
        MemoryDbService firstRestart = restartedService();
        when(containerManager.tryAdoptOrStart(anyString(), anyString(), anyList()))
                .thenThrow(new RuntimeException("container did not become ready"));
        firstRestart.restorePersistedRuntime().join();
        assertEquals(ClusterStatus.RESTORE_FAILED, firstRestart.getCluster("flaky", "us-east-1").getStatus());

        MemoryDbService secondRestart = restartedService();
        doReturn(restoredHandle()).when(containerManager).tryAdoptOrStart(anyString(), anyString(), anyList());
        secondRestart.restorePersistedRuntime().join();

        Cluster restored = secondRestart.getCluster("flaky", "us-east-1");
        assertEquals(ClusterStatus.AVAILABLE, restored.getStatus());
        assertEquals(16400, restored.getClusterEndpoint().port());
    }

    @Test
    void restoreReservesPortsBeforeTheBackgroundWorkSoACreateCannotTakeThem() throws InterruptedException {
        Cluster created = service.createCluster(cluster("persisted", "open-access"), "us-east-1");
        int port = created.getClusterEndpoint().port();
        MemoryDbService restarted = restartedService();
        CountDownLatch release = new CountDownLatch(1);
        when(containerManager.tryAdoptOrStart(anyString(), anyString(), anyList())).thenAnswer(inv -> {
            release.await(10, TimeUnit.SECONDS);
            return restoredHandle();
        });

        CompletableFuture<Void> restore = restarted.restorePersistedRuntime();
        try {
            assertEquals(ClusterStatus.CREATING, restarted.getCluster("persisted", "us-east-1").getStatus());
            Cluster other = restarted.createCluster(cluster("other", "open-access"), "us-east-1");
            assertTrue(other.getClusterEndpoint().port() != port, "a create must not take a restoring cluster's port");
        } finally {
            release.countDown();
        }
        restore.join();
        assertEquals(port, restarted.getCluster("persisted", "us-east-1").getClusterEndpoint().port());
    }

    @Test
    void clusterDeletedWhileRestoringIsNotResurrected() {
        service.createCluster(cluster("doomed", "open-access"), "us-east-1");
        MemoryDbService restarted = restartedService();
        CountDownLatch release = new CountDownLatch(1);
        MemoryDbContainerHandle handle = restoredHandle();
        when(containerManager.tryAdoptOrStart(anyString(), anyString(), anyList())).thenAnswer(inv -> {
            release.await(10, TimeUnit.SECONDS);
            return handle;
        });

        CompletableFuture<Void> restore = restarted.restorePersistedRuntime();
        restarted.deleteCluster("doomed", "us-east-1");
        release.countDown();
        restore.join();

        assertThrows(AwsException.class, () -> restarted.getCluster("doomed", "us-east-1"));
        verify(containerManager).stopByClusterName("doomed");
        verify(containerManager).stop(handle);
    }

    @Test
    void restoreDoesNothingInMockMode() {
        when(mdbConfig.mock()).thenReturn(true);
        service.createCluster(cluster("mocked", "open-access"), "us-east-1");
        MemoryDbService restarted = restartedService();

        restarted.restorePersistedRuntime().join();

        assertEquals(ClusterStatus.AVAILABLE, restarted.getCluster("mocked", "us-east-1").getStatus());
        verifyNoInteractions(containerManager, proxyManager, clusterFormation);
    }

    @Test
    void clusterCreatedInMockModeIsLeftAloneWhenMockModeIsOff() {
        when(mdbConfig.mock()).thenReturn(true);
        service.createCluster(cluster("mocked", "open-access"), "us-east-1");
        when(mdbConfig.mock()).thenReturn(false);
        MemoryDbService restarted = restartedService();

        restarted.restorePersistedRuntime().join();

        Cluster cluster = restarted.getCluster("mocked", "us-east-1");
        assertEquals(ClusterStatus.AVAILABLE, cluster.getStatus());
        assertEquals(6379, cluster.getClusterEndpoint().port());
        verify(containerManager, never()).tryAdoptOrStart(anyString(), anyString(), anyList());
    }

    @Test
    void restoreWithoutDockerDaemonStillReachesAvailable() {
        service.createCluster(cluster("no-docker", "open-access"), "us-east-1");
        MemoryDbService restarted = restartedService();
        when(containerManager.tryAdoptOrStart(anyString(), anyString(), anyList())).thenReturn(null);

        restarted.restorePersistedRuntime().join();

        Cluster restored = restarted.getCluster("no-docker", "us-east-1");
        assertEquals(ClusterStatus.AVAILABLE, restored.getStatus());
        assertNull(restored.getContainerId());
        verify(proxyManager, never()).startProxy(eq("no-docker"), anyBoolean(), anyInt(), anyString(), anyInt(), any());
    }
}
