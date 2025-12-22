#!/bin/bash
set -e

# mirror_test.sh
# Verifies jbazel -> Orchestrator -> Agent flow using local processes.

# --- 1. Setup Environment ---
# --- begin runfiles.bash initialization v3 ---
# set -uo pipefail; f=bazel_tools/tools/bash/runfiles/runfiles.bash
# source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
#   source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
#   source "$0.runfiles/$f" 2>/dev/null || \
#   source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
#   source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
#   { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v3 ---

# Fallback to direct paths observed in sandbox or bazel-bin
AGENT_BINARY="./bazel-bin/agent/agent_/agent"
if [ ! -f "$AGENT_BINARY" ]; then
    AGENT_BINARY="./agent/agent_/agent"
fi

ORCHESTRATOR_BINARY="./bazel-bin/orchestrator/server"
if [ ! -f "$ORCHESTRATOR_BINARY" ]; then
    ORCHESTRATOR_BINARY="./orchestrator/server"
fi

JBAZEL_BINARY="./bazel-bin/jbazel/src/jbazel_/jbazel"
if [ ! -f "$JBAZEL_BINARY" ]; then
    JBAZEL_BINARY="./jbazel/src/jbazel_/jbazel"
fi

PROXY_BINARY="./bazel-bin/proxy/proxy_/proxy"
if [ ! -f "$PROXY_BINARY" ]; then
    PROXY_BINARY="./proxy/proxy_/proxy"
fi

VERIFIER_BINARY="./bazel-bin/e2e/test_tools/verifier_/verifier"
if [ ! -f "$VERIFIER_BINARY" ]; then
    VERIFIER_BINARY="./e2e/test_tools/verifier_/verifier"
fi

# Ensure executable
[ -f "$AGENT_BINARY" ] && chmod +x "$AGENT_BINARY"
[ -f "$ORCHESTRATOR_BINARY" ] && chmod +x "$ORCHESTRATOR_BINARY"
[ -f "$JBAZEL_BINARY" ] && chmod +x "$JBAZEL_BINARY"
[ -f "$PROXY_BINARY" ] && chmod +x "$PROXY_BINARY"
[ -f "$VERIFIER_BINARY" ] && chmod +x "$VERIFIER_BINARY"

if [ ! -f "$AGENT_BINARY" ] || [ ! -f "$ORCHESTRATOR_BINARY" ] || [ ! -f "$JBAZEL_BINARY" ] || [ ! -f "$PROXY_BINARY" ] || [ ! -f "$VERIFIER_BINARY" ]; then
  echo "Error: Could not locate required binaries."
  exit 1
fi

# Resolve to absolute paths
AGENT_BINARY=$(readlink -f "$AGENT_BINARY")
ORCHESTRATOR_BINARY=$(readlink -f "$ORCHESTRATOR_BINARY")
JBAZEL_BINARY=$(readlink -f "$JBAZEL_BINARY")
PROXY_BINARY=$(readlink -f "$PROXY_BINARY")
VERIFIER_BINARY=$(readlink -f "$VERIFIER_BINARY")

export AGENT_BINARY="$AGENT_BINARY"
# Jbazel needs to know where Proxy is
export JBAZEL_PROXY_BIN="$PROXY_BINARY"
export ORCHESTRATOR_ADDR="localhost:50051"

# Create a temporary workspace
WORK_DIR=$(mktemp -d)
trap "rm -rf $WORK_DIR" EXIT

mkdir -p "$WORK_DIR/src/main"
touch "$WORK_DIR/MODULE.bazel"
echo 'print("Hello from remote bazel")' > "$WORK_DIR/src/main/BUILD"

# MOCK LOCAL BAZEL CLIENT
# This replaces real "bazel". It receives --output_base, finds the socket, and tests connection.
# Connection = Send a gRPC request e.g. /TestService/Echo (Verifier)
MOCK_BAZEL_CLIENT="$WORK_DIR/bazel_client"
cat > "$MOCK_BAZEL_CLIENT" <<EOF
#!/bin/bash
# Mock Bazel Client
# Expected usage: bazel --output_base=... command ...
OUTPUT_BASE=""
for i in "\$@"; do
    if [[ \$i == --output_base=* ]]; then
        OUTPUT_BASE="\${i#*=}"
    fi
done

if [ -z "\$OUTPUT_BASE" ]; then
    echo "MockClient: No output base provided"
    exit 1
fi

SOCKET="\$OUTPUT_BASE/server/server.socket"

