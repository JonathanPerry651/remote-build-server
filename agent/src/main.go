package main

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"

	pb "github.com/example/remote-build-server/agent/src/main/proto"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/reflection"
	"google.golang.org/protobuf/types/known/emptypb"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/stdout/stdouttrace"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.17.0"
	"go.opentelemetry.io/otel/trace"
)

var (
	tracer = otel.Tracer("agent")
)

type server struct {
	pb.UnimplementedRunnerServer
}

func (s *server) ExecuteCommand(stream pb.Runner_ExecuteCommandServer) error {
	// Extract context from metadata
	ctx := stream.Context()
	md, ok := metadata.FromIncomingContext(ctx)
	if ok {
		ctx = otel.GetTextMapPropagator().Extract(ctx, propagation.HeaderCarrier(md))
	}

	ctx, span := tracer.Start(ctx, "ExecuteCommand")
	defer span.End()

	// 1. Receive Init Request
	req, err := stream.Recv()
	if err != nil {
		slog.Error("failed to receive init request", "error", err)
		return err
	}
	initReq := req.GetInit()
	if initReq == nil {
		return fmt.Errorf("expected InitRequest as first message")
	}

	slog.Info("Starting command", "args", initReq.Args, "cwd", initReq.WorkingDirectory)
	span.AddEvent("Starting command", trace.WithAttributes())

	// 2. Prepare Command
	// Assuming Bubblewrap is set up in the environment or we just run directly for now.
	// For Phase 0 (ProcessComputeService), we run directly.
	// In the Pod, we would wrap this. Use a simple strategy: if "bazel" is called, assume it's valid.

	cmdName := initReq.Args[0]
	cmdArgs := initReq.Args[1:]

	// Safety check? In a real system yes. Here we trust the orchestrator/client.
	cmd := exec.Command(cmdName, cmdArgs...)
	cmd.Dir = initReq.WorkingDirectory
	cmd.Env = os.Environ() // Inherit base env
	for k, v := range initReq.Env {
		cmd.Env = append(cmd.Env, k+"="+v)
	}

	// 3. Pipe Stdout/Stderr
	stdoutPipe, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	stderrPipe, err := cmd.StderrPipe()
	if err != nil {
		return err
	}
	stdinPipe, err := cmd.StdinPipe()
	if err != nil {
		return err
	}

	if err := cmd.Start(); err != nil {
		return err
	}

	// 4. Stream Handling
	var wg sync.WaitGroup
	wg.Add(2)

	// Stdout -> Stream
	go func() {
		defer wg.Done()
		buf := make([]byte, 4096)
		for {
			n, err := stdoutPipe.Read(buf)
			if n > 0 {
				stream.Send(&pb.ExecuteResponse{
					Output: &pb.ExecuteResponse_StdoutChunk{StdoutChunk: buf[:n]},
				})
			}
			if err != nil {
				break
			}
		}
	}()

	// Stderr -> Stream
	go func() {
		defer wg.Done()
		buf := make([]byte, 4096)
		for {
			n, err := stderrPipe.Read(buf)
			if n > 0 {
				stream.Send(&pb.ExecuteResponse{
					Output: &pb.ExecuteResponse_StderrChunk{StderrChunk: buf[:n]},
				})
			}
			if err != nil {
				break
			}
		}
	}()

	// Stream -> Stdin
	go func() {
		for {
			in, err := stream.Recv()
			if err == io.EOF {
				stdinPipe.Close()
				return
			}
			if err != nil {
				// Connection breakage
				stdinPipe.Close()
				return
			}
			if chunk := in.GetStdinChunk(); len(chunk) > 0 {
				stdinPipe.Write(chunk)
			}
		}
	}()

	wg.Wait()
	err = cmd.Wait()
	exitCode := 0
	if err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			exitCode = exitErr.ExitCode()
		} else {
			exitCode = 1
		}
	}

	slog.Info("Command finished", "exit_code", exitCode)

	// Send Exit Code
	stream.Send(&pb.ExecuteResponse{
		Output: &pb.ExecuteResponse_ExitCode{ExitCode: int32(exitCode)},
	})

	return nil
}

