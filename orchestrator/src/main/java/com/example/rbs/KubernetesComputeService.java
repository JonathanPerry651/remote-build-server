package com.example.rbs;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.logging.Logger;

public class KubernetesComputeService implements ComputeService {
    private static final Logger logger = Logger.getLogger(KubernetesComputeService.class.getName());
    private final KubernetesClient k8sClient;

    public KubernetesComputeService(KubernetesClient k8sClient) {
        this.k8sClient = k8sClient;
    }

    @Override
    public String createContainer(String userId, String repoHash) {
        String podName = getPodName(userId, repoHash);
        logger.info("Creating pod: " + podName);

        // Define Pod
        Pod pod = new PodBuilder()
                .withNewMetadata()
                .withName(podName)
                .addToLabels("app", "bazel-build")
                .addToLabels("user", userId)
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("bazel-server")
                .withImage("busybox") // Placeholder image
                .withCommand("sh", "-c", "while true; do echo 'Bazel Server Running'; sleep 10; done")
                .endContainer()
                .endSpec()
                .build();

        // Create Pod
        try {
            k8sClient.pods().inNamespace("default").resource(pod).create();
            logger.info("Pod creation requested for: " + podName);
        } catch (Exception e) {
            logger.severe("Failed to create pod: " + e.getMessage());
            // It might already exist?
        }
        return podName;
    }

    @Override
    public void deleteContainer(String userId, String repoHash) {
        String podName = getPodName(userId, repoHash);
        logger.info("Deleting pod: " + podName);
        try {
            k8sClient.pods().inNamespace("default").withName(podName).withGracePeriod(0).delete();
            // Wait for it to be confirmed deleted to avoid race condition on immediate
            // recreate
            for (int i = 0; i < 30; i++) {
                if (k8sClient.pods().inNamespace("default").withName(podName).get() == null) {
                    break;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (Exception e) {
            logger.warning("Error deleting pod: " + e.getMessage());
        }
    }

    @Override
    public ContainerStatus getContainerStatus(String userId, String repoHash) {
        String podName = getPodName(userId, repoHash);
        Pod pod = k8sClient.pods().inNamespace("default").withName(podName).get();

        if (pod == null) {
            return null;
        }

        String phase = pod.getStatus().getPhase();
        String ip = pod.getStatus().getPodIP();

        String status = "PENDING";
        if ("Running".equals(phase)) {
            // Check readiness (e.g. ready condition). For now assuming Running is enough or
            // use 'Ready' condition.
            // Simple logic: if Running and IP assigned -> READY
            if (ip != null && !ip.isEmpty()) {
                status = "READY";
            }
        } else if ("Succeeded".equals(phase) || "Failed".equals(phase)) {
            status = "TERMINATED";
        }

        return new ContainerStatus(status, ip);
    }

    private String getPodName(String userId, String repoHash) {
        // Sanitize userId
        return "bazel-" + userId.toLowerCase().replaceAll("[^a-z0-9]", "") + "-" + repoHash;
    }
}
