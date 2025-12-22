# Production Architecture Threat Model

This document provides a security threat analysis for the Remote Build Server (RBS) production architecture. It aims to identify potential security risks and the mitigations implemented to address them.

## 1. Trust Boundaries

The system has the following key trust boundaries:

1.  **Developer Machine / Local Network**: Untrusted. The local Proxy and Bazel Client run here.
2.  **Public Network / Internet**: Untrusted. Communication path between Client and Control Plane.
3.  **Control Plane (GKE Control Plane & Orchestrator)**: Trusted. Manages sessions and authentication.
4.  **Build Plane (Agent Pods)**: Semi-Trusted. Executes user code. Must be isolated from other users and the Control Plane.
5.  **Storage (Cloud Filestore)**: Trusted Infrastructure, but shared capability.

## 2. Threat Analysis (STRIDE)

### Spoofing
*   **Threat**: Malicious actor impersonates a legitimate Proxy to command an Agent.
    *   **Mitigation**: **mTLS / Traffic Director**. Traffic Director and K8s Mesh CA ensure that only pods with valid, issued certificates can communicate. The Proxy authenticates via user credentials/tokens to the Orchestrator, which provisions the connection.
*   **Threat**: Malicious actor impersonates the Orchestrator to unexpected Agents.
    *   **Mitigation**: Workload Identity and Kubernetes implementation of mTLS (e.g., Istio/Traffic Director) ensures Agents only accept connections from authenticated mesh peers.

### Tampering
*   **Threat**: Tampering with source code or artifacts in transit.
    *   **Mitigation**: All data in transit (gRPC) is encrypted via **TLS/mTLS**.
*   **Threat**: User A modifying User B's build artifacts in shared storage.
    *   **Mitigation**: **Namespace Isolation**. Each session runs in its own Namespace with dedicated PVCs. While physical storage might be shared (Filestore), Kubernetes volume mounting logic prevents cross-namespace volume mounting without explicit permission.

### Repudiation
*   **Threat**: A user denies initiating a resource-intensive build.
    *   **Mitigation**: Orchestrator logs all `GetServer` and session provisioning requests with authenticated User IDs.

### Information Disclosure
*   **Threat**: Leaking source code to other users or the public.
    *   **Mitigation**:
        *   **Bubblewrap Sandboxing**: Prevents the build process from accessing files outside the mapped workspace within the container.
        *   **Namespace Isolation**: Prevents unauthorized network or process visibility between concurrent build pods.
        *   **UDS on Client**: Client-Proxy communication uses Unix Domain Sockets enabled with file permissions (0700), preventing other local users on the developer machine from snooping on the build stream.
*   **Threat**: Data Leakage via Shared Storage (Filestore).
    *   **Mitigation**: **Subpath Mounting**. Although the physical Filestore instance is shared, each Agent Pod (and client) mounts *only* a specific, dedicated subpath (e.g., `<filestore>/<user_id>/<repo_hash>`). Kubernetes volume mounting logic ensures that a Pod cannot break out of its mounted subpath to access the root or other users' data.

### Denial of Service
*   **Threat**: A user consumes all cluster resources.
    *   **Mitigation**:
        *   **Resource Quotas**: Each Namespace can have CPU/Memory quotas applied.
        *   **TTL/Reapers**: The Orchestrator monitors session activity and aggressively reclaims idle pods to free resources.

### Elevation of Privilege
*   **Threat**: A malicious build script breaks out of the container to access the Node or Cluster.
    *   **Assumption**: We assume **no container escapes**. The developer's code executes within a containerized Agent Pod, and we rely on the underlying Container Runtime (gVisor/RunC) to enforce this boundary.
    *   **Mitigation**:
        *   **Bubblewrap**: Adds depth (defense in depth), but the primary security guarantee is the container boundary itself.
        *   **Non-Root Execution**: Agents run as non-root users.

## 3. Data Flow Security

1.  **Client -> Proxy**: Local UDS. Secured by filesystem permissions.
2.  **Proxy -> Orchestrator**: Authenticated gRPC (Likely OIDC/mTLS).
3.  **Proxy -> Agent**: Proxyless gRPC (xDS). Secured by Traffic Director managed mTLS. Virtual addressing prevents direct IP scanning.
4.  **Agent -> Filestore**: NFSv3/v4 over VPC Peering. Security relies on VPC implementation and PVC binding.
