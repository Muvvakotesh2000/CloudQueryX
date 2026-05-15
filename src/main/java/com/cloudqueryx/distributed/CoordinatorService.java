package com.cloudqueryx.distributed;

import com.cloudqueryx.catalog.Catalog;
import com.cloudqueryx.common.Row;
import com.cloudqueryx.execution.ExecutionEngine;
import com.cloudqueryx.execution.QueryContext;
import com.cloudqueryx.optimizer.Optimizer;
import com.cloudqueryx.parser.SqlParser;
import com.cloudqueryx.planner.logical.LogicalPlan;
import com.cloudqueryx.planner.physical.PhysicalPlan;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CoordinatorService extends com.cloudqueryx.proto.CoordinatorServiceGrpc.CoordinatorServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorService.class);

    private final Catalog catalog;
    private final SqlParser parser;
    private final Optimizer optimizer;
    private final ExecutionEngine executionEngine;
    private final ClusterManager clusterManager;
    private final QueryFragmenter fragmenter;
    private final TaskScheduler scheduler;
    private final Map<String, QueryState> activeQueries = new ConcurrentHashMap<>();
    private Server server;

    public CoordinatorService(Catalog catalog, int workerThreads) {
        this.catalog = catalog;
        this.parser = new SqlParser(catalog);
        this.optimizer = new Optimizer(catalog);
        this.executionEngine = new ExecutionEngine();
        this.clusterManager = new ClusterManager(UUID.randomUUID().toString());
        this.fragmenter = new QueryFragmenter();
        this.scheduler = new TaskScheduler(clusterManager, workerThreads);
    }

    public void start(int port) throws IOException {
        server = ServerBuilder.forPort(port)
                .addService(this)
                .build()
                .start();
        log.info("Coordinator started on port {}", port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down coordinator...");
            stop();
        }));
    }

    public void stop() {
        scheduler.shutdown();
        if (server != null) {
            server.shutdown();
        }
    }

    public void awaitTermination() throws InterruptedException {
        if (server != null) server.awaitTermination();
    }

    public ExecutionEngine.QueryResult executeQuery(String sql) {
        QueryContext context = new QueryContext(catalog);
        String queryId = context.getQueryId();

        log.info("Executing query {}: {}", queryId.substring(0, 8), sql.trim());

        try {
            LogicalPlan logicalPlan = parser.parse(sql);
            LogicalPlan optimizedPlan = optimizer.optimizeLogical(logicalPlan);
            PhysicalPlan physicalPlan = optimizer.createPhysicalPlan(optimizedPlan);

            return executionEngine.execute(physicalPlan, context);
        } catch (Exception e) {
            log.error("Query {} failed: {}", queryId.substring(0, 8), e.getMessage());
            throw e;
        }
    }

    @Override
    public void submitQuery(com.cloudqueryx.proto.QueryRequest request,
                            StreamObserver<com.cloudqueryx.proto.QueryResponse> responseObserver) {
        try {
            ExecutionEngine.QueryResult result = executeQuery(request.getSql());

            var responseBuilder = com.cloudqueryx.proto.QueryResponse.newBuilder()
                    .setQueryId(request.getQueryId())
                    .setState(com.cloudqueryx.proto.QueryState.FINISHED);

            for (int i = 0; i < result.schema().getColumnCount(); i++) {
                var col = result.schema().getColumn(i);
                responseBuilder.addColumns(com.cloudqueryx.proto.ColumnMetadata.newBuilder()
                        .setName(col.name())
                        .setNullable(col.nullable())
                        .build());
            }

            for (Row row : result.rows()) {
                var rowBuilder = com.cloudqueryx.proto.RowData.newBuilder();
                for (int i = 0; i < row.getColumnCount(); i++) {
                    var val = row.getValue(i);
                    var valueBuilder = com.cloudqueryx.proto.ValueProto.newBuilder();
                    if (val.isNull()) {
                        valueBuilder.setIsNull(true);
                    } else {
                        switch (val.getType()) {
                            case INTEGER -> valueBuilder.setIntValue(val.asInt());
                            case BIGINT -> valueBuilder.setLongValue(val.asLong());
                            case DOUBLE, DECIMAL -> valueBuilder.setDoubleValue(val.asDouble());
                            case BOOLEAN -> valueBuilder.setBoolValue(val.asBoolean());
                            default -> valueBuilder.setStringValue(val.asString());
                        }
                    }
                    rowBuilder.addValues(valueBuilder);
                }
                responseBuilder.addRows(rowBuilder);
            }

            var stats = com.cloudqueryx.proto.QueryStats.newBuilder()
                    .setElapsedMillis(result.totalMs())
                    .setPlanningMillis(result.planningMs())
                    .setExecutionMillis(result.executionMs())
                    .setRowsScanned(result.rowsScanned())
                    .setRowsReturned(result.rowsReturned())
                    .build();
            responseBuilder.setStats(stats);

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            responseObserver.onNext(com.cloudqueryx.proto.QueryResponse.newBuilder()
                    .setQueryId(request.getQueryId())
                    .setState(com.cloudqueryx.proto.QueryState.FAILED)
                    .setErrorMessage(e.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void getClusterInfo(com.cloudqueryx.proto.ClusterInfoRequest request,
                               StreamObserver<com.cloudqueryx.proto.ClusterInfoResponse> responseObserver) {
        var response = com.cloudqueryx.proto.ClusterInfoResponse.newBuilder()
                .setCoordinatorCount(1)
                .setWorkerCount(clusterManager.getWorkerCount());

        for (var worker : clusterManager.getActiveWorkers()) {
            response.addNodes(com.cloudqueryx.proto.NodeInfo.newBuilder()
                    .setNodeId(worker.nodeId)
                    .setHost(worker.host)
                    .setPort(worker.port)
                    .setRole(com.cloudqueryx.proto.NodeRole.WORKER)
                    .setState(com.cloudqueryx.proto.NodeState.ACTIVE)
                    .build());
        }

        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    public Catalog getCatalog() { return catalog; }
    public ClusterManager getClusterManager() { return clusterManager; }

    private static class QueryState {
        final String queryId;
        volatile com.cloudqueryx.proto.QueryState state;
        volatile String error;

        QueryState(String queryId) {
            this.queryId = queryId;
            this.state = com.cloudqueryx.proto.QueryState.QUEUED;
        }
    }
}
