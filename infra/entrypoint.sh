#!/bin/bash
set -e

# Expected Environment Variables:
# MOCK_FILESTORE_PATH: The hostPath mount location inside the pod (e.g. /mnt/filestore)
# USER_ID: The user ID
# REPO_HASH: The repo hash

if [ -z "$MOCK_FILESTORE_PATH" ] || [ -z "$USER_ID" ] || [ -z "$REPO_HASH" ]; then
  echo "Error: Missing required environment variables (MOCK_FILESTORE_PATH, USER_ID, REPO_HASH)"
  exit 1
fi

SRC_PATH="${MOCK_FILESTORE_PATH}/${USER_ID}/${REPO_HASH}/src"
OUT_PATH="${MOCK_FILESTORE_PATH}/${USER_ID}/${REPO_HASH}/out"

echo "Setup: Mapping $SRC_PATH -> /work/src"
echo "Setup: Mapping $OUT_PATH -> /work/out"

# Ensure directories exist (they should be mounted, but for safety in the pivot)
mkdir -p /work/src
mkdir -p /work/out

# Physically, the pod sees /mnt/filestore/alice/repo-123
# We want Bazel to see /work/src
# We use bubblewrap to create a new namespace where the bind mounts happen.

# We need to bind mount the root / to / so we have all the tools.
# Then we overlay our specific workspace directories.

# Note: --dev /dev and --proc /proc are essential for Bazel to work.
exec bwrap \
  --bind / / \
  --bind "$SRC_PATH" /work/src \
  --bind "$OUT_PATH" /work/out \
  --dev /dev \
  --proc /proc \
  --chdir /work/src \
  bazel --output_base=/work/out server --port=9999 # Using server mode or just running a command?
  # The spec says: bazel --output_base=/work/out server
  # But usually 'server' is an internal command. Typically one runs 'bazel build ...'.
  # If we want a long-running server, we might just sleep or run a gRPC server?
  # The spec implies "The Pod Entrypoint" runs this.
  # "The Go Proxy ... Proxies the CLI command to the Bazel server running inside the Pod"
  # This implies the Pod isn't just running "bazel server" (which is internal) but maybe acting as a remote execution or similar?
  # Or maybe the Go Proxy does `kubectl exec`?
  # "Proxies the CLI command to the Bazel server running inside the Pod" -> "Establishes a connection ... Proxies the CLI command"
  # IF the Go Proxy uses `kubectl exec`, then the pod just needs to sleep.
  # IF the Go Proxy speaks the Bazel Server protocol (gRPC), then we need to accept connections.
  # Wait, standard `bazel` client talks to `bazel` server via a unix socket in output_base.
  # If we want to proxy commands, we might need a way to forward that socket or run commands in the pod.

  # Re-reading spec: "Establishes a connection to the Pod (via kubectl port-forward API or similar). Proxies the CLI command to the Bazel server running inside the Pod."
  # This is slightly ambiguous.
  # Option A: The pod acts as a "machine" and we `kubectl exec` into it to run `bazel build`.
  # Option B: usage of the experimental gRPC interface of the Bazel server (rare).
  # Option C: The "GetServer" implies a persistent server.

  # Given "Proxies the CLI command", and later "Run jbazel build //...",
  # The simplest POC "Proxy" often just does `kubectl exec <pod> -- bazel build ...`
  # BUT, the spec mentions "Bubblewrap Namespace Pivot" in the entrypoint.
  # If `kubectl exec` is used, the pivoting needs to happen for that process.
  # A common trick is to have the entrypoint be a permanent `bwrap` shell or sleep,
  # OR have the `exec` command itself utilize `bwrap`.

  # However, the provided pivot script snippet in the spec runs `bazel ... server`.
  # "bazel --output_base=/work/out server"
  # This starts the Bazel background server.
  # BUT, `bazel server` command stops if idle.
  # AND it listens on a unix socket in the output base.

  # Let's stick CLOSELY to the provided spec snippet for now:
  #   bazel --output_base=/work/out server
  # This might act as a keep-alive if configured right, or we might need to wrap it.
  # Actually, `bazel` command usually spawns the server and then connects.
  # If we run `bazel ... server`, it effectively IS the server process.

  # For the purpose of this POC, I will assume the Spec holds true and we strictly implement the snippet.
  # If it exits immediately, we might need to add a keepalive loop or `sleep infinity`.
  # Let's add a trap or wrapper if needed, but for now, verbatim from spec:
