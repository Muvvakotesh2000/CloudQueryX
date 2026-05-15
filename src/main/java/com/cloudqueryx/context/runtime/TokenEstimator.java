package com.cloudqueryx.context.runtime;

public class TokenEstimator {
    public int estimate(String text) {
        if (text == null || text.isBlank()) return 0;
        int chars = text.length();
        int words = text.trim().split("\\s+").length;
        return Math.max(1, Math.max((int) Math.ceil(chars / 4.0), (int) Math.ceil(words * 1.35)));
    }
}
