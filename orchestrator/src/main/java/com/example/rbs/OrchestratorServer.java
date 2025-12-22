package com.example.rbs;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class OrchestratorServer {
    private static final Logger logger = Logger.getLogger(OrchestratorServer.class.getName());

    private Server server;

    private void start() throws IOException {
        /* The port on which the server should run */
        int port = 50051;

        // Initialize Spanner Client
        SpannerOptions options = SpannerOptions.newBuilder().setEmulatorHost("localhost:9010").build();
        Spanner spanner = options.getService();
        String projectId = options.getProjectId();
        String instanceId = "test-instance";
        String databaseId = "test-database";

        // Create Instance if not exists (for Emulator)
        try {
            com.google.cloud.spanner.InstanceConfigId configId = com.google.cloud.spanner.InstanceConfigId.of(projectId,
                    "emulator-config");
            com.google.cloud.spanner.InstanceId instanceIdObj = com.google.cloud.spanner.InstanceId.of(projectId,
                    instanceId);
            com.google.cloud.spanner.InstanceInfo instanceInfo = com.google.cloud.spanner.InstanceInfo
                    .newBuilder(instanceIdObj)
                    .setInstanceConfigId(configId)
                    .setDisplayName("Test Instance")
                    .setNodeCount(1)
                    .build();

            spanner.getInstanceAdminClient().createInstance(instanceInfo).get();
        } catch (Exception e) {
            // Ignore if already exists (or other errors, assuming check later)
            // Real usage would check existence first
            logger.info("Instance creation failed (may already exist): " + e.getMessage());
        }

        // Create Database if not exists
        try {
            spanner.getDatabaseAdminClient().createDatabase(
                    instanceId,
                    databaseId,
                    java.util.Collections.singletonList(
                            "CREATE TABLE BuildSessions (" +
                                    "    UserId STRING(MAX) NOT NULL," +
                                    "    RepoHash STRING(MAX) NOT NULL," +
                                    "    SessionId STRING(MAX)," +
                                    "    PodIP STRING(MAX)," +
                                    "    Status STRING(MAX)" +
                                    ") PRIMARY KEY (UserId, RepoHash)"))
                    .get();
        } catch (Exception e) {
            logger.info("Database creation failed (may already exist): " + e.getMessage());
        }

        DatabaseId dbId = DatabaseId.of(projectId, instanceId, databaseId);
        DatabaseClient dbClient = spanner.getDatabaseClient(dbId);

        // Initialize Kubernetes Client
        KubernetesClient k8sClient = new KubernetesClientBuilder().build();
        ComputeService computeService = new KubernetesComputeService(k8sClient);

        server = ServerBuilder.forPort(port)
                .addService(new OrchestratorService(dbClient, computeService))
                .build()
                .start();
        logger.info("Server started, listening on " + port);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                // Use stderr here since the logger may have been reset by its JVM shutdown
                // hook.
                System.err.println("*** shutting down gRPC server since JVM is shutting down");
                try {
                    OrchestratorServer.this.stop();
                } catch (InterruptedException e) {
                    e.printStackTrace(System.err);
                }
                System.err.println("*** server shut down");
            }
        });
    }

    private void stop() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon
     * threads.
     */
    private void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        final OrchestratorServer server = new OrchestratorServer();
        server.start();
        server.blockUntilShutdown();
    }
}
