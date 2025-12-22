package main

import (
	"context"
	"crypto/md5"
	"crypto/rand"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	orchpb "github.com/example/remote-build-server/orchestrator/src/main/proto"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

// ProxyCodec to handle raw bytes
type proxyCodec struct{}

func (proxyCodec) Marshal(v interface{}) ([]byte, error) {
	out, ok := v.(*[]byte)
	if !ok {
		return nil, fmt.Errorf("unexpected type %T", v)
	}
	return *out, nil
}

func (proxyCodec) Unmarshal(data []byte, v interface{}) error {
	dst, ok := v.(*[]byte)
	if !ok {
		return fmt.Errorf("unexpected type %T", v)
	}
	*dst = data
	return nil
}

func (proxyCodec) Name() string {
	return "proxy"
}

func main() {
	// Custom Argument Parsing to support being invoked as "java"
	// We scan for --output_base and --workspace_directory to auto-detect Server Mode.
	// This replaces the need for a wrapper script.

	var outputBase string
	var workspaceDir string
	var serverMode bool

	startupArgs := os.Args[1:] // All arguments passed to "java"

	for _, arg := range startupArgs {
		if len(arg) > 13 && arg[:14] == "--output_base=" {
			outputBase = arg[14:]
			serverMode = true
		} else if len(arg) > 22 && arg[:22] == "--workspace_directory=" {
			workspaceDir = arg[22:]
		}
	}

	// Also support explicit flags for non-server mode (e.g. debugging or L7 proxy)
	// But if we look like a server, we run as a server.
	if serverMode {
		runServerMode(outputBase, workspaceDir, startupArgs)
		return
	}

	// Legacy/Stand-alone Proxy Flags
	var listenPath string
	var targetAddr string

	// We need to re-parse flags if not in server mode
	fs := flag.NewFlagSet("proxy", flag.ExitOnError)
	fs.StringVar(&listenPath, "listen-path", "", "Path to the Unix Domain Socket to listen on")
	fs.StringVar(&targetAddr, "target-addr", "", "Address of the remote agent (host:port)")
	fs.Parse(os.Args[1:])

	if listenPath == "" || targetAddr == "" {
		fmt.Fprintf(os.Stderr, "Usage: proxy --listen-path <socket_path> --target-addr <host:port> OR (as java) --output_base=... --workspace_directory=...\n")
		os.Exit(1)
	}

	runProxy(listenPath, targetAddr)
}

func runServerMode(outputBase, workspaceDir string, startupArgs []string) {
	if outputBase == "" || workspaceDir == "" {
		slog.Error("--output-base and --workspace-dir are required in server-mode")
		os.Exit(1)
	}

	// 1. Resolve Remote Server via Orchestrator
	orchestratorAddr := os.Getenv("ORCHESTRATOR_ADDR")
	if orchestratorAddr == "" {
		orchestratorAddr = "localhost:50051"
	}

	// Calculate RepoHash
	repoHash := fmt.Sprintf("%x", md5.Sum([]byte(workspaceDir)))
	userId := os.Getenv("RBS_USER_ID")
	if userId == "" {
		userId = "user1" // Default
	}
	sessionId := "session-" + fmt.Sprintf("%d", time.Now().Unix())

	slog.Info("Proxy Server Mode starting", "user", userId, "repo", repoHash, "args", startupArgs)

	conn, err := grpc.Dial(orchestratorAddr, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		slog.Error("did not connect to orchestrator", "error", err)
		os.Exit(1)
	}
	defer conn.Close()
	orchClient := orchpb.NewOrchestratorClient(conn)

	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second) // Long timeout for pod spinup
	defer cancel()

	var targetAddr string
	// Polling
	for {
		req := &orchpb.GetServerRequest{
			UserId:         userId,
			RepoHash:       repoHash,
			SessionId:      sessionId,
			SourcePath:     workspaceDir,
			StartupOptions: startupArgs,
			Region:         detectRegion(),
		}
		resp, err := orchClient.GetServer(ctx, req)
		if err != nil {
			slog.Info("Waiting for server...", "error", err)
			time.Sleep(2 * time.Second)
			continue
		}

		if resp.GetStatus() == "READY" {
			targetAddr = resp.GetServerAddress()
			slog.Info("Remote Server READY", "addr", targetAddr)
			break
		}
		time.Sleep(1 * time.Second)
	}

	// 2. Setup Local Listener
	// In Server Mode, we use Unix Domain Socket to emulate Bazel Server.
	serverDir := filepath.Join(outputBase, "server")
	if err := os.MkdirAll(serverDir, 0755); err != nil {
		slog.Error("failed to create server dir", "error", err)
		os.Exit(1)
	}

	socketPath := filepath.Join(serverDir, "server.socket")
	// Clean up old socket
	if err := os.Remove(socketPath); err != nil && !os.IsNotExist(err) {
		slog.Error("failed to remove existing socket", "error", err)
		os.Exit(1)
	}

	listener, err := net.Listen("unix", socketPath)
	if err != nil {
		slog.Error("failed to listen", "error", err)
		os.Exit(1)
	}
	slog.Info("Listening on UDS", "path", socketPath)

	// 3. Write Server Files
	// We no longer write command_port. Bazel Client should find server.socket.

	// Create secure random cookie
	cookie := make([]byte, 16)
	if _, err := rand.Read(cookie); err != nil {
		slog.Error("failed to generate cookie", "error", err)
		os.Exit(1)
	}
	hexCookie := fmt.Sprintf("%x", cookie)
	if err := os.WriteFile(filepath.Join(serverDir, "request_cookie"), []byte(hexCookie), 0644); err != nil {
		slog.Error("failed to write request_cookie", "error", err)
		os.Exit(1)
	}

	if err := os.WriteFile(filepath.Join(serverDir, "server.pid.txt"), []byte(fmt.Sprintf("%d", os.Getpid())), 0644); err != nil {
		slog.Error("failed to write pid file", "error", err)
		os.Exit(1)
	}

	// 4. Start Proxying
	// Forward to Remote Agent
	proxyHandler := func(srv interface{}, stream grpc.ServerStream) error {
		// Dial Backend (per request or pooled)
		// Assuming connection reuse is better, but allow simplistic dialing for now.
		// NOTE: NewServer creates a new goroutine per request.

		targetConn, err := grpc.Dial(targetAddr,
			grpc.WithTransportCredentials(insecure.NewCredentials()),
			grpc.WithDefaultCallOptions(grpc.ForceCodec(proxyCodec{})),
		)
		if err != nil {
			return status.Errorf(codes.Unavailable, "failed to dial backend: %v", err)
		}
		defer targetConn.Close()

		return forwardStream(stream, targetConn, sessionId)
	}

	grpcServer := grpc.NewServer(
		grpc.UnknownServiceHandler(proxyHandler),
		grpc.ForceServerCodec(proxyCodec{}),
	)

	slog.Info("Starting gRPC Proxy Server...")
	if err := grpcServer.Serve(listener); err != nil {
		slog.Error("failed to serve", "error", err)
		os.Exit(1)
	}
}

