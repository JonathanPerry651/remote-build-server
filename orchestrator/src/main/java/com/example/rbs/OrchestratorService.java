package com.example.rbs;

import com.example.rbs.proto.GetServerRequest;
import com.example.rbs.proto.GetServerResponse;
import com.example.rbs.proto.OrchestratorGrpc;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.TransactionRunner;
import io.grpc.stub.StreamObserver;
import java.util.logging.Logger;

public class OrchestratorService extends OrchestratorGrpc.OrchestratorImplBase {
  private static final Logger logger = Logger.getLogger(OrchestratorService.class.getName());

  private final DatabaseClient dbClient;
  private final ComputeService computeService;

  public OrchestratorService(DatabaseClient dbClient, ComputeService computeService) {
    this.dbClient = dbClient;
    this.computeService = computeService;
  }

  @Override
  public void getServer(GetServerRequest request, StreamObserver<GetServerResponse> responseObserver) {
    String userId = request.getUserId();
    String repoHash = request.getRepoHash();
    String clientSessionId = request.getSessionId(); // Optional, from client

    logger.info("Received GetServer request for User: " + userId + ", Repo: " + repoHash);

    try {
      // 1. Check if a session already exists for this User/Repo
      BuildSession session = getSession(userId, repoHash);

      if (session != null) {
        // Session exists.
        // If client provided a specific session ID, check if it matches.
        // If mismatch (and client session ID provided), it implies a new session is
        // desired -> Recreate.
        // If client session ID is empty (initial request), we return existing if valid.

        boolean sessionMismatch = !clientSessionId.isEmpty()
            && !clientSessionId.equals(session.sessionId);

        if (sessionMismatch) {
          logger.info("Session mismatch (Client: " + clientSessionId + ", DB: " + session.sessionId + "). Recreating.");
          // Delete old resources
          computeService.deleteContainer(userId, repoHash);

          // Create new flow
          handleNewSession(userId, repoHash, clientSessionId, responseObserver);
          return;
        } else {
          // Match or client didn't specify. Return status.
          // Check actual status of pod.
          checkAndUpdateStatus(userId, repoHash, session, responseObserver);
          return;
        }
      } else {
        // No session exists. Create new.
        handleNewSession(userId, repoHash,
            !clientSessionId.isEmpty() ? clientSessionId : java.util.UUID.randomUUID().toString(), responseObserver);
        return;
      }
    } catch (Exception e) {
      logger.severe("Error handling GetServer: " + e.getMessage());
      responseObserver.onError(io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
    }
  }

  private void handleNewSession(String userId, String repoHash, String sessionId,
      StreamObserver<GetServerResponse> responseObserver) {
    // Create Pod
    computeService.createContainer(userId, repoHash);

    // Save session 'PENDING'
    saveSession(userId, repoHash, sessionId, null, "PENDING");

    GetServerResponse response = GetServerResponse.newBuilder()
        .setStatus("PENDING")
        .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private void checkAndUpdateStatus(String userId, String repoHash, BuildSession session,
      StreamObserver<GetServerResponse> responseObserver) {
    // Verify against Compute Service
    ComputeService.ContainerStatus status = computeService.getContainerStatus(userId, repoHash);

    if (status == null) {
      // Pod missing?
      logger.warning("Pod missing for session " + session.sessionId);
      GetServerResponse response = GetServerResponse.newBuilder()
          .setStatus("PENDING")
          .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
      return;
    }

    // Update DB if changed
    if (!status.getStatus().equals(session.status)
        || (status.getIpAddress() != null && !status.getIpAddress().equals(session.podIp))) {
      saveSession(userId, repoHash, session.sessionId, status.getIpAddress(), status.getStatus());
    }

    GetServerResponse.Builder responseBuilder = GetServerResponse.newBuilder()
        .setStatus(status.getStatus());
    if (status.getIpAddress() != null) {
      responseBuilder.setPodIp(status.getIpAddress());
    }

    responseObserver.onNext(responseBuilder.build());
    responseObserver.onCompleted();
  }

  // --- DB Helpers (Spanner) ---

  private static class BuildSession {
    String sessionId;
    String podIp;
    String status;

    BuildSession(String sessionId, String podIp, String status) {
      this.sessionId = sessionId;
      this.podIp = podIp;
      this.status = status;
    }
  }

  private BuildSession getSession(String userId, String repoHash) {
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

  private void saveSession(String userId, String repoHash, String sessionId, String podIp, String status) {
    dbClient.readWriteTransaction().run(new TransactionRunner.TransactionCallable<Void>() {
      @Override
      public Void run(TransactionContext transaction) throws Exception {
        String ipWrapper = podIp;
        transaction.buffer(
            com.google.cloud.spanner.Mutation.newInsertOrUpdateBuilder("BuildSessions")
                .set("UserId").to(userId)
                .set("RepoHash").to(repoHash)
                .set("SessionId").to(sessionId)
                .set("PodIP").to(ipWrapper)
                .set("Status").to(status)
                .build());
        return null;
      }
    });
  }
}
