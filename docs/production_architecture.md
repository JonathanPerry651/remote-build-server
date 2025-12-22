# Production Architecture (Intended State)

This document outlines the final intended architecture for the Remote Build Server (RBS) running in a production Kubernetes (GKE) environment, integrating Google Cloud Filestore for high-performance shared storage.

## High-Level Architecture

The production environment consists of a Control Plane (Orchestrator + Database) and a Build Plane (Dynamic Agent Pods). Google Cloud Filestore provides a shared, low-latency filesystem accessible by the Agent Pods, enabling rapid access to source code and build artifacts.

```mermaid
graph TD
    subgraph "Developer Machine"
        Client[jBazel CLI]
        Source[Source Code]
    end

    subgraph "Google Cloud Platform"
        subgraph "GKE Cluster"
            Ingress[Load Balancer / Ingress]
            
            subgraph "Control Plane"
                Orch[Orchestrator Service]
            end
            
            subgraph "Build Plane (Nodes)"
                AgentPod1["Agent Pod (User A)"]
                AgentPod2["Agent Pod (User B)"]
            end
        end

        subgraph "Managed Services"
            DB[(Cloud Spanner)]
            Filestore[(Cloud Filestore / NFS)]
        end
    end

    %% Control Flow
    Client -- "gRPC (GetServer)" --> Ingress
    Ingress --> Orch
    Orch -- Read/Write Session State --> DB
    Orch -- K8s API (Spawn) --> AgentPod1
    
    %% Storage Flow
    Filestore -- "NFS Mount (PVC)" --- AgentPod1
    Filestore -- "NFS Mount (PVC)" --- AgentPod2
    
    %% Execution Flow
    Client -- "Spawn Process" --> Proxy["Proxy (Server Mode)"]
    Proxy -- "gRPC (mTLS)" --> AgentPod1
    
    %% Data Sync Idea (Implied)
    Client -.->|Sync Source| Filestore
```

## Storage Integration: Google Cloud Filestore

To ensure hermetic and fast builds, RBS leverages Google Cloud Filestore (Enterprise/High Scale) as the backing storage for build workspaces.

### Workflow
1.  **Provisioning**: When a user connects, the Orchestrator ensures a persistent volume (PVC) backed by Filestore is available for that user or session.
2.  **Shared Mounting**: 
    *   **Server Side**: The `KubernetesComputeService` spawns an Agent Pod with the Filestore volume mounted.
    *   **Client Side**: The developer's machine mounts the same Filestore volume (e.g., via NFS), ensuring instant visibility of source code without explicit synchronization steps.
3.  **Execution**: The `bazel` process running in the Agent Pod performs all reads/writes against the high-performance Filestore mount.
4.  **Persistence**: Build caches (bazel-out) can be preserved across sessions within the Filestore volume, speeding up subsequent builds.

### Benefits
*   **Performance**: Low-latency file operations compared to standard buckets.
*   **Consistency**: Standard POSIX compliance ensures Bazel behaves exactly as it does on a local disk.
*   **Persistence**: Workspaces survive pod restarts or rescheduling.

## Security & Isolation

### Namespace Isolation
To guarantee multi-tenant security and resource isolation, every build session operates within its own dedicated **Kubernetes Namespace**.
*   **Naming Convention**: `<sanitized_user>-rbs-<hash>`
*   **Resources**: 
    *   Each namespace contains the user's build Pod(s).
    *   Dedicated `ServiceAccount` and RBAC bindings restricted to that namespace.
    *   Automatic cleanup: Deleting the namespace removes all associated resources.

### Bubblewrap Sandboxing
To ensure hermetic builds and identical path structures between the client and server:
*   **Path Mapping**: The agent uses **Bubblewrap** (bwrap) to sandbox the execution environment.
*   **Virtual Filesystem**: It constructs a virtual filesystem within the container that exactly mirrors the directory structure of the developer's local machine (e.g., mounting the workspace at the exact same absolute path).
*   **Consistency**: This guarantees that build actions (which may rely on absolute paths) execute identically on the remote server as they would locally.

### mTLS Everywhere
All gRPC communications between components are secured using mutual TLS (mTLS).
*   **Client <-> Proxy**: Secured via local credentials or Unix sockets.
*   **Proxy <-> Orchestrator**: mTLS with per-user certificates.
*   **Proxy <-> Agent**: mTLS to ensure only the authorized proxy can command the build agent.
