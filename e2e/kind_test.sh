#!/bin/bash
set -e

# --- Runfiles Helper ---
if [[ -f "${RUNFILES_DIR:-/dev/null}/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
  source "${RUNFILES_DIR}/bazel_tools/tools/bash/runfiles/runfiles.bash"
elif [[ -f "$0.runfiles/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
  source "$0.runfiles/bazel_tools/tools/bash/runfiles/runfiles.bash"
else
  echo >&2 "ERROR: cannot find @bazel_tools//tools/bash/runfiles/runfiles.bash"
  exit 1
fi
# --- End Runfiles Helper ---

# Locate Binaries
# http_file targets:
KIND=$(rlocation kind/file/downloaded)
KUBECTL=$(rlocation kubectl/file/downloaded)
# Client binary:
# Client binary:
CLIENT=$(rlocation remote-build-server/e2e/e2e_client)
if [[ ! -f "$CLIENT" ]]; then
    echo "ERROR: Could not locate e2e_client binary or jar via rlocation"
    echo "Runfiles contents:"
    find . -maxdepth 4
    exit 1
fi

# Ensure executable if it's a script/binary
if [[ "$CLIENT" != *.jar ]]; then
    chmod +x "$CLIENT"
fi

# Artifacts
ORCHESTRATOR_TAR=$(rlocation remote-build-server/orchestrator/tarball.tar)
DEPLOYMENT_YAML=$(rlocation remote-build-server/infra/k8s/orchestrator.yaml)

if [[ ! -f "$KIND" ]]; then echo "Kind not found"; exit 1; fi
if [[ ! -f "$KUBECTL" ]]; then echo "Kubectl not found"; exit 1; fi

chmod +x "$KIND" "$KUBECTL"

CLUSTER_NAME="rbs-e2e"

# Cleanup function
function cleanup {
    echo "Cleaning up..."
    # Dump logs if we are failing (check for failure indicator or always dump?)
    # Easiest is to always dump orchestrator logs if they exist
    echo "--- Orchestrator Logs ---"
    "$KUBECTL" --context "kind-$CLUSTER_NAME" logs -l app=orchestrator --tail=200 || true
    echo "--- All Pods ---"
    "$KUBECTL" --context "kind-$CLUSTER_NAME" get pods --all-namespaces || true
    echo "--- All Namespaces ---"
    "$KUBECTL" --context "kind-$CLUSTER_NAME" get namespaces || true
    
    "$KIND" delete cluster --name "$CLUSTER_NAME" || true
}
trap cleanup EXIT

# Podman Setup
export KIND_EXPERIMENTAL_PROVIDER=podman

if ! command -v podman &> /dev/null; then
  echo "Podman not found in PATH"
  echo "PATH is: $PATH"
  exit 1
fi

# Create Cluster
if ! "$KIND" get clusters | grep -q "$CLUSTER_NAME"; then
  echo "Creating Kind cluster '$CLUSTER_NAME' with Podman..."
  "$KIND" create cluster --name "$CLUSTER_NAME"
fi

# Export Kubeconfig
export KUBECONFIG=$(mktemp)
"$KIND" get kubeconfig --name "$CLUSTER_NAME" > "$KUBECONFIG"
echo "Kubeconfig exported to $KUBECONFIG"

# Load Image
echo "Loading orchestrator image..."
# kind load image-archive requires the tarball
"$KIND" load image-archive "$ORCHESTRATOR_TAR" --name "$CLUSTER_NAME"

# Load Agent Image
AGENT_TAR=$(rlocation remote-build-server/agent/tarball.tar)
echo "Loading agent image..."
"$KIND" load image-archive "$AGENT_TAR" --name "$CLUSTER_NAME"

# Apply Deployment
echo "Applying deployment..."
"$KUBECTL" --context "kind-$CLUSTER_NAME" apply -f "$DEPLOYMENT_YAML"

# Wait for Rollout
echo "Waiting for rollout..."
# Might fail if image pull error, but we loaded it. imagePullPolicy: Never in yaml is critical.
"$KUBECTL" --context "kind-$CLUSTER_NAME" rollout status deployment/orchestrator --timeout=120s

# Port Forward
echo "Setting up port forwarding..."
POD_NAME=$("$KUBECTL" --context "kind-$CLUSTER_NAME" get pods -l app=orchestrator -o jsonpath="{.items[0].metadata.name}")

LOCAL_PORT=$(python3 -c 'import random; print(random.randint(20000, 30000))')
echo "Using local port: $LOCAL_PORT"

"$KUBECTL" --context "kind-$CLUSTER_NAME" port-forward "$POD_NAME" "$LOCAL_PORT":50051 &
PF_PID=$!

trap "kill $PF_PID || true; cleanup" EXIT

sleep 5

# Run Client
echo "Running E2E Client..."
"$CLIENT" "$LOCAL_PORT"
