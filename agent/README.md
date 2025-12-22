# Agent

The `agent` is a lightweight Go binary that runs inside the remote build container (the "Remote Build Execution" environment). It acts as the bridge between the external world (via the Proxy) and the local services running within the container.

## Role & Responsibilities

1.  **Tunnel Endpoint**:
    -   Listens for incoming gRPC connections from the client-side `proxy`.
    -   Serves as the generic L7 forwarding destination for the tunnelled traffic.

2.  **Traffic Forwarding**:
    -   Receives gRPC requests wrapped or forwarded by the proxy.
    -   Forwards these requests to the appropriate local Unix Domain Socket (UDS) or TCP port within the container.
    -   Primarily targets the **Bazel Server** socket to allow remote control of the build.

3.  **Observability & Health**:
    -   (Planned) detailed telemetry and health checks to report container status back to the Orchestrator or monitoring systems.

## Architecture

The Agent implements a generic gRPC proxying mechanism. It does not parse the Bazel protocol deeply; instead, it blindly forwards the gRPC frames to the destination, ensuring low latency and compatibility with various Bazel versions.

## Key Interactions

-   **Inbound**: Encrypted/Tunnelled gRPC traffic from `jbazel`'s proxy.
-   **Outbound**: Local gRPC traffic to the Bazel Server (or other local daemons).
