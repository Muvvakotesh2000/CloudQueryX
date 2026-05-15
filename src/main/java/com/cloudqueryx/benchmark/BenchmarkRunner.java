package com.cloudqueryx.benchmark;

import com.cloudqueryx.catalog.Catalog;
import com.cloudqueryx.catalog.TableDescriptor;
import com.cloudqueryx.common.*;
import com.cloudqueryx.distributed.CoordinatorService;
import com.cloudqueryx.execution.ExecutionEngine;
import com.cloudqueryx.memory.*;
import com.cloudqueryx.vector.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class BenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);

    private final Catalog catalog;
    private final CoordinatorService coordinator;
    private final VectorStore vectorStore;
    private final MemoryStore memoryStore;
    private final List<BenchmarkResult> results = new ArrayList<>();

    public BenchmarkRunner() {
        this.catalog = new Catalog();
        this.coordinator = new CoordinatorService(catalog, 4);
        this.vectorStore = new VectorStore(128);
        this.memoryStore = new MemoryStore(vectorStore);
    }

    public void generateSqlData(int customerCount, int orderCount, int productCount) {
        log.info("Generating benchmark data: {} customers, {} orders, {} products",
                customerCount, orderCount, productCount);
        Random rng = new Random(42);
        String[] cities = {"NYC", "LA", "Chicago", "Houston", "Phoenix", "Philadelphia", "San Antonio", "San Diego"};

        Schema customersSchema = new Schema(List.of(
                new Column("id", DataType.INTEGER, false, "customers"),
                new Column("name", DataType.VARCHAR, false, "customers"),
                new Column("city", DataType.VARCHAR, true, "customers"),
                new Column("age", DataType.INTEGER, true, "customers"),
                new Column("balance", DataType.DOUBLE, true, "customers")
        ));
        TableDescriptor customers = new TableDescriptor("customers", customersSchema);
        for (int i = 1; i <= customerCount; i++) {
            customers.insertRow(Row.of(
                    SqlValue.ofInt(i), SqlValue.ofString("Customer_" + i),
                    SqlValue.ofString(cities[rng.nextInt(cities.length)]),
                    SqlValue.ofInt(18 + rng.nextInt(62)),
                    SqlValue.ofDouble(Math.round(rng.nextDouble() * 100000 * 100.0) / 100.0)
            ));
        }
        customers.computeStatistics();
        catalog.registerTable(customers);

        String[] products = new String[productCount];
        for (int i = 0; i < productCount; i++) products[i] = "Product_" + (i + 1);

        Schema ordersSchema = new Schema(List.of(
                new Column("id", DataType.INTEGER, false, "orders"),
                new Column("customer_id", DataType.INTEGER, false, "orders"),
                new Column("product", DataType.VARCHAR, false, "orders"),
                new Column("amount", DataType.DOUBLE, false, "orders"),
                new Column("quantity", DataType.INTEGER, false, "orders")
        ));
        TableDescriptor orders = new TableDescriptor("orders", ordersSchema);
        for (int i = 1; i <= orderCount; i++) {
            orders.insertRow(Row.of(
                    SqlValue.ofInt(i), SqlValue.ofInt(1 + rng.nextInt(customerCount)),
                    SqlValue.ofString(products[rng.nextInt(productCount)]),
                    SqlValue.ofDouble(Math.round(rng.nextDouble() * 2000 * 100.0) / 100.0),
                    SqlValue.ofInt(1 + rng.nextInt(20))
            ));
        }
        orders.computeStatistics();
        catalog.registerTable(orders);
        log.info("Data generation complete");
    }

    public void generateVectorData(int count, int dimension) {
        log.info("Generating {} vector records with dimension {}", count, dimension);
        Random rng = new Random(42);
        for (int i = 0; i < count; i++) {
            float[] embedding = new float[dimension];
            for (int j = 0; j < dimension; j++) embedding[j] = rng.nextFloat() * 2 - 1;
            vectorStore.insert("benchmark", new VectorRecord(
                    "vec_" + i, "benchmark", embedding,
                    Map.of("category", "cat_" + (i % 10)), "content_" + i, null, null
            ));
        }
        log.info("Vector data generation complete");
    }

    public void generateMemoryData(int count) {
        log.info("Generating {} memory records", count);
        Random rng = new Random(42);
        MemoryType[] types = MemoryType.values();
        for (int i = 0; i < count; i++) {
            float[] embedding = new float[128];
            for (int j = 0; j < 128; j++) embedding[j] = rng.nextFloat() * 2 - 1;
            memoryStore.store(new MemoryRecord(
                    "mem_" + i, "bench_user", "default",
                    types[rng.nextInt(types.length)],
                    "Memory content #" + i, Map.of("source", "benchmark"),
                    embedding, rng.nextDouble(), 1.0, 1.0, "benchmark",
                    null, null, null, 0
            ));
        }
        log.info("Memory data generation complete");
    }

    public void runSqlBenchmarks() {
        log.info("=== SQL Benchmark Suite ===");

        benchmark("Simple scan", "SELECT * FROM customers");
        benchmark("Filtered scan", "SELECT * FROM customers WHERE age > 40");
        benchmark("Projection", "SELECT name, city FROM customers WHERE balance > 50000");
        benchmark("Aggregate COUNT", "SELECT COUNT(*) FROM orders");
        benchmark("Group by", "SELECT city, COUNT(*), AVG(age) FROM customers GROUP BY city");
        benchmark("Sort + limit", "SELECT name, age FROM customers ORDER BY age DESC LIMIT 20");
        benchmark("Join", "SELECT c.name, o.product, o.amount FROM customers c JOIN orders o ON c.id = o.customer_id WHERE o.amount > 1000");
        benchmark("Join + group by + sort", "SELECT c.city, SUM(o.amount) FROM customers c JOIN orders o ON c.id = o.customer_id GROUP BY c.city ORDER BY SUM(o.amount) DESC");
        benchmark("Complex filter", "SELECT * FROM customers WHERE age BETWEEN 25 AND 45 AND city IN ('NYC', 'LA', 'Chicago')");
        benchmark("LIKE filter", "SELECT name FROM customers WHERE name LIKE 'Customer_1%'");
    }

    public void runVectorBenchmarks() {
        log.info("=== Vector Search Benchmark Suite ===");
        Random rng = new Random(123);
        float[] query = new float[128];
        for (int j = 0; j < 128; j++) query[j] = rng.nextFloat() * 2 - 1;

        long start = System.nanoTime();
        List<VectorSearchResult> results = vectorStore.search("benchmark", query, 10, SimilarityMetric.COSINE);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        this.results.add(new BenchmarkResult("Vector cosine top-10", elapsed, results.size(), vectorStore.totalRecords()));
        log.info("Vector cosine top-10: {}ms, {} results from {} records",
                elapsed, results.size(), vectorStore.totalRecords());

        start = System.nanoTime();
        results = vectorStore.hybridSearch("benchmark", query, 10, SimilarityMetric.COSINE, "content_5", null);
        elapsed = (System.nanoTime() - start) / 1_000_000;
        this.results.add(new BenchmarkResult("Hybrid vector+keyword", elapsed, results.size(), vectorStore.totalRecords()));
        log.info("Hybrid vector+keyword: {}ms, {} results", elapsed, results.size());
    }

    public void runMemoryBenchmarks() {
        log.info("=== Memory Recall Benchmark Suite ===");
        Random rng = new Random(456);
        float[] query = new float[128];
        for (int j = 0; j < 128; j++) query[j] = rng.nextFloat() * 2 - 1;

        long start = System.nanoTime();
        List<MemoryRecallResult> results = memoryStore.recallSimilar("default", query, 10, null);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        this.results.add(new BenchmarkResult("Memory recall top-10", elapsed, results.size(), memoryStore.size()));
        log.info("Memory recall top-10: {}ms, {} results from {} memories",
                elapsed, results.size(), memoryStore.size());

        start = System.nanoTime();
        results = memoryStore.recallSimilar("default", query, 10, MemoryType.FACT);
        elapsed = (System.nanoTime() - start) / 1_000_000;
        this.results.add(new BenchmarkResult("Memory recall filtered by type", elapsed, results.size(), memoryStore.size()));
        log.info("Memory recall filtered: {}ms, {} results", elapsed, results.size());
    }

    private void benchmark(String name, String sql) {
        long start = System.nanoTime();
        ExecutionEngine.QueryResult result = coordinator.executeQuery(sql);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        results.add(new BenchmarkResult(name, elapsed, result.rows().size(), result.rowsScanned()));
        log.info("{}: {}ms, {} rows returned, {} rows scanned",
                name, elapsed, result.rows().size(), result.rowsScanned());
    }

    public void printReport() {
        System.out.println("\n=== Benchmark Report ===\n");
        System.out.printf("%-40s %10s %12s %12s%n", "Benchmark", "Time (ms)", "Rows Out", "Rows Scanned");
        System.out.println("-".repeat(76));
        for (BenchmarkResult r : results) {
            System.out.printf("%-40s %10d %12d %12d%n", r.name, r.timeMs, r.rowsReturned, r.rowsScanned);
        }
        System.out.println();
    }

    public record BenchmarkResult(String name, long timeMs, long rowsReturned, long rowsScanned) {}

    public static void main(String[] args) {
        BenchmarkRunner runner = new BenchmarkRunner();

        int customers = 10000, orders = 50000, products = 20;
        int vectors = 10000, memories = 5000;

        if (args.length > 0) customers = Integer.parseInt(args[0]);
        if (args.length > 1) orders = Integer.parseInt(args[1]);

        runner.generateSqlData(customers, orders, products);
        runner.generateVectorData(vectors, 128);
        runner.generateMemoryData(memories);

        runner.runSqlBenchmarks();
        runner.runVectorBenchmarks();
        runner.runMemoryBenchmarks();
        runner.printReport();
    }
}
