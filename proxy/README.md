# RBS Proxy

The `proxy` is a Go binary that acts as the "Client Side" of the Remote Build Server system. It is designed to be invoked by the Bazel Client as a replacement for the Java-based Bazel Server.

## Server Mode

The Proxy runs in "Server Mode" when invoked with standard Bazel Server flags:
```bash
proxy --output_base=... --workspace_directory=...
```

In this mode, it:
1.  **Handshakes**: Writes the necessary `server.socket` and `request_cookie` files to the output base, satisfying the Bazel Client's connection protocol.
2.  **Connects**: Authenticates with the **Orchestrator** to find or create a remote build session for the given workspace.
3.  **Tunnels**: Proxies the gRPC `CommandServer` stream from the local Bazel Client to the remote **Agent**.

## Configuration

The Proxy auto-configuration logic (Region, etc.) is handled internally. It connects to the Orchestrator via gRPC (defaults to `localhost:50051` or `RBS_ORCHESTRATOR_ADDR`).

## Usage

Configure your `.bazelrc` to use the Proxy as the server:

```bash
build --server_javabase=//tools/rbs_javabase:rbs_javabase
```

(The `rbs_javabase` target packages this `proxy` binary as `bin/java`).
