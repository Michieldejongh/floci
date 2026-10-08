package io.github.hectorvent.floci.services.memorydb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.container.ValkeyClusterFormation;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerManager;
import io.github.hectorvent.floci.services.memorydb.model.Cluster;
import io.github.hectorvent.floci.services.memorydb.model.ClusterStatus;
import io.github.hectorvent.floci.services.memorydb.proxy.MemoryDbProxyManager;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Floci restart, simulated in one process: the running service's proxy goes away (it is
 * process-local), and a second service, manager and proxy registry built over the same stores
 * stand in for the next process. Its restore must serve the cluster on the same endpoint again.
 */
@QuarkusTest
class MemoryDbRestoreIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/memorydb/aws4_request";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final int SOCKET_TIMEOUT_MS = 10_000;

    @Inject MemoryDbProxyManager proxyManager;
    @Inject MemoryDbContainerManager containerManager;
    @Inject ContainerBuilder containerBuilder;
    @Inject ContainerLifecycleManager lifecycleManager;
    @Inject ContainerLogStreamer logStreamer;
    @Inject ContainerDetector containerDetector;
    @Inject SigV4Validator sigV4Validator;
    @Inject ValkeyClusterFormation clusterFormation;
    @Inject StorageFactory storageFactory;
    @Inject EmulatorConfig config;
    @Inject RegionResolver regionResolver;

    private MemoryDbProxyManager restartedProxies;
    private final List<String> created = new ArrayList<>();

    @BeforeAll
    static void setup() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for MemoryDB integration tests");
    }

    @AfterEach
    void cleanup() {
        if (restartedProxies != null) {
            restartedProxies.stopAll();
        }
        for (String name : created) {
            try {
                memorydb("DeleteCluster", "{\"ClusterName\":\"" + name + "\"}");
            } catch (RuntimeException ignored) {
                // best-effort: the test may already have removed it
            }
        }
    }

    @Test
    void restoreAdoptsTheSurvivingContainerAndKeepsItsData() throws Exception {
        String name = "it-mdb-restore-adopt";
        int port = createCluster(name);
        String containerBefore = containerIdOf(name);
        assertEquals("OK", command(port, "SET", "{k}:greeting", "survived"));

        MemoryDbService restarted = simulateRestart(name);
        restarted.restorePersistedRuntime().join();

        Cluster restored = restarted.getCluster(name, "us-east-1");
        assertEquals(ClusterStatus.AVAILABLE, restored.getStatus());
        assertEquals(port, restored.getClusterEndpoint().port());
        assertEquals(containerBefore, restored.getContainerId(), "the surviving container must be adopted");
        assertTrue(((String) command(port, "CLUSTER", "INFO")).contains("cluster_state:ok"));
        assertEquals("survived", command(port, "GET", "{k}:greeting"));
    }

    @Test
    void restoreStartsAnEmptyContainerWhenTheOldOneIsGone() throws Exception {
        String name = "it-mdb-restore-fresh";
        int port = createCluster(name);
        String containerBefore = containerIdOf(name);
        assertEquals("OK", command(port, "SET", "{k}:greeting", "lost"));

        MemoryDbService restarted = simulateRestart(name);
        containerManager.stopByClusterName(name);
        restarted.restorePersistedRuntime().join();

        Cluster restored = restarted.getCluster(name, "us-east-1");
        assertEquals(ClusterStatus.AVAILABLE, restored.getStatus());
        assertEquals(port, restored.getClusterEndpoint().port());
        assertNotEquals(containerBefore, restored.getContainerId());
        assertTrue(((String) command(port, "CLUSTER", "INFO")).contains("cluster_state:ok"));
        List<?> primary = (List<?>) ((List<?>) ((List<?>) command(port, "CLUSTER", "SLOTS")).getFirst()).get(2);
        assertEquals((long) port, primary.get(1), "the fresh node must announce the same endpoint");
        assertNull(command(port, "GET", "{k}:greeting"));
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private int createCluster(String name) {
        created.add(name);
        return memorydb("CreateCluster", "{\"ClusterName\":\"" + name + "\",\"ACLName\":\"open-access\"}")
                .then().statusCode(200)
                .extract().path("Cluster.ClusterEndpoint.Port");
    }

    private String containerIdOf(String name) {
        return lifecycleManager.findByName(ContainerStorageHelper.resourceName(config, "memorydb", null, name))
                .orElseThrow().getId();
    }

    /** Drops this process's proxy, leaving the container, and builds the next process's service. */
    private MemoryDbService simulateRestart(String name) throws IOException {
        int port = memorydb("DescribeClusters", "{\"ClusterName\":\"" + name + "\"}")
                .then().statusCode(200)
                .extract().path("Clusters[0].ClusterEndpoint.Port");
        proxyManager.stopProxy(name);
        assertThrows(ConnectException.class, () -> openSocket(port).close(),
                "the endpoint must be dead before the restore, as it is after a restart");
        restartedProxies = new MemoryDbProxyManager();
        MemoryDbContainerManager restartedContainers = new MemoryDbContainerManager(containerBuilder,
                lifecycleManager, logStreamer, containerDetector, config, regionResolver);
        return new MemoryDbService(restartedContainers, restartedProxies, sigV4Validator, clusterFormation,
                storageFactory, config, regionResolver);
    }

    private static Object command(int port, String... args) throws IOException {
        try (Socket socket = openSocket(port)) {
            write(socket, args);
            return readReply(socket);
        }
    }

    private record RespError(String message) {}

    private static Response memorydb(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonMemoryDB." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/");
    }

    private static boolean isDockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Socket openSocket(int port) throws IOException {
        return openSocket("localhost", port);
    }

    private static Socket openSocket(String host, int port) throws IOException {
        Socket socket = new Socket(host, port);
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        return socket;
    }

    private static void write(Socket socket, String... args) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(bytes.length).append("\r\n").append(arg).append("\r\n");
        }
        OutputStream out = socket.getOutputStream();
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static Object readReply(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        int type = in.read();
        String line = readLine(in);
        return switch (type) {
            case '+' -> line;
            case '-' -> new RespError(line);
            case ':' -> Long.parseLong(line);
            case '$' -> readBulk(in, Integer.parseInt(line));
            case '*' -> {
                int count = Integer.parseInt(line);
                List<Object> items = new ArrayList<>(Math.max(count, 0));
                for (int i = 0; i < count; i++) {
                    items.add(readReply(socket));
                }
                yield items;
            }
            default -> throw new IOException("Unexpected RESP type: " + (char) type);
        };
    }

    private static String readBulk(InputStream in, int length) throws IOException {
        if (length < 0) {
            return null;
        }
        byte[] data = in.readNBytes(length);
        if (in.read() != '\r' || in.read() != '\n') {
            throw new IOException("Missing CRLF after bulk reply");
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                in.read();
                return sb.toString();
            }
            sb.append((char) b);
        }
        throw new IOException("Connection closed mid-line; read so far: " + sb);
    }
}
