# MemoryDB

**Protocol:** JSON 1.1 (`X-Amz-Target: AmazonMemoryDB.*`) for management API + Redis RESP protocol for data plane
**Management Endpoint:** `POST http://localhost:4566/`
**Data Endpoint:** `localhost:<proxy-port>` (TCP)

Floci manages real Valkey/Redis Docker containers and proxies TCP connections to them, so any Redis client works — including IAM-style authentication. MemoryDB is Redis wire-compatible, so it reuses Floci's ElastiCache RESP proxy and SigV4 validator.

## Cluster Mode

Like real MemoryDB, every cluster runs in cluster mode (`cluster-enabled yes`), so cluster clients — ioredis `Redis.Cluster`, redis-py `RedisCluster`, Lettuce, `valkey-cli -c` — connect the same way they do against AWS:

- Each cluster is **one shard with one node** that owns all 16384 hash slots; `CLUSTER INFO` reports `cluster_state:ok`. `NumShards` and `NumReplicasPerShard` are accepted and reported but not modelled as extra nodes.
- The node announces the `ClusterEndpoint` from `DescribeClusters` (host = `FLOCI_HOSTNAME`, default `localhost`; port = the proxy port) as its preferred endpoint, so `CLUSTER SLOTS`, `CLUSTER SHARDS`, `CLUSTER NODES` and `MOVED` redirects send clients back through the auth proxy.
- Cluster-mode announce settings need a Valkey 8+ image (the default `valkey/valkey:8`); the flags are passed through the image's `VALKEY_EXTRA_FLAGS` hook.
- TLS is not terminated: `TLSEnabled` is reported, but clients connect over plain TCP.

## Persistence and Restarts

With a persistent storage mode (`FLOCI_STORAGE_MODE=persistent`, `hybrid` or `wal`), clusters, users and ACLs survive a Floci restart, and so does each cluster's endpoint. On startup Floci brings every persisted cluster's data plane back:

- The cluster keeps its `ClusterEndpoint` port. Its proxy port is reserved before Floci accepts requests, so a new `CreateCluster` cannot take it. The host is re-read from `FLOCI_HOSTNAME`.
- The cluster reports `creating` while its container and auth proxy come back in the background, then `available`. Poll `DescribeClusters` (or use the SDK waiter) before connecting.
- A Valkey container still running under the cluster's name is adopted, so its keys survive. Otherwise a new, empty container is started: Floci persists the cluster record, never the keyspace. A surviving container that announces a different endpoint (for example after `FLOCI_HOSTNAME` changed) is replaced by an empty one.
- The single shard is formed again when its slots are not assigned, so `CLUSTER INFO` reports `cluster_state:ok` either way.
- A cluster whose container or proxy cannot be brought back reports `restore-failed` with no endpoint, and the reason is logged. Floci's own startup does not fail. Its port is released, and the next start retries the cluster on the first free port, so its endpoint may change.

By default Floci removes MemoryDB containers when it shuts down cleanly, so after a normal restart the clusters come back empty. Set `FLOCI_SERVICES_MEMORYDB_KEEP_RUNNING_ON_SHUTDOWN=true` to leave them running for the next start to adopt with their data. Only use it with a persistent storage mode: with in-memory storage nothing restores the clusters, and their containers are left behind until a cluster with the same name is created again or they are removed by hand.

## Mock Mode

Set `FLOCI_SERVICES_MEMORYDB_MOCK=true` to manage clusters as control-plane-only resources — no Redis container is started. The management API (`CreateCluster`, `DescribeClusters`, tagging, etc.) behaves normally and returns a `ClusterEndpoint` of `<hostname>:6379` (defaults to `localhost:6379`, controlled by `FLOCI_HOSTNAME`), but there is no live data plane to connect to. This is intended for Infrastructure-as-Code tools like Terraform and OpenTofu running in environments without a Docker socket, where you only need the AWS resources to exist and return consistent attributes.

## Supported Management Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateCluster` | Start a new MemoryDB (Redis/Valkey) cluster |
| `DescribeClusters` | List clusters and their connection info |
| `UpdateCluster` | Update mutable cluster attributes (e.g. description) |
| `DeleteCluster` | Stop and remove a cluster |
| `CreateUser` | - |
| `DescribeUsers` | - |
| `DeleteUser` | - |
| `CreateACL` | - |
| `DescribeACLs` | - |
| `DeleteACL` | - |
| `ListTags` | List tags for a cluster |
| `TagResource` | Add tags to a cluster |
| `UntagResource` | Remove tags from a cluster |
<!-- floci:actions:end -->

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_MEMORYDB_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_MEMORYDB_MOCK` | `false` | Track clusters without starting a real Redis container. Useful for IaC tools (Terraform/OpenTofu) when no Docker socket is available |
| `FLOCI_SERVICES_MEMORYDB_PROXY_BASE_PORT` | `6400` | First host port in the MemoryDB proxy range |
| `FLOCI_SERVICES_MEMORYDB_PROXY_MAX_PORT` | `6419` | Last host port in the MemoryDB proxy range |
| `FLOCI_SERVICES_MEMORYDB_DEFAULT_IMAGE` | `valkey/valkey:8` | Docker image for Redis/Valkey containers |
| `FLOCI_SERVICES_MEMORYDB_KEEP_RUNNING_ON_SHUTDOWN` | `false` | Leave cluster containers running when Floci shuts down, so the next start adopts them with their data (see [Persistence and Restarts](#persistence-and-restarts)) |

### Docker Compose

MemoryDB requires the Docker socket and port range exposure. For private registry authentication and other Docker settings see [Docker Configuration](../configuration/docker.md).

```yaml
services:
  floci:
    image: floci/floci:latest
    ports:
      - "4566:4566"
      - "6400-6419:6400-6419"   # MemoryDB proxy ports
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_SERVICES_DOCKER_NETWORK: my-project_default
```

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a cluster (starts a Valkey container)
aws memorydb create-cluster \
  --cluster-name my-memdb \
  --node-type db.t4g.small \
  --acl-name open-access \
  --endpoint-url $AWS_ENDPOINT_URL

# Get the connection address and port
aws memorydb describe-clusters \
  --cluster-name my-memdb \
  --query 'Clusters[0].ClusterEndpoint' \
  --endpoint-url $AWS_ENDPOINT_URL

# Connect with redis-cli (use the Port from ClusterEndpoint); -c follows cluster redirects
redis-cli -c -h localhost -p 6400 ping
redis-cli -c -h localhost -p 6400 cluster slots

# Delete the cluster
aws memorydb delete-cluster \
  --cluster-name my-memdb \
  --endpoint-url $AWS_ENDPOINT_URL
```
