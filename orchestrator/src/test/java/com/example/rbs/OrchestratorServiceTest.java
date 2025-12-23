package com.example.rbs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

import com.example.rbs.proto.GetServerRequest;
import com.example.rbs.proto.GetServerResponse;
import io.grpc.stub.StreamObserver;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public class OrchestratorServiceTest {

    @Test
    public void testGetServer_ExistingReadySession() {
        SessionRepository sessionRepo = mock(SessionRepository.class);
        ComputeService computeService = mock(ComputeService.class);

        // Simulate existing session found
        SessionRepository.BuildSession session = new SessionRepository.BuildSession("testuser", "hash", "session123",
                "10.0.0.1", "READY", System.currentTimeMillis());
        when(sessionRepo.getSession(anyString())).thenReturn(session);

        ComputeService.ContainerStatus containerStatus = new ComputeService.ContainerStatus("READY", "10.0.0.1");
        when(computeService.getContainerStatus(anyString(), anyString(), anyString())).thenReturn(containerStatus);

        OrchestratorService service = new OrchestratorService(sessionRepo, computeService);

        GetServerRequest request = GetServerRequest.newBuilder()
                .setUserId("testuser")
                .setRepoHash("hash")
                .setSessionId("session123")
                .build();

        StreamObserver<GetServerResponse> responseObserver = mock(StreamObserver.class);

        service.getServer(request, responseObserver);

        // Verify response
        ArgumentCaptor<GetServerResponse> responseCaptor = ArgumentCaptor.forClass(GetServerResponse.class);
        verify(responseObserver).onNext(responseCaptor.capture());
        verify(responseObserver).onCompleted();

        GetServerResponse response = responseCaptor.getValue();
        if (!"10.0.0.1".equals(response.getServerAddress())) {
            throw new RuntimeException("Expected ServerAddress 10.0.0.1, got " + response.getServerAddress());
        }
        if (!"READY".equals(response.getStatus())) {
            throw new RuntimeException("Expected Status READY, got " + response.getStatus());
        }
    }

    @Test
    public void testGetServer_UpdatesHeartbeat() {
        // This test verifies the fix for the race condition where long startup times
        // could cause
        // a session to be reaped because polling GetServer didn't update the heartbeat.

        SessionRepository sessionRepo = mock(SessionRepository.class);
        ComputeService computeService = mock(ComputeService.class);

        // Simulate existing session found (PENDING status)
        String sessionId = "session-race-condition";
        SessionRepository.BuildSession session = new SessionRepository.BuildSession("user1", "hash", sessionId,
                null, "PENDING", System.currentTimeMillis() - 300000); // Created 5 mins ago (stale if not updated)

        when(sessionRepo.getSession(sessionId)).thenReturn(session);

        // Compute service reports PENDING (still starting)
        ComputeService.ContainerStatus containerStatus = new ComputeService.ContainerStatus("PENDING", null);
        when(computeService.getContainerStatus(anyString(), anyString(), anyString())).thenReturn(containerStatus);

        OrchestratorService service = new OrchestratorService(sessionRepo, computeService);

        GetServerRequest request = GetServerRequest.newBuilder()
                .setUserId("user1")
                .setRepoHash("hash")
                .setSessionId(sessionId)
                .build();

        StreamObserver<GetServerResponse> responseObserver = mock(StreamObserver.class);

        // Action: Call getServer (Polling)
        service.getServer(request, responseObserver);

        // Verify: Heartbeat MUST be updated
        verify(sessionRepo, times(1)).updateHeartbeat(sessionId);

        // Ensure response is sent
        verify(responseObserver).onNext(any(GetServerResponse.class));
        verify(responseObserver).onCompleted();
    }
}
