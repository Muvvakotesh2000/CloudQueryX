package com.cloudqueryx.expression;

import com.cloudqueryx.common.DataType;
import com.cloudqueryx.common.Row;
import com.cloudqueryx.common.Schema;
import com.cloudqueryx.common.SqlValue;

import java.util.List;
import java.util.function.Function;

public interface Expression {

    SqlValue evaluate(Row row, Schema schema);

    DataType getType(Schema inputSchema);

    List<ColumnRef> getReferencedColumns();

    List<Expression> getChildren();

    <R> R accept(ExpressionVisitor<R> visitor);

    default Expression transform(Function<Expression, Expression> fn) {
        Expression transformed = fn.apply(this);
        if (transformed != this) return transformed;
        List<Expression> children = getChildren();
        if (children.isEmpty()) return this;
        return rebuildWithChildren(children.stream().map(c -> c.transform(fn)).toList());
    }

    Expression rebuildWithChildren(List<Expression> newChildren);

    default boolean isConstant() {
        return getReferencedColumns().isEmpty();
    }

    default boolean references(String tableName) {
        return getReferencedColumns().stream()
                .anyMatch(col -> tableName.equalsIgnoreCase(col.tableName()));
    }

    default boolean referencesOnly(String... tableNames) {
        for (ColumnRef col : getReferencedColumns()) {
            if (col.tableName() == null) continue;
            boolean found = false;
            for (String t : tableNames) {
                if (t.equalsIgnoreCase(col.tableName())) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }
}