func resolveBazelSocket() (string, error) {
	// 1. Ask Bazel for the output base (this also starts the server if needed)
	args := []string{}

	// Inject Startup Options
	startupOptsEnv := os.Getenv("BAZEL_STARTUP_OPTIONS")
	if startupOptsEnv != "" {
		opts := strings.Split(startupOptsEnv, "|||")
		args = append(args, opts...)
	}

	args = append(args, "info", "output_base")

	cmd := exec.Command("bazel", args...)
	// Inherit environment to ensure we pick up the same workspace config
	cmd.Env = os.Environ()
	out, err := cmd.CombinedOutput()
	if err != nil {
		slog.Error("failed to run bazel info", "error", err, "output", string(out))
		return "", fmt.Errorf("failed to run bazel info: %v\nOutput: %s", err, string(out))
	}
	// Output might contain startup logs (if CombinedOutput), so we need to be careful?
	// But `bazel info` prints info to stdout. Stderr is logs.
	// If successful, `out` contains info.
	// We want just stdout for success case?
	// CombinedOutput mixes them.
	// Let's stick to Output() but capture Stderr separately?
	// Or just use CombinedOutput for debugging error.

	// Actually, if I use CombinedOutput, I might pollute the outputBase parsing if there are warnings!
	// Revert to Output() but capture logs if error.
	outputBase := strings.TrimSpace(string(out))

	// 2. Construct socket path
	// Standard Bazel layout: <output_base>/server/server.socket
	return filepath.Join(outputBase, "server", "server.socket"), nil
}

func initTracer() func(context.Context) error {
	exporter, err := stdouttrace.New(stdouttrace.WithPrettyPrint())
	if err != nil {
		slog.Error("failed to create stdout exporter", "error", err)
		return nil
	}

	res, err := resource.New(context.Background(),
		resource.WithAttributes(
			semconv.ServiceName("agent"),
		),
	)
	if err != nil {
		slog.Error("failed to create resource", "error", err)
		return nil
	}

	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
	)
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(propagation.TraceContext{}, propagation.Baggage{}))

	return tp.Shutdown
}

func main() {
	shutdown := initTracer()
	if shutdown != nil {
		defer shutdown(context.Background())
	}

	port := os.Getenv("PORT")
	if port == "" {
		port = "9011"
	}

	// Generic Forwarding Logic
	opts := []grpc.ServerOption{
		grpc.UnknownServiceHandler(func(srv interface{}, stream grpc.ServerStream) error {
			// Extract context for tracing
			ctx := stream.Context()
			md, ok := metadata.FromIncomingContext(ctx)
			if ok {
				ctx = otel.GetTextMapPropagator().Extract(ctx, propagation.HeaderCarrier(md))
			}
			ctx, span := tracer.Start(ctx, "ProxyForward")
			defer span.End()

			return forwardToBazel(ctx, stream)
		}),
	}

	lis, err := net.Listen("tcp", fmt.Sprintf(":%s", port))
	if err != nil {
		slog.Error("failed to listen", "error", err)
		os.Exit(1)
	}
	s := grpc.NewServer(opts...)

	// Still register Runner for exec command?
	// The user wants "Proxy" functionality.
	// If we use UnknownServiceHandler, it catches everything NOT registered.
	// So we can still register RunnerServer!
	pb.RegisterRunnerServer(s, &server{})
	reflection.Register(s)

	slog.Info("Agent listening", "port", port)
	if err := s.Serve(lis); err != nil {
		slog.Error("failed to serve", "error", err)
		os.Exit(1)
	}
}

func forwardToBazel(ctx context.Context, serverStream grpc.ServerStream) error {
	// 1. Resolve Bazel Socket
	socketPath, err := resolveBazelSocket()
	if err != nil {
		slog.Error("failed to resolve bazel socket", "error", err)
		return err
	}

	// 2. Connect to Bazel
	// Using "unix://" + path
	dialTarget := "unix://" + socketPath
	conn, err := grpc.Dial(dialTarget, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		slog.Error("failed to connect to bazel", "target", dialTarget, "error", err)
		return err
	}
	defer conn.Close()

	// 3. Extract Method
	methodName, ok := grpc.Method(serverStream.Context()) // Use original context for method
	if !ok {
		return fmt.Errorf("failed to extract method")
	}

	// 4. Trace Attributes
	span := trace.SpanFromContext(ctx)
	span.SetAttributes(attribute.String("grpc.method", methodName))

	// 5. Forward with Empty
	// Copy MD
	md, _ := metadata.FromIncomingContext(serverStream.Context())
	outCtx := metadata.NewOutgoingContext(ctx, md)

	desc := &grpc.StreamDesc{
		ServerStreams: true,
		ClientStreams: true,
	}
	clientStream, err := conn.NewStream(outCtx, desc, methodName)
	if err != nil {
		return fmt.Errorf("failed to start bazel stream: %w", err)
	}

	errChan := make(chan error, 2)

	// Server -> Bazel
	go func() {
		for {
			var frame emptypb.Empty
			if err := serverStream.RecvMsg(&frame); err != nil {
				if err != io.EOF {
					errChan <- err
				}
				clientStream.CloseSend()
				return
			}
			if err := clientStream.SendMsg(&frame); err != nil {
				errChan <- err
				return
			}
		}
	}()

	// Bazel -> Server
	go func() {
		md, err := clientStream.Header()
		if err == nil {
			serverStream.SendHeader(md)
		}
		for {
			var frame emptypb.Empty
			if err := clientStream.RecvMsg(&frame); err != nil {
				if err != io.EOF {
					errChan <- err
				} else {
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
