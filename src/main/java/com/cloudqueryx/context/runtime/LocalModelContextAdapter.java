package com.cloudqueryx.context.runtime;

public class LocalModelContextAdapter extends AbstractModelContextAdapter {
    public String modelKey() { return "local"; }
    public int maxContextWindow() { return 32_000; }
    public boolean supportsPromptCaching() { return false; }
}
