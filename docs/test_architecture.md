# Remote Build Server Architecture

This document outlines the architecture of the Remote Build Server (RBS) system, identifying the testing flows and the target production state.

## 1. Process-Based Integration Test (`mirror_test`)

This flow represents the local development loop (`e2e/mirror_test.sh`) where all components run as local processes on the developer's machine. This is used for fast feedback and logic verification without the overhead of containers or Kubernetes.

```mermaid
sequenceDiagram
    autonumber
    participant T as Test Script (mirror_test.sh)
    participant C as Bazel Client
    participant Pr as Proxy (Server Mode)
    participant O as Orchestrator (Local Process)
    participant P as ProcessComputeService
    participant A as Agent (Local Process)

    Note over O: Configured with --local-mode
    
    T->>O: Start Orchestrator (Port 50051)
    
    T->>C: Run `bazel build //...`
    J->>Pr: Spawn (as java)
    activate Pr
    Pr->>O: GetServer(RepoHash, SourcePath)
    O->>P: createContainer(RepoHash, SourcePath)
    P->>A: Spawn `agent` binary (subprocess)
    activate A
    Note right of A: Listens on random ephemeral port
    A-->>P: PID & Port
    deactivate A
    P-->>O: Agent Address (localhost:PORT)
    O-->>Pr: ServerAddress (localhost:PORT)
    
    Pr->>A: Connect (gRPC)
    Pr-->>C: Handshake (Port, Cookie)
    
    C->>Pr: ExecuteCommand(gRPC)
    Pr->>A: Forward Request
    A->>A: Run `bazel build ...`
    A-->>Pr: Stream Output
    Pr-->>J: Stream Output
    deactivate Pr
```

## 2. Kind E2E Test Flow

This flow represents the `//e2e:kind_test` (or `run_kind_e2e.sh`) workflow, which validates the system in a Kubernetes environment (Kind).

```mermaid
sequenceDiagram
    autonumber
    participant T as Test Runner (Bazel)
    participant K as Kind Cluster (K8s API)
    participant O as Orchestrator (Pod)
    participant S as Spanner Emulator (Pod)
    participant K8 as KubernetesComputeService
    participant A as Agent (Pod)
    participant C as Test Client (Bazel)

    T->>K: `kubectl apply` Manifests
    K->>O: Deploy Orchestrator
    K->>S: Deploy Spanner Emulator
    
    T->>C: Run Test Command
    C->>O: GetServer(RepoHash)
    O->>K8: createContainer(RepoHash)
    K8->>K: Create Pod (Agent)
    K-->>A: Schedule Pod
    activate A
    A->>A: Start gRPC Server
    deactivate A
    K8->>K: Watch Pod Status IP
    K-->>K8: Pod IP
    K8-->>O: Agent Address (PodIP:9011)
    O-->>C: ServerAddress
    
    C->>A: Connect (gRPC)
    C->>A: ExecuteCommand
    A-->>C: Stream Output
```

