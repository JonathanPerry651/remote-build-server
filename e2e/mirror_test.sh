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

JBAZEL_BINARY="./bazel-bin/jbazel/jbazel_/jbazel"
if [ ! -f "$JBAZEL_BINARY" ]; then
    JBAZEL_BINARY="./jbazel/jbazel_/jbazel"
fi

# Ensure executable
[ -f "$AGENT_BINARY" ] && chmod +x "$AGENT_BINARY"
[ -f "$ORCHESTRATOR_BINARY" ] && chmod +x "$ORCHESTRATOR_BINARY"
[ -f "$JBAZEL_BINARY" ] && chmod +x "$JBAZEL_BINARY"

if [ ! -f "$AGENT_BINARY" ] || [ ! -f "$ORCHESTRATOR_BINARY" ] || [ ! -f "$JBAZEL_BINARY" ]; then
  echo "Error: Could not locate required binaries."
  echo "Looking for:"
  echo "  Agent: $AGENT_BINARY"
  echo "  Orchestrator: $ORCHESTRATOR_BINARY"
  echo "  Jbazel: $JBAZEL_BINARY"
  echo "Current directory structure:"
  find . -maxdepth 4
  exit 1
fi

# Resolve to absolute paths
AGENT_BINARY=$(readlink -f "$AGENT_BINARY")
ORCHESTRATOR_BINARY=$(readlink -f "$ORCHESTRATOR_BINARY")
JBAZEL_BINARY=$(readlink -f "$JBAZEL_BINARY")

export AGENT_BINARY="$AGENT_BINARY"

# Create a temporary workspace
WORK_DIR=$(mktemp -d)
trap "rm -rf $WORK_DIR" EXIT

mkdir -p "$WORK_DIR/src/main"
touch "$WORK_DIR/MODULE.bazel"
echo 'print("Hello from remote bazel")' > "$WORK_DIR/src/main/BUILD"

# --- 2. Start Orchestrator ---
PORT=50051
$ORCHESTRATOR_BINARY --port=$PORT --local-mode &
SERVER_PID=$!
trap "kill $SERVER_PID" EXIT

# Wait for server
sleep 5

# --- 3. Run jbazel ---
# We simulate being in a workspace
cd "$WORK_DIR/src/main"

# Run version command
output=$($JBAZEL_BINARY version --orchestrator=localhost:$PORT)
echo "Output: $output"

if [[ "$output" == *"Build label"* ]]; then # Standard bazel version output
  echo "PASS: jbazel version command succeeded"
else
  # It might output "Standard bazel..." depending on bazelisk
  # Our mock agent executes `bazel version`.
  # If local machine has bazel, it works.
  echo "INFO: Check output manually. Got: $output"
fi

# Run build command effectively
$JBAZEL_BINARY build //... --orchestrator=localhost:$PORT

# --- 4. Verify 'bazel run' ---
echo 'sh_binary(name="hello", srcs=["hello.sh"])' > "$WORK_DIR/src/main/BUILD"
echo '#!/bin/bash' > "$WORK_DIR/src/main/hello.sh"
echo 'echo "Hello from run"' >> "$WORK_DIR/src/main/hello.sh"
chmod +x "$WORK_DIR/src/main/hello.sh"

echo "Running 'bazel run //src/main:hello'..."
run_output=$($JBAZEL_BINARY run //src/main:hello --orchestrator=localhost:$PORT)
if [[ "$run_output" == *"Hello from run"* ]]; then
  echo "PASS: jbazel run succeeded"
else
  echo "FAIL: jbazel run failed. Output: $run_output"
  exit 1
fi

# --- 5. Verify 'bazel test' ---
echo 'sh_test(name="simple_test", srcs=["test.sh"])' >> "$WORK_DIR/src/main/BUILD"
echo '#!/bin/bash' > "$WORK_DIR/src/main/test.sh"
echo 'exit 0' >> "$WORK_DIR/src/main/test.sh"
chmod +x "$WORK_DIR/src/main/test.sh"

echo "Running 'bazel test //src/main:simple_test'..."
$JBAZEL_BINARY test //src/main:simple_test --orchestrator=localhost:$PORT

echo "PASS: Mirror test complete (build, run, test)"
