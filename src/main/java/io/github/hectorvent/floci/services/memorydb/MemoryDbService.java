package io.github.hectorvent.floci.services.memorydb;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.container.ValkeyClusterFormation;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerHandle;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerManager;
import io.github.hectorvent.floci.services.memorydb.model.Acl;
import io.github.hectorvent.floci.services.memorydb.model.AuthMode;
import io.github.hectorvent.floci.services.memorydb.model.Cluster;
import io.github.hectorvent.floci.services.memorydb.model.ClusterStatus;
import io.github.hectorvent.floci.services.memorydb.model.Endpoint;
import io.github.hectorvent.floci.services.memorydb.model.User;
import io.github.hectorvent.floci.services.memorydb.proxy.MemoryDbProxyManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core MemoryDB business logic — clusters, ACLs and users.
 *
 * <p>Authentication follows the real MemoryDB model: a {@link User} is created with a
 * password or IAM auth mode, attached to an {@link Acl}, and a cluster references that
 * ACL via {@code ACLName}. A cluster therefore has no auth mode of its own — its
 * effective authentication is resolved from the users of the ACL it references.
 *
 * <p>The built-in {@code open-access} ACL and {@code default} user (which AWS provides
 * out of the box and which cannot be created or deleted) are synthesized rather than
 * stored, so they always exist for every account and map to the no-auth path.
 */
@ApplicationScoped
public class MemoryDbService {

    private static final Logger LOG = Logger.getLogger(MemoryDbService.class);
    private static final String DEFAULT_ENGINE = "redis";
    private static final String DEFAULT_ENGINE_VERSION = "7.1";
    private static final String DEFAULT_ACL = "open-access";
    private static final String DEFAULT_USER = "default";
    private static final String ACTIVE = "active";
    private static final int REDIS_PORT = 6379;

    // Per the MemoryDB API: a user name must start with a letter and contain only
    // letters, digits and hyphens.
    private static final java.util.regex.Pattern USER_NAME_PATTERN =
            java.util.regex.Pattern.compile("[a-zA-Z][a-zA-Z0-9\\-]*");

    private final AccountAwareStorageBackend<Cluster> clusters;
    private final AccountAwareStorageBackend<User> users;
    private final AccountAwareStorageBackend<Acl> acls;
    private final MemoryDbContainerManager containerManager;
    private final MemoryDbProxyManager proxyManager;
    private final SigV4Validator sigV4Validator;
    private final ValkeyClusterFormation clusterFormation;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();
    private final Set<String> provisioningClusterNames = ConcurrentHashMap.newKeySet();
    private final Map<String, Object> clusterLocks = new ConcurrentHashMap<>();

    @Inject
    public MemoryDbService(MemoryDbContainerManager containerManager,
                           MemoryDbProxyManager proxyManager,
                           SigV4Validator sigV4Validator,
                           ValkeyClusterFormation clusterFormation,
                           StorageFactory storageFactory,
                           EmulatorConfig config,
                           RegionResolver regionResolver) {
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.sigV4Validator = sigV4Validator;
        this.clusterFormation = clusterFormation;
        this.config = config;
        this.regionResolver = regionResolver;
        this.clusters = storageFactory.create("memorydb", "memorydb-clusters.json",
                new TypeReference<Map<String, Cluster>>() {});
        this.users = storageFactory.create("memorydb", "memorydb-users.json",
                new TypeReference<Map<String, User>>() {});
        this.acls = storageFactory.create("memorydb", "memorydb-acls.json",
                new TypeReference<Map<String, Acl>>() {});
    }

    // ──────────────────────────── Clusters ────────────────────────────

    public Cluster createCluster(Cluster spec, String region) {
        String name = spec.getName();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ClusterName is required.", 400);
        }
        String resourceKey = key(region, name);
        if (clusters.get(resourceKey).isPresent() || legacyGet(clusters, name, region).isPresent()) {
            throw new AwsException("ClusterAlreadyExistsFault",
                    "Cluster with specified name already exists.", 400);
        }
        // Claim the name for the whole provisioning attempt so a concurrent create can't race
        // ahead and be stopped by this request's handle-less rollback fallback.
        if (!provisioningClusterNames.add(resourceKey)) {
            throw new AwsException("ClusterAlreadyExistsFault",
                    "Cluster " + name + " is already being created.", 400);
        }

