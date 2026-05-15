package com.cloudqueryx.execution;

import com.cloudqueryx.catalog.Catalog;
import com.cloudqueryx.catalog.TableDescriptor;
import com.cloudqueryx.common.*;
import com.cloudqueryx.expression.*;
import com.cloudqueryx.planner.logical.Join;
import com.cloudqueryx.planner.logical.Sort;
import com.cloudqueryx.planner.physical.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ExecutionEngineTest {

    private Catalog catalog;
    private ExecutionEngine engine;

    @BeforeEach
    void setUp() {
        catalog = new Catalog();
        engine = new ExecutionEngine();

        Schema usersSchema = new Schema(List.of(
                new Column("id", DataType.INTEGER, false, "users"),
                new Column("name", DataType.VARCHAR, false, "users"),
                new Column("age", DataType.INTEGER, true, "users")
        ));
        TableDescriptor users = new TableDescriptor("users", usersSchema);
        users.insertRow(Row.of(SqlValue.ofInt(1), SqlValue.ofString("Alice"), SqlValue.ofInt(30)));
        users.insertRow(Row.of(SqlValue.ofInt(2), SqlValue.ofString("Bob"), SqlValue.ofInt(25)));
        users.insertRow(Row.of(SqlValue.ofInt(3), SqlValue.ofString("Charlie"), SqlValue.ofInt(35)));
        users.insertRow(Row.of(SqlValue.ofInt(4), SqlValue.ofString("Diana"), SqlValue.ofInt(28)));
        users.insertRow(Row.of(SqlValue.ofInt(5), SqlValue.ofString("Eve"), SqlValue.ofInt(40)));
        catalog.registerTable(users);

        Schema ordersSchema = new Schema(List.of(
                new Column("id", DataType.INTEGER, false, "orders"),
                new Column("user_id", DataType.INTEGER, false, "orders"),
                new Column("amount", DataType.DOUBLE, false, "orders")
        ));
        TableDescriptor orders = new TableDescriptor("orders", ordersSchema);
        orders.insertRow(Row.of(SqlValue.ofInt(1), SqlValue.ofInt(1), SqlValue.ofDouble(100.0)));
        orders.insertRow(Row.of(SqlValue.ofInt(2), SqlValue.ofInt(1), SqlValue.ofDouble(200.0)));
        orders.insertRow(Row.of(SqlValue.ofInt(3), SqlValue.ofInt(2), SqlValue.ofDouble(150.0)));
        orders.insertRow(Row.of(SqlValue.ofInt(4), SqlValue.ofInt(3), SqlValue.ofDouble(300.0)));
        orders.insertRow(Row.of(SqlValue.ofInt(5), SqlValue.ofInt(5), SqlValue.ofDouble(50.0)));
        catalog.registerTable(orders);
    }

    @Test
    void executeSimpleScan() {
        TableScan scan = new TableScan("users", "users",
                catalog.getTableSchema("users"), 5);
        QueryContext ctx = new QueryContext(catalog);

        ExecutionEngine.QueryResult result = engine.execute(scan, ctx);

        assertThat(result.rows()).hasSize(5);
        assertThat(result.schema().getColumnCount()).isEqualTo(3);
    }

    @Test
    void executeFilteredScan() {
        TableScan scan = new TableScan("users", "users",
                catalog.getTableSchema("users"), 5);
        PhysicalFilter filter = new PhysicalFilter(scan,
                new ComparisonExpression(ComparisonExpression.Op.GREATER_THAN,
                        new ColumnRef("users", "age"), Literal.ofInt(28)));
        QueryContext ctx = new QueryContext(catalog);

        ExecutionEngine.QueryResult result = engine.execute(filter, ctx);

        assertThat(result.rows()).hasSize(3);
    }

    @Test
    void executeProjection() {
        TableScan scan = new TableScan("users", "users",
                catalog.getTableSchema("users"), 5);
        PhysicalProject project = new PhysicalProject(scan,
                List.of(new ColumnRef("users", "name"), new ColumnRef("users", "age")),
                List.of("name", "age"));
        QueryContext ctx = new QueryContext(catalog);

        ExecutionEngine.QueryResult result = engine.execute(project, ctx);

        assertThat(result.rows()).hasSize(5);
        assertThat(result.schema().getColumnCount()).isEqualTo(2);
        assertThat(result.rows().get(0).getValue(0).asString()).isEqualTo("Alice");
    }

    @Test
    void executeHashJoin() {
        TableScan users = new TableScan("users", "users",
                catalog.getTableSchema("users"), 5);
        TableScan orders = new TableScan("orders", "orders",
                catalog.getTableSchema("orders"), 5);

        HashJoin join = new HashJoin(users, orders,
                new ComparisonExpression(ComparisonExpression.Op.EQUAL,
                        new ColumnRef("users", "id"), new ColumnRef("orders", "user_id")),
                Join.JoinType.INNER);
        QueryContext ctx = new QueryContext(catalog);

        ExecutionEngine.QueryResult result = engine.execute(join, ctx);

        assertThat(result.rows()).hasSize(5);
        assertThat(result.schema().getColumnCount()).isEqualTo(6);
    }

    @Test
    void executeAggregate() {
        TableScan scan = new TableScan("users", "users",
                catalog.getTableSchema("users"), 5);

        HashAggregate agg = new HashAggregate(scan,
                List.of(),
                List.of(
                        new AggregateExpression(AggregateExpression.Function.COUNT, null, false),
                        new AggregateExpression(AggregateExpression.Function.AVG,
                                new ColumnRef("users", "age"), false)
                ),
                List.of("count", "avg_age"));
        QueryContext ctx = new QueryContext(catalog);

        ExecutionEngine.QueryResult result = engine.execute(agg, ctx);

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).getValue(0).asLong()).isEqualTo(5);
    }

    @Test
    void executeSortWithLimit() {
        TableScan scan = new TableScan("users", "users",
                catalog.getTableSchema("users"), 5);

        PhysicalSort sort = new PhysicalSort(scan, List.of(
                new Sort.SortKey(new ColumnRef("users", "age"), Sort.SortKey.Direction.DESC)));

        PhysicalLimit limit = new PhysicalLimit(sort, 3, 0);
        QueryContext ctx = new QueryContext(catalog);

        ExecutionEngine.QueryResult result = engine.execute(limit, ctx);

        assertThat(result.rows()).hasSize(3);
        assertThat(result.rows().get(0).getValue(2).asInt()).isEqualTo(40);
        assertThat(result.rows().get(1).getValue(2).asInt()).isEqualTo(35);
    }

    @Test
    void executeArithmeticExpression() {
        TableScan scan = new TableScan("users", "users",
                catalog.getTableSchema("users"), 5);

        PhysicalProject project = new PhysicalProject(scan,
                List.of(
                        new ColumnRef("users", "name"),
                        new ArithmeticExpression(ArithmeticExpression.Op.MULTIPLY,
                                new ColumnRef("users", "age"), Literal.ofInt(2))
                ),
                List.of("name", "double_age"));
        QueryContext ctx = new QueryContext(catalog);

        ExecutionEngine.QueryResult result = engine.execute(project, ctx);

        assertThat(result.rows()).hasSize(5);
        assertThat(result.rows().get(0).getValue(1).asInt()).isEqualTo(60);
    }

    @Test
    void executeGroupByAggregate() {
        Schema ordersSchema = catalog.getTableSchema("orders");
        TableScan scan = new TableScan("orders", "orders", ordersSchema, 5);

        HashAggregate agg = new HashAggregate(scan,
                List.of(new ColumnRef("orders", "user_id")),
                List.of(
                        new AggregateExpression(AggregateExpression.Function.SUM,
                                new ColumnRef("orders", "amount"), false),
                        new AggregateExpression(AggregateExpression.Function.COUNT, null, false)
                ),
                List.of("user_id", "total_amount", "order_count"));
        QueryContext ctx = new QueryContext(catalog);

        ExecutionEngine.QueryResult result = engine.execute(agg, ctx);

        assertThat(result.rows().size()).isGreaterThanOrEqualTo(1);
    }
}
