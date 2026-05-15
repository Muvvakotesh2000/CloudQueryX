package com.cloudqueryx.execution.operators;

import com.cloudqueryx.common.*;
import com.cloudqueryx.execution.Operator;
import com.cloudqueryx.planner.logical.Sort.SortKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public class SortOperator implements Operator {

    private final Operator input;
    private final List<SortKey> sortKeys;
    private Iterator<Row> sortedIterator;

    public SortOperator(Operator input, List<SortKey> sortKeys) {
        this.input = input;
        this.sortKeys = sortKeys;
    }

    @Override
    public void open() {
        input.open();

        List<Row> rows = new ArrayList<>();
        Row row;
        while ((row = input.next()) != null) {
            rows.add(row);
        }

        Schema schema = input.getOutputSchema();
        Comparator<Row> comparator = buildComparator(schema);
        rows.sort(comparator);
        sortedIterator = rows.iterator();
    }

    private Comparator<Row> buildComparator(Schema schema) {
        Comparator<Row> result = null;
        for (SortKey key : sortKeys) {
            Comparator<Row> keyComparator = (r1, r2) -> {
                SqlValue v1 = key.expression().evaluate(r1, schema);
                SqlValue v2 = key.expression().evaluate(r2, schema);

                if (v1.isNull() && v2.isNull()) return 0;
                if (v1.isNull()) return key.nullOrder() == SortKey.NullOrder.NULLS_FIRST ? -1 : 1;
                if (v2.isNull()) return key.nullOrder() == SortKey.NullOrder.NULLS_FIRST ? 1 : -1;

                int cmp = v1.compareTo(v2);
                return key.direction() == SortKey.Direction.DESC ? -cmp : cmp;
            };
            result = result == null ? keyComparator : result.thenComparing(keyComparator);
        }
        return result != null ? result : (r1, r2) -> 0;
    }

    @Override
    public Row next() {
        return sortedIterator.hasNext() ? sortedIterator.next() : null;
    }

    @Override
    public Schema getOutputSchema() {
        return input.getOutputSchema();
    }

    @Override
    public void close() {
        input.close();
        sortedIterator = null;
    }
}
