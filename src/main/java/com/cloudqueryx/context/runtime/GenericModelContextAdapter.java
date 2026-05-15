package com.cloudqueryx.context.runtime;

import java.util.List;

public class GenericModelContextAdapter implements ModelContextAdapter {
    private final String key;
    private final int maxContextTokens;
    private final String formatStyle;
    private final boolean cacheHints;
    private final double costPerToken;

    public GenericModelContextAdapter(String key, int maxContextTokens, String formatStyle,
                                      boolean cacheHints, double costPerToken) {
        this.key = key;
        this.maxContextTokens = maxContextTokens;
        this.formatStyle = formatStyle;
        this.cacheHints = cacheHints;
        this.costPerToken = costPerToken;
    }

    @Override
    public String modelKey() {
        return key;
    }

    @Override
    public int maxContextWindow() {
        return maxContextTokens;
    }

    @Override
    public boolean supportsPromptCaching() {
        return cacheHints;
    }

    @Override
    public double estimatedCostUsd(int tokens) {
        return Math.round(tokens * costPerToken * 10000.0) / 10000.0;
    }

    @Override
    public String format(String query, String mode, List<TokenBudgetOptimizer.BundleCandidate> items) {
        if ("JSON_CONTEXT_OBJECT".equals(formatStyle)) return jsonFormat(query, mode, items);
        if ("XML_LIKE_SECTIONS".equals(formatStyle)) return xmlFormat(query, mode, items);
        return markdownFormat(query, mode, items);
    }

    private String markdownFormat(String query, String mode, List<TokenBudgetOptimizer.BundleCandidate> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("# CloudQueryX Context Bundle\n\n");
        sb.append("Query: ").append(query).append("\n");
        sb.append("Mode: ").append(mode).append("\n\n");
        sb.append("Use this context only when relevant. Do not invent missing facts.\n\n");
        appendMarkdownSection(sb, "Memories", items, "MEMORY");
        appendMarkdownSection(sb, "Sources", items, "SOURCE_CHUNK");
        appendMarkdownSection(sb, "Other Context", items, null);
        return sb.toString();
    }

    private void appendMarkdownSection(StringBuilder sb, String title,
                                       List<TokenBudgetOptimizer.BundleCandidate> items,
                                       String type) {
        sb.append("## ").append(title).append("\n");
        int count = 0;
        for (TokenBudgetOptimizer.BundleCandidate item : items) {
            boolean match = type == null
                    ? !"MEMORY".equals(item.result().type()) && !"SOURCE_CHUNK".equals(item.result().type())
                    : type.equals(item.result().type());
            if (!match) continue;
            count++;
            sb.append("- Score: ").append(String.format("%.3f", item.result().finalScore()))
                    .append("; Reason: ").append(item.result().reason()).append("\n")
                    .append(item.content()).append("\n\n");
        }
        if (count == 0) sb.append("No selected items.\n\n");
    }

    private String xmlFormat(String query, String mode, List<TokenBudgetOptimizer.BundleCandidate> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("<context_bundle>\n");
        sb.append("  <query>").append(escape(query)).append("</query>\n");
        sb.append("  <mode>").append(escape(mode)).append("</mode>\n");
        sb.append("  <items>\n");
        for (TokenBudgetOptimizer.BundleCandidate item : items) {
            sb.append("    <item type=\"").append(escape(item.result().type())).append("\" score=\"")
                    .append(String.format("%.3f", item.result().finalScore())).append("\">\n")
                    .append("      <reason>").append(escape(item.result().reason())).append("</reason>\n")
                    .append("      <content>").append(escape(item.content())).append("</content>\n")
                    .append("    </item>\n");
        }
        sb.append("  </items>\n");
        sb.append("  <instructions>Use this context only when relevant. Do not invent missing facts.</instructions>\n");
        sb.append("</context_bundle>");
        return sb.toString();
    }

    private String jsonFormat(String query, String mode, List<TokenBudgetOptimizer.BundleCandidate> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"query\": \"").append(escapeJson(query)).append("\",\n");
        sb.append("  \"mode\": \"").append(escapeJson(mode)).append("\",\n");
        sb.append("  \"items\": [\n");
        for (int i = 0; i < items.size(); i++) {
            TokenBudgetOptimizer.BundleCandidate item = items.get(i);
            sb.append("    {\"type\":\"").append(escapeJson(item.result().type()))
                    .append("\",\"score\":").append(String.format("%.3f", item.result().finalScore()))
                    .append(",\"reason\":\"").append(escapeJson(item.result().reason()))
                    .append("\",\"content\":\"").append(escapeJson(item.content())).append("\"}");
            if (i < items.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ],\n  \"instructions\": \"Use this context only when relevant. Do not invent missing facts.\"\n}");
        return sb.toString();
    }

    private String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
