package com.example.rbs;

import com.example.rbs.proto.GetServerRequest;
import com.example.rbs.proto.GetServerResponse;
import com.example.rbs.proto.HeartbeatRequest;
import com.example.rbs.proto.HeartbeatResponse;
import com.example.rbs.proto.OrchestratorGrpc;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.TransactionRunner;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class OrchestratorService extends OrchestratorGrpc.OrchestratorImplBase {
  private static final Logger logger = Logger.getLogger(OrchestratorService.class.getName());

  private final SessionRepository sessionRepo;
  private final ComputeService computeService;
  private final ScheduledExecutorService reaperExecutor;

  public OrchestratorService(SessionRepository sessionRepo, ComputeService computeService) {
    this.sessionRepo = sessionRepo;
    this.computeService = computeService;
    this.reaperExecutor = Executors.newSingleThreadScheduledExecutor();
    this.reaperExecutor.scheduleAtFixedRate(this::reapStaleSessions, 1, 1, TimeUnit.MINUTES);
  }

  @Override
  public void getServer(GetServerRequest request, StreamObserver<GetServerResponse> responseObserver) {
    String userId = request.getUserId();
    String repoHash = request.getRepoHash();
    String clientSessionId = request.getSessionId(); // From client (Proxy)
    String sourcePath = request.getSourcePath();
    String region = request.getRegion();

    logger.info(
        "Received GetServer request for User: " + userId + ", Repo: " + repoHash + " (Session: " + clientSessionId
            + ")");

    try {
      if (clientSessionId.isEmpty()) {
        // Should not happen for new Proxy logic, but strictly if missing we could gen
        // one.
        // But strict mirroring requires client to own identity.
        logger.warning("Missing sessionId in request");
        responseObserver
            .onError(io.grpc.Status.INVALID_ARGUMENT.withDescription("SessionId required").asRuntimeException());
        return;
      }

      SessionRepository.BuildSession session = sessionRepo.getSession(clientSessionId);

      if (session != null) {
        // Session exists.
        // Check actual status of pod.
        checkAndUpdateStatus(userId, repoHash, session, responseObserver);
        return;
      } else {
        // No session exists for this SessionID. Create new.
        handleNewSession(userId, repoHash, clientSessionId, sourcePath,
            request.getStartupOptionsList(), region, responseObserver);
        return;
      }
    } catch (Exception e) {
      logger.severe("Error handling GetServer: " + e.getMessage());
      responseObserver.onError(io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
    }
  }

  private void handleNewSession(String userId, String repoHash, String sessionId, String sourcePath,
      java.util.List<String> startupOptions, String region, StreamObserver<GetServerResponse> responseObserver) {
    // Create Pod
    computeService.createContainer(userId, repoHash, sessionId, sourcePath, startupOptions, region);

    // Save session 'PENDING'
    sessionRepo.saveSession(userId, repoHash, sessionId, null, "PENDING");

    GetServerResponse response = GetServerResponse.newBuilder()
        .setStatus("PENDING")
        .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private void checkAndUpdateStatus(String userId, String repoHash, SessionRepository.BuildSession session,
      StreamObserver<GetServerResponse> responseObserver) {
    // Verify against Compute Service
    ComputeService.ContainerStatus status = computeService.getContainerStatus(userId, repoHash, session.sessionId);

    if (status == null) {
      // Pod missing?
      logger.warning("Pod missing for session " + session.sessionId);
      GetServerResponse response = GetServerResponse.newBuilder()
          .setStatus("PENDING") // Or LOST? Client will retry or fail. PENDING makes sense if creating.
          .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
      return;
    }

    // Update DB if changed
    if (!status.getStatus().equals(session.status)
        || (status.getAddress() != null && !status.getAddress().equals(session.serverAddress))) {
      sessionRepo.saveSession(userId, repoHash, session.sessionId, status.getAddress(), status.getStatus());
    }

    GetServerResponse.Builder responseBuilder = GetServerResponse.newBuilder()
        .setStatus(status.getStatus());
    if (status.getAddress() != null) {
      responseBuilder.setServerAddress(status.getAddress());
    }

    responseObserver.onNext(responseBuilder.build());
    responseObserver.onCompleted();
  }

  @Override
  public void heartbeat(HeartbeatRequest request, StreamObserver<HeartbeatResponse> responseObserver) {
    String sessionId = request.getSessionId();
    sessionRepo.updateHeartbeat(sessionId);
    responseObserver.onNext(HeartbeatResponse.newBuilder().build());
    responseObserver.onCompleted();
  }

  void reapStaleSessions() {
    try {
      // 5 minutes stale threshold
      long staleThreshold = 5 * 60 * 1000;
      java.util.List<SessionRepository.BuildSession> staleSessions = sessionRepo.getStaleSessions(staleThreshold);
      for (SessionRepository.BuildSession session : staleSessions) {
        logger.info("Reaping stale session: " + session.sessionId + " (User: " + session.userId + ")");
        computeService.deleteContainer(session.userId, session.repoHash, session.sessionId);
        sessionRepo.deleteSession(session.sessionId);
      }
    } catch (Exception e) {
      logger.severe("Error in reaper task: " + e.getMessage());
    }
  }

  // --- DB Helpers moved to SessionRepository implementations ---
}
