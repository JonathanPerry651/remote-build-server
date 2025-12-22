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

    @Before
    public void setUp() {
        computeService = new ProcessComputeService();
        sessionRepo = new InMemorySessionRepository();
        service = new OrchestratorService(sessionRepo, computeService);
    }

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
        assertThat(computeService.getContainerStatus(userId, repoHash)).isNotNull();
        assertThat(computeService.getContainerStatus(userId, repoHash).getStatus()).isEqualTo("READY"); // Process
                                                                                                        // starts
                                                                                                        // instantly

        // --- Step 2: Poll (Check Ready) ---
        // Mock DB: Session exists, Status PENDING (Orchestrator logic updates checks
        // compute service)
        // Since process compute service returns READY immediately for 'Running'
        // processes.
        // --- Step 2: Poll (Check Ready) ---
        // InMemory DB implicitly holds state. Check if session stored.
        // We don't need to mock query/update anymore. logic flows naturally.

        GetServerResponse resp2 = callGetServer(req1);

        assertThat(resp2.getStatus()).isEqualTo("READY");
        assertThat(resp2.getServerAddress()).contains("127.0.0.1");

        // --- Step 3: Change Session (Delete & Recreate) ---
        String sessionId2 = "session2";
        // Mock DB: shows old session
        // --- Step 3: Change Session (Delete & Recreate) ---
        String sessionId2 = "session2";

        GetServerRequest req2 = GetServerRequest.newBuilder()
                .setUserId(userId).setRepoHash(repoHash).setSessionId(sessionId2).build();

        // Orchestrator will delete old container and create new one

        GetServerResponse resp3 = callGetServer(req2);

        assertThat(resp3.getStatus()).isEqualTo("PENDING");
        assertThat(computeService.getContainerStatus(userId, repoHash)).isNotNull();
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

    // Mock helpers removed
}
}
