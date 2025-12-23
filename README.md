# Remote Build Server (RBS)

Current Status: **Phase 2 (Generic gRPC Proxy)**

RBS is a system that allows local Bazel clients to execute builds on remote infrastructure while maintaining a native local experience. It achieves this by replacing the local Bazel Server process with a "Proxy" that tunnels gRPC commands to a remote "Agent" container.

## Documentation

*   **[Architecture](docs/architecture.md)**: High-level system design.
*   **[Production Architecture](docs/production_architecture.md)**: Setup for GKE/Filestore/Spanner.
*   **[Lifecycle Concerns](docs/lifecycle_concerns.md)**: Deep dive into connection, discovery, and storage.

## Components

*   **[Proxy](proxy/)**: Local Go binary running in "Server Mode" (masquerading as the Bazel JVM).
*   **[Orchestrator](orchestrator/)**: Control plane for session management and pod provisioning.
*   **[Agent](agent/)**: Remote execution shim running inside the build container.

## Quick Start

### 1. Build the Tools
```bash
bazel build //proxy //agent //orchestrator:server
```

### 2. Run End-to-End Test (Local Mirror)
```bash
./e2e/mirror_test.sh
```
This script simulates a full "client -> proxy -> orchestrator -> agent -> mock_server" loop on your local machine.

### 3. Run Kubernetes E2E Test
```bash
bazel test //e2e:kind_test
```
Deploys the stack to a local `kind` cluster and verifies pod spawning and execution.
