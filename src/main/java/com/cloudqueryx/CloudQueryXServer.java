package com.cloudqueryx;

import com.cloudqueryx.benchmark.BenchmarkRunner;
import com.cloudqueryx.catalog.Catalog;
import com.cloudqueryx.cli.CloudQueryXCli;
import com.cloudqueryx.config.AppConfig;
import com.cloudqueryx.distributed.CoordinatorService;
import com.cloudqueryx.distributed.WorkerService;
import com.cloudqueryx.web.api.ApiServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class CloudQueryXServer {

    private static final Logger log = LoggerFactory.getLogger(CloudQueryXServer.class);
    private static final String VERSION = "1.0.0";

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0].toLowerCase() : "standalone";

        log.info("Starting CloudQueryX v{} in {} mode", VERSION, mode);

        switch (mode) {
            case "standalone" -> runStandalone();
            case "coordinator" -> runCoordinator(args);
            case "worker" -> runWorker(args);
            case "web" -> runWebServer(args);
            case "benchmark" -> runBenchmark(args);
            case "version" -> System.out.println("CloudQueryX v" + VERSION);
            case "help" -> printUsage();
            default -> {
                System.err.println("Unknown mode: " + mode);
                printUsage();
                System.exit(1);
            }
        }
    }

    private static void runStandalone() throws IOException {
        log.info("Running in standalone mode (embedded coordinator + CLI)");
        Catalog catalog = new Catalog();
        CoordinatorService coordinator = new CoordinatorService(catalog, 4);
        CloudQueryXCli cli = new CloudQueryXCli(coordinator);
        cli.run();
    }

    private static void runCoordinator(String[] args) throws IOException, InterruptedException {
        int port = 9090;
        if (args.length > 1) port = Integer.parseInt(args[1]);

        log.info("Starting coordinator on port {}", port);
        Catalog catalog = new Catalog();
        CoordinatorService coordinator = new CoordinatorService(catalog, 8);
        coordinator.start(port);
        log.info("Coordinator ready. Waiting for workers...");
        coordinator.awaitTermination();
    }

    private static void runWorker(String[] args) throws IOException, InterruptedException {
        int port = 9091;
        String coordinatorHost = "localhost";
        int coordinatorPort = 9090;

        if (args.length > 1) port = Integer.parseInt(args[1]);
        if (args.length > 2) coordinatorHost = args[2];
        if (args.length > 3) coordinatorPort = Integer.parseInt(args[3]);

        log.info("Starting worker on port {}, coordinator at {}:{}",
                port, coordinatorHost, coordinatorPort);

        Catalog catalog = new Catalog();
        WorkerService worker = new WorkerService(catalog, 4);
        worker.start(port, coordinatorHost, coordinatorPort);
        log.info("Worker {} ready", worker.getNodeId());
        worker.awaitTermination();
    }

    private static void runWebServer(String[] args) throws IOException {
        AppConfig config = AppConfig.getInstance();
        int port = args.length > 1 ? Integer.parseInt(args[1]) : config.serverPort();

        log.info("Starting web server on port {}", port);
        log.info("Database: {}", config.dbUrl());
        ApiServer apiServer = new ApiServer(port);
        apiServer.start();
        log.info("CloudQueryX AI Context Engine running at http://localhost:{}", port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            apiServer.stop();
        }));

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runBenchmark(String[] args) {
        BenchmarkRunner runner = new BenchmarkRunner();
        int customers = 10000, orders = 50000;
        if (args.length > 1) customers = Integer.parseInt(args[1]);
        if (args.length > 2) orders = Integer.parseInt(args[2]);
        runner.generateSqlData(customers, orders, 20);
        runner.generateVectorData(10000, 128);
        runner.generateMemoryData(5000);
        runner.runSqlBenchmarks();
        runner.runVectorBenchmarks();
        runner.runMemoryBenchmarks();
        runner.printReport();
    }

    private static void printUsage() {
        System.out.println("""
                Usage: cloudqueryx <mode> [options]

                Modes:
                  standalone                          Interactive SQL shell (default)
                  coordinator [port]                  Start coordinator node
                  worker [port] [coord-host] [coord-port]  Start worker node
                  web [port]                          Start web server (default: 8080)
                  benchmark [customers] [orders]      Run benchmark suite
                  version                             Show version
                  help                                Show this help

                Environment variables (or .env file):
                  CLOUDQUERYX_DB_URL                  PostgreSQL JDBC URL
                  CLOUDQUERYX_DB_USER                 Database username
                  CLOUDQUERYX_DB_PASSWORD             Database password
                  CLOUDQUERYX_PORT                    Server port (default: 8080)
                  CLOUDQUERYX_VECTOR_DIMENSION        Embedding dimension (default: 384)

                Examples:
                  cloudqueryx web                     Start with .env config
                  cloudqueryx web 8080                Start web server on port 8080
                  cloudqueryx coordinator 9090         Start coordinator on port 9090
                  cloudqueryx worker 9091 localhost 9090  Start worker
                """);
    }
}
