package com.cloudqueryx.execution.operators;

import com.cloudqueryx.catalog.Catalog;
import com.cloudqueryx.catalog.TableDescriptor;
import com.cloudqueryx.common.Row;
import com.cloudqueryx.common.Schema;
import com.cloudqueryx.execution.Operator;
import com.cloudqueryx.execution.QueryContext;
import com.cloudqueryx.expression.Expression;

import java.util.Iterator;
import java.util.List;

public class ScanOperator implements Operator {

    private final String tableName;
    private final Schema outputSchema;
    private final Expression pushdownPredicate;
    private final QueryContext context;
    private Iterator<Row> iterator;
    private long rowsProduced;

    public ScanOperator(String tableName, Schema outputSchema,
                        Expression pushdownPredicate, QueryContext context) {
        this.tableName = tableName;
        this.outputSchema = outputSchema;
        this.pushdownPredicate = pushdownPredicate;
        this.context = context;
    }

    @Override
    public void open() {
        TableDescriptor table = context.getCatalog().getTable(tableName);
        List<Row> data = table.getData();
        iterator = data.iterator();
        rowsProduced = 0;
    }

    @Override
    public Row next() {
        while (iterator.hasNext()) {
            if (context.isCancelled()) return null;

            Row row = iterator.next();
            context.addRowsScanned(1);

            if (pushdownPredicate != null) {
                var result = pushdownPredicate.evaluate(row, outputSchema);
                if (result.isNull() || !result.asBoolean()) continue;
            }

            rowsProduced++;
            return row;
        }
        return null;
    }

    @Override
    public Schema getOutputSchema() {
        return outputSchema;
    }

    @Override
    public void close() {
        iterator = null;
    }

    @Override
    public long getRowsProduced() {
        return rowsProduced;
    }
}