# Wait for socket availability?
# jbazel waits for proxy.
# So socket should be there.

if [ ! -S "\$SOCKET" ]; then
    echo "MockClient: Socket not found at \$SOCKET"
    exit 1
fi

echo "MockClient: Connecting to \$SOCKET"
# Use Verifier Client logic
"$VERIFIER_BINARY" --mode client --target "unix://\$SOCKET"
exit \$?
EOF
chmod +x "$MOCK_BAZEL_CLIENT"
export JBAZEL_BAZEL_BIN="$MOCK_BAZEL_CLIENT"

# Mock Remote Bazel Server (Using Agent?)
# Mirror Test runs: Jbazel -> Proxy -> Agent -> Remote Server.
# In "Local Mode", Orchestrator spawns an Agent.
# The Agent registers with Orchestrator.
# But for "Mirror Test" we want to test the full loop?
# Wait, Orchestrator spawns Agent. Agent listens.
# Where does Agent forward to?
# Agent tries to find "bazel info output_base" locally where Agent runs.
# In Mirror Test, Agent runs locally.
# So Agent will try to run "bazel info..." on the test machine.
# We need to trick Agent to find OUR Mock Server.
# Agent calls `resolveBazelSocket`. `bazel info output_base`.
# We need to MOCK `bazel` for the AGENT too!
# But AGENT doesn't accept a flag for `bazel` bin.
# It uses "bazel" from PATH.
# We can prepend to PATH given to Agent?
# ProcessComputeService spawns Agent.
# Does it pass PATH?
# In local Process test, it inherits PATH?
# Yes.
# So if we put a `bazel` shim in PATH, Agent picks it up.

MOCK_BAZEL_SERVER_BIN="$WORK_DIR/bin/bazel"
mkdir -p "$(dirname "$MOCK_BAZEL_SERVER_BIN")"
cat > "$MOCK_BAZEL_SERVER_BIN" <<EOF
#!/bin/bash
if [ "\$1" == "info" ] && [ "\$2" == "output_base" ]; then
    echo "$WORK_DIR/remote_output_base"
else
    echo "Unknown command \$@"
    exit 1
fi
EOF
chmod +x "$MOCK_BAZEL_SERVER_BIN"
export PATH="$(dirname "$MOCK_BAZEL_SERVER_BIN"):$PATH"

# Start Mock Remote Bazel Server (Verifier Server)
REMOTE_SOCKET_DIR="$WORK_DIR/remote_output_base/server"
mkdir -p "$REMOTE_SOCKET_DIR"
REMOTE_SOCKET="$REMOTE_SOCKET_DIR/server.socket"
"$VERIFIER_BINARY" --mode server --addr "$REMOTE_SOCKET" &
REMOTE_SERVER_PID=$!
trap "kill $REMOTE_SERVER_PID; rm -rf $WORK_DIR" EXIT

# --- 2. Start Orchestrator ---
PORT=50051
# The orchestrator will spawn an AGENT (locally).
# That Agent will inherit our PATH (with mock bazel).
# That Agent will resolve socket to REMOTE_SOCKET.
# That Agent will listen on random port and register with Orchestrator.
$ORCHESTRATOR_BINARY --port=$PORT --local-mode &
SERVER_PID=$!
trap "kill $SERVER_PID $REMOTE_SERVER_PID; rm -rf $WORK_DIR" EXIT

# Wait for orchestrator
sleep 5

# --- 3. Run jbazel ---
# We simulate being in a workspace
cd "$WORK_DIR/src/main"

# Run jbazel
# jbazel finds Orchestrator.
# Gets Agent Addr.
# Starts Proxy -> Agent.
# Runs Mock Local Bazel -> Proxy.
# Proxy -> Agent -> Remote Mock Bazel.
# Remote Mock Bazel echoes.
# Mock Local Bazel (Verifier) checks Echo.

output_file="$WORK_DIR/jbazel.log"
$JBAZEL_BINARY test //... --orchestrator=localhost:$PORT > "$output_file" 2>&1 || true
jbazel_exit=$?

echo "Jbazel Output:"
cat "$output_file"

if [ $jbazel_exit -eq 0 ]; then
  if grep -q "SUCCESS: Echo received" "$output_file"; then
    echo "PASS: Full Proxy Loop Verified"
  else
    echo "FAIL: Exit 0 but 'SUCCESS' not found"
    exit 1
  fi
else
  echo "FAIL: Jbazel exited with $jbazel_exit"
  exit 1
fi
