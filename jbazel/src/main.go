package main

import (
	"context"
	"crypto/md5"
	"fmt"
	"io"
	"log"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"time"

	runnerpb "github.com/example/remote-build-server/agent/src/main/proto"
	orchpb "github.com/example/remote-build-server/orchestrator/src/main/proto"
	"github.com/spf13/cobra"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/stdout/stdouttrace"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.17.0"
)

var (
	orchestratorAddr string
	tracer           = otel.Tracer("jbazel")
)

func initTracer() func(context.Context) error {
	exporter, err := stdouttrace.New(stdouttrace.WithPrettyPrint())
	if err != nil {
		slog.Error("failed to create stdout exporter", "error", err)
		return nil
	}

	res, err := resource.New(context.Background(),
		resource.WithAttributes(
			semconv.ServiceName("jbazel"),
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

	rootCmd := &cobra.Command{
		Use:   "jbazel",
		Short: "Remote Bazel Wrapper",
		// We want to capture all args for "calling bazel" if not a known subcommand
		// But Bazelisk usually just passes everything.
		// Strategy: If arg[1] is a jbazel specific flag or subcommand, handle it.
		// Otherwise, treat keys as bazel command.
		// Cobra's DisableFlagParsing allows us to get raw args.
		DisableFlagParsing: true,
		Run:                runProxy,
	}

	// We have to parse flags manually if DisableFlagParsing is true,
	// OR we define flags but assume users put them before the command?
	// "jbazel --orchestrator=... build //..."
	// Since we want to support "bazel build ...", we should probably parse our flags
	// from ENV or a specific config, or look for specific flags manually.
	// For this POC, let's assume we look for --orchestrator in the args manually or env.

	if err := rootCmd.Execute(); err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}
}

func runProxy(cmd *cobra.Command, args []string) {
	// 0. Manual Flag Parsing (Mock)
	// Pull out --orchestrator if present, otherwise default or Env
	targetArgs := []string{}
	orchAddr := "localhost:50051"
	if v := os.Getenv("ORCHESTRATOR_ADDR"); v != "" {
		orchAddr = v
	}

	for _, arg := range args {
		if strings.HasPrefix(arg, "--orchestrator=") {
			orchAddr = strings.TrimPrefix(arg, "--orchestrator=")
		} else if arg == "version" && len(args) == 1 {
			// Let's implement a local version check or proxy it?
			// Technical spec says "Mirror Test: Run jbazel version".
			// Bazel version usually prints client info (local bazel) and server info.
			// We should probably just proxy it.
			targetArgs = append(targetArgs, arg)
		} else {
			targetArgs = append(targetArgs, arg)
		}
	}

	if len(targetArgs) == 0 {
		cmd.Help()
		return
	}

	// 1. Workspace Discovery & Hashing
	cwd, err := os.Getwd()
	if err != nil {
		log.Fatalf("Failed to get CWD: %v", err)
	}
	workspaceRoot, err := findWorkspaceRoot(cwd)
	if err != nil {
		log.Fatalf("Could not find workspace root: %v", err)
	}
	repoHash := fmt.Sprintf("%x", md5.Sum([]byte(workspaceRoot)))
	userId := "user1" // Fixed for Phase 0
	sessionId := "session-" + fmt.Sprintf("%d", time.Now().Unix())

	// log.Printf("Workspace: %s, Hash: %s", workspaceRoot, repoHash) // Silent unless debug?

	// 2. Orchestrator Interaction (Get Pod Address)
	conn, err := grpc.Dial(orchAddr, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		log.Fatalf("did not connect to orchestrator: %v", err)
	}
	defer conn.Close()
	orchClient := orchpb.NewOrchestratorClient(conn)

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second) // Long timeout for provisioning
	defer cancel()

	// Polling Loop for Server Readiness
	var serverAddr string
	for {
		resp, err := orchClient.GetServer(ctx, &orchpb.GetServerRequest{
			UserId:     userId,
			RepoHash:   repoHash,
			SessionId:  sessionId,
			SourcePath: workspaceRoot,
		})
		if err != nil {
			log.Fatalf("could not get server: %v", err)
		}

		if resp.GetStatus() == "READY" {
			serverAddr = resp.GetServerAddress()
			break
		}
		// log.Printf("Waiting for server... Status: %s", resp.GetStatus())
		time.Sleep(1 * time.Second)
	}

	// 3. Connect to Agent
	// log.Printf("Connecting to Agent at %s", serverAddr)
	agentConn, err := grpc.Dial(serverAddr, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		log.Fatalf("Failed to dial agent: %v", err)
	}
	defer agentConn.Close()

	runnerClient := runnerpb.NewRunnerClient(agentConn)

	// 4. Execute Command Stream
	stream, err := runnerClient.ExecuteCommand(ctx)
	if err != nil {
		log.Fatalf("Failed to start command stream: %v", err)
	}

	// Send Init
	wdRel, _ := filepath.Rel(workspaceRoot, cwd) // Run in relative dir inside workspace
	// If we can'trel, just use "."
	if wdRel == "" || strings.HasPrefix(wdRel, "..") {
		wdRel = "."
	}

	// The agent expects working_dir. Current Agent impl sets Cmd.Dir = initReq.WorkingDirectory.
	// Spec says: "Physically... /data/alice/repo... Bazel sees /work/src".
	// "bwrap --bind ... /work/src".
	// So if I am in root, I am in /work/src.
	// If I am in subdir foo, I should be in /work/src/foo.
	// But for the agent (running in the pod, OUTSIDE bwrap initially?), it spawns the command.
	// Phase 0 Agent spawns "exec.Command" directly.
	// So Directory should be the host path if running `sleep` or `bazel` locally.
	// AND `bazel` works in the workspace.
	// If Agent runs `bazel`, it needs to be in the workspace dir.
	// Where is the workspace in the Agent's env?
	// Using ProcessComputeService: We spawn agent locally.
	// We should probably pass the workspace root to the Agent, OR implicitly assume it runs in the workspace?
	// The Agent is spawned by ProcessComputeService. It probably runs in the CWD of the test or /tmp.
	// CRITICAL: For Mirror Test (ProcessComputeService), the Agent needs to run WHERE THE SOURCE IS.
	// `jbazel` knows only local path.
	// `agent` is spawned by `Orchestrator` which is local.
	// The `Orchestrator` knows `repoHash` but not the path?
	// Wait, Spec says: "Workspace Discovery... Generates Hash... Orchestrator... Creates Pod... Mounts HostPath".
	// In `kind_test`, we mounted host path.
	// In `ProcessComputeService` test, we spawn `agent`.
	// The `agent` needs access to the source code.
	// `jbazel` is finding existing source.
	// The `agent` should be started such that it can access mapped source.
	// BUT `ProcessComputeService` spawns `agent` without knowing the source path?
	// Ah, `ProcessComputeService.createContainer` receives `userId` and `repoHash`.
	// It doesn't know the physical path on the client machine unless we tell it.
	// BUT `mirror_test.sh` runs everything locally.
	// Orchestrator needs to know where the repo is to tell Agent? Or Agent needs to be told?
	// `jbazel` sends command.
	// If `agent` just executes `bazel`, `bazel` needs to be in a workspace.
	// For `local-mode` test, `agent` could use `initReq.WorkingDirectory`?
	// `jbazel` sends absolute path?
	// If `jbazel` sends absolute path `/tmp/work/src`, and `agent` is local, it works!
	// So let's send Absolute Path in InitRequest for `local-mode` compatibility.
	// For real Pods, `jbazel` path `/Users/jonathan...` won't match Pod path `/work/src`.
	// So `jbazel` should really send RELATIVE path from workspace root, and Agent should prepend its mount point.
	// BUT for Phase 0 Local Process test, Absolute works if Agent is local.
	// Let's send BOTH: `working_directory` (process specific) and `workspace_relative_path`.
	// `runner.proto`: `working_directory`.
	// I will send valid absolute path for this test.

	err = stream.Send(&runnerpb.ExecuteRequest{
		Input: &runnerpb.ExecuteRequest_Init{
			Init: &runnerpb.InitRequest{
				Args: append([]string{"bazel"}, targetArgs...), // Hardcode bazel binary call?
				Env: map[string]string{
					"USER": "test", // Basic env
				},
				WorkingDirectory: cwd, // Pass local CWD. Works for Local Process Agent.
			},
		},
	})
	if err != nil {
		log.Fatalf("Failed to send init: %v", err)
	}

	// Pipe IO
	waitc := make(chan struct{})

	// Receiver
	go func() {
		for {
			in, err := stream.Recv()
			if err == io.EOF {
				close(waitc)
				return
			}
			if err != nil {
				log.Printf("Stream recv error: %v", err)
				close(waitc)
				return
			}

			if chunk := in.GetStdoutChunk(); len(chunk) > 0 {
				os.Stdout.Write(chunk)
			}
			if chunk := in.GetStderrChunk(); len(chunk) > 0 {
				os.Stderr.Write(chunk)
			}
			if exitCode := in.GetExitCode(); in.Output != nil { // Check oneof type?
				// Oneof logic: GetExitCode might return 0 if not set?
				// Proto generated code usually has getters that return default.
				// We should check the wrapper type. But `GetExitCode` is safe if we trust the loop breaks on EOF.
				// Actually the server sends ExitCode then closes?
				// Or sends ExitCode and we exit?
				// Assume we get ExitCode message, then continue?
				// We need to capture it.
				// Best effort: if we see ExitCode struct, save it and exit loop?
				// Stream usually closes after exit code.
				// Let's rely on receiving it.
				_ = exitCode
				if _, ok := in.Output.(*runnerpb.ExecuteResponse_ExitCode); ok {
					os.Exit(int(exitCode))
				}
			}
		}
	}()

	// Sender (Stdin)
	go func() {
		buf := make([]byte, 4096)
		for {
			n, err := os.Stdin.Read(buf)
			if err == io.EOF {
				stream.CloseSend()
				return
			}
			if n > 0 {
				stream.Send(&runnerpb.ExecuteRequest{
					Input: &runnerpb.ExecuteRequest_StdinChunk{StdinChunk: buf[:n]},
				})
			}
		}
	}()

	<-waitc

	// Handle signals?
	// Forwarding signals is good practice.
}

func findWorkspaceRoot(startDir string) (string, error) {
	dir := startDir
	for {
		if _, err := os.Stat(filepath.Join(dir, "MODULE.bazel")); err == nil {
			return dir, nil
		}
		if _, err := os.Stat(filepath.Join(dir, "WORKSPACE")); err == nil {
			return dir, nil
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return "", fmt.Errorf("root reached without finding WORKSPACE/MODULE.bazel")
		}
		dir = parent
	}
}
