#!/bin/bash
set -e

# Setup test environment
WORK_DIR=$(mktemp -d)
PRJ_DIR="${WORK_DIR}/project"
mkdir -p "${PRJ_DIR}"
touch "${PRJ_DIR}/WORKSPACE"

# Find a free port
FAKE_AGENT_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("", 0)); print(s.getsockname()[1]); s.close()')
FAKE_AGENT_ADDR="localhost:${FAKE_AGENT_PORT}"

# Create a small Go program to act as the "Remote Agent/Bazel"
cat > "${WORK_DIR}/fake_upstream.go" <<EOF
package main
import (
    "fmt"
    "net"
    "google.golang.org/grpc"
    "google.golang.org/grpc/reflection"
    "log"
)

func main() {
    lis, err := net.Listen("tcp", ":${FAKE_AGENT_PORT}")
    if err != nil {
        log.Fatalf("failed to listen: %v", err)
    }
    s := grpc.NewServer(grpc.UnknownServiceHandler(func(srv interface{}, stream grpc.ServerStream) error {
        // Just accept stream, read a bit, then exit (simulating shutdown)
        fmt.Println("Received request, shutting down upstream...")
        return nil // Close stream
    }))
    reflection.Register(s)
    fmt.Println("Fake Upstream Listening")
    if err := s.Serve(lis); err != nil {
        log.Fatalf("failed to serve: %v", err)
    }
}
EOF

# Build Fake Upstream
go build -o "${WORK_DIR}/fake_upstream" "${WORK_DIR}/fake_upstream.go"
"${WORK_DIR}/fake_upstream" > "${WORK_DIR}/upstream.log" 2>&1 &
UPSTREAM_PID=$!
echo "Fake Upstream PID: $UPSTREAM_PID"

sleep 2

# 2. Start Proxy in Legacy Mode (Connecting to Fake Upstream)
PROXY_SOCKET="${WORK_DIR}/server.socket"
# Build Proxy
bazel build //proxy
# Note: Path depends on Bazel/rules_go version
if [ -f "bazel-bin/proxy/proxy_/proxy" ]; then
    PROXY_BIN="./bazel-bin/proxy/proxy_/proxy"
else
    PROXY_BIN="./bazel-bin/proxy/proxy"
fi

"${PROXY_BIN}" --listen-path "${PROXY_SOCKET}" --target-addr "${FAKE_AGENT_ADDR}" > "${WORK_DIR}/proxy.log" 2>&1 &
PROXY_PID=$!
echo "Proxy PID: $PROXY_PID"

sleep 2

# 3. Simulate Client sending traffic
cat > "${WORK_DIR}/client.go" <<EOF
package main
import (
    "context"
    "log"
    "time"
    "net"
    "google.golang.org/grpc"
    "google.golang.org/grpc/credentials/insecure"
)

func main() {
    dialer := func(ctx context.Context, addr string) (net.Conn, error) {
        return net.Dial("unix", addr)
    }
    conn, err := grpc.Dial("${PROXY_SOCKET}", grpc.WithTransportCredentials(insecure.NewCredentials()), grpc.WithContextDialer(dialer))
    if err != nil {
        log.Fatalf("did not connect: %v", err)
    }
    defer conn.Close()
    
    ctx, cancel := context.WithTimeout(context.Background(), time.Second)
    defer cancel()
    
    // We use a raw invoke to trigger traffic
    err = conn.Invoke(ctx, "/anything.Service/Method", nil, nil)
    log.Printf("Invoke finished: %v", err)
}
EOF
cd "${WORK_DIR}"
go mod init client
go get google.golang.org/grpc
go get google.golang.org/protobuf
go build -o "${WORK_DIR}/client" "${WORK_DIR}/client.go"

"${WORK_DIR}/client"

sleep 2

echo "--- Upstream Log ---"
cat "${WORK_DIR}/upstream.log" || true
echo "--- Proxy Log ---"
cat "${WORK_DIR}/proxy.log" || true
echo "--- Client Output ---"

if kill -0 "$PROXY_PID" 2>/dev/null; then
    echo "FAILURE: Proxy is still running after upstream closed."
    kill "$PROXY_PID"
    kill "$UPSTREAM_PID"
    exit 1
else
    echo "SUCCESS: Proxy exited."
fi
