# jbazel

`jbazel` is the client-side C++ / Go hybrid CLI tool that serves as the entry point for the Remote Build Server system. It is designed to be a drop-in replacement for the standard `bazel` command (or wrapped by it).

## Role & Responsibilities

1.  **Session Management**:
    -   Calculates a unique hash for the current repository workspace.
    -   Contacts the **Orchestrator** (via gRPC) to ensure a remote build session (container) exists for the current user and repository.
    -   Retrieves the connection details (IP/Port) for the remote **Agent**.

2.  **Proxy Orchestration**:
    -   Automatically spawns a local `proxy` subprocess.
    -   Configures the proxy to establish a secure gRPC tunnel to the remote **Agent**.
    -   Manages the lifecycle of the proxy, ensuring it runs only while the build is active.

3.  **Bazel Execution**:
    -   Downloads or locates the correct `bazel_client` binary.
    -   Executes `bazel_client` with modified flags to route all Build Event Protocol (BEP) and Remote execution traffic through the local proxy socket.
    -   This transparently offloads the build to the remote environment without the user needing to manually configure connections.

## Key Interactions

-   **Input**: User command line arguments (e.g., `jbazel build //...`).
-   **Output**: Standard Bazel output stream.
-   **Dependencies**:
    -   **Orchestrator**: For session discovery.
    -   **Proxy**: For data tunneling.
    -   **Bazel Client**: The underlying build tool.
