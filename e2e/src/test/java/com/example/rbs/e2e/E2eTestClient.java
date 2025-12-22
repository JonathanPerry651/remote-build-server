package com.example.rbs.e2e;

import com.example.rbs.proto.GetServerRequest;
import com.example.rbs.proto.GetServerResponse;
import com.example.rbs.proto.OrchestratorGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

public class E2eTestClient {
    public static void main(String[] args) throws Exception {
        String host = "localhost";
        int port = 50051;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        System.out.println("Running E2E Client...");

        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();

        try {
            OrchestratorGrpc.OrchestratorBlockingStub stub = OrchestratorGrpc.newBlockingStub(channel);
            KubernetesClient k8sClient = new KubernetesClientBuilder().build();

            String userId = "test-user-" + UUID.randomUUID().toString().substring(0, 8);
            String sanitizedUserId = userId.toLowerCase().replaceAll("[^a-z0-9]", "");
            String repoHash = "repo" + UUID.randomUUID().toString().substring(0, 8);
            String sessionId = UUID.randomUUID().toString();
            String podName = "bazel-" + sanitizedUserId + "-" + repoHash;

            System.out.println("1. Requesting server for user=" + userId + ", repo=" + repoHash);
            GetServerRequest request = GetServerRequest.newBuilder()
                    .setUserId(userId)
                    .setRepoHash(repoHash)
                    .setSessionId(sessionId)
                    .build();

            GetServerResponse response = stub.getServer(request);
            System.out.println("Response: Status=" + response.getStatus() + ", PodIP=" + response.getServerAddress());

            // 2. Poll for READY
            System.out.println("2. Polling for READY status...");
            long deadline = System.currentTimeMillis() + 60000;
            while (System.currentTimeMillis() < deadline) {
                if ("READY".equals(response.getStatus())) {
                    break;
                }
                Thread.sleep(1000);
                response = stub.getServer(request);
                System.out.println("Polled Status: " + response.getStatus() + ", IP=" + response.getServerAddress());
            }

            assertThat(response.getStatus())
                    .as("Orchestrator should eventually report READY")
                    .isEqualTo("READY");

            assertThat(response.getServerAddress())
                    .as("Orchestrator should return a Pod IP when READY")
                    .isNotEmpty();

            System.out.println("Orchestrator reports READY. IP=" + response.getServerAddress());

            // 3. Verify in K8s
            System.out.println("3. Verifying Pod in K8s...");
            Pod pod = k8sClient.pods().inNamespace("default").withName(podName).get();

            assertThat(pod)
                    .as("Pod %s should exist in Kubernetes", podName)
                    .isNotNull();

            System.out.println("Pod " + podName + " found. Phase: " + pod.getStatus().getPhase());

            // 4. Trigger deletion
            System.out.println("4. Triggering deletion by changing session");
            String newSessionId = UUID.randomUUID().toString();
            System.out.println("Sending new session ID: " + newSessionId);

            response = stub.getServer(GetServerRequest.newBuilder()
                    .setUserId(userId)
                    .setRepoHash(repoHash)
                    .setSessionId(newSessionId)
                    .build());

            System.out.println("Response after session change: Status=" + response.getStatus());

            // 5. Verify old pod is being deleted
            System.out.println("5. Verifying old pod deletion sequence...");
            Pod newPod = k8sClient.pods().inNamespace("default").withName(podName).get();

            if (newPod != null) {
                System.out.println("Pod exists (likely the new one). Phase: " + newPod.getStatus().getPhase());
                String oldUid = pod.getMetadata().getUid();
                String newUid = newPod.getMetadata().getUid();

                assertThat(newUid)
                        .as("If a pod exists after session change, it must be a NEW pod (different UID)")
                        .isNotEqualTo(oldUid);

                System.out.println("Verified new pod has different UID: " + newUid + " vs old " + oldUid);
            } else {
                System.out.println("Pod currently does not exist (expected if in between delete/create).");
            }

            System.out.println("E2E Test Passed!");

        } finally {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
