package com.cloudqueryx.storage;

import com.cloudqueryx.catalog.Catalog;
import com.cloudqueryx.catalog.TableDescriptor;
import com.cloudqueryx.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CsvDataSource {

    private static final Logger log = LoggerFactory.getLogger(CsvDataSource.class);

    public static TableDescriptor loadCsv(Path filePath, String tableName, Catalog catalog) throws IOException {
        log.info("Loading CSV file: {} as table: {}", filePath, tableName);

        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String headerLine = reader.readLine();
            if (headerLine == null) throw new IOException("Empty CSV file: " + filePath);

            String[] headers = parseCsvLine(headerLine);
            List<String[]> dataLines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    dataLines.add(parseCsvLine(line));
                }
            }

            DataType[] types = inferTypes(headers, dataLines);
            List<Column> columns = new ArrayList<>();
            for (int i = 0; i < headers.length; i++) {
                columns.add(new Column(headers[i].trim(), types[i], true, tableName));
            }
            Schema schema = new Schema(columns);
            TableDescriptor table = new TableDescriptor(tableName, schema);

            for (String[] fields : dataLines) {
                SqlValue[] values = new SqlValue[headers.length];
                for (int i = 0; i < headers.length; i++) {
                    String field = i < fields.length ? fields[i].trim() : "";
                    values[i] = parseValue(field, types[i]);
                }
                table.insertRow(new Row(values));
            }

            table.computeStatistics();
            catalog.registerTable(table);

            log.info("Loaded {} rows into table {}", dataLines.size(), tableName);
            return table;
        }
    }

    public void loadFromString(String tableName, String csvContent, Catalog catalog) {
        String[] lines = csvContent.split("\n");
        if (lines.length == 0) throw new IllegalArgumentException("Empty CSV content");

        String[] headers = parseCsvLine(lines[0]);
        List<String[]> dataLines = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) dataLines.add(parseCsvLine(lines[i]));
        }

        DataType[] types = inferTypes(headers, dataLines);
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < headers.length; i++) {
            columns.add(new Column(headers[i].trim(), types[i], true, tableName));
        }
        Schema schema = new Schema(columns);
        TableDescriptor table = new TableDescriptor(tableName, schema);

        for (String[] fields : dataLines) {
            SqlValue[] values = new SqlValue[headers.length];
            for (int i = 0; i < headers.length; i++) {
                String field = i < fields.length ? fields[i].trim() : "";
                values[i] = parseValue(field, types[i]);
            }
            table.insertRow(new Row(values));
        }

        table.computeStatistics();
        catalog.registerTable(table);
        log.info("Loaded {} rows into table {} from CSV string", dataLines.size(), tableName);
    }

    private static DataType[] inferTypes(String[] headers, List<String[]> data) {
        DataType[] types = new DataType[headers.length];
        for (int col = 0; col < headers.length; col++) {
            types[col] = DataType.VARCHAR;
            boolean allInt = true, allDouble = true, allBool = true;

            for (String[] row : data) {
                if (col >= row.length) continue;
                String val = row[col].trim();
                if (val.isEmpty() || val.equalsIgnoreCase("null")) continue;

                if (allInt) {
                    try { Long.parseLong(val); } catch (NumberFormatException e) { allInt = false; }
                }
                if (allDouble) {
                    try { Double.parseDouble(val); } catch (NumberFormatException e) { allDouble = false; }
                }
                if (allBool && !val.equalsIgnoreCase("true") && !val.equalsIgnoreCase("false")) {
                    allBool = false;
                }
            }

            if (allBool && !data.isEmpty()) types[col] = DataType.BOOLEAN;
            else if (allInt && !data.isEmpty()) types[col] = DataType.BIGINT;
            else if (allDouble && !data.isEmpty()) types[col] = DataType.DOUBLE;
        }
        return types;
    }

    private static SqlValue parseValue(String field, DataType type) {
        if (field.isEmpty() || field.equalsIgnoreCase("null")) return SqlValue.ofNull();
        return switch (type) {
            case INTEGER -> SqlValue.ofInt(Integer.parseInt(field));
            case BIGINT -> SqlValue.ofLong(Long.parseLong(field));
            case DOUBLE -> SqlValue.ofDouble(Double.parseDouble(field));
            case BOOLEAN -> SqlValue.ofBoolean(Boolean.parseBoolean(field));
            default -> SqlValue.ofString(field);
        };
    }

    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}
