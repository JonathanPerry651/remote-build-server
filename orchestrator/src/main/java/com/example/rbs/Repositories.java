package com.example.rbs;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.TransactionRunner;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class InMemorySessionRepository implements SessionRepository {
    private final Map<String, BuildSession> store = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemorySessionRepository(Clock clock) {
        this.clock = clock;
    }

    // Key is now SessionId

    @Override
    public BuildSession getSession(String sessionId) {
        return store.get(sessionId);
    }

    @Override
    public void saveSession(String userId, String repoHash, String sessionId, String serverAddress, String status) {
        store.put(sessionId,
                new BuildSession(userId, repoHash, sessionId, serverAddress, status, clock.millis()));
    }

    @Override
    public void updateHeartbeat(String sessionId) {
        BuildSession old = store.get(sessionId);
        if (old != null) {
            store.put(sessionId, new BuildSession(old.userId, old.repoHash, old.sessionId, old.serverAddress,
                    old.status, clock.millis()));
        }
    }

    @Override
    public void deleteSession(String sessionId) {
        store.remove(sessionId);
    }

    @Override
    public java.util.List<BuildSession> getStaleSessions(long inactiveMillis) {
        java.util.List<BuildSession> stale = new java.util.ArrayList<>();
        long now = clock.millis();
        for (BuildSession s : store.values()) {
            if (now - s.lastHeartbeat > inactiveMillis) {
                stale.add(s);
            }
        }
        return stale;
    }
}

class SpannerSessionRepository implements SessionRepository {
    private final DatabaseClient dbClient;
    private final Clock clock;

    public SpannerSessionRepository(DatabaseClient dbClient, Clock clock) {
        this.dbClient = dbClient;
        this.clock = clock;
    }

    @Override
    public BuildSession getSession(String sessionId) {
        try (ResultSet resultSet = dbClient.singleUse().executeQuery(
                Statement
                        .newBuilder(
                                "SELECT UserId, RepoHash, SessionId, PodIP, Status, LastHeartbeat FROM BuildSessions WHERE SessionId = @sessionId")
                        .bind("sessionId").to(sessionId)
                        .build())) {
            if (resultSet.next()) {
                return new BuildSession(
                        resultSet.getString("UserId"),
                        resultSet.getString("RepoHash"),
                        resultSet.getString("SessionId"),
                        resultSet.isNull("PodIP") ? null : resultSet.getString("PodIP"),
                        resultSet.isNull("Status") ? "UNKNOWN" : resultSet.getString("Status"),
                        resultSet.isNull("LastHeartbeat") ? 0 : resultSet.getLong("LastHeartbeat"));
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
                                .set("LastHeartbeat").to(clock.millis())
                                .build());
                return null;
            }
        });
    }

    @Override
    public void updateHeartbeat(String sessionId) {
        dbClient.readWriteTransaction().run(new TransactionRunner.TransactionCallable<Void>() {
            @Override
            public Void run(TransactionContext transaction) throws Exception {
                // To update LastHeartbeat we need PK (UserId, RepoHash) ???
                // Wait, if PK is (UserId, RepoHash), then SessionId is not PK in Spanner.
                // We likely need to query to get keys first.
                // Or change Schema?
                // For now, assume Schema is PK(UserId, RepoHash) as per original design?
                // Actually SpannerSessionRepository implementation before implied we needed to
                // lookup.
                // IF we want 1-to-1, SessionId SHOULD BE UNIQUE.
                // Ideally Spanner schema changes to PK(SessionId).
                // BUT I cannot change Spanner schema easily here without migration.
                // So I will Query-then-Update.

                try (ResultSet resultSet = transaction.executeQuery(
                        Statement.newBuilder("SELECT UserId, RepoHash FROM BuildSessions WHERE SessionId = @sessionId")
                                .bind("sessionId").to(sessionId).build())) {
                    if (resultSet.next()) {
                        String userId = resultSet.getString("UserId");
                        String repoHash = resultSet.getString("RepoHash");
                        transaction.buffer(
                                com.google.cloud.spanner.Mutation.newUpdateBuilder("BuildSessions")
                                        .set("UserId").to(userId)
                                        .set("RepoHash").to(repoHash)
                                        .set("LastHeartbeat").to(clock.millis())
                                        .build());
                    }
                }
                return null;
            }
        });
    }

    @Override
    public void deleteSession(String sessionId) {
        dbClient.readWriteTransaction().run(new TransactionRunner.TransactionCallable<Void>() {
            @Override
            public Void run(TransactionContext transaction) throws Exception {
                try (ResultSet resultSet = transaction.executeQuery(
                        Statement.newBuilder("SELECT UserId, RepoHash FROM BuildSessions WHERE SessionId = @sessionId")
                                .bind("sessionId").to(sessionId).build())) {
                    if (resultSet.next()) {
                        String userId = resultSet.getString("UserId");
                        String repoHash = resultSet.getString("RepoHash");
                        transaction.buffer(
                                com.google.cloud.spanner.Mutation.delete("BuildSessions",
                                        com.google.cloud.spanner.Key.of(userId, repoHash)));
                    }
                }
                return null;
            }
        });
    }

    @Override
    public java.util.List<BuildSession> getStaleSessions(long inactiveMillis) {
        long cutoff = clock.millis() - inactiveMillis;
        java.util.List<BuildSession> stale = new java.util.ArrayList<>();
        try (ResultSet resultSet = dbClient.singleUse().executeQuery(
                Statement.newBuilder(
                        "SELECT UserId, RepoHash, SessionId, PodIP, Status, LastHeartbeat FROM BuildSessions WHERE LastHeartbeat < @cutoff")
                        .bind("cutoff").to(cutoff).build())) {
            while (resultSet.next()) {
                stale.add(new BuildSession(
                        resultSet.getString("UserId"),
                        resultSet.getString("RepoHash"),
                        resultSet.getString("SessionId"),
                        resultSet.isNull("PodIP") ? null : resultSet.getString("PodIP"),
                        resultSet.isNull("Status") ? "UNKNOWN" : resultSet.getString("Status"),
                        resultSet.isNull("LastHeartbeat") ? 0 : resultSet.getLong("LastHeartbeat")));
            }
        }
        return stale;
    }
}
