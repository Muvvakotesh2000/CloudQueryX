package com.cloudqueryx.execution.operators;

import com.cloudqueryx.common.*;
import com.cloudqueryx.execution.Operator;
import com.cloudqueryx.expression.Expression;

import java.util.ArrayList;
import java.util.List;

public class ProjectOperator implements Operator {

    private final Operator input;
    private final List<Expression> expressions;
    private final List<String> aliases;
    private Schema outputSchema;

    public ProjectOperator(Operator input, List<Expression> expressions, List<String> aliases) {
        this.input = input;
        this.expressions = expressions;
        this.aliases = aliases;
    }

    @Override
    public void open() {
        input.open();
        List<Column> columns = new ArrayList<>();
        Schema inputSchema = input.getOutputSchema();
        for (int i = 0; i < expressions.size(); i++) {
            String name = (aliases != null && i < aliases.size() && aliases.get(i) != null)
                    ? aliases.get(i) : "col" + i;
            columns.add(new Column(name, expressions.get(i).getType(inputSchema)));
        }
        outputSchema = new Schema(columns);
    }

    @Override
    public Row next() {
        Row inputRow = input.next();
        if (inputRow == null) return null;

        SqlValue[] values = new SqlValue[expressions.size()];
        Schema inputSchema = input.getOutputSchema();
        for (int i = 0; i < expressions.size(); i++) {
            values[i] = expressions.get(i).evaluate(inputRow, inputSchema);
        }
        return new Row(values);
    }

    @Override
    public Schema getOutputSchema() {
        return outputSchema;
    }

    @Override
    public void close() {
        input.close();
    }
}
