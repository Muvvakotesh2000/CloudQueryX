package com.cloudqueryx.web.auth;

import com.cloudqueryx.config.AppConfig;
import com.cloudqueryx.web.api.JsonUtil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

public class SupabaseJwtValidator {

    private final AppConfig config;

    public SupabaseJwtValidator(AppConfig config) {
        this.config = config;
    }

    public Optional<SupabaseUser> validate(String jwt) {
        String secret = config.supabaseJwtSecret();
        if (jwt == null || jwt.isBlank() || secret == null || secret.isBlank()) return Optional.empty();
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return Optional.empty();
        try {
            Map<String, Object> header = JsonUtil.parseString(decode(parts[0]));
            if (!"HS256".equals(header.get("alg"))) return Optional.empty();
            String signed = parts[0] + "." + parts[1];
            String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(hmacSha256(signed, secret));
            if (!constantTimeEquals(expected, parts[2])) return Optional.empty();

            Map<String, Object> payload = JsonUtil.parseString(decode(parts[1]));
            long exp = number(payload.get("exp"));
            if (exp > 0 && Instant.now().getEpochSecond() >= exp) return Optional.empty();
            String subject = string(payload.get("sub"));
            String email = string(payload.get("email"));
            if (subject == null || subject.isBlank()) return Optional.empty();
            if (email == null || email.isBlank()) email = subject + "@supabase.local";
            return Optional.of(new SupabaseUser(subject, email.toLowerCase()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String decode(String part) {
        return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }

    private byte[] hmacSha256(String value, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] left = a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getBytes(StandardCharsets.UTF_8);
        if (left.length != right.length) return false;
        int diff = 0;
        for (int i = 0; i < left.length; i++) diff |= left[i] ^ right[i];
        return diff == 0;
    }

    private long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private String string(Object value) {
        return value != null ? value.toString() : null;
    }

    public record SupabaseUser(String id, String email) {}
}
