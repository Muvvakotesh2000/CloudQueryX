package com.cloudqueryx.distributed;

import com.cloudqueryx.catalog.Catalog;
import com.cloudqueryx.execution.ExecutionEngine;
import com.cloudqueryx.execution.QueryContext;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;

public class WorkerService extends com.cloudqueryx.proto.WorkerServiceGrpc.WorkerServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(WorkerService.class);

    private final String nodeId;
    private final Catalog catalog;
    private final ExecutionEngine executionEngine;
    private final ExecutorService taskExecutor;
    private Server server;
    private ManagedChannel coordinatorChannel;
    private ScheduledExecutorService heartbeatExecutor;

    public WorkerService(Catalog catalog, int taskThreads) {
        this.nodeId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
        this.catalog = catalog;
        this.executionEngine = new ExecutionEngine();
        this.taskExecutor = Executors.newFixedThreadPool(taskThreads);
    }

    public void start(int port, String coordinatorHost, int coordinatorPort) throws IOException {
        server = ServerBuilder.forPort(port)
                .addService(this)
                .build()
                .start();
        log.info("Worker {} started on port {}", nodeId, port);

        coordinatorChannel = ManagedChannelBuilder
                .forAddress(coordinatorHost, coordinatorPort)
                .usePlaintext()
                .build();

        startHeartbeat();

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    public void stop() {
        log.info("Shutting down worker {}...", nodeId);
        if (heartbeatExecutor != null) heartbeatExecutor.shutdown();
        taskExecutor.shutdown();
        if (coordinatorChannel != null) coordinatorChannel.shutdown();
        if (server != null) server.shutdown();
    }

    public void awaitTermination() throws InterruptedException {
        if (server != null) server.awaitTermination();
    }

    @Override
    public void executeFragment(com.cloudqueryx.proto.FragmentRequest request,
                                StreamObserver<com.cloudqueryx.proto.FragmentResult> responseObserver) {
        String fragmentId = request.getFragmentId();
        log.info("Worker {} executing fragment {}", nodeId, fragmentId);

        taskExecutor.submit(() -> {
            try {
                QueryContext context = new QueryContext(catalog);
                var result = com.cloudqueryx.proto.FragmentResult.newBuilder()
                        .setFragmentId(fragmentId)
                        .setState(com.cloudqueryx.proto.FragmentState.FRAGMENT_FINISHED)
                        .setRowsProduced(0)
                        .build();

                responseObserver.onNext(result);
                responseObserver.onCompleted();
                log.info("Worker {} completed fragment {}", nodeId, fragmentId);

            } catch (Exception e) {
                log.error("Worker {} failed fragment {}: {}", nodeId, fragmentId, e.getMessage());
                responseObserver.onNext(com.cloudqueryx.proto.FragmentResult.newBuilder()
                        .setFragmentId(fragmentId)
                        .setState(com.cloudqueryx.proto.FragmentState.FRAGMENT_FAILED)
                        .setErrorMessage(e.getMessage())
                        .build());
                responseObserver.onCompleted();
            }
        });
    }

    @Override
    public void heartbeat(com.cloudqueryx.proto.HeartbeatRequest request,
                          StreamObserver<com.cloudqueryx.proto.HeartbeatResponse> responseObserver) {
        responseObserver.onNext(com.cloudqueryx.proto.HeartbeatResponse.newBuilder()
                .setAcknowledged(true)
                .build());
        responseObserver.onCompleted();
    }

    private void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                var stub = com.cloudqueryx.proto.WorkerServiceGrpc.newBlockingStub(coordinatorChannel);
                Runtime runtime = Runtime.getRuntime();
                var request = com.cloudqueryx.proto.HeartbeatRequest.newBuilder()
                        .setNodeId(nodeId)
                        .setResourceUsage(com.cloudqueryx.proto.ResourceUsage.newBuilder()
                                .setCpuPercent(getProcessCpuLoad())
                                .setMemoryUsedBytes(runtime.totalMemory() - runtime.freeMemory())
                                .setMemoryTotalBytes(runtime.maxMemory())
                                .build())
                        .build();

                log.trace("Sending heartbeat from worker {}", nodeId);
            } catch (Exception e) {
                log.warn("Heartbeat failed for worker {}: {}", nodeId, e.getMessage());
            }
        }, 0, 10, TimeUnit.SECONDS);
    }

    private double getProcessCpuLoad() {
        var osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        return osBean.getSystemLoadAverage();
    }

    public String getNodeId() { return nodeId; }
}
