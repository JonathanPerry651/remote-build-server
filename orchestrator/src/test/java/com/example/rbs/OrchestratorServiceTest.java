package com.example.rbs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.rbs.proto.GetServerRequest;
import com.example.rbs.proto.GetServerResponse;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ReadContext;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.grpc.stub.StreamObserver;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public class OrchestratorServiceTest {

    @Test
    public void testGetServer_ExistingReadySession() {
        DatabaseClient dbClient = mock(DatabaseClient.class);
        KubernetesClient k8sClient = mock(KubernetesClient.class);

        // Mock Spanner behavior
        ReadContext readContext = mock(ReadContext.class);
        when(dbClient.singleUse()).thenReturn(readContext);
        ResultSet resultSet = mock(ResultSet.class);
        when(readContext.executeQuery(any(Statement.class))).thenReturn(resultSet);

        // Simulate existing session found
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("SessionId")).thenReturn("session123");
        when(resultSet.isNull("PodIP")).thenReturn(false);
        when(resultSet.getString("PodIP")).thenReturn("10.0.0.1");
        when(resultSet.isNull("Status")).thenReturn(false);
        when(resultSet.getString("Status")).thenReturn("READY");

        OrchestratorService service = new OrchestratorService(dbClient, k8sClient);

        GetServerRequest request = GetServerRequest.newBuilder()
                .setUserId("testuser")
                .setRepoHash("hash")
                .setSessionId("session123") // Match existing session
                .build();

        StreamObserver<GetServerResponse> responseObserver = mock(StreamObserver.class);

        service.getServer(request, responseObserver);

        // Verify response
        ArgumentCaptor<GetServerResponse> responseCaptor = ArgumentCaptor.forClass(GetServerResponse.class);
        verify(responseObserver).onNext(responseCaptor.capture());
        verify(responseObserver).onCompleted();

        GetServerResponse response = responseCaptor.getValue();
        if (!"10.0.0.1".equals(response.getPodIp())) {
            throw new RuntimeException("Expected PodIP 10.0.0.1, got " + response.getPodIp());
        }
        if (!"READY".equals(response.getStatus())) {
            throw new RuntimeException("Expected Status READY, got " + response.getStatus());
        }
    }
}
