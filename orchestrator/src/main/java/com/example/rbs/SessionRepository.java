package com.example.rbs;

public interface SessionRepository {
    BuildSession getSession(String userId, String repoHash);

    void saveSession(String userId, String repoHash, String sessionId, String serverAddress, String status);

    class BuildSession {
        public final String sessionId;
        public final String serverAddress;
        public final String status;

        public BuildSession(String sessionId, String serverAddress, String status) {
            this.sessionId = sessionId;
            this.serverAddress = serverAddress;
            this.status = status;
        }
    }
}
