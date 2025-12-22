package com.example.rbs;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.TransactionRunner;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class InMemorySessionRepository implements SessionRepository {
    private final Map<String, BuildSession> store = new ConcurrentHashMap<>();

    private String key(String userId, String repoHash) {
        return userId + "#" + repoHash;
    }

    @Override
    public BuildSession getSession(String userId, String repoHash) {
        return store.get(key(userId, repoHash));
    }

    @Override
    public void saveSession(String userId, String repoHash, String sessionId, String serverAddress, String status) {
        store.put(key(userId, repoHash), new BuildSession(sessionId, serverAddress, status));
    }
}

class SpannerSessionRepository implements SessionRepository {
    private final DatabaseClient dbClient;

    public SpannerSessionRepository(DatabaseClient dbClient) {
        this.dbClient = dbClient;
    }

    @Override
    public BuildSession getSession(String userId, String repoHash) {
        try (ResultSet resultSet = dbClient.singleUse().executeQuery(
                Statement
                        .newBuilder(
                                "SELECT SessionId, PodIP, Status FROM BuildSessions WHERE UserId = @userId AND RepoHash = @repoHash")
                        .bind("userId").to(userId)
                        .bind("repoHash").to(repoHash)
                        .build())) {
            if (resultSet.next()) {
                return new BuildSession(
                        resultSet.getString("SessionId"),
                        resultSet.isNull("PodIP") ? null : resultSet.getString("PodIP"),
                        resultSet.isNull("Status") ? "UNKNOWN" : resultSet.getString("Status"));
            }
            return null;
        }
    }

    @Override
    public void saveSession(String userId, String repoHash, String sessionId, String serverAddress, String status) {
        dbClient.readWriteTransaction().run(new TransactionRunner.TransactionCallable<Void>() {
            @Override
            public Void run(TransactionContext transaction) throws Exception {
                transaction.buffer(
                        com.google.cloud.spanner.Mutation.newInsertOrUpdateBuilder("BuildSessions")
                                .set("UserId").to(userId)
                                .set("RepoHash").to(repoHash)
                                .set("SessionId").to(sessionId)
                                .set("PodIP").to(serverAddress) // Mapped to PodIP col for now
                                .set("Status").to(status)
                                .build());
                return null;
            }
        });
    }
}
