package com.cloudqueryx.context.runtime;

public class OpenAIContextAdapter extends AbstractModelContextAdapter {
    public String modelKey() { return "openai"; }
    public int maxContextWindow() { return 128_000; }
    public boolean supportsPromptCaching() { return true; }
}
