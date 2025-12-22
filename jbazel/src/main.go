package main

import (
	"context"
	"fmt"
	"log"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/bazelbuild/bazelisk/core"
	"github.com/bazelbuild/bazelisk/repositories"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/stdout/stdouttrace"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.17.0"

	"github.com/spf13/cobra"
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

	if err := rootCmd.Execute(); err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}
}

func runProxy(cmd *cobra.Command, args []string) {
	// 0. Manual Flag Parsing (Mock)
	targetArgs := []string{}
	orchAddr := "localhost:50051"
	if v := os.Getenv("ORCHESTRATOR_ADDR"); v != "" {
		orchAddr = v
	}

	for _, arg := range args {
		if strings.HasPrefix(arg, "--orchestrator=") {
			orchAddr = strings.TrimPrefix(arg, "--orchestrator=")
		} else {
			targetArgs = append(targetArgs, arg)
		}
	}

	if len(targetArgs) == 0 {
		cmd.Help()
		return
	}

	// 2. Setup Server Javabase Environment

	// RBS_PROXY_BIN: Resolve absolute path to 'proxy' binary
	proxyBin := os.Getenv("JBAZEL_PROXY_BIN")
	if proxyBin == "" {
		proxyBin = "proxy"
	}
	proxyBinAbs, err := exec.LookPath(proxyBin)
	if err != nil {
		// Not in PATH, check if it's a relative/absolute path
		if filepath.IsAbs(proxyBin) {
			proxyBinAbs = proxyBin
		} else {
			proxyBinAbs, _ = filepath.Abs(proxyBin) // Best effort
		}
	} else {
		proxyBinAbs, _ = filepath.Abs(proxyBinAbs)
	}
	os.Setenv("RBS_PROXY_BIN", proxyBinAbs)
	os.Setenv("ORCHESTRATOR_ADDR", orchAddr)
	os.Setenv("RBS_USER_ID", "user1") // TODO: Configurable

	// 3. Inject --server_javabase flag
	// Prepend to ensure it's a startup option (before command)

	bazelArgs := append([]string{"--server_javabase=//tools/rbs_javabase:rbs_javabase"}, targetArgs...)

	// 4. Run Bazel
	bazelBin := os.Getenv("JBAZEL_BAZEL_BIN") // For testing
	if bazelBin != "" && bazelBin != "bazel" {
		// Test Mode
		slog.Info("Running explicit bazel binary", "bin", bazelBin, "args", bazelArgs)
		cmd := exec.Command(bazelBin, bazelArgs...)
		cmd.Stdin = os.Stdin
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		if err := cmd.Run(); err != nil {
			if exitErr, ok := err.(*exec.ExitError); ok {
				os.Exit(exitErr.ExitCode())
			}
			log.Fatalf("Bazel failed: %v", err)
		}
		return
	}

	slog.Info("Running bazel via Bazelisk", "args", bazelArgs)

	// Bazelisk Setup
	gcs := &repositories.GCSRepo{}
	config := core.MakeDefaultConfig()
	gitHub := repositories.CreateGitHubRepo(config.Get("BAZELISK_GITHUB_TOKEN"))
	repos := core.CreateRepositories(gcs, gitHub, gcs, gcs, true)

	exitCode, err := core.RunBazeliskWithArgsFuncAndConfig(func(string) []string { return bazelArgs }, repos, config)
	if err != nil {
		log.Printf("Bazelisk failed: %v", err)
	}
	os.Exit(exitCode)
}
