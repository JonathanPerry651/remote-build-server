package com.example.rbs;

public interface SessionRepository {
    BuildSession getSession(String sessionId);

    void saveSession(String userId, String repoHash, String sessionId, String serverAddress, String status);

    void updateHeartbeat(String sessionId);

    void deleteSession(String sessionId);

    java.util.List<BuildSession> getStaleSessions(long inactiveMillis);

    class BuildSession {
        public final String userId;
        public final String repoHash;
        public final String sessionId;
        public final String serverAddress;
        public final String status;
        public final long lastHeartbeat;

        public BuildSession(String userId, String repoHash, String sessionId, String serverAddress, String status,
                long lastHeartbeat) {
            this.userId = userId;
            this.repoHash = repoHash;
            this.sessionId = sessionId;
            this.serverAddress = serverAddress;
            this.status = status;
            this.lastHeartbeat = lastHeartbeat;
        }
    }
}
