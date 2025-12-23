# Orchestrator

The `orchestrator` is the central control plane for the Remote Build Server system. Written in Java, it manages the lifecycle of remote build environments and coordinates connections between users and their compute resources.

## Role & Responsibilities

1.  **Session Management**:
    -   Maintains a mapping of `(User ID, Repository Hash)` to `(Container ID, Status, Address)`.
    -   Uses a persistent store (e.g., Cloud Spanner) or in-memory store (for testing) to track active sessions.

2.  **Compute Provisioning**:
    -   Interacts with the infrastructure provider (e.g., Kubernetes via `ComputeService`) to spawn, check, and terminate build containers/pods.
    -   Ensures that a user always gets routed to their specific, persistent build environment.
    -   Handles race conditions to ensure only one active container per session.

3.  **Discovery API**:
    -   Exposes a gRPC service (`Orchestrator`) consumed by the Proxy.
    -   **`GetServer` RPC**:
        -   If a session exists and is READY: Returns the Agent's address.
        -   If a session exists and is PENDING: Returns the status, telling the client to wait.
        -   If no session exists: Triggers the creation of a new container and returns PENDING.

## Key Interactions

-   **Clients**: Proxy instances (running as `server_javabase`) requesting build servers.
-   **Infrastructure**: Kubernetes API / Docker (via `ComputeService`) to manage pods.
-   **Database**: Spanner (via `SessionRepository`) for state persistence.
