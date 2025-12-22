package main

import (
	"context"
	"crypto/md5"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"time"

	pb "github.com/example/remote-build-server/orchestrator/src/main/proto" // This import path will depend on how rules_proto_grpc_go generates it
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Println("Usage: jbazel <command> [args...]")
		os.Exit(1)
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

	log.Printf("Workspace: %s, Hash: %s", workspaceRoot, repoHash)

	// 2. Orchestrator Interaction
	conn, err := grpc.Dial("localhost:50051", grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		log.Fatalf("did not connect: %v", err)
	}
	defer conn.Close()
	c := pb.NewOrchestratorClient(conn)

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	log.Printf("Requesting server for User: %s, Hash: %s", userId, repoHash)
	resp, err := c.GetServer(ctx, &pb.GetServerRequest{
		UserId:    userId,
		RepoHash:  repoHash,
		SessionId: sessionId,
	})
	if err != nil {
		log.Fatalf("could not get server: %v", err)
	}

	log.Printf("Server Status: %s, PodIP: %s", resp.GetStatus(), resp.GetPodIp())

	if resp.GetStatus() != "READY" {
		// Simple polling for Phase 0 (Orchestrator should ideally wait or stream)
		// But our Orchestrator returns PENDING immediately.
		// So we poll.
		for {
			time.Sleep(1 * time.Second)
			resp, err = c.GetServer(ctx, &pb.GetServerRequest{
				UserId:    userId,
				RepoHash:  repoHash,
				SessionId: sessionId,
			})
			if err != nil {
				log.Fatalf("polling failed: %v", err)
			}
			if resp.GetStatus() == "READY" {
				break
			}
			log.Printf("Waiting... Status: %s", resp.GetStatus())
		}
	}

	// 3. Execution Proxy (kubectl exec)
	// Pod name assumption: bazel-<userId>-<repoHash>
	// Note: In a real world, the Orchestrator should return the Pod Name or we strictly define it.
	// Our Orchestrator logic (OrchestratorService.java) uses getPodName(userId, repoHash).
	// Let's assume standard format: "bazel-" + userId + "-" + repoHash
	podName := fmt.Sprintf("bazel-%s-%s", userId, repoHash)

	cmdArgs := []string{"exec", "-it", podName, "--", "bazel"}
	cmdArgs = append(cmdArgs, os.Args[1:]...)

	log.Printf("Executing: kubectl %v", cmdArgs)

	kubectlCmd := exec.Command("kubectl", cmdArgs...)
	kubectlCmd.Stdin = os.Stdin
	kubectlCmd.Stdout = os.Stdout
	kubectlCmd.Stderr = os.Stderr

	if err := kubectlCmd.Run(); err != nil {
		log.Fatalf("Command failed: %v", err)
	}
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
