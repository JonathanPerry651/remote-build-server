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
    private DatabaseClient dbClient;

    @Before
    public void setUp() {
        computeService = new ProcessComputeService();
        dbClient = mock(DatabaseClient.class);
        service = new OrchestratorService(dbClient, computeService);
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
        // Mock DB: No session
        mockSpannerQuery(null, null, null);
        mockSpannerUpdate();

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
        mockSpannerQuery(sessionId1, null, "PENDING");
        mockSpannerUpdate(); // It will update to READY

        GetServerResponse resp2 = callGetServer(req1);

        assertThat(resp2.getStatus()).isEqualTo("READY");
        assertThat(resp2.getPodIp()).isEqualTo("127.0.0.1");

        // --- Step 3: Change Session (Delete & Recreate) ---
        String sessionId2 = "session2";
        // Mock DB: shows old session
        mockSpannerQuery(sessionId1, "127.0.0.1", "READY");
        mockSpannerUpdate(); // Update new session

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

    private void mockSpannerQuery(String sessionId, String podIp, String status) {
        ReadContext readContext = mock(ReadContext.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dbClient.singleUse()).thenReturn(readContext);
        when(readContext.executeQuery(any(Statement.class))).thenReturn(resultSet);

        if (sessionId != null) {
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getString("SessionId")).thenReturn(sessionId);
            when(resultSet.isNull("PodIP")).thenReturn(podIp == null);
            if (podIp != null)
                when(resultSet.getString("PodIP")).thenReturn(podIp);
            when(resultSet.getString("Status")).thenReturn(status);
        } else {
            when(resultSet.next()).thenReturn(false);
        }
    }

    private void mockSpannerUpdate() {
        TransactionRunner runner = mock(TransactionRunner.class);
        when(dbClient.readWriteTransaction()).thenReturn(runner);
        when(runner.run(any())).thenAnswer(invocation -> {
            TransactionRunner.TransactionCallable<Void> callable = invocation.getArgument(0);
            return callable.run(mock(TransactionContext.class));
        });
    }
}
