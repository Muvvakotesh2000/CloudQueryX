package com.cloudqueryx.context.runtime;

public class ClaudeContextAdapter extends AbstractModelContextAdapter {
    public String modelKey() { return "claude"; }
    public int maxContextWindow() { return 200_000; }
    public boolean supportsPromptCaching() { return true; }
}