        try {
            String aclName = spec.getAclName();
            if (aclName == null || aclName.isBlank()) {
                throw new AwsException("InvalidParameterValueException", "ACLName is required.", 400);
            }
            requireAclExists(aclName, region);
            boolean authRequired = isAuthRequired(aclName, region);

            Cluster cluster = new Cluster();
            cluster.setName(name);
            cluster.setAccountId(regionResolver.getAccountId());
            cluster.setRegion(region);
            cluster.setDescription(spec.getDescription());
            cluster.setStatus(ClusterStatus.AVAILABLE);
            cluster.setNodeType(spec.getNodeType() != null ? spec.getNodeType() : "db.t4g.small");
            cluster.setNumberOfShards(spec.getNumberOfShards() > 0 ? spec.getNumberOfShards() : 1);
            cluster.setEngine(spec.getEngine() != null ? spec.getEngine() : DEFAULT_ENGINE);
            cluster.setEngineVersion(spec.getEngineVersion() != null ? spec.getEngineVersion() : DEFAULT_ENGINE_VERSION);
            cluster.setAclName(aclName);
            cluster.setTlsEnabled(spec.isTlsEnabled());
            cluster.setArn(buildArn(region, "cluster", name));
            cluster.setCreatedAt(Instant.now());
            cluster.setTags(spec.getTags());

            if (config.services().memorydb().mock()) {
                LOG.infov("Creating MemoryDB cluster {0} in mock mode (no container)", name);
                cluster.setClusterEndpoint(new Endpoint(resolveEndpointHost(), REDIS_PORT));
            } else {
                startBackend(cluster, authRequired);
            }

            clusters.put(resourceKey, cluster);
            LOG.infov("MemoryDB cluster {0} created (acl={1}, authRequired={2}), endpoint={3}:{4}",
                    name, aclName, String.valueOf(authRequired), cluster.getClusterEndpoint().address(),
                    String.valueOf(cluster.getClusterEndpoint().port()));
            return cluster;
        } finally {
            provisioningClusterNames.remove(resourceKey);
        }
    }

    public Cluster getCluster(String name) {
        return getCluster(name, currentRegion());
    }

    public Cluster getCluster(String name, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ClusterName is required.", 400);
        }
        return resourceGet(clusters, name, region).orElseThrow(() ->
                new AwsException("ClusterNotFoundFault", "Cluster not found.", 404));
    }

    public Collection<Cluster> describeClusters(String filterName) {
        return describeClusters(filterName, currentRegion());
    }

    public Collection<Cluster> describeClusters(String filterName, String region) {
        migrateLegacyClusters(region);
        if (filterName != null && !filterName.isBlank()) {
            return List.of(getCluster(filterName, region));
        }
        return clusters.scan(k -> k.startsWith(region + ":"));
    }

    public Cluster updateCluster(String name, String description) {
        return updateCluster(name, description, currentRegion());
    }

    public Cluster updateCluster(String name, String description, String region) {
        Cluster cluster = getCluster(name, region);
        if (description != null) {
            cluster.setDescription(description);
        }
        clusters.put(key(region, name), cluster);
        return cluster;
    }

    public Cluster deleteCluster(String name) {
        return deleteCluster(name, currentRegion());
    }

    public Cluster deleteCluster(String name, String region) {
        Cluster cluster = getCluster(name, region);
        synchronized (lockFor(regionResolver.getAccountId(), key(region, name))) {
            boolean restoring = cluster.getStatus() == ClusterStatus.CREATING;
            cluster.setStatus(ClusterStatus.DELETING);
            String identity = identityName(cluster.getAccountId(), region, name);

            proxyManager.stopProxy(identity);

            if (cluster.getContainerId() != null) {
                containerManager.stop(new MemoryDbContainerHandle(
                        cluster.getContainerId(), identity, cluster.getContainerHost(), cluster.getContainerPort()));
            } else if (restoring) {
                // A cluster still being restored has no container id yet; the restore discards
                // whatever it starts once it sees the record gone, this catches one already up.
                containerManager.stopByClusterName(identity);
            }

            releaseProxyPort(cluster.getProxyPort());
            clusters.delete(key(region, name));
        }
        LOG.infov("MemoryDB cluster {0} deleted", name);
        return cluster;
    }

    // ──────────────────────────── Restore after restart ────────────────────────────

    /** A persisted cluster being restored, with the account and store key that own it. */
    private record RestoreTarget(String accountId, String key, String region, Cluster cluster) {}

    /**
     * Brings the data plane back for every cluster restored from disk. Invoked from
     * {@code EmulatorLifecycle} after {@code storageFactory.loadAll()}, as ElastiCache's restore
     * is. Only the record is persisted; the auth proxy is process-local, so without this a
     * cluster comes back {@code available} with nothing listening on its endpoint, and the port
     * it advertises is free for the next create to take.
     *
     * <p>The proxy port each record carries is reserved synchronously, before any request can
     * claim it, and the record is marked {@code creating}. The container and proxy work then runs
     * in the background, so it does not delay emulator readiness: a container still running
     * under the cluster's name is adopted with its keyspace, otherwise an empty one is started,
     * the single-shard cluster is formed when its slots are not already assigned, and the proxy
     * is started on the same port. A cluster whose data plane cannot come back is reported
     * {@code restore-failed}; Floci's own startup never fails on it.
     *
     * <p>Mock mode has no data plane to restore, and clusters created in mock mode carry no proxy
     * port, so both are left as they are.
     */
    public CompletableFuture<Void> restorePersistedRuntime() {
        if (config.services().memorydb().mock()) {
            return CompletableFuture.completedFuture(null);
        }
        List<RestoreTarget> toRestore = new ArrayList<>();
        for (AccountAwareStorageBackend.AccountEntry<Cluster> entry : clusters.scanAllAccountEntries(k -> true)) {
            Cluster cluster = entry.value();
            if (cluster.getName() == null || cluster.getStatus() == ClusterStatus.DELETING) {
                continue;
            }
            if (cluster.getProxyPort() <= 0 && cluster.getStatus() != ClusterStatus.RESTORE_FAILED) {
                continue;
            }
            RestoreTarget target = canonicalTarget(entry);
            synchronized (lockFor(target.accountId(), target.key())) {
                reserveCluster(target, toRestore);
                clusters.putForAccount(target.accountId(), target.key(), cluster);
            }
        }
        if (toRestore.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        LOG.infov("Restoring {0} MemoryDB cluster(s) in the background", String.valueOf(toRestore.size()));
        return CompletableFuture.runAsync(() -> toRestore.forEach(this::restoreCluster));
    }

    /**
     * Resolves the owner of a persisted record, moving one stored under a pre-region key to the
     * {@code region:name} key requests use, so a delete and the restore lock the same record.
     */
    private RestoreTarget canonicalTarget(AccountAwareStorageBackend.AccountEntry<Cluster> entry) {
        Cluster cluster = entry.value();
        String region = cluster.getRegion();
        if (region == null || region.isBlank()) {
            int separator = entry.key().indexOf(':');
            region = separator > 0 ? entry.key().substring(0, separator) : regionResolver.getDefaultRegion();
            cluster.setRegion(region);
        }
        if (cluster.getAccountId() == null) {
            cluster.setAccountId(entry.accountId());
        }
        String canonicalKey = key(region, cluster.getName());
        if (!canonicalKey.equals(entry.key())) {
            clusters.deleteForAccount(entry.accountId(), entry.key());
        }
        return new RestoreTarget(entry.accountId(), canonicalKey, region, cluster);
    }

    /**
     * Reserves the proxy port a cluster comes back advertising and marks it {@code creating}. A
     * port outside the configured range, or already taken by another record, is replaced by a
     * fresh one, which changes that cluster's endpoint. The host is re-derived from configuration
     * rather than replayed, so a changed {@code FLOCI_HOSTNAME} is honoured.
     */
    private void reserveCluster(RestoreTarget target, List<RestoreTarget> toRestore) {
        Cluster cluster = target.cluster();
        int previousPort = cluster.getProxyPort();
        try {
            int base = config.services().memorydb().proxyBasePort();
            int max = config.services().memorydb().proxyMaxPort();
            boolean keepPort = previousPort >= base && previousPort <= max && usedPorts.add(previousPort);
            int proxyPort = keepPort ? previousPort : allocateProxyPort();
            if (!keepPort && previousPort > 0) {
                LOG.warnv("MemoryDB cluster {0} cannot keep proxy port {1}; its endpoint moves to port {2}",
                        cluster.getName(), String.valueOf(previousPort), String.valueOf(proxyPort));
            }
            cluster.setProxyPort(proxyPort);
            cluster.setClusterEndpoint(new Endpoint(resolveEndpointHost(), proxyPort));
            cluster.setStatus(ClusterStatus.CREATING);
            // Whatever the record carried describes the previous process's container; a delete
            // taken during the restore must not act on it.
            cluster.setContainerId(null);
            cluster.setContainerHost(null);
            cluster.setContainerPort(0);
            toRestore.add(target);
        } catch (RuntimeException e) {
            markRestoreFailed(cluster);
            LOG.warnv(e, "Failed to restore MemoryDB cluster {0}", cluster.getName());
        }
    }

    /**
     * Brings back the container and auth proxy behind one cluster. The container work runs
     * outside the cluster's lock, so a restore that may pull an image does not hold a delete of
     * the same cluster behind it; the write-back takes the lock and re-reads the record, because
     * a delete taken meanwhile has already removed it.
     */
    private void restoreCluster(RestoreTarget target) {
        Cluster cluster = target.cluster();
        String name = cluster.getName();
        String region = target.region();
        String identity = identityName(target.accountId(), region, name);
        int proxyPort = cluster.getProxyPort();
        String endpointHost = cluster.getClusterEndpoint().address();
        String image = config.services().memorydb().defaultImage();
        try {
            boolean authRequired = isAuthRequiredForAccount(target.accountId(), cluster.getAclName(), region);
            MemoryDbContainerHandle handle = containerManager.tryAdoptOrStart(identity, image,
                    ValkeyClusterFormation.nodeFlags(endpointHost,
                            ValkeyClusterFormation.resolveAnnounceIp(endpointHost), proxyPort));
            if (handle != null) {
                if (clusterFormation.isFormed(handle.getHost(), handle.getPort())) {
                    clusterFormation.awaitOk(identity, handle.getHost(), handle.getPort());
                } else {
                    clusterFormation.form(identity, List.of(new ValkeyClusterFormation.Node(
                            handle.getHost(), handle.getPort(), handle.getHost(), 0, true)), 1);
                }
                proxyManager.startProxy(identity, authRequired, proxyPort, handle.getHost(), handle.getPort(),
                        (username, secret) -> authenticate(name, username, secret, region));
            } else {
                LOG.warnv("MemoryDB cluster {0} restored without a backing container: no Docker daemon "
                        + "is reachable. Metadata operations work; connections to the cluster do not "
                        + "until a daemon appears.", name);
            }
            synchronized (lockFor(target.accountId(), target.key())) {
                if (restoreTargetLost(target)) {
                    abandonRestoredBackend(identity, handle);
                    return;
                }
                cluster.setContainerId(handle != null ? handle.getContainerId() : null);
                cluster.setContainerHost(handle != null ? handle.getHost() : null);
                cluster.setContainerPort(handle != null ? handle.getPort() : 0);
                cluster.setStatus(ClusterStatus.AVAILABLE);
                clusters.putForAccount(target.accountId(), target.key(), cluster);
            }
            LOG.infov("Restored MemoryDB cluster {0}, endpoint={1}:{2}", name, endpointHost,
                    String.valueOf(proxyPort));
        } catch (RuntimeException e) {
            failRestore(target, identity, e);
        }
    }

    /** Whether the record a restore is about to write back is still there; callers hold its lock. */
    private boolean restoreTargetLost(RestoreTarget target) {
        return clusters.getForAccount(target.accountId(), target.key())
                .map(current -> current.getStatus() == ClusterStatus.DELETING)
                .orElse(true);
    }

    /**
     * Drops what a restore started for a cluster deleted while it ran. The port is not released:
     * the delete released it already, and a later create may hold it by now.
     */
    private void abandonRestoredBackend(String identity, MemoryDbContainerHandle handle) {
        try {
            proxyManager.stopProxy(identity);
        } catch (RuntimeException e) {
            LOG.warnv("Error stopping proxy for deleted MemoryDB cluster {0}: {1}", identity, e.getMessage());
        }
        if (handle != null) {
            try {
                containerManager.stop(handle);
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping container for deleted MemoryDB cluster {0}: {1}", identity, e.getMessage());
            }
        }
        LOG.infov("Discarded the restored data plane for MemoryDB cluster {0}: it was deleted while "
                + "it was being restored", identity);
    }

    /**
     * Reports a cluster whose data plane could not be brought back as {@code restore-failed}
     * without deleting the record, tears down what the attempt left running and frees its port.
     * The next start retries it on a fresh port.
     */
    private void failRestore(RestoreTarget target, String identity, RuntimeException cause) {
        Cluster cluster = target.cluster();
        synchronized (lockFor(target.accountId(), target.key())) {
            try {
                proxyManager.stopProxy(identity);
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping proxy for MemoryDB cluster {0}: {1}", identity, e.getMessage());
            }
            try {
                containerManager.stopByClusterName(identity);
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping container for MemoryDB cluster {0}: {1}", identity, e.getMessage());
            }
            if (restoreTargetLost(target)) {
                LOG.warnv(cause, "Failed to restore MemoryDB cluster {0}, which was deleted while it was "
                        + "being restored", cluster.getName());
                return;
            }
            releaseProxyPort(cluster.getProxyPort());
            markRestoreFailed(cluster);
            try {
                clusters.putForAccount(target.accountId(), target.key(), cluster);
            } catch (RuntimeException persistFailure) {
                cause.addSuppressed(persistFailure);
            }
            LOG.warnv(cause, "Failed to restore MemoryDB cluster {0}", cluster.getName());
        }
    }

    private void markRestoreFailed(Cluster cluster) {
        cluster.setStatus(ClusterStatus.RESTORE_FAILED);
        cluster.setClusterEndpoint(null);
        cluster.setProxyPort(0);
        cluster.setContainerId(null);
        cluster.setContainerHost(null);
        cluster.setContainerPort(0);
    }

    /**
     * {@link #isAuthRequired} for a named account, as a restore runs outside any request. An ACL
     * that cannot be found counts as requiring auth, so a restore never opens a protected cluster.
     */
    private boolean isAuthRequiredForAccount(String accountId, String aclName, String region) {
        if (DEFAULT_ACL.equals(aclName)) {
            return false;
        }
        Optional<Acl> acl = acls.getForAccount(accountId, key(region, aclName));
        if (acl.isEmpty()) {
            return true;
        }
        for (String userName : acl.get().getUserNames()) {
            User user = DEFAULT_USER.equals(userName)
                    ? builtinDefaultUser(region)
                    : users.getForAccount(accountId, key(region, userName)).orElse(null);
            if (user == null || user.getAuthMode() != AuthMode.NO_PASSWORD) {
                return true;
            }
        }
        return false;
    }

    private Object lockFor(String accountId, String clusterKey) {
        return clusterLocks.computeIfAbsent(accountId + "/" + clusterKey, k -> new Object());
    }

    // ──────────────────────────── Users ────────────────────────────

    public User createUser(User spec, String region) {
        String name = spec.getName();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "UserName is required.", 400);
        }
        if (!USER_NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("InvalidParameterValueException",
                    "UserName must start with a letter and contain only letters, digits and hyphens.", 400);
        }
        String resourceKey = key(region, name);
        if (DEFAULT_USER.equals(name) || users.get(resourceKey).isPresent()
                || legacyGet(users, name, region).isPresent()) {
            throw new AwsException("UserAlreadyExistsFault",
                    "User with specified name already exists.", 400);
        }
        if (spec.getAuthMode() == null) {
            throw new AwsException("InvalidParameterValueException",
                    "AuthenticationMode is required.", 400);
        }
        // AuthenticationMode.Type accepts "no-password" in the wire enum, but the service
        // rejects it on create: per the API, all newly-created users must authenticate with
        // a password or IAM. "no-password" is only ever the built-in default user.
        if (spec.getAuthMode() == AuthMode.NO_PASSWORD) {
            throw new AwsException("InvalidParameterValueException",
                    "AuthenticationMode Type must be 'password' or 'iam' for a new user.", 400);
        }
        if (spec.getAuthMode() == AuthMode.PASSWORD
                && (spec.getPasswords() == null || spec.getPasswords().isEmpty())) {
            throw new AwsException("InvalidParameterValueException",
                    "At least one password is required for password authentication.", 400);
        }
        if (spec.getAccessString() == null || spec.getAccessString().isBlank()) {
            throw new AwsException("InvalidParameterValueException", "AccessString is required.", 400);
        }

        User user = new User();
        user.setName(name);
        user.setAccountId(regionResolver.getAccountId());
        user.setRegion(region);
        user.setStatus(ACTIVE);
        user.setAuthMode(spec.getAuthMode());
        user.setPasswords(spec.getPasswords());
        user.setAccessString(spec.getAccessString());
        user.setMinimumEngineVersion(DEFAULT_ENGINE_VERSION);
        user.setArn(buildArn(region, "user", name));
        user.setCreatedAt(Instant.now());

        users.put(resourceKey, user);
        LOG.infov("MemoryDB user {0} created with authMode={1}", name, user.getAuthMode());
        return user;
    }

    public Collection<User> describeUsers(String filterName, String region) {
        if (filterName != null && !filterName.isBlank()) {
            return resourceGet(users, filterName, region)
                    .map(List::of)
                    .or(() -> DEFAULT_USER.equals(filterName)
                            ? java.util.Optional.of(List.of(builtinDefaultUser(region)))
                            : java.util.Optional.empty())
                    .orElseThrow(() -> new AwsException("UserNotFoundFault", "User not found.", 404));
        }
        migrateLegacyUsers(region);
        List<User> all = new ArrayList<>();
        all.add(builtinDefaultUser(region));
        all.addAll(users.scan(k -> k.startsWith(region + ":")));
        return all;
    }

    public User deleteUser(String name) {
        return deleteUser(name, currentRegion());
    }

    public User deleteUser(String name, String region) {
        if (DEFAULT_USER.equals(name)) {
            throw new AwsException("InvalidParameterValueException",
                    "The default user cannot be deleted.", 400);
        }
        User user = resourceGet(users, name, region).orElseThrow(() ->
                new AwsException("UserNotFoundFault", "User not found.", 404));
        users.delete(key(region, name));
        LOG.infov("MemoryDB user {0} deleted", name);
        return user;
    }

    // ──────────────────────────── ACLs ────────────────────────────

    public Acl createAcl(Acl spec, String region) {
        String name = spec.getName();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ACLName is required.", 400);
        }
        String resourceKey = key(region, name);
        if (DEFAULT_ACL.equals(name) || acls.get(resourceKey).isPresent()
                || legacyGet(acls, name, region).isPresent()) {
            throw new AwsException("ACLAlreadyExistsFault",
                    "ACL with specified name already exists.", 400);
        }
        if (!spec.getUserNames().contains(DEFAULT_USER)) {
            throw new AwsException("DefaultUserRequired",
                    "A default user is required and must be specified.", 400);
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String userName : spec.getUserNames()) {
            if (!seen.add(userName)) {
                throw new AwsException("DuplicateUserNameFault",
                        "Duplicate user name " + userName + " in ACL.", 400);
            }
            if (!userExists(userName, region)) {
                throw new AwsException("UserNotFoundFault", "User " + userName + " not found.", 404);
            }
        }

        Acl acl = new Acl();
        acl.setName(name);
        acl.setAccountId(regionResolver.getAccountId());
        acl.setRegion(region);
        acl.setStatus(ACTIVE);
        acl.setUserNames(new ArrayList<>(spec.getUserNames()));
        acl.setMinimumEngineVersion(DEFAULT_ENGINE_VERSION);
        acl.setArn(buildArn(region, "acl", name));
        acl.setCreatedAt(Instant.now());

        acls.put(resourceKey, acl);
        LOG.infov("MemoryDB ACL {0} created with users={1}", name, acl.getUserNames());
        return acl;
    }

    public Collection<Acl> describeAcls(String filterName, String region) {
        if (filterName != null && !filterName.isBlank()) {
            return resourceGet(acls, filterName, region)
                    .map(List::of)
                    .or(() -> DEFAULT_ACL.equals(filterName)
                            ? java.util.Optional.of(List.of(builtinOpenAccessAcl(region)))
                            : java.util.Optional.empty())
                    .orElseThrow(() -> new AwsException("ACLNotFoundFault", "ACL not found.", 404));
        }
        migrateLegacyAcls(region);
        List<Acl> all = new ArrayList<>();
        all.add(builtinOpenAccessAcl(region));
        all.addAll(acls.scan(k -> k.startsWith(region + ":")));
        return all;
    }

    public Acl deleteAcl(String name) {
        return deleteAcl(name, currentRegion());
    }

    public Acl deleteAcl(String name, String region) {
        if (DEFAULT_ACL.equals(name)) {
            throw new AwsException("InvalidParameterValueException",
                    "The open-access ACL cannot be deleted.", 400);
        }
        Acl acl = resourceGet(acls, name, region).orElseThrow(() ->
                new AwsException("ACLNotFoundFault", "ACL not found.", 404));
        if (!clustersUsingAcl(name, region).isEmpty()) {
            throw new AwsException("InvalidACLStateFault",
                    "ACL " + name + " is associated with one or more clusters.", 400);
        }
        acls.delete(key(region, name));
        LOG.infov("MemoryDB ACL {0} deleted", name);
        return acl;
    }

    /** Names of ACLs that include the given user; used to populate the user response. */
    public List<String> aclNamesForUser(String userName) {
        return aclNamesForUser(userName, currentRegion());
    }

    public List<String> aclNamesForUser(String userName, String region) {
        migrateLegacyAcls(region);
        List<String> result = new ArrayList<>();
        if (DEFAULT_USER.equals(userName)) {
            result.add(DEFAULT_ACL);
        }
        acls.scan(k -> k.startsWith(region + ":")).stream()
                .filter(a -> a.getUserNames().contains(userName))
                .map(Acl::getName)
                .forEach(result::add);
        return result;
    }

    /** Names of clusters currently referencing the given ACL; used to populate the ACL response. */
    public List<String> clustersUsingAcl(String aclName) {
        return clustersUsingAcl(aclName, currentRegion());
    }

    public List<String> clustersUsingAcl(String aclName, String region) {
        migrateLegacyClusters(region);
        return clusters.scan(k -> k.startsWith(region + ":")).stream()
                .filter(c -> aclName.equals(c.getAclName()))
                .map(Cluster::getName)
                .toList();
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> listTags(String resourceArn) {
        return clusterByArn(resourceArn).getTags();
    }

    public Map<String, String> tagResource(String resourceArn, Map<String, String> tags) {
        Cluster cluster = clusterByArn(resourceArn);
        cluster.getTags().putAll(tags);
        clusters.put(key(cluster.getRegion(), cluster.getName()), cluster);
        return cluster.getTags();
    }

    public Map<String, String> untagResource(String resourceArn, List<String> tagKeys) {
        Cluster cluster = clusterByArn(resourceArn);
        tagKeys.forEach(cluster.getTags()::remove);
        clusters.put(key(cluster.getRegion(), cluster.getName()), cluster);
        return cluster.getTags();
    }

    // ──────────────────────────── Authentication ────────────────────────────

    /**
     * Validates a Redis AUTH attempt against the ACL the cluster references. A blank
     * username corresponds to the single-argument {@code AUTH <secret>} form and targets
     * the {@code default} user. The user's own auth mode decides how the secret is
     * checked: a password is compared against the user's passwords, an IAM token is
     * verified as a SigV4 presigned URL.
     */
    public boolean authenticate(String clusterName, String username, String secret) {
        return authenticate(clusterName, username, secret, currentRegion());
    }

    public boolean authenticate(String clusterName, String username, String secret, String region) {
        Cluster cluster = resourceGet(clusters, clusterName, region).orElse(null);
        if (cluster == null) {
            return false;
        }
        String aclName = cluster.getAclName();
        if (DEFAULT_ACL.equals(aclName)) {
            return true;
        }
        Acl acl = resourceGet(acls, aclName, region).orElse(null);
        if (acl == null) {
            return false;
        }
        String target = (username == null || username.isEmpty()) ? DEFAULT_USER : username;
        if (!acl.getUserNames().contains(target)) {
            return false;
        }
        User user = resolveUser(target, region);
        if (user == null) {
            return false;
        }
        return switch (user.getAuthMode()) {
            case IAM -> sigV4Validator.validate(secret, clusterName, user.getName());
            case PASSWORD -> user.getPasswords() != null && user.getPasswords().contains(secret);
            case NO_PASSWORD -> true;
        };
    }

    /** True if the ACL has at least one user that requires a credential (password or IAM). */
    private boolean isAuthRequired(String aclName, String region) {
        if (DEFAULT_ACL.equals(aclName)) {
            return false;
        }
        Acl acl = resourceGet(acls, aclName, region).orElse(null);
        if (acl == null) {
            return false;
        }
        return acl.getUserNames().stream()
                .map(name -> resolveUser(name, region))
                .filter(java.util.Objects::nonNull)
                .anyMatch(u -> u.getAuthMode() != AuthMode.NO_PASSWORD);
    }

    private void requireAclExists(String aclName, String region) {
        if (!DEFAULT_ACL.equals(aclName) && resourceGet(acls, aclName, region).isEmpty()) {
            throw new AwsException("ACLNotFoundFault", "ACL " + aclName + " not found.", 404);
        }
    }

    private boolean userExists(String name, String region) {
        return DEFAULT_USER.equals(name) || resourceGet(users, name, region).isPresent();
    }

    private User resolveUser(String name, String region) {
        return resourceGet(users, name, region)
                .orElseGet(() -> DEFAULT_USER.equals(name) ? builtinDefaultUser(region) : null);
    }

    private User builtinDefaultUser(String region) {
        User user = new User();
        user.setName(DEFAULT_USER);
        user.setStatus(ACTIVE);
        user.setAuthMode(AuthMode.NO_PASSWORD);
        user.setAccessString("on ~* &* +@all");
        user.setMinimumEngineVersion(DEFAULT_ENGINE_VERSION);
        if (region != null) {
            user.setArn(buildArn(region, "user", DEFAULT_USER));
        }
        return user;
    }

    private Acl builtinOpenAccessAcl(String region) {
        Acl acl = new Acl();
        acl.setName(DEFAULT_ACL);
        acl.setStatus(ACTIVE);
        acl.setUserNames(new ArrayList<>(List.of(DEFAULT_USER)));
        acl.setMinimumEngineVersion(DEFAULT_ENGINE_VERSION);
        if (region != null) {
            acl.setArn(buildArn(region, "acl", DEFAULT_ACL));
        }
        return acl;
    }

    // ──────────────────────────── Internals ────────────────────────────

    private Cluster clusterByArn(String resourceArn) {
        if (resourceArn == null) {
            throw new AwsException("InvalidParameterValueException", "ResourceArn is required.", 400);
        }
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
            if (!"memorydb".equals(arn.service()) || !currentRegion().equals(arn.region())
                    || !regionResolver.getAccountId().equals(arn.accountId())) {
                throw new IllegalArgumentException("ARN owner mismatch");
            }
        } catch (IllegalArgumentException e) {
            throw new AwsException("ClusterNotFoundFault", "Cluster not found.", 404);
        }
        migrateLegacyClusters(currentRegion());
        return clusters.scan(k -> k.startsWith(currentRegion() + ":")).stream()
                .filter(c -> resourceArn.equals(c.getArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ClusterNotFoundFault", "Cluster not found.", 404));
    }

    private void startBackend(Cluster cluster, boolean authRequired) {
        String name = cluster.getName();
        String identity = identityName(cluster.getAccountId(), cluster.getRegion(), name);
        int proxyPort = allocateProxyPort();
        String image = config.services().memorydb().defaultImage();
        LOG.infov("Creating MemoryDB cluster {0} with authRequired={1} on proxy port {2}",
                name, String.valueOf(authRequired), String.valueOf(proxyPort));

        MemoryDbContainerHandle handle = null;
        try {
            // A cluster record is metadata: its name, endpoint host and proxy port are derived
            // from configuration and need no Docker, so the cluster is created and reaches
            // 'available' even when no daemon is reachable. Only connecting to the cache needs
            // the container.
            // MemoryDB always runs in cluster mode: the node announces the proxy endpoint, so
            // cluster clients follow CLUSTER SLOTS/SHARDS back through the proxy.
            String endpointHost = resolveEndpointHost();
            handle = containerManager.tryStart(identity, image, ValkeyClusterFormation.nodeFlags(
                    endpointHost, ValkeyClusterFormation.resolveAnnounceIp(endpointHost), proxyPort));
            cluster.setClusterEndpoint(new Endpoint(endpointHost, proxyPort));
            cluster.setProxyPort(proxyPort);

            if (handle != null) {
                cluster.setContainerId(handle.getContainerId());
                cluster.setContainerHost(handle.getHost());
                cluster.setContainerPort(handle.getPort());

                // A single shard owning all 16384 slots; NumShards/NumReplicasPerShard are not modelled.
                clusterFormation.form(identity, List.of(new ValkeyClusterFormation.Node(
                        handle.getHost(), handle.getPort(), handle.getHost(), 0, true)), 1);

                proxyManager.startProxy(identity, authRequired, proxyPort,
                        handle.getHost(), handle.getPort(),
                        (username, secret) -> authenticate(name, username, secret, cluster.getRegion()));
            } else {
                LOG.warnv("MemoryDB cluster {0} created without a backing container: no Docker "
                        + "daemon is reachable. Metadata operations work; connections to the "
                        + "cluster do not until a daemon appears.", name);
            }
        } catch (RuntimeException e) {
            LOG.warnv("MemoryDB cluster {0} provisioning failed, rolling back: {1}", name, e.getMessage());
            rollbackBackend(identity, handle, proxyPort);
            throw e;
        }
    }

    private void rollbackBackend(String name, MemoryDbContainerHandle handle, int proxyPort) {
        try {
            try {
                // The proxy only starts after the container is ready, so a null handle means it
                // never started — nothing to stop.
                if (handle != null) {
                    proxyManager.stopProxy(name);
                }
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping proxy for MemoryDB cluster {0}: {1}", name, e.getMessage());
            }
            try {
                if (handle != null) {
                    // We have the exact handle from this request's start() call, so stop by it
                    // directly. Falling back to stopByClusterName here instead would look up
                    // whatever is currently registered for name, which could be a different
                    // container if an overlapping create for the same name raced ahead of this
                    // rollback.
                    containerManager.stop(handle);
                } else {
                    // No handle: a readiness timeout in containerManager.start() throws after the
                    // container was created and registered but before the handle is returned, so
                    // cleaning up by handle here isn't possible. stopByClusterName is idempotent,
                    // so it's safe when the container never started.
                    containerManager.stopByClusterName(name);
                }
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping container for MemoryDB cluster {0}: {1}", name, e.getMessage());
            }
        } finally {
            releaseProxyPort(proxyPort);
        }
    }

    private String key(String region, String name) {
        return region + ":" + name;
    }

    private String currentRegion() {
        String region = regionResolver.getRegion();
        if (region != null) {
            return region;
        }
        String defaultRegion = regionResolver.getDefaultRegion();
        return defaultRegion != null ? defaultRegion : "us-east-1"; // partition-literal: reached only when the resolver has no default region (test constructors)
    }

    private String identityName(String accountId, String region, String name) {
        String defaultAccount = regionResolver.getDefaultAccountId();
        String defaultRegion = regionResolver.getDefaultRegion();
        if ((defaultAccount == null || java.util.Objects.equals(defaultAccount, accountId))
                && (defaultRegion == null || java.util.Objects.equals(defaultRegion, region))) {
            return name;
        }
        return accountId + "-" + region + "-" + name;
    }

    private <V> java.util.Optional<V> legacyGet(StorageBackend<String, V> store, String name, String region) {
        String defaultAccount = regionResolver.getDefaultAccountId();
        String defaultRegion = regionResolver.getDefaultRegion();
        if ((defaultAccount != null && !java.util.Objects.equals(defaultAccount, regionResolver.getAccountId()))
                || (defaultRegion != null && !java.util.Objects.equals(defaultRegion, region))) {
            return java.util.Optional.empty();
        }
        return store.get(name);
    }

    private <V> java.util.Optional<V> resourceGet(StorageBackend<String, V> store, String name, String region) {
        Optional<V> result = store.get(key(region, name));
        if (result.isPresent()) {
            return result;
        }
        Optional<V> legacy = legacyGet(store, name, region);
        if (legacy.isPresent()) {
            setOwner(legacy.get(), region);
            store.put(key(region, name), legacy.get());
            store.delete(name);
        }
        return legacy;
    }

    private void migrateLegacyClusters(String region) {
        migrateLegacy(clusters, region, Cluster.class);
    }

    private void migrateLegacyUsers(String region) {
        migrateLegacy(users, region, User.class);
    }

    private void migrateLegacyAcls(String region) {
        migrateLegacy(acls, region, Acl.class);
    }

    private <V> void migrateLegacy(StorageBackend<String, V> store, String region, Class<V> type) {
        if (!isDefaultOwner(region) || !(store instanceof AccountAwareStorageBackend<V> aware)) {
            return;
        }
        String accountId = regionResolver.getAccountId();
        for (V legacy : aware.scanUnscopedLegacy(value -> type.isInstance(value))) {
            String name = resourceName(legacy);
            if (name == null || name.isBlank()) {
                continue;
            }
            aware.getForAccountMigratingLegacyKeys(accountId, name, List.of(), value -> type.isInstance(value))
                    .ifPresent(value -> migrateLegacyValue(aware, accountId, value, region));
        }
        // A named lookup through AccountAwareStorageBackend may have already moved an old
        // unscoped record to account/name without adding the new region component. Move those
        // intermediate keys as well so unfiltered operations cannot omit them.
        for (String legacyKey : aware.keysForAccount(accountId)) {
            if (legacyKey.contains(":")) {
                continue;
            }
            aware.getForAccount(accountId, legacyKey)
                    .filter(type::isInstance)
                    .ifPresent(value -> migrateLegacyValue(aware, accountId, value, region));
        }
    }

    private <V> void migrateLegacyValue(AccountAwareStorageBackend<V> aware, String accountId,
                                         V legacy, String region) {
        String name = resourceName(legacy);
        if (name == null || name.isBlank()) {
            return;
        }
        setOwner(legacy, region);
        aware.putForAccount(accountId, key(region, name), legacy);
        aware.deleteForAccount(accountId, name);
    }

    private String resourceName(Object resource) {
        return switch (resource) {
            case Cluster cluster -> cluster.getName();
            case User user -> user.getName();
            case Acl acl -> acl.getName();
            default -> null;
        };
    }

    private boolean isDefaultOwner(String region) {
        String defaultAccount = regionResolver.getDefaultAccountId();
        String defaultRegion = regionResolver.getDefaultRegion();
        return (defaultAccount == null || java.util.Objects.equals(defaultAccount, regionResolver.getAccountId()))
                && (defaultRegion == null || java.util.Objects.equals(defaultRegion, region));
    }

    private void setOwner(Object resource, String region) {
        String accountId = regionResolver.getAccountId();
        if (resource instanceof Cluster cluster) {
            cluster.setAccountId(accountId);
            cluster.setRegion(region);
        } else if (resource instanceof User user) {
            user.setAccountId(accountId);
            user.setRegion(region);
        } else if (resource instanceof Acl acl) {
            acl.setAccountId(accountId);
            acl.setRegion(region);
        }
    }

    private String buildArn(String region, String resourceType, String name) {
        return regionResolver.buildArn("memorydb", region, resourceType + "/" + name);
    }

    private String resolveEndpointHost() {
        return config.hostname().orElse("localhost");
    }

    private int allocateProxyPort() {
        int base = config.services().memorydb().proxyBasePort();
        int max = config.services().memorydb().proxyMaxPort();
        for (int port = base; port <= max; port++) {
            if (usedPorts.add(port)) {
                return port;
            }
        }
        throw new AwsException("InsufficientClusterCapacityFault",
                "No available proxy ports in range " + base + "-" + max, 503);
    }

    private void releaseProxyPort(int port) {
        usedPorts.remove(port);
    }
}
