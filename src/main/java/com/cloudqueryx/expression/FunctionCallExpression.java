package com.cloudqueryx.expression;

import com.cloudqueryx.common.*;

import java.util.ArrayList;
import java.util.List;

public record FunctionCallExpression(String name, List<Expression> arguments)
        implements Expression {

    @Override
    public SqlValue evaluate(Row row, Schema schema) {
        List<SqlValue> args = arguments.stream().map(a -> a.evaluate(row, schema)).toList();
        return switch (name.toUpperCase()) {
            case "ABS" -> {
                SqlValue v = args.get(0);
                yield v.isNull() ? SqlValue.ofNull() : SqlValue.ofDouble(Math.abs(v.asDouble()));
            }
            case "UPPER" -> {
                SqlValue v = args.get(0);
                yield v.isNull() ? SqlValue.ofNull() : SqlValue.ofString(v.asString().toUpperCase());
            }
            case "LOWER" -> {
                SqlValue v = args.get(0);
                yield v.isNull() ? SqlValue.ofNull() : SqlValue.ofString(v.asString().toLowerCase());
            }
            case "LENGTH", "LEN" -> {
                SqlValue v = args.get(0);
                yield v.isNull() ? SqlValue.ofNull() : SqlValue.ofInt(v.asString().length());
            }
            case "TRIM" -> {
                SqlValue v = args.get(0);
                yield v.isNull() ? SqlValue.ofNull() : SqlValue.ofString(v.asString().trim());
            }
            case "SUBSTRING", "SUBSTR" -> {
                SqlValue str = args.get(0);
                if (str.isNull()) yield SqlValue.ofNull();
                int start = args.get(1).asInt() - 1;
                int len = args.size() > 2 ? args.get(2).asInt() : str.asString().length() - start;
                yield SqlValue.ofString(str.asString().substring(start, Math.min(start + len, str.asString().length())));
            }
            case "CONCAT" -> {
                StringBuilder sb = new StringBuilder();
                for (SqlValue a : args) {
                    if (a.isNull()) yield SqlValue.ofNull();
                    sb.append(a.asString());
                }
                yield SqlValue.ofString(sb.toString());
            }
            case "COALESCE" -> {
                for (SqlValue a : args) {
                    if (!a.isNull()) yield a;
                }
                yield SqlValue.ofNull();
            }
            case "NULLIF" -> {
                SqlValue a = args.get(0);
                SqlValue b = args.get(1);
                yield (a.compareTo(b) == 0) ? SqlValue.ofNull() : a;
            }
            case "FLOOR" -> {
                SqlValue v = args.get(0);
                yield v.isNull() ? SqlValue.ofNull() : SqlValue.ofDouble(Math.floor(v.asDouble()));
            }
            case "CEIL", "CEILING" -> {
                SqlValue v = args.get(0);
                yield v.isNull() ? SqlValue.ofNull() : SqlValue.ofDouble(Math.ceil(v.asDouble()));
            }
            case "ROUND" -> {
                SqlValue v = args.get(0);
                if (v.isNull()) yield SqlValue.ofNull();
                int scale = args.size() > 1 ? args.get(1).asInt() : 0;
                double factor = Math.pow(10, scale);
                yield SqlValue.ofDouble(Math.round(v.asDouble() * factor) / factor);
            }
            case "POWER", "POW" -> {
                yield SqlValue.ofDouble(Math.pow(args.get(0).asDouble(), args.get(1).asDouble()));
            }
            case "SQRT" -> {
                yield SqlValue.ofDouble(Math.sqrt(args.get(0).asDouble()));
            }
            case "NOW", "CURRENT_TIMESTAMP" -> {
                yield SqlValue.ofTimestamp(java.time.LocalDateTime.now());
            }
            case "CURRENT_DATE" -> {
                yield SqlValue.ofDate(java.time.LocalDate.now());
            }
            default -> throw new CloudQueryXException(
                    CloudQueryXException.ErrorCode.EXECUTION_ERROR,
                    "Unknown function: " + name);
        };
    }

    @Override
    public DataType getType(Schema inputSchema) {
        return switch (name.toUpperCase()) {
            case "COUNT" -> DataType.BIGINT;
            case "ABS", "FLOOR", "CEIL", "CEILING", "ROUND", "POWER", "POW", "SQRT" -> DataType.DOUBLE;
            case "UPPER", "LOWER", "TRIM", "SUBSTRING", "SUBSTR", "CONCAT" -> DataType.VARCHAR;
            case "LENGTH", "LEN" -> DataType.INTEGER;
            case "NOW", "CURRENT_TIMESTAMP" -> DataType.TIMESTAMP;
            case "CURRENT_DATE" -> DataType.DATE;
            case "COALESCE" -> arguments.isEmpty() ? DataType.NULL : arguments.get(0).getType(inputSchema);
            case "NULLIF" -> arguments.isEmpty() ? DataType.NULL : arguments.get(0).getType(inputSchema);
            default -> arguments.isEmpty() ? DataType.VARCHAR : arguments.get(0).getType(inputSchema);
        };
    }

    @Override
    public List<ColumnRef> getReferencedColumns() {
        List<ColumnRef> refs = new ArrayList<>();
        for (Expression arg : arguments) {
            refs.addAll(arg.getReferencedColumns());
        }
        return refs;
    }

    @Override
    public List<Expression> getChildren() {
        return arguments;
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitFunctionCall(this);
    }

    @Override
    public Expression rebuildWithChildren(List<Expression> c) {
        return new FunctionCallExpression(name, c);
    }

    @Override
    public String toString() {
        return name + "(" + String.join(", ", arguments.stream().map(Object::toString).toList()) + ")";
    }
}
