package com.example.rbs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.rbs.proto.GetServerRequest;
import com.example.rbs.proto.GetServerResponse;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ReadContext;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.TransactionRunner;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ProcessLifecycleTest {

    private ProcessComputeService computeService;
    private OrchestratorService service;
    private InMemorySessionRepository sessionRepo;

    @After
    public void tearDown() {
        computeService.cleanup();
    }

    @Test
    public void testFullLifecycle() {
        String userId = "user1";
        String repoHash = "repo1";
        String sessionId1 = "session1";

        // --- Step 1: Request Server (New Session) ---
        // InMemory DB is empty by default

        GetServerRequest req1 = GetServerRequest.newBuilder()
                .setUserId(userId).setRepoHash(repoHash).setSessionId(sessionId1).build();

        GetServerResponse resp1 = callGetServer(req1);

        assertThat(resp1.getStatus()).isEqualTo("PENDING");

        // Verify Process Spawned
        assertThat(computeService.getContainerStatus(userId, repoHash, sessionId1)).isNotNull();
        assertThat(computeService.getContainerStatus(userId, repoHash, sessionId1).getStatus()).isEqualTo("READY"); // Process
        // starts
        // instantly

        // --- Step 2: Poll (Check Ready) ---
        // InMemory DB implicitly holds state. Check if session stored.
        // We don't need to mock query/update anymore. logic flows naturally.

        GetServerResponse resp2 = callGetServer(req1);

        assertThat(resp2.getStatus()).isEqualTo("READY");
        assertThat(resp2.getServerAddress()).contains("127.0.0.1");

        // --- Step 3: Change Session (Delete & Recreate) ---
        // --- Step 3: Change Session (Delete & Recreate) ---
        String sessionId2 = "session2";
        // Mock DB: shows old session

        GetServerRequest req2 = GetServerRequest.newBuilder()
                .setUserId(userId).setRepoHash(repoHash).setSessionId(sessionId2).build();

        // Orchestrator will delete old container and create new one
        // Wait, NO. Orchestrator logic changed.
        // If client sends new sessionId2, Orchestrator looks it up. IT IS NOT FOUND.
        // Orchestrator creates NEW session2.
        // Old session1 is still there!
        // THIS IS VALID BEHAVIOR for 1-to-1 mapping.
        // But what about colliding processes?
        // ProcessComputeService uses "proc-user-repo-session". So they don't collide.
        // So both run.

        // Wait, ProcessLifecycleTest logic at "Step 3" assumes "Changed Session ->
        // Delete".
        // With 1-to-1, that's not what happens automatically unless client requests
        // explicit delete or reaper runs.
        // The test scenario "Change Session (Delete & Recreate)" is mirroring the old
        // logic of "Session Mismatch".
        // But now, a mismatch isn't a *mismatch*, found vs requested. It's just *not
        // found*.
        // So I should verify that *new* session is created.

        // Actually, if I want to test "Delete & Recreate", I should verify that
        // session1 still exists (orphaned until reaped)
        // AND session2 is created.

        // But wait, user said "Whenever bazel would start a new server, then a new
        // worker should get started."
        // That implies parallel existence.
        // "whenever bazel would kill a running server, the proxy should die (and thus
        // the build worker should get reaped)."
        // That implies explicitly killing.

        GetServerResponse resp3 = callGetServer(req2);

        assertThat(resp3.getStatus()).isEqualTo("PENDING");
        assertThat(computeService.getContainerStatus(userId, repoHash, sessionId2)).isNotNull();
        // Check session1 still exists?
        assertThat(computeService.getContainerStatus(userId, repoHash, sessionId1)).isNotNull();
    }

    @Test
    public void testReaperCleansUpStaleSessions() throws Exception {
        String userId = "user-reaper";
        String repoHash = "repo-reaper";
        String sessionId = "session-reaper";

        // 1. Create Session
        GetServerRequest req = GetServerRequest.newBuilder()
                .setUserId(userId).setRepoHash(repoHash).setSessionId(sessionId).build();
        callGetServer(req);

        // Verify active and get PID
        assertThat(computeService.getContainerStatus(userId, repoHash, sessionId)).isNotNull();
        assertThat(sessionRepo.getSession(sessionId)).isNotNull();
        long pid = computeService.getPid(userId, repoHash, sessionId);
        assertThat(pid).isGreaterThan(0);

        // 2. Advance time (5 mins + 1ms)
        mutableClock.advance(java.time.Duration.ofMinutes(5).plusMillis(1));

        // 3. Trigger Reaper
        service.reapStaleSessions();

        // 4. Verify Cleanup
        assertThat(computeService.getContainerStatus(userId, repoHash, sessionId)).isNull();
        assertThat(sessionRepo.getSession(sessionId)).isNull();

        // 5. Verify Process is Dead
        // Wait a small moment for async OS cleanup if needed, though waitFor() in
        // deleteContainer should handle it.
        // ProcessHandle.of(pid) returns Optional<ProcessHandle>. If present, check
        // isAlive().
        boolean isAlive = java.lang.ProcessHandle.of(pid).map(java.lang.ProcessHandle::isAlive).orElse(false);
        assertThat(isAlive).as("Process " + pid + " should be dead").isFalse();
    }

    private GetServerResponse callGetServer(GetServerRequest req) {
        AtomicReference<GetServerResponse> ref = new AtomicReference<>();
        service.getServer(req, new StreamObserver<GetServerResponse>() {
            @Override
            public void onNext(GetServerResponse value) {
                ref.set(value);
            }

            @Override
            public void onError(Throwable t) {
                throw new RuntimeException(t);
            }

            @Override
            public void onCompleted() {
            }
        });
        return ref.get();
    }

    // --- Mutable Clock Helper ---
    private static class MutableClock extends java.time.Clock {
        private java.time.Instant instant = java.time.Instant.now();
        private final java.time.ZoneId zone = java.time.ZoneId.systemDefault();

        public void advance(java.time.Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return zone;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.time.Instant instant() {
            return instant;
        }
    }

    private MutableClock mutableClock;

    @Before
    public void setUp() {
        computeService = new ProcessComputeService();
        mutableClock = new MutableClock();
        sessionRepo = new InMemorySessionRepository(mutableClock);
        service = new OrchestratorService(sessionRepo, computeService);
    }
}
