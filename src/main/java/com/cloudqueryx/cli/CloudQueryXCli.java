package com.cloudqueryx.cli;

import com.cloudqueryx.catalog.Catalog;
import com.cloudqueryx.common.*;
import com.cloudqueryx.catalog.TableDescriptor;
import com.cloudqueryx.distributed.CoordinatorService;
import com.cloudqueryx.execution.ExecutionEngine;
import com.cloudqueryx.storage.CsvDataSource;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

public class CloudQueryXCli {

    private static final String BANNER = """

             ╔═══════════════════════════════════════════════════════════╗
             ║              CloudQueryX v1.0.0                          ║
             ║       Distributed SQL Query Engine                       ║
             ║       Cost-Based Optimizer | MPP Execution               ║
             ╚═══════════════════════════════════════════════════════════╝
            """;

    private static final String HELP = """
            Commands:
              SQL statements   - Execute SQL queries (SELECT, CREATE TABLE, INSERT, etc.)
              \\tables          - List all tables
              \\schema <table>  - Show table schema
              \\stats <table>   - Show table statistics
              \\explain <sql>   - Show query execution plan
              \\load <file> <t> - Load CSV file as table
              \\benchmark       - Run benchmark queries on sample data
              \\help            - Show this help message
              \\quit            - Exit the CLI
            """;

    private final CoordinatorService coordinator;

    public CloudQueryXCli(CoordinatorService coordinator) {
        this.coordinator = coordinator;
    }

    public void run() throws IOException {
        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build();

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .parser(new DefaultParser())
                .build();

        System.out.println(BANNER);
        System.out.println("Type \\help for available commands.\n");

        loadSampleData();

        StringBuilder multiLineQuery = new StringBuilder();

        while (true) {
            String prompt = multiLineQuery.isEmpty() ? "cloudqueryx> " : "         ... ";
            String line;
            try {
                line = reader.readLine(prompt);
            } catch (UserInterruptException e) {
                if (!multiLineQuery.isEmpty()) {
                    multiLineQuery.setLength(0);
                    System.out.println("Query cancelled.");
                    continue;
                }
                break;
            } catch (EndOfFileException e) {
                break;
            }

            if (line == null || line.isBlank()) continue;

            String trimmed = line.trim();

            if (trimmed.startsWith("\\")) {
                handleCommand(trimmed);
                continue;
            }

            multiLineQuery.append(line).append(" ");

            if (trimmed.endsWith(";")) {
                String sql = multiLineQuery.toString().trim();
                if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1);
                multiLineQuery.setLength(0);
                executeAndPrint(sql);
            }
        }

