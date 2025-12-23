package com.example.rbs;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.logging.Logger;

public class KubernetesComputeService implements ComputeService {
    private static final Logger logger = Logger.getLogger(KubernetesComputeService.class.getName());
    private final KubernetesClient k8sClient;

    public KubernetesComputeService(KubernetesClient k8sClient) {
        this.k8sClient = k8sClient;
    }

    @Override
    public String createContainer(String userId, String repoHash, String sessionId, String sourcePath,
            java.util.List<String> startupOptions, String region) {
        // TODO: Implement startupOptions for Kubernetes
        // For now, ignoring them in K8s implementation until next phase
        String namespace = getNamespaceName(userId, repoHash, sessionId);
        String serviceAccountName = "sa-" + userId.toLowerCase().replaceAll("[^a-z0-9]", "");
        String podName = "bazel-server"; // Fixed name since we are in a unique namespace

        logger.info("Ensuring namespace: " + namespace);
        createNamespace(namespace);
        createServiceAccount(namespace, serviceAccountName);

        logger.info("Creating pod: " + podName + " in namespace " + namespace + " (source: " + sourcePath
                + ") in region: " + region);

        // Define Pod
        java.util.Map<String, String> annotations = new java.util.HashMap<>();
        if (region != null && !region.isEmpty()) {
            annotations.put("rbs.region", region);
        }

        Pod pod = new PodBuilder()
                .withNewMetadata()
                .withName(podName)
                .withNamespace(namespace)
                .addToLabels("app", "bazel-build")
                .addToLabels("user", userId)
                .addToLabels("session", sessionId)
                .addToAnnotations(annotations)
                .endMetadata()
                .withNewSpec()
                .withServiceAccountName(serviceAccountName)
                .addNewVolume()
                .withName("workspace-volume")
                .withNewHostPath()
                .withPath(sourcePath)
                .endHostPath()
                .endVolume()
                .addNewContainer()
                .withName("bazel-server")
                .withImage("localhost/agent:latest")
                .withImagePullPolicy("Never") // Use local image for Kind/Minikube
                .addNewVolumeMount()
                .withName("workspace-volume")
                .withMountPath(sourcePath) // Mount at same path as host
                .endVolumeMount()
                .addNewEnv()
                .withName("PORT")
                .withValue("9011") // Standard agent port
                .endEnv()
                .addNewEnv()
                .withName("BAZEL_STARTUP_OPTIONS")
                .withValue(startupOptions != null ? String.join("|||", startupOptions) : "")
                .endEnv()
                // .withCommand("sh", "-c", "while true; do echo 'Bazel Server Running'; sleep
                // 10; done") // Debug
                .endContainer()
                .endSpec()
                .build();

        // Create Pod
        try {
            k8sClient.pods().inNamespace(namespace).resource(pod).create();
            logger.info("Pod creation requested for: " + podName);
        } catch (Exception e) {
            logger.severe("Failed to create pod: " + e.getMessage());
            throw new RuntimeException("Failed to create pod: " + e.getMessage(), e);
        }
        return podName;
    }

    @Override
    public void deleteContainer(String userId, String repoHash, String sessionId) {
        String namespace = getNamespaceName(userId, repoHash, sessionId);
        logger.info("Deleting namespace: " + namespace);
        try {
            k8sClient.namespaces().withName(namespace).withGracePeriod(0).delete();

            // Wait for deletion (up to 60 seconds)
            long deadline = System.currentTimeMillis() + 60000;
            while (System.currentTimeMillis() < deadline) {
                if (k8sClient.namespaces().withName(namespace).get() == null) {
                    logger.info("Namespace " + namespace + " deleted successfully.");
                    return;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for namespace deletion");
                }
            }
            logger.warning("Timed out waiting for namespace deletion: " + namespace);
        } catch (Exception e) {
            logger.warning("Error deleting namespace: " + e.getMessage());
        }
    }

    @Override
    public ContainerStatus getContainerStatus(String userId, String repoHash, String sessionId) {
        String namespace = getNamespaceName(userId, repoHash, sessionId);
        String podName = "bazel-server";
        Pod pod = k8sClient.pods().inNamespace(namespace).withName(podName).get();

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

    private String getNamespaceName(String userId, String repoHash, String sessionId) {
        String sanitizedUser = userId.toLowerCase().replaceAll("[^a-z0-9]", "");
        // Use hash of SessionID to keep it short if needed, or substring
        String sessionSuffix = sessionId.substring(0, Math.min(sessionId.length(), 8));
        return sanitizedUser + "-rbs-" + repoHash + "-" + sessionSuffix;
    }

    private void createNamespace(String namespace) {
        if (k8sClient.namespaces().withName(namespace).get() == null) {
            k8sClient.namespaces().resource(new io.fabric8.kubernetes.api.model.NamespaceBuilder()
                    .withNewMetadata().withName(namespace).endMetadata().build()).create();
        }
    }

    private void createServiceAccount(String namespace, String saName) {
        if (k8sClient.serviceAccounts().inNamespace(namespace).withName(saName).get() == null) {
            k8sClient.serviceAccounts().inNamespace(namespace)
                    .resource(new io.fabric8.kubernetes.api.model.ServiceAccountBuilder()
                            .withNewMetadata().withName(saName).endMetadata().build())
                    .create();
        }
    }
}
