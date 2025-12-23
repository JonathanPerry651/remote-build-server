package com.example.rbs;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * ComputeService implementation that spawns local processes.
 * Used for testing without Kubernetes.
 */
public class ProcessComputeService implements ComputeService {
    private static final Logger logger = Logger.getLogger(ProcessComputeService.class.getName());
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Map<String, Integer> ports = new ConcurrentHashMap<>();

    @Override
    public String createContainer(String userId, String repoHash, String sessionId, String sourcePath,
            java.util.List<String> startupOptions, String region) {
        String containerId = getContainerId(userId, repoHash, sessionId);
        logger.info("Process: Spawning process for " + containerId + " (source: " + sourcePath + ")");

        if (processes.containsKey(containerId) && processes.get(containerId).isAlive()) {
            logger.info("Process already running: " + containerId);
            return containerId;
        }

        try {
            // Spawn the Agent binary
            // Find free port
            int port = findFreePort();

            // Expected Path to Agent Binary (via Runfiles or System Property)
            // For now, assume it's set via Env or System Property, or hardcoded for test
            String agentPath = System.getenv("AGENT_BINARY");
            if (agentPath == null) {
                // Fallback for direct local run
                agentPath = "./agent/agent";
            }

            ProcessBuilder pb = new ProcessBuilder(agentPath);
            pb.environment().put("PORT", String.valueOf(port));
            if (startupOptions != null && !startupOptions.isEmpty()) {
                String joinedOptions = String.join("|||", startupOptions);
                pb.environment().put("BAZEL_STARTUP_OPTIONS", joinedOptions);
            }
            if (sourcePath != null && !sourcePath.isEmpty()) {
                // If source path is provided, set it as working directory
                pb.directory(new java.io.File(sourcePath));
            }
            pb.inheritIO(); // Useful for debugging test output
            Process p = pb.start();
            processes.put(containerId, p);
            ports.put(containerId, port);

            logger.info("Agent process spawned for " + containerId + " on port " + port + ", pid=" + p.pid());
        } catch (IOException e) {
            logger.severe("Failed to spawn process: " + e.getMessage());
            throw new RuntimeException(e);
        }
        return containerId;
    }

    @Override
    public void deleteContainer(String userId, String repoHash, String sessionId) {
        String containerId = getContainerId(userId, repoHash, sessionId);
        Process p = processes.remove(containerId);
        if (p != null) {
            logger.info("Process: Killing process for " + containerId + ", pid=" + p.pid());
            p.destroyForcibly();
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            logger.warning("Process not found for " + containerId);
        }
    }

    @Override
    public ContainerStatus getContainerStatus(String userId, String repoHash, String sessionId) {
        String containerId = getContainerId(userId, repoHash, sessionId);
        Process p = processes.get(containerId);

        Integer port = ports.get(containerId);
        if (p != null && p.isAlive() && port != null) {
            return new ContainerStatus("READY", "127.0.0.1:" + port);
        } else if (p != null && !p.isAlive()) {
            return new ContainerStatus("TERMINATED", null);
        }

        return null;
    }

    public long getPid(String userId, String repoHash, String sessionId) {
        String containerId = getContainerId(userId, repoHash, sessionId);
        Process p = processes.get(containerId);
        return p != null ? p.pid() : -1;
    }

    private String getContainerId(String userId, String repoHash, String sessionId) {
        return "proc-" + userId + "-" + repoHash + "-" + sessionId;
    }

    private int findFreePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Could not find free port", e);
        }
    }

    public void cleanup() {
        for (Process p : processes.values()) {
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
        processes.clear();
    }
}
