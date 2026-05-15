import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

public class CheckDatabaseWritable {
    public static void main(String[] args) throws Exception {
        Map<String, String> env = readEnv(Path.of(".env"));
        try (Connection connection = DriverManager.getConnection(
                required(env, "CLOUDQUERYX_DB_URL"),
                required(env, "CLOUDQUERYX_DB_USER"),
                required(env, "CLOUDQUERYX_DB_PASSWORD"));
             Statement statement = connection.createStatement()) {
            print(statement, "SHOW transaction_read_only");
            print(statement, "SELECT current_setting('transaction_read_only')");
            print(statement, "SELECT pg_is_in_recovery()");
            statement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE");
            print(statement, "SHOW transaction_read_only");
        }
    }

    private static void print(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            if (rs.next()) System.out.println(sql + " => " + rs.getString(1));
        }
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + key);
        return value;
    }

    private static Map<String, String> readEnv(Path path) throws Exception {
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) continue;
            String key = trimmed.substring(0, trimmed.indexOf('=')).trim();
            String value = trimmed.substring(trimmed.indexOf('=') + 1).trim();
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
        }
        return values;
    }
}
