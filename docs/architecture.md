# Remote Build Server Architecture

This document outlines the architecture of the Remote Build Server (RBS) system, identifying the testing flows and the target production state.

## 1. Process-Based Integration Test (`mirror_test`)

This flow represents the local development loop (`e2e/mirror_test.sh`) where all components run as local processes on the developer's machine. This is used for fast feedback and logic verification without the overhead of containers or Kubernetes.

```mermaid
sequenceDiagram
    autonumber
    participant T as Test Script (mirror_test.sh)
    participant J as jBazel (Client)
    participant O as Orchestrator (Local Process)
    participant P as ProcessComputeService
    participant A as Agent (Local Process)

    Note over O: Configured with --local-mode
    
    T->>O: Start Orchestrator (Port 50051)
    
    T->>J: Run `jbazel build //...`
    J->>O: GetServer(RepoHash, SourcePath)
    O->>P: createContainer(RepoHash, SourcePath)
    P->>A: Spawn `agent` binary (subprocess)
    activate A
    Note right of A: Listens on random ephemeral port
    A-->>P: PID & Port
    deactivate A
    P-->>O: Agent Address (localhost:PORT)
    O-->>J: ServerAddress (localhost:PORT)
    
    J->>A: Connect (gRPC)
    J->>A: ExecuteCommand(stdin/out/err)
    A->>A: Run `bazel build ...` in SourcePath
    A-->>J: Stream Output
    A-->>J: Exit Code
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
    participant J as jBazel (Test Client)

    T->>K: `kubectl apply` Manifests
    K->>O: Deploy Orchestrator
    K->>S: Deploy Spanner Emulator
    
    T->>J: Run Test Command
    J->>O: GetServer(RepoHash)
    O->>K8: createContainer(RepoHash)
    K8->>K: Create Pod (Agent)
    K-->>A: Schedule Pod
    activate A
    A->>A: Start gRPC Server
    deactivate A
    K8->>K: Watch Pod Status IP
    K-->>K8: Pod IP
    K8-->>O: Agent Address (PodIP:9011)
    O-->>J: ServerAddress
    
    J->>A: Connect (gRPC)
    J->>A: ExecuteCommand
    A-->>J: Stream Output
```

## 3. Intended Final State (Production)

This runs in a production Kubernetes cluster. The Client (`jbazel`) runs on the developer's laptop, connecting to a public endpoint for the Orchestrator, but tunneling or directly connecting to Agents for build execution.

```mermaid
graph TD
    subgraph "Developer Machine"
        Client[jBazel CLI]
        Source[Source Code]
    end

    subgraph "Production Cluster (GKE)"
        Ingress[Load Balancer / Ingress]
        
        subgraph "Control Plane"
            Orch[Orchestrator Service]
            DB[(Spanner Database)]
        end
        
        subgraph "Build Plane (Nodes)"
            AgentPod1["Agent Pod (User A)"]
            AgentPod2["Agent Pod (User B)"]
        end
    end

    Client -- "gRPC (GetServer)" --> Ingress
    Ingress --> Orch
    Orch -- Read/Write State --> DB
    Orch -- K8s API --> AgentPod1
    
    Client -- "gRPC (ExecuteCommand)" --> AgentPod1
    
    note["Note: Direct connection to Agent \n may require Proxy/Tunnel if not \n on same VPC"]
    Client -.-> note
```
