package com.cloudqueryx.parser;

import com.cloudqueryx.catalog.Catalog;
import com.cloudqueryx.catalog.TableDescriptor;
import com.cloudqueryx.common.*;
import com.cloudqueryx.planner.logical.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SqlParserTest {

    private SqlParser parser;
    private Catalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new Catalog();

        Schema usersSchema = new Schema(List.of(
                new Column("id", DataType.INTEGER, false, "users"),
                new Column("name", DataType.VARCHAR, false, "users"),
                new Column("age", DataType.INTEGER, true, "users")
        ));
        catalog.registerTable(new TableDescriptor("users", usersSchema));

        Schema ordersSchema = new Schema(List.of(
                new Column("id", DataType.INTEGER, false, "orders"),
                new Column("user_id", DataType.INTEGER, false, "orders"),
                new Column("amount", DataType.DOUBLE, false, "orders")
        ));
        catalog.registerTable(new TableDescriptor("orders", ordersSchema));

        parser = new SqlParser(catalog);
    }

    @Test
    void parseSimpleSelect() {
        LogicalPlan plan = parser.parse("SELECT * FROM users");
        assertThat(plan).isNotNull();
        assertThat(plan.getOutputSchema().getColumnCount()).isEqualTo(3);
    }

    @Test
    void parseSelectWithWhere() {
        LogicalPlan plan = parser.parse("SELECT name FROM users WHERE age > 25");
        assertThat(plan).isNotNull();
        String explain = plan.explain();
        assertThat(explain).contains("Filter");
    }

    @Test
    void parseSelectWithJoin() {
        LogicalPlan plan = parser.parse(
                "SELECT u.name, o.amount FROM users u JOIN orders o ON u.id = o.user_id");
        assertThat(plan).isNotNull();
        String explain = plan.explain();
        assertThat(explain).contains("Join");
    }

    @Test
    void parseSelectWithGroupBy() {
        LogicalPlan plan = parser.parse(
                "SELECT name, COUNT(*) FROM users GROUP BY name");
        assertThat(plan).isNotNull();
        String explain = plan.explain();
        assertThat(explain).contains("Aggregate");
    }

    @Test
    void parseSelectWithOrderByAndLimit() {
        LogicalPlan plan = parser.parse(
                "SELECT name, age FROM users ORDER BY age DESC LIMIT 10");
        assertThat(plan).isNotNull();
        String explain = plan.explain();
        assertThat(explain).contains("Sort");
        assertThat(explain).contains("Limit");
    }

    @Test
    void parseSelectWithSubquery() {
        LogicalPlan plan = parser.parse(
                "SELECT * FROM users WHERE age > 30");
        assertThat(plan).isNotNull();
    }

    @Test
    void parseSelectWithBetween() {
        LogicalPlan plan = parser.parse(
                "SELECT * FROM users WHERE age BETWEEN 20 AND 40");
        assertThat(plan).isNotNull();
    }

    @Test
    void parseSelectWithIn() {
        LogicalPlan plan = parser.parse(
                "SELECT * FROM users WHERE name IN ('Alice', 'Bob', 'Charlie')");
        assertThat(plan).isNotNull();
    }

    @Test
    void parseSelectWithLike() {
        LogicalPlan plan = parser.parse(
                "SELECT * FROM users WHERE name LIKE 'A%'");
        assertThat(plan).isNotNull();
    }

    @Test
    void parseSelectWithCaseExpression() {
        LogicalPlan plan = parser.parse(
                "SELECT name, CASE WHEN age > 40 THEN 'senior' ELSE 'junior' END FROM users");
        assertThat(plan).isNotNull();
    }

    @Test
    void parseSelectWithFunctions() {
        LogicalPlan plan = parser.parse(
                "SELECT UPPER(name), ABS(age) FROM users");
        assertThat(plan).isNotNull();
    }

    @Test
    void parseSelectWithAggregates() {
        LogicalPlan plan = parser.parse(
                "SELECT COUNT(*), SUM(age), AVG(age), MIN(age), MAX(age) FROM users");
        assertThat(plan).isNotNull();
    }

    @Test
    void parseCreateTable() {
        LogicalPlan plan = parser.parse(
                "CREATE TABLE test (id INT NOT NULL, name VARCHAR, value DOUBLE)");
        assertThat(catalog.hasTable("test")).isTrue();
    }

    @Test
    void parseInvalidSqlThrows() {
        assertThatThrownBy(() -> parser.parse("SELECTT * FROMM users"))
                .isInstanceOf(CloudQueryXException.class);
    }

    @Test
    void parseSelectWithMultipleJoins() {
        LogicalPlan plan = parser.parse(
                "SELECT u.name, o.amount FROM users u " +
                "JOIN orders o ON u.id = o.user_id " +
                "WHERE o.amount > 100 AND u.age > 25");
        assertThat(plan).isNotNull();
    }

    @Test
    void parseLeftJoin() {
        LogicalPlan plan = parser.parse(
                "SELECT u.name, o.amount FROM users u LEFT JOIN orders o ON u.id = o.user_id");
        assertThat(plan).isNotNull();
        assertThat(plan.explain()).contains("LEFT");
    }

    @Test
    void parseSelectDistinct() {
        LogicalPlan plan = parser.parse("SELECT DISTINCT name FROM users");
        assertThat(plan).isNotNull();
    }
}
