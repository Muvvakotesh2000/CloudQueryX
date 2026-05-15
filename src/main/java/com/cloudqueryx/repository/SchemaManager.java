package com.cloudqueryx.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class SchemaManager {

    private static final Logger log = LoggerFactory.getLogger(SchemaManager.class);

    private SchemaManager() {}

    public static void runMigration(DatabaseConfig db, String resourcePath) {
        try (InputStream is = SchemaManager.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.warn("Migration file not found: {}", resourcePath);
                return;
            }
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            List<String> statements = splitStatements(sql);
            try (Connection conn = db.getConnection();
                 Statement stmt = conn.createStatement()) {
                int applied = 0;
                for (String s : statements) {
                    try {
                        stmt.execute(s);
                        applied++;
                    } catch (SQLException e) {
                        log.debug("Statement skipped ({}): {}", e.getMessage(), s.substring(0, Math.min(80, s.length())));
                    }
                }
                log.info("Migration applied: {} ({}/{} statements)", resourcePath, applied, statements.size());
            }
        } catch (SQLException e) {
            log.warn("Migration connection failed ({}): {}", resourcePath, e.getMessage());
        } catch (IOException e) {
            log.error("Failed to read migration file: {}", resourcePath, e);
        }
    }

    private static List<String> splitStatements(String sql) {
        List<String> results = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inDollarQuote = false;
        String[] lines = sql.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") && !inDollarQuote) continue;

            if (trimmed.contains("$$")) {
                inDollarQuote = !inDollarQuote;
                if (trimmed.contains("$$") && trimmed.indexOf("$$") != trimmed.lastIndexOf("$$")) {
                    inDollarQuote = false;
                }
            }

            current.append(line).append("\n");

            if (!inDollarQuote && trimmed.endsWith(";")) {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty() && !stmt.equals(";")) {
                    results.add(stmt);
                }
                current.setLength(0);
            }
        }

        String remaining = current.toString().trim();
        if (!remaining.isEmpty() && !remaining.equals(";")) {
            results.add(remaining);
        }
        return results;
    }
}