        System.out.println("\nGoodbye!");
    }

    private void handleCommand(String command) {
        String[] parts = command.split("\\s+", 3);
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "\\quit", "\\q", "\\exit" -> System.exit(0);
            case "\\help", "\\h" -> System.out.println(HELP);
            case "\\tables" -> listTables();
            case "\\schema" -> {
                if (parts.length < 2) { System.out.println("Usage: \\schema <table>"); return; }
                showSchema(parts[1]);
            }
            case "\\stats" -> {
                if (parts.length < 2) { System.out.println("Usage: \\stats <table>"); return; }
                showStats(parts[1]);
            }
            case "\\explain" -> {
                if (parts.length < 2) { System.out.println("Usage: \\explain <sql>"); return; }
                explainQuery(command.substring(command.indexOf(' ') + 1));
            }
            case "\\load" -> {
                if (parts.length < 3) { System.out.println("Usage: \\load <file> <table>"); return; }
                loadCsv(parts[1], parts[2]);
            }
            case "\\benchmark" -> runBenchmark();
            default -> System.out.println("Unknown command: " + cmd + ". Type \\help for help.");
        }
    }

    private void executeAndPrint(String sql) {
        try {
            long start = System.currentTimeMillis();
            ExecutionEngine.QueryResult result = coordinator.executeQuery(sql);
            System.out.println(ResultFormatter.formatTable(result));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    private void explainQuery(String sql) {
        try {
            var parser = new com.cloudqueryx.parser.SqlParser(coordinator.getCatalog());
            var optimizer = new com.cloudqueryx.optimizer.Optimizer(coordinator.getCatalog());
            var logicalPlan = parser.parse(sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql);

            System.out.println("=== Logical Plan ===");
            System.out.println(logicalPlan.explain());

            var optimizedPlan = optimizer.optimizeLogical(logicalPlan);
            System.out.println("=== Optimized Logical Plan ===");
            System.out.println(optimizedPlan.explain());

            var physicalPlan = optimizer.createPhysicalPlan(optimizedPlan);
            System.out.println("=== Physical Plan ===");
            System.out.println(physicalPlan.explain());

            var cost = optimizer.getCostModel().estimateCost(optimizedPlan);
            System.out.printf("Estimated cost: %.1f | Estimated rows: %d%n%n", cost.cost(), cost.rowCount());
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    private void listTables() {
        var tables = coordinator.getCatalog().getAllTables();
        if (tables.isEmpty()) {
            System.out.println("No tables found.");
            return;
        }
        System.out.println("\n  Tables:");
        for (var table : tables) {
            System.out.printf("    %-20s %d columns, %d rows%n",
                    table.getName(),
                    table.getSchema().getColumnCount(),
                    table.getStatistics().getRowCount());
        }
        System.out.println();
    }

    private void showSchema(String tableName) {
        try {
            var table = coordinator.getCatalog().getTable(tableName);
            System.out.println("\n  Schema for " + tableName + ":");
            for (var col : table.getSchema().getColumns()) {
                System.out.printf("    %-20s %-10s %s%n",
                        col.name(), col.type(), col.nullable() ? "NULLABLE" : "NOT NULL");
            }
            System.out.println();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    private void showStats(String tableName) {
        try {
            var table = coordinator.getCatalog().getTable(tableName);
            table.computeStatistics();
            var stats = table.getStatistics();
            System.out.println("\n  Statistics for " + tableName + ":");
            System.out.printf("    Rows: %d%n", stats.getRowCount());
            System.out.printf("    Size: %d bytes%n", stats.getSizeInBytes());
            for (var col : table.getSchema().getColumns()) {
                var colStats = stats.getColumnStatistics(col.name());
                System.out.printf("    %-20s distinct=%d, nulls=%d%n",
                        col.name(), colStats.getDistinctCount(), colStats.getNullCount());
            }
            System.out.println();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    private void loadCsv(String filePath, String tableName) {
        try {
            CsvDataSource.loadCsv(Path.of(filePath), tableName, coordinator.getCatalog());
            System.out.println("Loaded table: " + tableName);
        } catch (Exception e) {
            System.err.println("Error loading CSV: " + e.getMessage());
        }
    }

    private void loadSampleData() {
        Catalog catalog = coordinator.getCatalog();
        System.out.println("Loading sample dataset...");

        Schema customersSchema = new Schema(List.of(
                new Column("id", DataType.INTEGER, false, "customers"),
                new Column("name", DataType.VARCHAR, false, "customers"),
                new Column("email", DataType.VARCHAR, true, "customers"),
                new Column("city", DataType.VARCHAR, true, "customers"),
                new Column("age", DataType.INTEGER, true, "customers")
        ));
        TableDescriptor customers = new TableDescriptor("customers", customersSchema);

        String[] names = {"Alice", "Bob", "Charlie", "Diana", "Eve", "Frank", "Grace", "Henry", "Ivy", "Jack"};
        String[] cities = {"New York", "San Francisco", "Chicago", "Boston", "Seattle", "Austin", "Denver", "Miami"};
        Random rng = new Random(42);
        for (int i = 1; i <= 1000; i++) {
            String name = names[rng.nextInt(names.length)] + "_" + i;
            customers.insertRow(Row.of(
                    SqlValue.ofInt(i),
                    SqlValue.ofString(name),
                    SqlValue.ofString(name.toLowerCase() + "@example.com"),
                    SqlValue.ofString(cities[rng.nextInt(cities.length)]),
                    SqlValue.ofInt(20 + rng.nextInt(50))
            ));
        }
        catalog.registerTable(customers);

        Schema ordersSchema = new Schema(List.of(
                new Column("id", DataType.INTEGER, false, "orders"),
                new Column("customer_id", DataType.INTEGER, false, "orders"),
                new Column("product", DataType.VARCHAR, false, "orders"),
                new Column("amount", DataType.DOUBLE, false, "orders"),
                new Column("status", DataType.VARCHAR, true, "orders")
        ));
        TableDescriptor orders = new TableDescriptor("orders", ordersSchema);

        String[] products = {"Laptop", "Phone", "Tablet", "Monitor", "Keyboard", "Mouse", "Headphones", "Camera"};
        String[] statuses = {"completed", "pending", "shipped", "cancelled"};
        for (int i = 1; i <= 5000; i++) {
            orders.insertRow(Row.of(
                    SqlValue.ofInt(i),
                    SqlValue.ofInt(1 + rng.nextInt(1000)),
                    SqlValue.ofString(products[rng.nextInt(products.length)]),
                    SqlValue.ofDouble(Math.round(rng.nextDouble() * 2000 * 100.0) / 100.0),
                    SqlValue.ofString(statuses[rng.nextInt(statuses.length)])
            ));
        }
        catalog.registerTable(orders);

        Schema productsSchema = new Schema(List.of(
                new Column("id", DataType.INTEGER, false, "products"),
                new Column("name", DataType.VARCHAR, false, "products"),
                new Column("category", DataType.VARCHAR, false, "products"),
                new Column("price", DataType.DOUBLE, false, "products")
        ));
        TableDescriptor productsTable = new TableDescriptor("products", productsSchema);
        String[] categories = {"Electronics", "Accessories", "Peripherals"};
        for (int i = 0; i < products.length; i++) {
            productsTable.insertRow(Row.of(
                    SqlValue.ofInt(i + 1),
                    SqlValue.ofString(products[i]),
                    SqlValue.ofString(categories[i % categories.length]),
                    SqlValue.ofDouble(100 + rng.nextDouble() * 1500)
            ));
        }
        catalog.registerTable(productsTable);

        catalog.analyzeAllTables();
        System.out.printf("Loaded 3 tables: customers (1000 rows), orders (5000 rows), products (%d rows)%n%n",
                products.length);
    }

    private void runBenchmark() {
        System.out.println("\n=== Running Benchmark Queries ===\n");

        String[] queries = {
                "SELECT COUNT(*) FROM orders",
                "SELECT city, COUNT(*) FROM customers GROUP BY city ORDER BY COUNT(*) DESC",
                "SELECT c.name, SUM(o.amount) FROM customers c JOIN orders o ON c.id = o.customer_id GROUP BY c.name ORDER BY SUM(o.amount) DESC LIMIT 10",
                "SELECT product, AVG(amount), MIN(amount), MAX(amount) FROM orders WHERE status = 'completed' GROUP BY product",
                "SELECT c.city, COUNT(o.id), SUM(o.amount) FROM customers c JOIN orders o ON c.id = o.customer_id WHERE c.age > 30 GROUP BY c.city ORDER BY SUM(o.amount) DESC",
        };

        for (int i = 0; i < queries.length; i++) {
            System.out.printf("--- Benchmark %d/%d ---%n", i + 1, queries.length);
            System.out.println("SQL: " + queries[i]);
            executeAndPrint(queries[i]);
        }

        System.out.println("=== Benchmark Complete ===\n");
    }

    public static void main(String[] args) throws IOException {
        Catalog catalog = new Catalog();
        CoordinatorService coordinator = new CoordinatorService(catalog, 4);
        CloudQueryXCli cli = new CloudQueryXCli(coordinator);
        cli.run();
    }
}
