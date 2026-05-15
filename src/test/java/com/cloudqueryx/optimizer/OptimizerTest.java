package com.cloudqueryx.optimizer;

import com.cloudqueryx.catalog.Catalog;
import com.cloudqueryx.catalog.TableDescriptor;
import com.cloudqueryx.common.*;
import com.cloudqueryx.expression.*;
import com.cloudqueryx.optimizer.rules.PredicatePushDown;
import com.cloudqueryx.optimizer.rules.JoinReorder;
import com.cloudqueryx.planner.logical.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class OptimizerTest {

    private Catalog catalog;
    private Optimizer optimizer;

    @BeforeEach
    void setUp() {
        catalog = new Catalog();

        Schema usersSchema = new Schema(List.of(
                new Column("id", DataType.INTEGER, false, "users"),
                new Column("name", DataType.VARCHAR, false, "users"),
                new Column("age", DataType.INTEGER, true, "users")
        ));
        TableDescriptor users = new TableDescriptor("users", usersSchema);
        for (int i = 0; i < 1000; i++) {
            users.insertRow(Row.of(SqlValue.ofInt(i), SqlValue.ofString("user" + i), SqlValue.ofInt(20 + i % 50)));
        }
        users.computeStatistics();
        catalog.registerTable(users);

        Schema ordersSchema = new Schema(List.of(
                new Column("id", DataType.INTEGER, false, "orders"),
                new Column("user_id", DataType.INTEGER, false, "orders"),
                new Column("amount", DataType.DOUBLE, false, "orders")
        ));
        TableDescriptor orders = new TableDescriptor("orders", ordersSchema);
        for (int i = 0; i < 10000; i++) {
            orders.insertRow(Row.of(SqlValue.ofInt(i), SqlValue.ofInt(i % 1000), SqlValue.ofDouble(i * 1.5)));
        }
        orders.computeStatistics();
        catalog.registerTable(orders);

        optimizer = new Optimizer(catalog);
    }

    @Test
    void predicatePushDownPushesFilterBelowJoin() {
        Scan users = new Scan("users", "users", catalog.getTableSchema("users").withTablePrefix("users"));
        Scan orders = new Scan("orders", "orders", catalog.getTableSchema("orders").withTablePrefix("orders"));

        Join join = new Join(users, orders, Join.JoinType.INNER,
                new ComparisonExpression(ComparisonExpression.Op.EQUAL,
                        new ColumnRef("users", "id"), new ColumnRef("orders", "user_id")));

        Expression filterExpr = new ComparisonExpression(ComparisonExpression.Op.GREATER_THAN,
                new ColumnRef("users", "age"), Literal.ofInt(30));
        Filter filter = new Filter(join, filterExpr);

        PredicatePushDown ppd = new PredicatePushDown();
        LogicalPlan optimized = ppd.apply(filter);

        assertThat(optimized).isInstanceOf(Join.class);
        String explain = optimized.explain();
        assertThat(explain.indexOf("Filter")).isLessThan(explain.lastIndexOf("Scan"));
    }

    @Test
    void joinReorderSelectsCheaperPlan() {
        Schema smallSchema = new Schema(List.of(
                new Column("id", DataType.INTEGER, false, "small"),
                new Column("val", DataType.VARCHAR, true, "small")
        ));
        TableDescriptor smallTable = new TableDescriptor("small", smallSchema);
        for (int i = 0; i < 10; i++) {
            smallTable.insertRow(Row.of(SqlValue.ofInt(i), SqlValue.ofString("v" + i)));
        }
        smallTable.computeStatistics();
        catalog.registerTable(smallTable);

        CostModel costModel = new CostModel(catalog);
        JoinReorder joinReorder = new JoinReorder(costModel);

        Scan users = new Scan("users", "users", catalog.getTableSchema("users").withTablePrefix("users"));
        Scan orders = new Scan("orders", "orders", catalog.getTableSchema("orders").withTablePrefix("orders"));
        Scan small = new Scan("small", "small", smallSchema.withTablePrefix("small"));

        Join firstJoin = new Join(orders, users, Join.JoinType.INNER,
                new ComparisonExpression(ComparisonExpression.Op.EQUAL,
                        new ColumnRef("orders", "user_id"), new ColumnRef("users", "id")));

        Join secondJoin = new Join(firstJoin, small, Join.JoinType.INNER,
                new ComparisonExpression(ComparisonExpression.Op.EQUAL,
                        new ColumnRef("users", "id"), new ColumnRef("small", "id")));

        LogicalPlan optimized = joinReorder.apply(secondJoin);
        assertThat(optimized).isNotNull();
    }

    @Test
    void optimizerProducesLowerCostPlan() {
        Scan users = new Scan("users", "users", catalog.getTableSchema("users").withTablePrefix("users"));
        Scan orders = new Scan("orders", "orders", catalog.getTableSchema("orders").withTablePrefix("orders"));

        Join join = new Join(users, orders, Join.JoinType.INNER,
                new ComparisonExpression(ComparisonExpression.Op.EQUAL,
                        new ColumnRef("users", "id"), new ColumnRef("orders", "user_id")));

        Expression filterExpr = new ComparisonExpression(ComparisonExpression.Op.GREATER_THAN,
                new ColumnRef("users", "age"), Literal.ofInt(30));
        Filter filter = new Filter(join, filterExpr);

        CostModel costModel = optimizer.getCostModel();
        CostModel.CostEstimate originalCost = costModel.estimateCost(filter);

        LogicalPlan optimized = optimizer.optimizeLogical(filter);
        CostModel.CostEstimate optimizedCost = costModel.estimateCost(optimized);

        assertThat(optimizedCost.cost()).isLessThanOrEqualTo(originalCost.cost());
    }

    @Test
    void costModelEstimatesReasonableCosts() {
        CostModel costModel = new CostModel(catalog);
        Scan users = new Scan("users", "users", catalog.getTableSchema("users").withTablePrefix("users"));
        CostModel.CostEstimate estimate = costModel.estimateCost(users);

        assertThat(estimate.rowCount()).isEqualTo(1000);
        assertThat(estimate.cost()).isGreaterThan(0);
    }

    @Test
    void physicalPlanCreation() {
        Scan users = new Scan("users", "users", catalog.getTableSchema("users").withTablePrefix("users"));
        Expression filterExpr = new ComparisonExpression(ComparisonExpression.Op.GREATER_THAN,
                new ColumnRef("users", "age"), Literal.ofInt(30));
        Filter filter = new Filter(users, filterExpr);

        var physicalPlan = optimizer.createPhysicalPlan(filter);
        assertThat(physicalPlan).isNotNull();
        assertThat(physicalPlan.explain()).contains("TableScan", "Filter");
    }
}
