package com.example.rbs;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import org.junit.Rule;
import org.junit.Test;
import java.util.List;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class KubernetesComputeServiceTest {
    @Rule
    public KubernetesServer server = new KubernetesServer(false, true);

    @Test
    public void testCreateContainerWithMounts() {
        KubernetesClient client = server.getClient();
        KubernetesComputeService service = new KubernetesComputeService(client);

        String userId = "testUser";
        String repoHash = "abcdef123456";
        String sessionId = "session123";
        String sourcePath = "/host/path/to/repo";
        List<String> startupOptions = Arrays.asList("--foo=bar");

        String expectedNamespace = "testuser-rbs-abcdef123456-session1"; // truncated session
        String expectedSa = "sa-testuser";

        String podName = service.createContainer(userId, repoHash, sessionId, sourcePath, startupOptions, "us-west1");

        // Check Namespace creation
        assertNotNull("Namespace should exist", client.namespaces().withName(expectedNamespace).get());

        // Check ServiceAccount creation
        assertNotNull("ServiceAccount should exist",
                client.serviceAccounts().inNamespace(expectedNamespace).withName(expectedSa).get());

        // Check Pod
        Pod pod = client.pods().inNamespace(expectedNamespace).withName(podName).get();
        assertNotNull("Pod should exist in namespace " + expectedNamespace, pod);
        assertEquals("localhost/agent:latest", pod.getSpec().getContainers().get(0).getImage());
        assertEquals(expectedSa, pod.getSpec().getServiceAccountName());
        assertEquals(expectedSa, pod.getSpec().getServiceAccountName()); // check duplication? No.
        assertEquals("session123", pod.getMetadata().getLabels().get("session")); // Check label
        assertEquals("us-west1", pod.getMetadata().getAnnotations().get("rbs.region"));

        // Verify Volume
        boolean foundVolume = false;
        for (Volume v : pod.getSpec().getVolumes()) {
            if ("workspace-volume".equals(v.getName())) {
                assertEquals(sourcePath, v.getHostPath().getPath());
                foundVolume = true;
            }
        }
        assertTrue("Volume 'workspace-volume' mismatch", foundVolume);

        // Verify VolumeMount
        boolean foundMount = false;
        for (VolumeMount vm : pod.getSpec().getContainers().get(0).getVolumeMounts()) {
            if ("workspace-volume".equals(vm.getName())) {
                assertEquals(sourcePath, vm.getMountPath());
                foundMount = true;
            }
        }
        assertTrue("VolumeMount 'workspace-volume' not found", foundMount);

        // Verify Env
        String startupOpts = pod.getSpec().getContainers().get(0).getEnv().stream()
                .filter(e -> "BAZEL_STARTUP_OPTIONS".equals(e.getName()))
                .findFirst().get().getValue();
        assertEquals("--foo=bar", startupOpts);
    }
}