func runProxy(listenPath, targetAddr string) {
	// Clean up old socket
	if err := os.Remove(listenPath); err != nil && !os.IsNotExist(err) {
		slog.Error("failed to remove existing socket", "path", listenPath, "error", err)
		os.Exit(1)
	}

	// Listen UDS
	lis, err := net.Listen("unix", listenPath)
	if err != nil {
		slog.Error("failed to listen", "path", listenPath, "error", err)
		os.Exit(1)
	}
	defer lis.Close()

	// Handle cleanup
	c := make(chan os.Signal, 1)
	signal.Notify(c, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-c
		os.Remove(listenPath)
		os.Exit(0)
	}()

	slog.Info("Proxy listening", "path", listenPath, "target", targetAddr)

	proxyHandler := func(srv interface{}, stream grpc.ServerStream) error {
		conn, err := grpc.Dial(targetAddr,
			grpc.WithTransportCredentials(insecure.NewCredentials()),
			grpc.WithDefaultCallOptions(grpc.ForceCodec(proxyCodec{})),
		)
		if err != nil {
			slog.Error("failed to connect to agent", "target", targetAddr, "error", err)
			return err
		}
		defer conn.Close()
		return forwardStream(stream, conn, "") // No session ID for legacy mode?
	}

	s := grpc.NewServer(
		grpc.UnknownServiceHandler(proxyHandler),
		grpc.ForceServerCodec(proxyCodec{}),
	)

	if err := s.Serve(lis); err != nil {
		slog.Error("failed to serve", "error", err)
		os.Exit(1)
	}
}

func forwardStream(serverStream grpc.ServerStream, clientConn *grpc.ClientConn, sessionId string) error {
	ctx := serverStream.Context()

	// 1. Extract Method Name
	methodName, ok := grpc.Method(ctx)
	if !ok {
		return fmt.Errorf("failed to extract method name from context")
	}

	// 2. Copy Metadata (Incoming -> Outgoing)
	md, _ := metadata.FromIncomingContext(ctx)
	outCtx := metadata.NewOutgoingContext(ctx, md)
	if sessionId != "" {
		outCtx = metadata.AppendToOutgoingContext(outCtx, "x-rbs-session-id", sessionId)
	}

	// 3. Initiate Client Stream to Agent
	desc := &grpc.StreamDesc{
		ServerStreams: true,
		ClientStreams: true,
	}

	clientStream, err := clientConn.NewStream(outCtx, desc, methodName)
	if err != nil {
		return fmt.Errorf("failed to create client stream: %w", err)
	}

	// slog.Info("Forwarding call", "method", methodName)

	errChan := make(chan error, 2)

	// Server -> Client (Request)
	go func() {
		for {
			var frame []byte
			// RecvMsg expects pointer to []byte because of proxyCodec
			if err := serverStream.RecvMsg(&frame); err != nil {
				if err != io.EOF {
					errChan <- err
				}
				clientStream.CloseSend() // Close upstream
				return
			}

			if err := clientStream.SendMsg(&frame); err != nil {
				errChan <- err
				return
			}
		}
	}()

	// Client -> Server (Response)
	go func() {
		// Header handling
		md, err := clientStream.Header()
		if err == nil {
			serverStream.SendHeader(md)
		}

		for {
			var frame []byte
			if err := clientStream.RecvMsg(&frame); err != nil {
				if err != io.EOF {
					errChan <- err
				} else {
					// Finish stream trailer
					serverStream.SetTrailer(clientStream.Trailer())
					errChan <- nil
				}
				return
			}

			if err := serverStream.SendMsg(&frame); err != nil {
				errChan <- err
				return
			}
		}
	}()

	return <-errChan
}
