### Technical Specification: Global Multi-Repo Bazel Platform (Phase 0: Local Test Harness)

This document serves as the implementation guide for **Antigravity** to build a functional proof-of-concept (POC). This phase focuses on the "Local Test Harness," simulating the complex GKE/Filestore/Spanner environment using local tools to validate the **Go Proxy**, **Java Orchestrator**, and **Bubblewrap Namespace Pivot**.

---

## 1. Phase 0: Architecture & Components

The goal is to run the entire stack on a single developer machine with no external cloud dependencies or Buildbarn requirements.

* **State:** Cloud Spanner Emulator (Local Binary).
* **Cluster:** `kind` (Kubernetes in Docker).
* **Storage:** Local `hostPath` directories simulating Filestore subpaths.
* **Build Logic:** Standard local Bazel build (no remote execution).

---

## 2. Component Implementation Details

### A. Metadata Layer: Spanner Emulator

The emulator will run as a sidecar or standalone process. The Java Orchestrator will connect to it via `localhost:9010`.

**Schema (`schema.sql`):**

```sql
CREATE TABLE BuildSessions (
    UserId STRING(MAX) NOT NULL,
    RepoHash STRING(MAX) NOT NULL, -- MD5 of the local path
    SessionId STRING(MAX),         -- ID to handle desktop bounces
    PodIP STRING(MAX),
    Status STRING(MAX)             -- PENDING, READY
) PRIMARY KEY (UserId, RepoHash);

```

### B. The Java Orchestrator (The "Spawner")

A minimal Spring Boot application using the **Fabric8 Kubernetes Client**.

* **The Request:** Receives a gRPC call: `GetServer(userId, repoHash, sessionId)`.
* **The Transaction:** 1.  Queries Spanner Emulator.
2.  If `SessionId` differs from the record, deletes any existing Pod for that `UserId/RepoHash`.
3.  Creates a new Pod in the `kind` cluster.
* **The Manifest Logic:**
* Uses a `hostPath` volume to mount a local directory (e.g., `~/mock-filestore`) into the Pod.
* Injects the `RepoHash` and `UserId` as environment variables.



### C. The Go "jbazel" Wrapper (The "Proxy")

A Go binary designed to replace the standard `bazel` entry point.

1. **Workspace Discovery:**
* Walks up the directory tree to find `MODULE.bazel`.
* Generates `RepoHash = MD5(AbsolutePathToRepo)`.


2. **Server Readiness:**
* Calls the Java Orchestrator.
* Waits until the Pod status is `Running`.


3. **The "Hop":**
* Establishes a connection to the Pod (via `kubectl port-forward` API or similar).
* Proxies the CLI command to the Bazel server running inside the Pod.



### D. The Pod Entrypoint: Bubblewrap (bwrap)

The Docker image used in the `kind` cluster must have `bubblewrap` installed.

**The Pivot Script (`entrypoint.sh`):**

```bash
#!/bin/bash
# Physically, the pod sees /data/alice/repo-123
# We want Bazel to see /work/src

bwrap \
  --bind / / \
  --bind "${MOCK_FILESTORE_PATH}/${USER_ID}/${REPO_HASH}/src" /work/src \
  --bind "${MOCK_FILESTORE_PATH}/${USER_ID}/${REPO_HASH}/out" /work/out \
  --dev /dev \
  --proc /proc \
  bazel --output_base=/work/out server

```

---

## 3. Test Harness Workflow (The POC Run)

1. **Setup Folders:** * `mkdir -p /tmp/mock-filestore/alice/hash-123/src`
* Add a simple `WORKSPACE` and `cc_binary` to that folder.


2. **Run Services:** Start Spanner Emulator and Java Orchestrator.
3. **Execute:** Run `jbazel build //...` from the `/tmp/.../src` directory.
4. **Verification:**
* Observe the Java Orchestrator logs: "Creating Pod for repo hash-123".
* Observe the `kind` cluster: `kubectl get pods` should show a new bazel-server pod.
* Check `/tmp/mock-filestore/alice/hash-123/out`: Bazel artifacts should appear here.
* **The "Mirror" Test:** Verify you can execute the resulting binary from your *local terminal* using the path in the `/tmp/.../out` directory.



---

## 4. Why This Validates the Full Design

* **Spanner:** Validates the transactional logic for session management.
* **Fabric8/Kind:** Validates the "On-Demand" pod spawning latency and API interaction.
* **Bubblewrap:** Proves we can swap the Bazel server's workspace view without changing Pod mounts.
* **Mirror Mounts:** Proves that `/work/out` in the pod and the local folder on the disk are consistent enough for local execution.

