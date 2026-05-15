package com.cloudqueryx.web.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JsonUtil() {}

    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseBody(InputStream body) {
        try {
            return MAPPER.readValue(body, Map.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON body");
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseString(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON");
        }
    }

    public static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    public static int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) return Integer.parseInt(s);
        return defaultVal;
    }

    public static double getDouble(Map<String, Object> map, String key, double defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) return Double.parseDouble(s);
        return defaultVal;
    }

    public static boolean getBoolean(Map<String, Object> map, String key, boolean defaultVal) {
        Object val = map.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return defaultVal;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Map) return (Map<String, Object>) val;
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    public static java.util.List<Object> getList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof java.util.List) return (java.util.List<Object>) val;
        return java.util.List.of();
    }
}
