package com.cloudqueryx.cli;

import com.cloudqueryx.common.Row;
import com.cloudqueryx.common.Schema;
import com.cloudqueryx.execution.ExecutionEngine;

import java.util.List;

public class ResultFormatter {

    public static String formatTable(ExecutionEngine.QueryResult result) {
        Schema schema = result.schema();
        List<Row> rows = result.rows();

        if (schema.getColumnCount() == 0) {
            return "OK";
        }

        int[] widths = new int[schema.getColumnCount()];
        String[] headers = new String[schema.getColumnCount()];

        for (int i = 0; i < schema.getColumnCount(); i++) {
            headers[i] = schema.getColumn(i).name();
            widths[i] = headers[i].length();
        }

        String[][] data = new String[rows.size()][schema.getColumnCount()];
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < schema.getColumnCount(); c++) {
                data[r][c] = rows.get(r).getValue(c).asString();
                widths[c] = Math.max(widths[c], data[r][c].length());
            }
        }

        for (int i = 0; i < widths.length; i++) {
            widths[i] = Math.min(widths[i], 50);
        }

        StringBuilder sb = new StringBuilder();

        sb.append(formatSeparator(widths));
        sb.append(formatRow(headers, widths));
        sb.append(formatSeparator(widths));

        for (String[] row : data) {
            sb.append(formatRow(row, widths));
        }

        sb.append(formatSeparator(widths));
        sb.append(String.format("%d row(s) returned in %dms (planning: %dms, execution: %dms)%n",
                rows.size(), result.totalMs(), result.planningMs(), result.executionMs()));
        sb.append(String.format("Scanned: %d rows | Processed: %s%n",
                result.rowsScanned(), formatBytes(result.bytesProcessed())));

        return sb.toString();
    }

    private static String formatSeparator(int[] widths) {
        StringBuilder sb = new StringBuilder("+");
        for (int width : widths) {
            sb.append("-".repeat(width + 2)).append("+");
        }
        sb.append("\n");
        return sb.toString();
    }

    private static String formatRow(String[] values, int[] widths) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < values.length; i++) {
            String val = values[i];
            if (val.length() > widths[i]) {
                val = val.substring(0, widths[i] - 2) + "..";
            }
            sb.append(String.format(" %-" + widths[i] + "s |", val));
        }
        sb.append("\n");
        return sb.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
