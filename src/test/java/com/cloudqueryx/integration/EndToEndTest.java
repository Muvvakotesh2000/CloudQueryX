package com.cloudqueryx.integration;

import com.cloudqueryx.catalog.Catalog;
import com.cloudqueryx.catalog.TableDescriptor;
import com.cloudqueryx.common.*;
import com.cloudqueryx.distributed.CoordinatorService;
import com.cloudqueryx.execution.ExecutionEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

class EndToEndTest {

    private CoordinatorService coordinator;

    @BeforeEach
    void setUp() {
        Catalog catalog = new Catalog();

        Schema customersSchema = new Schema(List.of(
                new Column("id", DataType.INTEGER, false, "customers"),
                new Column("name", DataType.VARCHAR, false, "customers"),
                new Column("city", DataType.VARCHAR, true, "customers"),
                new Column("age", DataType.INTEGER, true, "customers")
        ));
        TableDescriptor customers = new TableDescriptor("customers", customersSchema);
        Random rng = new Random(42);
        String[] cities = {"NYC", "LA", "CHI", "HOU", "PHX"};
        for (int i = 1; i <= 100; i++) {
            customers.insertRow(Row.of(
                    SqlValue.ofInt(i),
                    SqlValue.ofString("Customer_" + i),
                    SqlValue.ofString(cities[rng.nextInt(cities.length)]),
                    SqlValue.ofInt(20 + rng.nextInt(50))
            ));
        }
        customers.computeStatistics();
        catalog.registerTable(customers);

        Schema ordersSchema = new Schema(List.of(
                new Column("id", DataType.INTEGER, false, "orders"),
                new Column("customer_id", DataType.INTEGER, false, "orders"),
                new Column("product", DataType.VARCHAR, false, "orders"),
                new Column("amount", DataType.DOUBLE, false, "orders")
        ));
        TableDescriptor orders = new TableDescriptor("orders", ordersSchema);
        String[] products = {"Laptop", "Phone", "Tablet"};
        for (int i = 1; i <= 500; i++) {
            orders.insertRow(Row.of(
                    SqlValue.ofInt(i),
                    SqlValue.ofInt(1 + rng.nextInt(100)),
                    SqlValue.ofString(products[rng.nextInt(products.length)]),
                    SqlValue.ofDouble(Math.round(rng.nextDouble() * 1000 * 100.0) / 100.0)
            ));
        }
        orders.computeStatistics();
        catalog.registerTable(orders);

        coordinator = new CoordinatorService(catalog, 4);
    }

    @Test
    void simpleSelectAll() {
        ExecutionEngine.QueryResult result = coordinator.executeQuery("SELECT * FROM customers");
        assertThat(result.rows()).hasSize(100);
        assertThat(result.schema().getColumnCount()).isEqualTo(4);
    }

    @Test
    void selectWithFilter() {
        ExecutionEngine.QueryResult result = coordinator.executeQuery(
                "SELECT name, age FROM customers WHERE age > 40");
        assertThat(result.rows()).allSatisfy(row ->
                assertThat(row.getValue(1).asInt()).isGreaterThan(40));
    }

    @Test
    void selectWithOrderByLimit() {
        ExecutionEngine.QueryResult result = coordinator.executeQuery(
                "SELECT name, age FROM customers ORDER BY age DESC LIMIT 5");
        assertThat(result.rows()).hasSize(5);
        for (int i = 0; i < result.rows().size() - 1; i++) {
            assertThat(result.rows().get(i).getValue(1).asInt())
                    .isGreaterThanOrEqualTo(result.rows().get(i + 1).getValue(1).asInt());
        }
    }

