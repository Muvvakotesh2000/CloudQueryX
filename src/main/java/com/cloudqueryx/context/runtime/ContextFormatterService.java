package com.cloudqueryx.context.runtime;

import java.util.*;

public class ContextFormatterService {
    private final Map<String, ModelContextAdapter> adapters;

    public ContextFormatterService() {
        List<ModelContextAdapter> list = List.of(
                new GenericModelContextAdapter("small-context-model", 8000, "MARKDOWN_SECTIONS", false, 0.000001),
                new GenericModelContextAdapter("medium-context-model", 32000, "XML_LIKE_SECTIONS", true, 0.000002),
                new GenericModelContextAdapter("large-context-model", 128000, "XML_LIKE_SECTIONS", true, 0.000003),
                new GenericModelContextAdapter("local-model", 16000, "PLAIN_TEXT", false, 0.0),
                new GenericModelContextAdapter("custom-model", 64000, "JSON_CONTEXT_OBJECT", false, 0.0)
        );
        Map<String, ModelContextAdapter> map = new HashMap<>();
        for (ModelContextAdapter adapter : list) map.put(adapter.modelKey(), adapter);
        this.adapters = Map.copyOf(map);
    }

    public ModelContextAdapter adapter(String key) {
        if (key == null || key.isBlank()) return adapters.get("medium-context-model");
        return adapters.getOrDefault(key.toLowerCase(Locale.ROOT), adapters.get("medium-context-model"));
    }
}
