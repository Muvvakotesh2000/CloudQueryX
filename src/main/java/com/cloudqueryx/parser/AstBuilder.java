package com.cloudqueryx.parser;

import com.cloudqueryx.catalog.Catalog;
import com.cloudqueryx.catalog.TableDescriptor;
import com.cloudqueryx.common.*;
import com.cloudqueryx.expression.*;
import com.cloudqueryx.planner.logical.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AstBuilder extends CloudQuerySQLBaseVisitor<Object> {

    private final Catalog catalog;

    public AstBuilder(Catalog catalog) {
        this.catalog = catalog;
    }

    // ─── Statements ─────────────────────────────────────────────────────────

    @Override
    public LogicalPlan visitStatements(CloudQuerySQLParser.StatementsContext ctx) {
        return (LogicalPlan) visit(ctx.statement(0));
    }

    @Override
    public LogicalPlan visitStatement(CloudQuerySQLParser.StatementContext ctx) {
        if (ctx.selectStatement() != null) return visitSelectStatement(ctx.selectStatement());
        if (ctx.createTableStatement() != null) return (LogicalPlan) visit(ctx.createTableStatement());
        if (ctx.explainStatement() != null) return (LogicalPlan) visit(ctx.explainStatement());
        if (ctx.insertStatement() != null) return (LogicalPlan) visit(ctx.insertStatement());
        throw CloudQueryXException.parseError("Unsupported statement type");
    }

    // ─── SELECT ─────────────────────────────────────────────────────────────

    @Override
    public LogicalPlan visitSelectStatement(CloudQuerySQLParser.SelectStatementContext ctx) {
        LogicalPlan plan = visitQueryExpression(ctx.queryExpression());

        if (ctx.orderByClause() != null) {
            List<Sort.SortKey> sortKeys = visitOrderByClause(ctx.orderByClause(), plan.getOutputSchema());
            sortKeys = resolveSortKeysAgainstSchema(sortKeys, plan.getOutputSchema());
            plan = new Sort(plan, sortKeys);
        }

        if (ctx.limitClause() != null) {
            plan = visitLimitClause(ctx.limitClause(), plan);
        }

        return plan;
    }

    @Override
    public LogicalPlan visitQueryExpression(CloudQuerySQLParser.QueryExpressionContext ctx) {
        LogicalPlan plan = visitQueryTerm(ctx.queryTerm(0));

        for (int i = 1; i < ctx.queryTerm().size(); i++) {
            LogicalPlan right = visitQueryTerm(ctx.queryTerm(i));
            boolean all = ctx.ALL(i - 1) != null;
            plan = new Union(plan, right, all);
        }

        return plan;
    }

    @Override
    public LogicalPlan visitQueryTerm(CloudQuerySQLParser.QueryTermContext ctx) {
        LogicalPlan plan = null;

        if (ctx.FROM() != null) {
            List<CloudQuerySQLParser.TableReferenceContext> tableRefs = ctx.tableReference();
            plan = visitTableReference(tableRefs.get(0));
            for (int i = 1; i < tableRefs.size(); i++) {
                LogicalPlan right = visitTableReference(tableRefs.get(i));
                plan = new Join(plan, right, Join.JoinType.CROSS, null);
            }
        }

        if (ctx.whereClause() != null) {
            Expression condition = visitExpression(ctx.whereClause().expression());
            plan = new Filter(plan, condition);
        }

        List<Expression> groupByKeys = new ArrayList<>();
        if (ctx.groupByClause() != null) {
            for (var expr : ctx.groupByClause().expression()) {
                groupByKeys.add(visitExpression(expr));
            }
        }

        List<Expression> selectExprs = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        boolean hasAggregates = false;

        for (var item : ctx.selectItem()) {
            if (item instanceof CloudQuerySQLParser.SelectAllContext) {
                if (plan != null) {
                    for (Column col : plan.getOutputSchema().getColumns()) {
                        selectExprs.add(new ColumnRef(col.tableName(), col.name()));
                        aliases.add(col.name());
                    }
                }
            } else if (item instanceof CloudQuerySQLParser.SelectAllFromTableContext allFrom) {
                List<CloudQuerySQLParser.IdentifierContext> parts = allFrom.qualifiedName().identifier();
                String tableName = parts.get(parts.size() - 1).getText();
                String resolvedTable = resolveTableAlias(tableName, plan);
                if (plan != null) {
                    for (Column col : plan.getOutputSchema().getColumns()) {
                        if (resolvedTable.equalsIgnoreCase(col.tableName())) {
                            selectExprs.add(new ColumnRef(col.tableName(), col.name()));
                            aliases.add(col.name());
                        }
                    }
                }
            } else if (item instanceof CloudQuerySQLParser.SelectExpressionContext selExpr) {
                Expression expr = visitExpression(selExpr.expression());
                selectExprs.add(expr);
                if (selExpr.columnAlias() != null) {
                    aliases.add(getIdentifierText(selExpr.columnAlias()));
                } else {
                    aliases.add(inferAlias(expr));
                }
                if (containsAggregate(expr)) hasAggregates = true;
            }
        }

        if (hasAggregates || !groupByKeys.isEmpty()) {
            List<AggregateExpression> aggs = new ArrayList<>();

            for (Expression expr : selectExprs) {
                collectAggregates(expr, aggs);
            }

            List<String> outputNames = new ArrayList<>();
            for (Expression key : groupByKeys) {
                outputNames.add(inferAlias(key));
            }
            for (AggregateExpression agg : aggs) {
                outputNames.add(agg.toString());
            }

            plan = new Aggregate(plan, groupByKeys, aggs, outputNames);

            if (ctx.havingClause() != null) {
                Expression having = visitExpression(ctx.havingClause().expression());
                having = rewriteForAggregateOutput(having, groupByKeys, aggs, outputNames);
                plan = new Filter(plan, having);
            }

            List<Expression> rewrittenExprs = new ArrayList<>();
            for (Expression expr : selectExprs) {
                rewrittenExprs.add(rewriteForAggregateOutput(expr, groupByKeys, aggs, outputNames));
            }

            plan = new Project(plan, rewrittenExprs, aliases);
        } else {
            if (plan == null) {
                plan = new Values(List.of(List.of()), new Schema(List.of()));
            }
            boolean isIdentityProjection = selectExprs.size() == plan.getOutputSchema().getColumnCount()
                    && selectExprs.stream().allMatch(e -> e instanceof ColumnRef);
            if (!isIdentityProjection || !aliases.isEmpty()) {
                plan = new Project(plan, selectExprs, aliases);
            }
        }

        return plan;
    }

    // ─── FROM / JOIN ────────────────────────────────────────────────────────

    @Override
    public LogicalPlan visitTableReference(CloudQuerySQLParser.TableReferenceContext ctx) {
        LogicalPlan plan = (LogicalPlan) visit(ctx.tablePrimary());

        for (var joinClause : ctx.joinClause()) {
            plan = (LogicalPlan) visitJoinClauseWithLeft(joinClause, plan);
        }

        return plan;
    }

    @Override
    public LogicalPlan visitTableSource(CloudQuerySQLParser.TableSourceContext ctx) {
        List<CloudQuerySQLParser.IdentifierContext> parts = ctx.qualifiedName().identifier();
        String tableName = parts.get(parts.size() - 1).getText();

        TableDescriptor table = catalog.getTable(tableName);
        String alias = tableName;
        if (ctx.identifier() != null) {
            alias = getIdentifierText(ctx.identifier());
        }

        Schema aliasedSchema = table.getSchema().withTablePrefix(alias);
        return new Scan(tableName, alias, aliasedSchema);
    }

    @Override
    public LogicalPlan visitSubquerySource(CloudQuerySQLParser.SubquerySourceContext ctx) {
        LogicalPlan subquery = visitSelectStatement(ctx.selectStatement());
        if (ctx.identifier() != null) {
            String alias = getIdentifierText(ctx.identifier());
            Schema aliased = subquery.getOutputSchema().withTablePrefix(alias);
            return new Project(subquery,
                    subquery.getOutputSchema().getColumns().stream()
                            .map(c -> (Expression) new ColumnRef(c.tableName(), c.name()))
                            .toList(),
                    aliased.getColumns().stream().map(Column::name).toList());
        }
        return subquery;
    }

    private LogicalPlan visitJoinClauseWithLeft(CloudQuerySQLParser.JoinClauseContext ctx, LogicalPlan left) {
        if (ctx instanceof CloudQuerySQLParser.QualifiedJoinContext qj) {
            LogicalPlan right = (LogicalPlan) visit(qj.tablePrimary());
            Join.JoinType joinType = resolveJoinType(qj.joinType());
            Expression condition = null;
            if (qj.joinCriteria() != null && qj.joinCriteria().expression() != null) {
                condition = visitExpression(qj.joinCriteria().expression());
            }
            return new Join(left, right, joinType, condition);
        } else if (ctx instanceof CloudQuerySQLParser.CrossJoinContext cj) {
            LogicalPlan right = (LogicalPlan) visit(cj.tablePrimary());
            return new Join(left, right, Join.JoinType.CROSS, null);
        }
        throw CloudQueryXException.parseError("Unsupported join type");
    }

    private Join.JoinType resolveJoinType(CloudQuerySQLParser.JoinTypeContext ctx) {
        if (ctx == null || ctx.getText().isEmpty()) return Join.JoinType.INNER;
        String text = ctx.getText().toUpperCase();
        if (text.contains("LEFT")) return Join.JoinType.LEFT;
        if (text.contains("RIGHT")) return Join.JoinType.RIGHT;
        if (text.contains("FULL")) return Join.JoinType.FULL;
        return Join.JoinType.INNER;
    }

    // ─── Expressions ────────────────────────────────────────────────────────

    public Expression visitExpression(CloudQuerySQLParser.ExpressionContext ctx) {
        return (Expression) visit(ctx);
    }

    @Override
    public Expression visitPrimaryExpression(CloudQuerySQLParser.PrimaryExpressionContext ctx) {
        return (Expression) visit(ctx.primary());
    }

    @Override
    public Expression visitComparisonExpression(CloudQuerySQLParser.ComparisonExpressionContext ctx) {
        Expression left = visitExpression(ctx.expression(0));
        Expression right = visitExpression(ctx.expression(1));
        ComparisonExpression.Op op = switch (ctx.comparisonOperator().getText()) {
            case "=" -> ComparisonExpression.Op.EQUAL;
            case "<>", "!=" -> ComparisonExpression.Op.NOT_EQUAL;
            case "<" -> ComparisonExpression.Op.LESS_THAN;
            case ">" -> ComparisonExpression.Op.GREATER_THAN;
            case "<=" -> ComparisonExpression.Op.LESS_THAN_OR_EQUAL;
            case ">=" -> ComparisonExpression.Op.GREATER_THAN_OR_EQUAL;
            default -> throw CloudQueryXException.parseError("Unknown operator: " + ctx.comparisonOperator().getText());
        };
        return new ComparisonExpression(op, left, right);
    }

    @Override
    public Expression visitAndExpression(CloudQuerySQLParser.AndExpressionContext ctx) {
        return new LogicalExpression(LogicalExpression.Op.AND,
                visitExpression(ctx.expression(0)), visitExpression(ctx.expression(1)));
    }

    @Override
    public Expression visitOrExpression(CloudQuerySQLParser.OrExpressionContext ctx) {
        return new LogicalExpression(LogicalExpression.Op.OR,
                visitExpression(ctx.expression(0)), visitExpression(ctx.expression(1)));
    }

    @Override
    public Expression visitNotExpression(CloudQuerySQLParser.NotExpressionContext ctx) {
        return new NotExpression(visitExpression(ctx.expression()));
    }

    @Override
    public Expression visitMultiplicativeExpression(CloudQuerySQLParser.MultiplicativeExpressionContext ctx) {
        Expression left = visitExpression(ctx.expression(0));
        Expression right = visitExpression(ctx.expression(1));
        ArithmeticExpression.Op op = switch (ctx.op.getText()) {
            case "*" -> ArithmeticExpression.Op.MULTIPLY;
            case "/" -> ArithmeticExpression.Op.DIVIDE;
            case "%" -> ArithmeticExpression.Op.MODULO;
            default -> throw CloudQueryXException.parseError("Unknown operator: " + ctx.op.getText());
        };
        return new ArithmeticExpression(op, left, right);
    }

    @Override
    public Expression visitAdditiveExpression(CloudQuerySQLParser.AdditiveExpressionContext ctx) {
        Expression left = visitExpression(ctx.expression(0));
        Expression right = visitExpression(ctx.expression(1));
        ArithmeticExpression.Op op = ctx.op.getText().equals("+")
                ? ArithmeticExpression.Op.ADD : ArithmeticExpression.Op.SUBTRACT;
        return new ArithmeticExpression(op, left, right);
    }

    @Override
    public Expression visitUnaryExpression(CloudQuerySQLParser.UnaryExpressionContext ctx) {
        Expression operand = visitExpression(ctx.expression());
        if (ctx.op.getText().equals("-")) return new NegateExpression(operand);
        return operand;
    }

    @Override
    public Expression visitFunctionExpression(CloudQuerySQLParser.FunctionExpressionContext ctx) {
        List<CloudQuerySQLParser.IdentifierContext> parts = ctx.qualifiedName().identifier();
        String funcName = parts.get(parts.size() - 1).getText().toUpperCase();
        boolean distinct = ctx.DISTINCT() != null;

        List<Expression> args = new ArrayList<>();
        for (var expr : ctx.expression()) {
            args.add(visitExpression(expr));
        }

        if (isAggregateFunction(funcName)) {
            AggregateExpression.Function func = switch (funcName) {
                case "COUNT" -> AggregateExpression.Function.COUNT;
                case "SUM" -> AggregateExpression.Function.SUM;
                case "AVG" -> AggregateExpression.Function.AVG;
                case "MIN" -> AggregateExpression.Function.MIN;
                case "MAX" -> AggregateExpression.Function.MAX;
                default -> throw CloudQueryXException.parseError("Unknown aggregate: " + funcName);
            };
            return new AggregateExpression(func, args.isEmpty() ? null : args.get(0), distinct);
        }

        return new FunctionCallExpression(funcName, args);
    }

    @Override
    public Expression visitCountAllExpression(CloudQuerySQLParser.CountAllExpressionContext ctx) {
        return new AggregateExpression(AggregateExpression.Function.COUNT, null, false);
    }

    @Override
    public Expression visitIsNullExpression(CloudQuerySQLParser.IsNullExpressionContext ctx) {
        Expression operand = visitExpression(ctx.expression());
        boolean negated = ctx.NOT() != null;
        return new IsNullExpression(operand, negated);
    }

    @Override
    public Expression visitBetweenExpression(CloudQuerySQLParser.BetweenExpressionContext ctx) {
        Expression value = visitExpression(ctx.expression(0));
        Expression lower = visitExpression(ctx.expression(1));
        Expression upper = visitExpression(ctx.expression(2));
        boolean negated = ctx.NOT() != null;
        return new BetweenExpression(value, lower, upper, negated);
    }

    @Override
    public Expression visitInExpression(CloudQuerySQLParser.InExpressionContext ctx) {
        Expression value = visitExpression(ctx.expression(0));
        List<Expression> list = new ArrayList<>();
        for (int i = 1; i < ctx.expression().size(); i++) {
            list.add(visitExpression(ctx.expression(i)));
        }
        boolean negated = ctx.NOT() != null;
        return new InExpression(value, list, negated);
    }

    @Override
    public Expression visitLikeExpression(CloudQuerySQLParser.LikeExpressionContext ctx) {
        Expression value = visitExpression(ctx.expression(0));
        Expression pattern = visitExpression(ctx.expression(1));
        boolean negated = ctx.NOT() != null;
        return new LikeExpression(value, pattern, negated);
    }

    @Override
    public Expression visitCaseExpression(CloudQuerySQLParser.CaseExpressionContext ctx) {
        Expression operand = ctx.operand != null ? visitExpression(ctx.operand) : null;

        List<CaseExpression.WhenClause> whenClauses = new ArrayList<>();
        var whens = ctx.WHEN();
        for (int i = 0; i < whens.size(); i++) {
            int exprOffset = (operand != null ? 1 : 0);
            Expression condition = visitExpression(ctx.expression(exprOffset + i * 2));
            Expression result = visitExpression(ctx.expression(exprOffset + i * 2 + 1));
            whenClauses.add(new CaseExpression.WhenClause(condition, result));
        }

        Expression elseExpr = ctx.elseResult != null ? visitExpression(ctx.elseResult) : null;
        return new CaseExpression(operand, whenClauses, elseExpr);
    }

    @Override
    public Expression visitCastExpression(CloudQuerySQLParser.CastExpressionContext ctx) {
        Expression operand = visitExpression(ctx.expression());
        DataType targetType = resolveDataType(ctx.columnType());
        return new CastExpression(operand, targetType);
    }

    @Override
    public Expression visitConcatenationExpression(CloudQuerySQLParser.ConcatenationExpressionContext ctx) {
        Expression left = visitExpression(ctx.expression(0));
        Expression right = visitExpression(ctx.expression(1));
        return new FunctionCallExpression("CONCAT", List.of(left, right));
    }

    @Override
    public Expression visitExistsExpression(CloudQuerySQLParser.ExistsExpressionContext ctx) {
        return Literal.ofBoolean(true);
    }

    @Override
    public Expression visitScalarSubqueryExpression(CloudQuerySQLParser.ScalarSubqueryExpressionContext ctx) {
        return Literal.ofNull();
    }

    // ─── Primary Literals ───────────────────────────────────────────────────

    @Override
    public Expression visitNumericLiteral(CloudQuerySQLParser.NumericLiteralContext ctx) {
        String text = ctx.NUMBER().getText();
        try {
            long val = Long.parseLong(text);
            if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
                return Literal.ofInt((int) val);
            }
            return Literal.ofLong(val);
        } catch (NumberFormatException e) {
            return Literal.ofDouble(Double.parseDouble(text));
        }
    }

    @Override
    public Expression visitDecimalLiteral(CloudQuerySQLParser.DecimalLiteralContext ctx) {
        return Literal.ofDouble(Double.parseDouble(ctx.DECIMAL_NUMBER().getText()));
    }

    @Override
    public Expression visitStringLiteral(CloudQuerySQLParser.StringLiteralContext ctx) {
        String text = ctx.STRING().getText();
        String unquoted = text.substring(1, text.length() - 1).replace("''", "'");
        return Literal.ofString(unquoted);
    }

    @Override
    public Expression visitBooleanTrueLiteral(CloudQuerySQLParser.BooleanTrueLiteralContext ctx) {
        return Literal.ofBoolean(true);
    }

    @Override
    public Expression visitBooleanFalseLiteral(CloudQuerySQLParser.BooleanFalseLiteralContext ctx) {
        return Literal.ofBoolean(false);
    }

    @Override
    public Expression visitNullLiteral(CloudQuerySQLParser.NullLiteralContext ctx) {
        return Literal.ofNull();
    }

    @Override
    public Expression visitColumnReference(CloudQuerySQLParser.ColumnReferenceContext ctx) {
        return new ColumnRef(null, getIdentifierText(ctx.identifier()));
    }

    @Override
    public Expression visitQualifiedColumnReference(CloudQuerySQLParser.QualifiedColumnReferenceContext ctx) {
        List<CloudQuerySQLParser.IdentifierContext> parts = ctx.qualifiedName().identifier();
        if (parts.size() == 1) {
            return new ColumnRef(null, getIdentifierText(parts.get(0)));
        }
        String table = getIdentifierText(parts.get(parts.size() - 2));
        String column = getIdentifierText(parts.get(parts.size() - 1));
        return new ColumnRef(table, column);
    }

    @Override
    public Expression visitParenthesizedExpression(CloudQuerySQLParser.ParenthesizedExpressionContext ctx) {
        return visitExpression(ctx.expression());
    }

    // ─── ORDER BY / LIMIT ───────────────────────────────────────────────────

    private List<Sort.SortKey> resolveSortKeysAgainstSchema(List<Sort.SortKey> keys, Schema schema) {
        List<Sort.SortKey> resolved = new ArrayList<>();
        for (Sort.SortKey key : keys) {
            Expression expr = key.expression();
            Expression rewritten = resolveExprAgainstSchema(expr, schema);
            resolved.add(new Sort.SortKey(rewritten, key.direction(), key.nullOrder()));
        }
        return resolved;
    }

    private Expression resolveExprAgainstSchema(Expression expr, Schema schema) {
        String exprStr = expr.toString();
        for (Column col : schema.getColumns()) {
            if (col.name().equalsIgnoreCase(exprStr) || col.qualifiedName().equalsIgnoreCase(exprStr)) {
                return new ColumnRef(col.tableName(), col.name());
            }
        }
        if (expr instanceof ColumnRef ref) {
            if (schema.hasColumn(ref.qualifiedName())) return expr;
            if (schema.hasColumn(ref.columnName())) return new ColumnRef(null, ref.columnName());
        }
        return expr;
    }

    private List<Sort.SortKey> visitOrderByClause(CloudQuerySQLParser.OrderByClauseContext ctx, Schema schema) {
        List<Sort.SortKey> keys = new ArrayList<>();
        for (var item : ctx.sortItem()) {
            Expression expr = visitExpression(item.expression());
            Sort.SortKey.Direction dir = Sort.SortKey.Direction.ASC;
            if (item.ordering != null && item.ordering.getText().equalsIgnoreCase("DESC")) {
                dir = Sort.SortKey.Direction.DESC;
            }
            Sort.SortKey.NullOrder nullOrder = dir == Sort.SortKey.Direction.ASC
                    ? Sort.SortKey.NullOrder.NULLS_LAST : Sort.SortKey.NullOrder.NULLS_FIRST;
            if (item.nullOrdering != null) {
                nullOrder = item.nullOrdering.getText().equalsIgnoreCase("FIRST")
                        ? Sort.SortKey.NullOrder.NULLS_FIRST : Sort.SortKey.NullOrder.NULLS_LAST;
            }
            keys.add(new Sort.SortKey(expr, dir, nullOrder));
        }
        return keys;
    }

    private LogicalPlan visitLimitClause(CloudQuerySQLParser.LimitClauseContext ctx, LogicalPlan input) {
        long limit = Long.MAX_VALUE;
        long offset = 0;
        if (ctx.limit != null) {
            limit = evaluateConstantLong(ctx.limit);
        }
        if (ctx.offset != null) {
            offset = evaluateConstantLong(ctx.offset);
        }
        return new Limit(input, limit, offset);
    }

    // ─── DDL ────────────────────────────────────────────────────────────────

    @Override
    public LogicalPlan visitCreateTableStatement(CloudQuerySQLParser.CreateTableStatementContext ctx) {
        List<CloudQuerySQLParser.IdentifierContext> parts = ctx.qualifiedName().identifier();
        String tableName = parts.get(parts.size() - 1).getText();
        List<Column> columns = new ArrayList<>();
        for (var colDef : ctx.columnDefinition()) {
            String colName = getIdentifierText(colDef.identifier());
            DataType type = resolveDataType(colDef.columnType());
            boolean nullable = colDef.columnConstraint().stream()
                    .noneMatch(c -> c instanceof CloudQuerySQLParser.NotNullConstraintContext);
            columns.add(new Column(colName, type, nullable, tableName));
        }
        Schema schema = new Schema(columns);
        TableDescriptor table = new TableDescriptor(tableName, schema);
        catalog.registerTable(table);
        return new Values(List.of(), schema);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private DataType resolveDataType(CloudQuerySQLParser.ColumnTypeContext ctx) {
        if (ctx instanceof CloudQuerySQLParser.IntTypeContext || ctx instanceof CloudQuerySQLParser.IntegerTypeContext) return DataType.INTEGER;
        if (ctx instanceof CloudQuerySQLParser.BigintTypeContext) return DataType.BIGINT;
        if (ctx instanceof CloudQuerySQLParser.DoubleTypeContext) return DataType.DOUBLE;
        if (ctx instanceof CloudQuerySQLParser.DecimalTypeContext) return DataType.DECIMAL;
        if (ctx instanceof CloudQuerySQLParser.VarcharTypeContext) return DataType.VARCHAR;
        if (ctx instanceof CloudQuerySQLParser.BooleanTypeContext) return DataType.BOOLEAN;
        if (ctx instanceof CloudQuerySQLParser.DateTypeContext) return DataType.DATE;
        if (ctx instanceof CloudQuerySQLParser.TimestampTypeContext) return DataType.TIMESTAMP;
        throw CloudQueryXException.parseError("Unknown data type: " + ctx.getText());
    }

    private boolean isAggregateFunction(String name) {
        return switch (name) {
            case "COUNT", "SUM", "AVG", "MIN", "MAX" -> true;
            default -> false;
        };
    }

    private boolean containsAggregate(Expression expr) {
        if (expr instanceof AggregateExpression) return true;
        for (Expression child : expr.getChildren()) {
            if (containsAggregate(child)) return true;
        }
        return false;
    }

    private void collectAggregates(Expression expr, List<AggregateExpression> aggs) {
        if (expr instanceof AggregateExpression agg) {
            if (!aggs.contains(agg)) aggs.add(agg);
            return;
        }
        for (Expression child : expr.getChildren()) {
            collectAggregates(child, aggs);
        }
    }

    private String inferAlias(Expression expr) {
        if (expr instanceof ColumnRef ref) return ref.columnName();
        if (expr instanceof AggregateExpression agg) return agg.toString().toLowerCase();
        return expr.toString();
    }

    private String getIdentifierText(CloudQuerySQLParser.IdentifierContext ctx) {
        if (ctx.QUOTED_IDENTIFIER() != null) {
            String text = ctx.QUOTED_IDENTIFIER().getText();
            return text.substring(1, text.length() - 1);
        }
        return ctx.getText();
    }

    private String getIdentifierText(CloudQuerySQLParser.ColumnAliasContext ctx) {
        if (ctx.identifier() != null) return getIdentifierText(ctx.identifier());
        String text = ctx.STRING().getText();
        return text.substring(1, text.length() - 1);
    }

    private long evaluateConstantLong(CloudQuerySQLParser.ExpressionContext ctx) {
        Expression expr = visitExpression(ctx);
        if (expr instanceof Literal lit) {
            return lit.value().asLong();
        }
        throw CloudQueryXException.parseError("Expected constant integer expression");
    }

    private Expression rewriteForAggregateOutput(Expression expr,
            List<Expression> groupByKeys, List<AggregateExpression> aggs, List<String> outputNames) {
        if (expr instanceof AggregateExpression agg) {
            int idx = aggs.indexOf(agg);
            if (idx >= 0) {
                return new ColumnRef(null, outputNames.get(groupByKeys.size() + idx));
            }
            for (int i = 0; i < aggs.size(); i++) {
                if (aggs.get(i).toString().equals(agg.toString())) {
                    return new ColumnRef(null, outputNames.get(groupByKeys.size() + i));
                }
            }
        }

        if (expr instanceof ColumnRef ref) {
            for (int i = 0; i < groupByKeys.size(); i++) {
                Expression key = groupByKeys.get(i);
                if (key instanceof ColumnRef keyRef) {
                    if (ref.qualifiedName().equalsIgnoreCase(keyRef.qualifiedName())
                            || ref.columnName().equalsIgnoreCase(keyRef.columnName())) {
                        return new ColumnRef(null, outputNames.get(i));
                    }
                }
            }
        }

        List<Expression> children = expr.getChildren();
        if (children.isEmpty()) return expr;

        List<Expression> rewritten = new ArrayList<>();
        boolean changed = false;
        for (Expression child : children) {
            Expression r = rewriteForAggregateOutput(child, groupByKeys, aggs, outputNames);
            rewritten.add(r);
            if (r != child) changed = true;
        }
        return changed ? expr.rebuildWithChildren(rewritten) : expr;
    }

    private String resolveTableAlias(String name, LogicalPlan plan) {
        return name;
    }
}
