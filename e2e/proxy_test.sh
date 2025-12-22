#!/bin/bash
set -e

# Artifacts
AGENT_BIN="$1"
PROXY_BIN="$2"
VERIFIER_BIN="$3"

# Setup Paths
TEST_TMP=$(mktemp -d)
WORKSPACE_DIR="$TEST_TMP/workspace"
OUTPUT_BASE="$TEST_TMP/output_base"
MOCK_BAZEL_BIN="$TEST_TMP/bin/bazel"
SERVER_SOCKET_DIR="$OUTPUT_BASE/server"
SERVER_SOCKET="$SERVER_SOCKET_DIR/server.socket"
PROXY_SOCKET="$TEST_TMP/proxy.socket"

mkdir -p "$WORKSPACE_DIR"
mkdir -p "$SERVER_SOCKET_DIR"
mkdir -p "$(dirname "$MOCK_BAZEL_BIN")"

# 1. Create Mock Bazel CLI Logic
# When agent runs `bazel info output_base`, it should return our OUTPUT_BASE
cat > "$MOCK_BAZEL_BIN" <<EOF
#!/bin/bash
if [ "\$1" == "info" ] && [ "\$2" == "output_base" ]; then
    echo "$OUTPUT_BASE"
else
    echo "Unknown command"
    exit 1
fi
EOF
chmod +x "$MOCK_BAZEL_BIN"
export PATH="$(dirname "$MOCK_BAZEL_BIN"):$PATH"

# 2. Start (Mock) Real Bazel Server (gRPC) using Verifier
# This listens on unix socket and echoes gRPC calls
"$VERIFIER_BIN" --mode server --addr "$SERVER_SOCKET" &
SERVER_PID=$!
echo "Started Mock Bazel gRPC Server (PID $SERVER_PID) at $SERVER_SOCKET"
sleep 2

# 3. Start Agent
export PORT=9012
"$AGENT_BIN" &
AGENT_PID=$!
echo "Started Agent (PID $AGENT_PID) on port 9012"
sleep 2

# 4. Start Proxy
"$PROXY_BIN" --listen-path "$PROXY_SOCKET" --target-addr "localhost:9012" &
PROXY_PID=$!
echo "Started Proxy (PID $PROXY_PID) on $PROXY_SOCKET"
sleep 2

# 5. Test Client using Verifier
# Connects to Proxy UDS, sends generic RPC (TestService/Echo) and expects Echo
"$VERIFIER_BIN" --mode client --target "unix://$PROXY_SOCKET"
CLIENT_EXIT=$?

if [ $CLIENT_EXIT -eq 0 ]; then
    echo "SUCCESS: Proxy Tunnel works!"
else
    echo "FAILURE: Client failed"
    exit 1
fi

# Cleanup
kill $PROXY_PID $AGENT_PID $SERVER_PID
rm -rf "$TEST_TMP"
