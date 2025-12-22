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

    @Override
    public String createContainer(String userId, String repoHash) {
        String containerId = getContainerId(userId, repoHash);
        logger.info("Process: Spawning process for " + containerId);

        if (processes.containsKey(containerId) && processes.get(containerId).isAlive()) {
            logger.info("Process already running: " + containerId);
            return containerId;
        }

        try {
            // Emulate a server that just sleeps
            ProcessBuilder pb = new ProcessBuilder("sleep", "3600");
            Process p = pb.start();
            processes.put(containerId, p);
            logger.info("Process spawned for " + containerId + ", pid=" + p.pid());
        } catch (IOException e) {
            logger.severe("Failed to spawn process: " + e.getMessage());
            throw new RuntimeException(e);
        }
        return containerId;
    }

    @Override
    public void deleteContainer(String userId, String repoHash) {
        String containerId = getContainerId(userId, repoHash);
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
    public ContainerStatus getContainerStatus(String userId, String repoHash) {
        String containerId = getContainerId(userId, repoHash);
        Process p = processes.get(containerId);

        if (p != null && p.isAlive()) {
            return new ContainerStatus("READY", "127.0.0.1");
        } else if (p != null && !p.isAlive()) {
            return new ContainerStatus("TERMINATED", null);
        }

        return null;
    }

    private String getContainerId(String userId, String repoHash) {
        return "proc-" + userId + "-" + repoHash;
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
