package com.example.rbs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        SessionRepository.BuildSession session = new SessionRepository.BuildSession("session123", "10.0.0.1", "READY");
        when(sessionRepo.getSession(anyString(), anyString())).thenReturn(session);

        ComputeService.ContainerStatus containerStatus = new ComputeService.ContainerStatus("READY", "10.0.0.1");
        when(computeService.getContainerStatus(anyString(), anyString())).thenReturn(containerStatus);

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
}
