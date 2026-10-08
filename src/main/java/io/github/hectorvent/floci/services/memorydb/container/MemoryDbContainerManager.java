package io.github.hectorvent.floci.services.memorydb.container;

import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.elasticache.container.RespLineReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages backend Docker container lifecycle for MemoryDB clusters.
 * In native (dev) mode, binds container port 6379 to a random host port.
 * In Docker mode, uses the container's internal network IP directly.
 */
@ApplicationScoped
public class MemoryDbContainerManager {

    private static final Logger LOG = Logger.getLogger(MemoryDbContainerManager.class);
    private static final int BACKEND_PORT = 6379;
    private static final String VALKEY_EXTRA_FLAGS = "VALKEY_EXTRA_FLAGS";

    private static final int BACKEND_READY_DEADLINE_MS = 60_000;
    private static final int BACKEND_READY_RETRY_MS = 100;
    private static final int BACKEND_PROBE_CONNECT_MS = 2_000;
    private static final byte[] RESP_PING = "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8);

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Map<String, MemoryDbContainerHandle> activeContainers = new ConcurrentHashMap<>();
    private volatile boolean dockerUnavailableLogged;

    @Inject
    public MemoryDbContainerManager(ContainerBuilder containerBuilder,
                                    ContainerLifecycleManager lifecycleManager,
                                    ContainerLogStreamer logStreamer,
                                    ContainerDetector containerDetector,
                                    EmulatorConfig config,
                                    RegionResolver regionResolver) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.config = config;
        this.regionResolver = regionResolver;
    }

    /**
     * Attempts {@link #start} and reports the backend as unavailable instead of propagating the
     * failure, when the cause is that no Docker daemon is reachable from Floci — Floci running
     * inside Docker without a mounted socket, or a stopped daemon on the host. A failure raised
     * while the daemon <em>is</em> reachable is a genuine container problem and still propagates,
     * so nothing changes for a Floci that can start MemoryDB containers.
     *
     * @return the container handle, or {@code null} when no Docker daemon is reachable
     */
    public MemoryDbContainerHandle tryStart(String clusterName, String image) {
        return tryStart(clusterName, image, List.of());
    }

    /** {@link #tryStart(String, String)} with extra Valkey server flags, as for {@link #start(String, String, List)}. */
    public MemoryDbContainerHandle tryStart(String clusterName, String image, List<String> extraServerFlags) {
        try {
            MemoryDbContainerHandle handle = start(clusterName, image, extraServerFlags);
            dockerUnavailableLogged = false;
            return handle;
        } catch (RuntimeException e) {
            if (isDockerReachable()) {
                throw e;
            }
            if (!dockerUnavailableLogged) {
                dockerUnavailableLogged = true;
                LOG.warnv("No Docker daemon is reachable from Floci ({0}). MemoryDB metadata "
                        + "operations keep working and clusters still reach 'available', but they "
                        + "have no backing Redis/Valkey container until a daemon becomes reachable.",
                        e.getMessage());
            }
            return null;
        }
    }

    /**
     * {@link #tryStart(String, String, List)} for a cluster restored after a Floci restart: a
     * container still there under the cluster's name (Floci restarted but Docker did not) is
     * adopted, started again if it had stopped, so its keyspace survives. A container created
     * with different server flags announces a stale endpoint, so it is replaced by a fresh, empty
     * one, as is a survivor that cannot be adopted.
     *
     * @return the container handle, or {@code null} when no Docker daemon is reachable
     */
    public MemoryDbContainerHandle tryAdoptOrStart(String clusterName, String image, List<String> extraServerFlags) {
        String containerName = containerName(clusterName);
        Optional<Container> existing = lifecycleManager.findByName(containerName);
        if (existing.isPresent()) {
            String containerId = existing.get().getId();
            if (createdWithFlags(containerId, extraServerFlags)) {
                try {
                    return adopt(clusterName, containerId);
                } catch (RuntimeException e) {
                    LOG.warnv("Could not adopt container {0} for MemoryDB cluster {1}, starting an empty "
                            + "one instead: {2}", containerName, clusterName, e.getMessage());
                }
            } else {
                LOG.warnv("Container {0} for MemoryDB cluster {1} announces a different endpoint than "
                        + "the cluster now has; replacing it with an empty one", containerName, clusterName);
            }
        }
        return tryStart(clusterName, image, extraServerFlags);
    }

    /**
     * Whether the container was created with these server flags. An environment Docker would not
     * report counts as a match: replacing the container destroys its data, so that is done only
     * on a known mismatch.
     */
    private boolean createdWithFlags(String containerId, List<String> extraServerFlags) {
        Optional<List<String>> env = lifecycleManager.containerEnv(containerId);
        if (env.isEmpty()) {
            return true;
        }
        String expected = extraServerFlags.isEmpty()
                ? null
                : VALKEY_EXTRA_FLAGS + "=" + String.join(" ", extraServerFlags);
        String actual = env.get().stream()
                .filter(entry -> entry.startsWith(VALKEY_EXTRA_FLAGS + "="))
                .findFirst()
                .orElse(null);
        return Objects.equals(expected, actual);
    }

    private MemoryDbContainerHandle adopt(String clusterName, String containerId) {
        ContainerInfo info = lifecycleManager.adopt(containerId, List.of(BACKEND_PORT));
        EndpointInfo endpoint = info.getEndpoint(BACKEND_PORT);
        LOG.infov("Adopted MemoryDB backend container for cluster {0}: {1}", clusterName, endpoint);
        MemoryDbContainerHandle handle = registerHandle(clusterName, info.containerId(), endpoint);
        waitForBackendReady(clusterName, endpoint.host(), endpoint.port());
        return handle;
    }

    /**
     * Probes the configured Docker endpoint, which is how a missing daemon is told apart from a
     * container that failed for its own reasons.
     */
    public boolean isDockerReachable() {
        try {
            lifecycleManager.getDockerClient().pingCmd().exec();
            return true;
        } catch (Exception e) {
            LOG.debugv("Docker daemon is not reachable: {0}", e.getMessage());
            return false;
        }
    }

    public MemoryDbContainerHandle start(String clusterName, String image) {
        return start(clusterName, image, List.of());
    }

    /**
     * Starts a backend container for the given cluster, appending {@code extraServerFlags} to the
     * Valkey server command line (via the image's {@code VALKEY_EXTRA_FLAGS} hook). The service
     * uses this to run the node in cluster mode with the proxy endpoint announced.
     */
    public MemoryDbContainerHandle start(String clusterName, String image, List<String> extraServerFlags) {
        LOG.infov("Starting MemoryDB backend container for cluster: {0}", clusterName);

        String containerName = containerName(clusterName);

        lifecycleManager.removeIfExists(containerName);

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withDockerNetwork(config.services().memorydb().dockerNetwork())
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "memorydb", clusterName, regionResolver.getAccountId(), resolvedRegion()));

        if (!extraServerFlags.isEmpty()) {
            specBuilder.withEnv(VALKEY_EXTRA_FLAGS, String.join(" ", extraServerFlags));
        }

        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withDynamicPort(BACKEND_PORT);
        } else {
            specBuilder.withExposedPort(BACKEND_PORT);
        }

        ContainerSpec spec = specBuilder.build();

        ContainerInfo info = lifecycleManager.createAndStart(spec);
        EndpointInfo endpoint = info.getEndpoint(BACKEND_PORT);

        LOG.infov("MemoryDB backend for cluster {0}: {1}", clusterName, endpoint);

        MemoryDbContainerHandle handle = registerHandle(clusterName, info.containerId(), endpoint);
        waitForBackendReady(clusterName, endpoint.host(), endpoint.port());
        return handle;
    }

    private MemoryDbContainerHandle registerHandle(String clusterName, String containerId, EndpointInfo endpoint) {
        MemoryDbContainerHandle handle = new MemoryDbContainerHandle(
                containerId, clusterName, endpoint.host(), endpoint.port());
        activeContainers.put(clusterName, handle);

        String shortId = containerId.length() >= 8 ? containerId.substring(0, 8) : containerId;
        String logGroup = "/aws/memorydb/cluster/" + clusterName + "/engine-log";
        String logStream = logStreamer.generateLogStreamName(shortId);

        Closeable logHandle = logStreamer.attach(
                containerId, logGroup, logStream, resolvedRegion(), "memorydb:" + clusterName);
        handle.setLogStream(logHandle);
        return handle;
    }

    private static void waitForBackendReady(String clusterName, String host, int port) {
        long deadline = System.currentTimeMillis() + BACKEND_READY_DEADLINE_MS;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), BACKEND_PROBE_CONNECT_MS);
                s.setTcpNoDelay(true);
                s.setSoTimeout(BACKEND_PROBE_CONNECT_MS);
                OutputStream out = s.getOutputStream();
                out.write(RESP_PING);
                out.flush();
                String line = RespLineReader.readAsciiLineCrLf(s.getInputStream());
                if (line.startsWith("+PONG")) {
                    if (attempt > 1) {
                        LOG.infov("MemoryDB backend ready for cluster {0} after {1} probe attempt(s)", clusterName, attempt);
                    }
                    return;
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debugv("MemoryDB backend probe for cluster {0}: unexpected line {1}", clusterName, line);
                }
            } catch (IOException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debugv("MemoryDB backend probe for cluster {0} attempt {1}: {2}", clusterName, attempt, e.getMessage());
                }
            }
            try {
                Thread.sleep(BACKEND_READY_RETRY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for MemoryDB backend " + clusterName, ie);
            }
        }
        throw new RuntimeException(
                "MemoryDB backend for cluster " + clusterName + " did not become ready on " + host + ":" + port
                        + " within " + BACKEND_READY_DEADLINE_MS + "ms");
    }

    public void stop(MemoryDbContainerHandle handle) {
        if (handle == null) {
            return;
        }
        activeContainers.remove(handle.getClusterName());
        lifecycleManager.stopAndRemove(handle.getContainerId(), handle.getLogStream());
    }

    /**
     * Stops and removes the backend container for a cluster by name, if one exists.
     * Used by the service's provisioning rollback: {@link #start} registers the container in
     * {@code activeContainers} <em>before</em> {@link #waitForBackendReady}, so a readiness
     * timeout throws without ever returning the handle to the caller. In that case rollback
     * can't go through {@link #stop} (it has no handle), so it cleans up by name instead. Falls
     * back to the deterministic container name to catch a container that failed before it was
     * registered. Idempotent — a no-op when nothing is running for the name.
     */
    public void stopByClusterName(String clusterName) {
        MemoryDbContainerHandle handle = activeContainers.get(clusterName);
        if (handle != null) {
            stop(handle);
            return;
        }
        lifecycleManager.removeIfExists(containerName(clusterName));
    }

    private String containerName(String clusterName) {
        return ContainerStorageHelper.resourceName(config, "memorydb", null, clusterName);
    }

    private String resolvedRegion() {
        String region = regionResolver.getRegion();
        return region != null ? region : regionResolver.getDefaultRegion();
    }

    /**
     * Stops every cluster container on shutdown, unless {@code keep-running-on-shutdown} is set:
     * then the containers are left running for the next start to adopt, and only their log
     * streams are closed.
     */
    public void stopAll() {
        List<MemoryDbContainerHandle> handles = new ArrayList<>(activeContainers.values());
        if (config.services().memorydb().keepRunningOnShutdown()) {
            if (!handles.isEmpty()) {
                LOG.infov("Leaving {0} MemoryDB container(s) running on shutdown", handles.size());
            }
            for (MemoryDbContainerHandle handle : handles) {
                activeContainers.remove(handle.getClusterName());
                if (handle.getLogStream() != null) {
                    lifecycleManager.closeLogStreamAfterContainerStop(handle.getLogStream());
                }
            }
            return;
        }
        if (!handles.isEmpty()) {
            LOG.infov("Stopping {0} MemoryDB container(s) on shutdown", handles.size());
        }
        for (MemoryDbContainerHandle handle : handles) {
            stop(handle);
        }
    }
}
