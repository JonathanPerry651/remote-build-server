package com.example.rbs;

/**
 * Interface for managing compute resources (containers/pods/processes).
 */
public interface ComputeService {
    /**
     * Creates a compute container for a build session.
     * 
     * @param userId   The user ID.
     * @param repoHash The repository hash.
     * @return The name/ID of the created container.
     */
    String createContainer(String userId, String repoHash);

    /**
     * Deletes a compute container.
     * 
     * @param userId   The user ID.
     * @param repoHash The repository hash.
     */
    void deleteContainer(String userId, String repoHash);

    /**
     * Gets the status of a container.
     * 
     * @param userId   The user ID.
     * @param repoHash The repository hash.
     * @return The status (e.g., "PENDING", "READY", "UNKNOWN") or null if not
     *         found.
     */
    ContainerStatus getContainerStatus(String userId, String repoHash);

    class ContainerStatus {
        private final String status; // e.g. "READY", "PENDING"
        private final String ipAddress;

        public ContainerStatus(String status, String ipAddress) {
            this.status = status;
            this.ipAddress = ipAddress;
        }

        public String getStatus() {
            return status;
        }

        public String getIpAddress() {
            return ipAddress;
        }
    }
}
