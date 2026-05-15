package com.cloudqueryx.context.runtime;

public class GeminiContextAdapter extends AbstractModelContextAdapter {
    public String modelKey() { return "gemini"; }
    public int maxContextWindow() { return 1_000_000; }
    public boolean supportsPromptCaching() { return true; }
}
