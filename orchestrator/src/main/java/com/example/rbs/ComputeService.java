package com.example.rbs;

/**
 * Interface for managing compute resources (containers/pods/processes).
 */
public interface ComputeService {
    /**
     * Creates a compute container for a build session.
     * 
     * @param userId         The user ID.
     * @param repoHash       The repository hash.
     * @param sessionId      The unique session ID.
     * @param sourcePath     The source path.
     * @param startupOptions The list of startup options for the Bazel server.
     * @param region         The target region for the container.
     * @return The name/ID of the created container.
     */
    String createContainer(String userId, String repoHash, String sessionId, String sourcePath,
            java.util.List<String> startupOptions,
            String region);

    /**
     * Deletes a compute container.
     * 
     * @param userId    The user ID.
     * @param repoHash  The repository hash.
     * @param sessionId The unique session ID.
     */
    void deleteContainer(String userId, String repoHash, String sessionId);

    /**
     * Gets the status of a container.
     * 
     * @param userId    The user ID.
     * @param repoHash  The repository hash.
     * @param sessionId The unique session ID.
     * @return The status (e.g., "PENDING", "READY", "UNKNOWN") or null if not
     *         found.
     */
    ContainerStatus getContainerStatus(String userId, String repoHash, String sessionId);

    class ContainerStatus {
        private final String status; // e.g. "READY", "PENDING"
        private final String address;

        public ContainerStatus(String status, String address) {
            this.status = status;
            this.address = address;
        }

        public String getStatus() {
            return status;
        }

        public String getAddress() {
            return address;
        }
    }
}
