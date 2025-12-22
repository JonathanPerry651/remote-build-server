package main

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"os/exec"
	"sync"

	pb "github.com/example/remote-build-server/agent/src/main/proto"
	"google.golang.org/grpc"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/reflection"

	"go.opentelemetry.io/otel"
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

	lis, err := net.Listen("tcp", fmt.Sprintf(":%s", port))
	if err != nil {
		slog.Error("failed to listen", "error", err)
		os.Exit(1)
	}
	s := grpc.NewServer()
	pb.RegisterRunnerServer(s, &server{})
	reflection.Register(s)
	slog.Info("Agent listening", "port", port)
	if err := s.Serve(lis); err != nil {
		slog.Error("failed to serve", "error", err)
		os.Exit(1)
	}
}
