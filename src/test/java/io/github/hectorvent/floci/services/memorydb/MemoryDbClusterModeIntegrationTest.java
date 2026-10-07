package io.github.hectorvent.floci.services.memorydb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MemoryDB always runs in cluster mode. Walks the path a cluster client takes: ask the
 * cluster endpoint for its topology, then connect to the announced node and run commands.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MemoryDbClusterModeIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/memorydb/aws4_request";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String OPEN_CLUSTER = "it-mdb-cm-open";
    private static final String AUTH_CLUSTER = "it-mdb-cm-auth";
    private static final String AUTH_USER = "it-mdb-cm-user";
    private static final String AUTH_ACL = "it-mdb-cm-acl";
    private static final String AUTH_PASSWORD = "it-mdb-cm-password";
    private static final int SOCKET_TIMEOUT_MS = 10_000;

    private static String openHost;
    private static int openPort;
    private static int authPort;

    @BeforeAll
    static void setup() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for MemoryDB integration tests");
    }

    @AfterAll
    static void cleanup() {
        for (String[] call : List.of(
                new String[] {"DeleteCluster", "{\"ClusterName\":\"" + OPEN_CLUSTER + "\"}"},
                new String[] {"DeleteCluster", "{\"ClusterName\":\"" + AUTH_CLUSTER + "\"}"},
                new String[] {"DeleteACL", "{\"ACLName\":\"" + AUTH_ACL + "\"}"},
                new String[] {"DeleteUser", "{\"UserName\":\"" + AUTH_USER + "\"}"})) {
            try {
                memorydb(call[0], call[1]);
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    @Test
    @Order(1)
    void createOpenCluster() {
        Response response = memorydb("CreateCluster", "{\"ClusterName\":\"" + OPEN_CLUSTER + "\","
                + "\"ACLName\":\"open-access\"}");
        response.then().statusCode(200);
        openHost = response.path("Cluster.ClusterEndpoint.Address");
        openPort = response.path("Cluster.ClusterEndpoint.Port");
    }

    @Test
    @Order(2)
    void clusterInfoReportsStateOk() throws Exception {
        try (Socket socket = openSocket(openPort)) {
            write(socket, "INFO", "cluster");
            String section = (String) readReply(socket);
            assertTrue(section.contains("cluster_enabled:1"), "Expected cluster mode but got: " + section);

            write(socket, "CLUSTER", "INFO");
            String info = (String) readReply(socket);
            assertTrue(info.contains("cluster_state:ok"), "Expected cluster_state:ok but got: " + info);
            assertTrue(info.contains("cluster_slots_assigned:16384"), "Expected all slots assigned: " + info);
        }
    }

    @Test
    @Order(3)
    void clusterSlotsAnnouncesTheClusterEndpointForAllSlots() throws Exception {
        try (Socket socket = openSocket(openPort)) {
            write(socket, "CLUSTER", "SLOTS");
            List<?> slots = (List<?>) readReply(socket);
            assertEquals(1, slots.size(), "One shard expected, got: " + slots);
            List<?> range = (List<?>) slots.getFirst();
            assertEquals(0L, range.get(0));
            assertEquals(16383L, range.get(1));
            List<?> primary = (List<?>) range.get(2);
            assertEquals(openHost, primary.get(0), "Primary must be announced at the ClusterEndpoint host");
            assertEquals((long) openPort, primary.get(1), "Primary must be announced at the proxy port");
        }
    }

    @Test
    @Order(4)
    void setAndGetThroughTheAnnouncedNode() throws Exception {
        String host;
        int port;
        try (Socket socket = openSocket(openPort)) {
            write(socket, "CLUSTER", "SLOTS");
            List<?> primary = (List<?>) ((List<?>) ((List<?>) readReply(socket)).getFirst()).get(2);
            host = (String) primary.get(0);
            port = ((Long) primary.get(1)).intValue();
        }
        try (Socket socket = openSocket(host, port)) {
            write(socket, "READONLY");
            assertEquals("OK", readReply(socket));
            write(socket, "SET", "{user1}:greeting", "hello");
            assertEquals("OK", readReply(socket));
            write(socket, "GET", "{user1}:greeting");
            assertEquals("hello", readReply(socket));
        }
    }

    @Test
    @Order(5)
    void authenticatedClientSeesTheClusterTopology() throws Exception {
        memorydb("CreateUser", "{\"UserName\":\"" + AUTH_USER + "\","
                + "\"AccessString\":\"on ~* +@all\","
                + "\"AuthenticationMode\":{\"Type\":\"password\",\"Passwords\":[\"" + AUTH_PASSWORD + "\"]}}")
            .then().statusCode(200);
        memorydb("CreateACL", "{\"ACLName\":\"" + AUTH_ACL + "\",\"UserNames\":[\"default\",\"" + AUTH_USER + "\"]}")
            .then().statusCode(200);
        authPort = memorydb("CreateCluster", "{\"ClusterName\":\"" + AUTH_CLUSTER + "\","
                + "\"ACLName\":\"" + AUTH_ACL + "\"}")
            .then().statusCode(200)
            .extract().path("Cluster.ClusterEndpoint.Port");

        try (Socket socket = openSocket(authPort)) {
            write(socket, "CLUSTER", "SLOTS");
            Object denied = readReply(socket);
            assertTrue(denied instanceof RespError e && e.message().startsWith("NOAUTH"),
                    "Expected NOAUTH before AUTH but got: " + denied);
        }
        try (Socket socket = openSocket(authPort)) {
            write(socket, "AUTH", AUTH_USER, AUTH_PASSWORD);
            assertEquals("OK", readReply(socket));
            write(socket, "CLUSTER", "SLOTS");
            List<?> primary = (List<?>) ((List<?>) ((List<?>) readReply(socket)).getFirst()).get(2);
            assertEquals((long) authPort, primary.get(1));
            write(socket, "SET", "k", "v");
            assertEquals("OK", readReply(socket));
        }
    }

    // ──────────────────────────── Helpers ────────────────────────────

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