    @Test
    void aggregateCountAll() {
        ExecutionEngine.QueryResult result = coordinator.executeQuery("SELECT COUNT(*) FROM orders");
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).getValue(0).asLong()).isEqualTo(500);
    }

    @Test
    void aggregateWithGroupBy() {
        ExecutionEngine.QueryResult result = coordinator.executeQuery(
                "SELECT city, COUNT(*) FROM customers GROUP BY city");
        assertThat(result.rows().size()).isGreaterThanOrEqualTo(1);
        long totalCount = result.rows().stream()
                .mapToLong(r -> r.getValue(1).asLong())
                .sum();
        assertThat(totalCount).isEqualTo(100);
    }

    @Test
    void joinWithFilter() {
        ExecutionEngine.QueryResult result = coordinator.executeQuery(
                "SELECT c.name, o.product, o.amount FROM customers c " +
                "JOIN orders o ON c.id = o.customer_id WHERE o.amount > 500");
        assertThat(result.rows()).allSatisfy(row ->
                assertThat(row.getValue(2).asDouble()).isGreaterThan(500));
    }

    @Test
    void joinWithGroupByAndOrderBy() {
        ExecutionEngine.QueryResult result = coordinator.executeQuery(
                "SELECT c.city, SUM(o.amount) FROM customers c " +
                "JOIN orders o ON c.id = o.customer_id " +
                "GROUP BY c.city ORDER BY SUM(o.amount) DESC");
        assertThat(result.rows().size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void selectWithLike() {
        ExecutionEngine.QueryResult result = coordinator.executeQuery(
                "SELECT name FROM customers WHERE name LIKE 'Customer_1%'");
        assertThat(result.rows()).allSatisfy(row ->
                assertThat(row.getValue(0).asString()).startsWith("Customer_1"));
    }

    @Test
    void selectWithBetween() {
        ExecutionEngine.QueryResult result = coordinator.executeQuery(
                "SELECT name, age FROM customers WHERE age BETWEEN 30 AND 40");
        assertThat(result.rows()).allSatisfy(row -> {
            int age = row.getValue(1).asInt();
            assertThat(age).isBetween(30, 40);
        });
    }

    @Test
    void selectWithInClause() {
        ExecutionEngine.QueryResult result = coordinator.executeQuery(
                "SELECT name, city FROM customers WHERE city IN ('NYC', 'LA')");
        assertThat(result.rows()).allSatisfy(row -> {
            String city = row.getValue(1).asString();
            assertThat(city).isIn("NYC", "LA");
        });
    }

    @Test
    void selectWithArithmeticExpression() {
        ExecutionEngine.QueryResult result = coordinator.executeQuery(
                "SELECT product, amount, amount * 1.1 FROM orders LIMIT 5");
        assertThat(result.rows()).hasSize(5);
        assertThat(result.schema().getColumnCount()).isEqualTo(3);
    }

    @Test
    void selectWithIsNull() {
        ExecutionEngine.QueryResult result = coordinator.executeQuery(
                "SELECT name FROM customers WHERE city IS NOT NULL");
        assertThat(result.rows()).isNotEmpty();
    }

    @Test
    void aggregateSumAvgMinMax() {
        ExecutionEngine.QueryResult result = coordinator.executeQuery(
                "SELECT product, SUM(amount), AVG(amount), MIN(amount), MAX(amount) " +
                "FROM orders GROUP BY product");
        assertThat(result.rows().size()).isGreaterThanOrEqualTo(1);
        assertThat(result.schema().getColumnCount()).isEqualTo(5);
    }

    @Test
    void complexQueryWithPredicatePushDown() {
        ExecutionEngine.QueryResult result = coordinator.executeQuery(
                "SELECT c.city, COUNT(o.id), SUM(o.amount) " +
                "FROM customers c JOIN orders o ON c.id = o.customer_id " +
                "WHERE c.age > 30 AND o.amount > 200 " +
                "GROUP BY c.city ORDER BY SUM(o.amount) DESC");
        assertThat(result.rows()).isNotEmpty();
    }

    @Test
    void createTableAndQuery() {
        coordinator.executeQuery("CREATE TABLE test_table (id INT NOT NULL, value VARCHAR)");
        ExecutionEngine.QueryResult result = coordinator.executeQuery("SELECT * FROM test_table");
        assertThat(result.rows()).isEmpty();
    }
}
