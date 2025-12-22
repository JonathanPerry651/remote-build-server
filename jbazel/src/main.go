package main

import (
	"context"
	"crypto/md5"
	"fmt"
	"log"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/bazelbuild/bazelisk/core"
	"github.com/bazelbuild/bazelisk/repositories"

	"time"

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

	// Configuration
	proxyBin := os.Getenv("JBAZEL_PROXY_BIN")
	if proxyBin == "" {
		// Fallback or error?
		// For E2E we set it.
		log.Println("Warning: JBAZEL_PROXY_BIN not set, assuming 'proxy' in PATH")
		proxyBin = "proxy"
	}
	bazelBin := os.Getenv("JBAZEL_BAZEL_BIN")
	if bazelBin == "" {
		bazelBin = "bazel"
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
	userId := "user1"
	sessionId := "session-" + fmt.Sprintf("%d", time.Now().Unix())

	// 2. Orchestrator Interaction (Get Pod Address)
	conn, err := grpc.Dial(orchAddr, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		log.Fatalf("did not connect to orchestrator: %v", err)
	}
	defer conn.Close()
	orchClient := orchpb.NewOrchestratorClient(conn)

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	var serverAddr string
	// Polling
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
		time.Sleep(1 * time.Second)
	}

	// 3. Start Local Proxy
	// Prepare Output Base for Proxy Socket
	// We use a custom output base to control the socket location
	// or we use the default bazel one?
	// To reliably intercept, we should probably force an output base or find the existing one.
	// For this implementation, we force a specific output base or intercept standard one?
	// The Architecture says: "The proxy listens on a UNIX domain socket... located at <output_base>/server/server.socket".
	// If we want to use the USER'S bazel, we should respect their output base.
	// But getting it is hard (bazel info output_base runs server).
	// Strategy:
	// Use a JBAZEL specific output base to ensure isolation and interception.
	// e.g. ~/.cache/jbazel/<hash>
	homeDir, _ := os.UserHomeDir()
	jbazelBase := filepath.Join(homeDir, ".cache", "jbazel", repoHash)
	jbazelBase, _ = filepath.Abs(jbazelBase) // Ensure absolute path for URI
	os.MkdirAll(filepath.Join(jbazelBase, "server"), 0755)

	socketPath := filepath.Join(jbazelBase, "server", "server.socket")

	// Start Proxy
	// We need to ensure no existing server or proxy is running on this socket?
	// Or we just kill it?
	// Bazel client respects `server/server.pid.txt`.
	// If we are the server, we should probably write our PID?
	// Or let the Proxy manage it.
	// Use `proxy` binary.
	proxyCmd := exec.Command(proxyBin, "--listen-path", socketPath, "--target-addr", serverAddr)
	proxyCmd.Stdout = os.Stdout
	proxyCmd.Stderr = os.Stderr
	if err := proxyCmd.Start(); err != nil {
		log.Fatalf("Failed to start proxy: %v", err)
	}
	defer func() {
		proxyCmd.Process.Kill()
		proxyCmd.Wait()
	}()

	// Wait for socket to appear
	for i := 0; i < 50; i++ {
		if _, err := os.Stat(socketPath); err == nil {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}

	// 4. Exec Local Bazel Client
	// Point it to our output base
	// We append our output base arg.
	// NOTE: output_base must be the first arg usually? "bazel --output_base=... build ..."
	// We insert it at the beginning.
	bazelArgs := append([]string{"--output_base=" + jbazelBase}, targetArgs...)

	// 4. Exec Local Bazel Client via Bazelisk
	// Convert bazelArgs to what bazelisk expects
	// Bazelisk expects the full args including the command.

	// Create Bazelisk instance
	// We need to set the OutputBase. Bazelisk usually delegates to the underlying Bazel.
	// We are passing --output_base to Bazel via args.

	// 4. Exec Local Bazel Client
	// If JBAZEL_BAZEL_BIN is set, use it (useful for testing/mocking).
	// Otherwise, use Bazelisk to automatically manage/download Bazel.

	if bazelBin != "bazel" {
		// Explicit binary provided (test mode)
		slog.Info("Running explicit bazel binary", "bin", bazelBin, "args", bazelArgs, "proxy_target", serverAddr)
		runBazel := exec.Command(bazelBin, bazelArgs...)
		runBazel.Stdin = os.Stdin
		runBazel.Stdout = os.Stdout
		runBazel.Stderr = os.Stderr

		if err := runBazel.Run(); err != nil {
			if exitError, ok := err.(*exec.ExitError); ok {
				os.Exit(exitError.ExitCode())
			}
			log.Fatalf("Bazel failed: %v", err)
		}
		return
	}

	slog.Info("Running bazel via Bazelisk", "args", bazelArgs, "proxy_target", serverAddr)

	// Since we can't easily capture exit code from bazelisk.Run if it calls os.Exit,
	// checking standard library usage.
	// 'core.Run' usually calls the binary.
	// We will try using core.New().Run(args, repoRoot) concept if available,
	// or look for the correct API.
	// Assuming standard library usage:
	// repositories := repositories.New()
	// b := core.New(repositories)
	// exitCode, err := b.Run(bazelArgs, workspaceRoot)

	// Initializing Repositories like bazelisk main
	gcs := &repositories.GCSRepo{}
	config := core.MakeDefaultConfig()
	gitHub := repositories.CreateGitHubRepo(config.Get("BAZELISK_GITHUB_TOKEN"))
	// Fetch LTS releases & candidates, rolling releases and Bazel-at-commits from GCS, forks from GitHub.
	repos := core.CreateRepositories(gcs, gitHub, gcs, gcs, true)

	exitCode, err := core.RunBazeliskWithArgsFuncAndConfig(func(string) []string { return bazelArgs }, repos, config)
	if err != nil {
		log.Printf("Bazelisk failed: %v", err)
	}
	os.Exit(exitCode)
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
