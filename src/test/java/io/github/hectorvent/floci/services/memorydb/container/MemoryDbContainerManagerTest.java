package io.github.hectorvent.floci.services.memorydb.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.services.elasticache.container.ValkeyClusterFormation;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryDbContainerManagerTest {

    private static final Logger LOG = Logger.getLogger(MemoryDbContainerManagerTest.class);

    private EmulatorConfig.MemoryDbServiceConfig memorydbConfig;

    @Test
    void stopByClusterNameRemovesByDeterministicNameWhenNothingRegistered() {
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        MemoryDbContainerManager manager = new MemoryDbContainerManager(
                mock(ContainerBuilder.class), lifecycleManager, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), mock(EmulatorConfig.class), mock(RegionResolver.class));

        // No container was ever registered for this name (e.g. it failed before registration).
        // Rollback must still fall back to the deterministic name so nothing is orphaned.
        manager.stopByClusterName("my-cluster");

        verify(lifecycleManager).removeIfExists("floci-aws-memorydb-my-cluster");
    }

    @Test
    void tryStartReportsUnavailableInsteadOfThrowingWhenNoDockerDaemonIsReachable() {
        // Floci running inside Docker without a mounted daemon socket: the MemoryDB control
        // plane must keep working, so the failure is reported rather than propagated.
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any()))
                .thenThrow(new RuntimeException("java.net.SocketException: No such file or directory"));
        when(lifecycleManager.getDockerClient()).thenThrow(
                new RuntimeException("java.net.SocketException: No such file or directory"));

        MemoryDbContainerManager manager = newManager(lifecycleManager);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertNull(manager.tryStart("cluster1", "valkey/valkey:8"),
                    "attempt " + attempt + " should report unavailable");
        }
        assertFalse(manager.isDockerReachable());
    }

    @Test
    void tryStartPropagatesFailuresRaisedWhileTheDaemonIsReachable() {
        // A reachable daemon that cannot start the container is a genuine failure, not a
        // degraded mode: CreateCluster must still surface it.
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any()))
                .thenThrow(new RuntimeException("no such image: valkey/valkey:8"));
        DockerClient dockerClient = mock(DockerClient.class, Mockito.RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

        MemoryDbContainerManager manager = newManager(lifecycleManager);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.tryStart("cluster1", "valkey/valkey:8"));
        assertEquals("no such image: valkey/valkey:8", failure.getMessage());
    }

    @Test
    void tryStartReturnsTheHandleOnceADaemonIsReachable() throws IOException, InterruptedException {
        // Real loopback socket standing in for the backend container's Redis port, so the
        // manager's PING/PONG readiness probe succeeds without a real container.
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread responder = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    socket.getInputStream().read(new byte[64]);
                    OutputStream out = socket.getOutputStream();
                    out.write("+PONG\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    // Test teardown races the socket close; nothing left to assert on.
                    LOG.debugv(e, "Acceptor socket closed during test teardown");
                }
            });
            responder.setDaemon(true);
            responder.start();

            ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
            when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                    "container-id", Map.of(6379, new ContainerLifecycleManager.EndpointInfo(
                            "127.0.0.1", serverSocket.getLocalPort()))));

            MemoryDbContainerManager manager = newManager(lifecycleManager);

            MemoryDbContainerHandle handle = manager.tryStart("cluster1", "valkey/valkey:8");

            assertEquals("container-id", handle.getContainerId());
            assertEquals(serverSocket.getLocalPort(), handle.getPort());
            responder.join(5000);
        }
    }

    @Test
    void startLabelsContainerWithResourceIdentity() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread responder = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    socket.getInputStream().read(new byte[64]);
                    OutputStream out = socket.getOutputStream();
                    out.write("+PONG\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    LOG.debugv(e, "Acceptor socket closed during test teardown");
                }
            });
            responder.setDaemon(true);
            responder.start();

            ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
            when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                    "container-id", Map.of(6379, new ContainerLifecycleManager.EndpointInfo(
                            "127.0.0.1", serverSocket.getLocalPort()))));

            ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
            ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            when(containerBuilder.newContainer(anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(mock(ContainerSpec.class));

            EmulatorConfig config = mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
            EmulatorConfig.MemoryDbServiceConfig memorydb = mock(EmulatorConfig.MemoryDbServiceConfig.class);
            when(config.services()).thenReturn(services);
            when(services.memorydb()).thenReturn(memorydb);
            when(memorydb.dockerNetwork()).thenReturn(Optional.empty());

            MemoryDbContainerManager manager = new MemoryDbContainerManager(containerBuilder, lifecycleManager,
                    mock(ContainerLogStreamer.class), mock(ContainerDetector.class), config,
                    new RegionResolver("us-east-1", "000000000000"));

            manager.start("cluster1", "valkey/valkey:8");

            verify(builder).withLabels(Map.of(
                    "io.floci", "aws",
                    "io.floci.service", "memorydb",
                    "io.floci.resource-id", "cluster1",
                    "io.floci.account", "000000000000",
                    "io.floci.region", "us-east-1"));
        }
    }

    @Test
    void startPassesClusterModeFlagsThroughTheValkeyExtraFlagsHook() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread responder = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    socket.getInputStream().read(new byte[64]);
                    OutputStream out = socket.getOutputStream();
                    out.write("+PONG\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    LOG.debugv(e, "Acceptor socket closed during test teardown");
                }
            });
            responder.setDaemon(true);
            responder.start();

            ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
            when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                    "container-id", Map.of(6379, new ContainerLifecycleManager.EndpointInfo(
                            "127.0.0.1", serverSocket.getLocalPort()))));

            ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
            ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
            when(containerBuilder.newContainer(anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(mock(ContainerSpec.class));

            EmulatorConfig config = mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
            EmulatorConfig.MemoryDbServiceConfig memorydb = mock(EmulatorConfig.MemoryDbServiceConfig.class);
            when(config.services()).thenReturn(services);
            when(services.memorydb()).thenReturn(memorydb);
            when(memorydb.dockerNetwork()).thenReturn(Optional.empty());

            MemoryDbContainerManager manager = new MemoryDbContainerManager(containerBuilder, lifecycleManager,
                    mock(ContainerLogStreamer.class), mock(ContainerDetector.class), config,
                    new RegionResolver("us-east-1", "000000000000"));

            manager.start("cluster1", "valkey/valkey:8",
                    ValkeyClusterFormation.nodeFlags("localhost", "127.0.0.1", 6400));

            verify(builder).withEnv("VALKEY_EXTRA_FLAGS", "--cluster-enabled yes"
                    + " --cluster-announce-hostname localhost"
                    + " --cluster-preferred-endpoint-type hostname"
                    + " --cluster-announce-client-ipv4 127.0.0.1"
                    + " --cluster-announce-port 6400"
                    + " --cluster-announce-bus-port 16379");
        }
    }

    private static final List<String> FLAGS = ValkeyClusterFormation.nodeFlags("localhost", "127.0.0.1", 6400);

    private static ServerSocket pongServer() throws IOException {
        ServerSocket serverSocket = new ServerSocket(0);
        Thread responder = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                socket.getInputStream().read(new byte[64]);
                OutputStream out = socket.getOutputStream();
                out.write("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                LOG.debugv(e, "Acceptor socket closed during test teardown");
            }
        });
        responder.setDaemon(true);
        responder.start();
        return serverSocket;
    }

    private static Container existingContainer(String id) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        return container;
    }

    @Test
    void tryAdoptOrStartAdoptsASurvivingContainerCreatedWithTheSameFlags() throws IOException {
        try (ServerSocket serverSocket = pongServer()) {
            ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
            Container survivor = existingContainer("survivor-id");
            when(lifecycleManager.findByName("floci-aws-memorydb-cluster1")).thenReturn(Optional.of(survivor));
            when(lifecycleManager.containerEnv("survivor-id")).thenReturn(Optional.of(List.of(
                    "PATH=/usr/bin", "VALKEY_EXTRA_FLAGS=" + String.join(" ", FLAGS))));
            when(lifecycleManager.adopt("survivor-id", List.of(6379))).thenReturn(
                    new ContainerLifecycleManager.ContainerInfo("survivor-id", Map.of(6379,
                            new ContainerLifecycleManager.EndpointInfo("127.0.0.1", serverSocket.getLocalPort()))));

            MemoryDbContainerHandle handle = newManager(lifecycleManager)
                    .tryAdoptOrStart("cluster1", "valkey/valkey:8", FLAGS);

            assertEquals("survivor-id", handle.getContainerId());
            assertEquals(serverSocket.getLocalPort(), handle.getPort());
            verify(lifecycleManager, never()).removeIfExists(anyString());
            verify(lifecycleManager, never()).createAndStart(any());
        }
    }

    @Test
    void tryAdoptOrStartReplacesASurvivorThatAnnouncesADifferentEndpoint() throws IOException {
        try (ServerSocket serverSocket = pongServer()) {
            ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
            Container survivor = existingContainer("survivor-id");
            when(lifecycleManager.findByName("floci-aws-memorydb-cluster1")).thenReturn(Optional.of(survivor));
            when(lifecycleManager.containerEnv("survivor-id")).thenReturn(Optional.of(List.of(
                    "VALKEY_EXTRA_FLAGS=" + String.join(" ",
                            ValkeyClusterFormation.nodeFlags("old-host", "127.0.0.1", 6400)))));
            when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                    "fresh-id", Map.of(6379, new ContainerLifecycleManager.EndpointInfo(
                            "127.0.0.1", serverSocket.getLocalPort()))));

            MemoryDbContainerHandle handle = newManager(lifecycleManager)
                    .tryAdoptOrStart("cluster1", "valkey/valkey:8", FLAGS);

            assertEquals("fresh-id", handle.getContainerId());
            verify(lifecycleManager, never()).adopt(anyString(), any());
            verify(lifecycleManager).removeIfExists("floci-aws-memorydb-cluster1");
        }
    }

    @Test
    void tryAdoptOrStartStartsAFreshContainerWhenNoneSurvived() throws IOException {
        try (ServerSocket serverSocket = pongServer()) {
            ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
            when(lifecycleManager.findByName(anyString())).thenReturn(Optional.empty());
            when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                    "fresh-id", Map.of(6379, new ContainerLifecycleManager.EndpointInfo(
                            "127.0.0.1", serverSocket.getLocalPort()))));

            MemoryDbContainerHandle handle = newManager(lifecycleManager)
                    .tryAdoptOrStart("cluster1", "valkey/valkey:8", FLAGS);

            assertEquals("fresh-id", handle.getContainerId());
            verify(lifecycleManager, never()).adopt(anyString(), any());
        }
    }

    @Test
    void stopAllLeavesContainersRunningWhenKeepRunningOnShutdownIsSet() throws IOException {
        try (ServerSocket serverSocket = pongServer()) {
            ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
            when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                    "container-id", Map.of(6379, new ContainerLifecycleManager.EndpointInfo(
                            "127.0.0.1", serverSocket.getLocalPort()))));
            MemoryDbContainerManager manager = newManager(lifecycleManager);
            when(memorydbConfig.keepRunningOnShutdown()).thenReturn(true);
            manager.start("cluster1", "valkey/valkey:8");

            manager.stopAll();

            verify(lifecycleManager, never()).stopAndRemove(anyString(), any());
        }
    }

    private MemoryDbContainerManager newManager(ContainerLifecycleManager lifecycleManager) {
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ContainerSpec.class));

        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.MemoryDbServiceConfig memorydb = mock(EmulatorConfig.MemoryDbServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.memorydb()).thenReturn(memorydb);
        when(memorydb.dockerNetwork()).thenReturn(Optional.empty());
        memorydbConfig = memorydb;

        return new MemoryDbContainerManager(containerBuilder, lifecycleManager, logStreamer,
                mock(ContainerDetector.class), config, new RegionResolver("us-east-1", "000000000000"));
    }
}
