package com.cloudqueryx.memory;

public enum MemoryType {
    // Core 4 access patterns (Oracle-inspired)
    WORKING,       // Short-lived, high-decay — active conversation context
    SEMANTIC,      // Long-lived facts and knowledge — slow decay
    EPISODIC,      // Event-based autobiographical memories — medium decay
    PROCEDURAL,    // Learned patterns and procedures — very slow decay

    // Granular types (map to a core pattern for decay)
    CONVERSATION,
    USER_PROFILE,
    DECISION,
    BEHAVIOR_EVENT,
    DOCUMENT,
    SUMMARY,
    AGENT_ACTION,
    FEEDBACK,
    PREFERENCE,
    FACT;

    public MemoryType corePattern() {
        return switch (this) {
            case WORKING, CONVERSATION -> WORKING;
            case SEMANTIC, FACT, DOCUMENT, SUMMARY -> SEMANTIC;
            case EPISODIC, BEHAVIOR_EVENT, DECISION, FEEDBACK -> EPISODIC;
            case PROCEDURAL, AGENT_ACTION, USER_PROFILE, PREFERENCE -> PROCEDURAL;
        };
    }
}
