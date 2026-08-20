package com.cloudqueryx.context.runtime;

import com.cloudqueryx.repository.AgenticMemoryFileRepository;
import com.cloudqueryx.repository.CompressedProfileRepository;
import com.cloudqueryx.repository.MemoryRepository;

import java.util.*;

public class AssistantMemoryRuntime {
    private static final int PROFILE_TOKEN_CAP = 800;
    private final CompressedProfileRepository profileRepo;
    private final AgenticMemoryFileRepository fileRepo;
    private final MemoryRepository memoryRepo;
    private final TokenEstimator tokenEstimator;

    public AssistantMemoryRuntime(CompressedProfileRepository profileRepo,
                                  AgenticMemoryFileRepository fileRepo,
                                  MemoryRepository memoryRepo,
                                  TokenEstimator tokenEstimator) {
        this.profileRepo = profileRepo;
        this.fileRepo = fileRepo;
        this.memoryRepo = memoryRepo;
        this.tokenEstimator = tokenEstimator;
    }

    public ProfileContext profile(String databaseId, String userId) {
        Optional<CompressedProfileRepository.ProfileRow> existing = profileRepo.get(databaseId, userId);
        if (existing.isPresent() && existing.get().tokenEstimate() <= PROFILE_TOKEN_CAP) {
            return new ProfileContext(existing.get().profileText(), existing.get().tokenEstimate(),
                    existing.get().version(), false, "Loaded bounded compressed profile");
        }
        List<MemoryRepository.MemoryRow> memories = memoryRepo.getByUser(databaseId, userId, 24);
        String profileText = summarizeProfile(memories);
        int tokens = tokenEstimator.estimate(profileText);
        CompressedProfileRepository.ProfileRow saved = profileRepo.upsert(databaseId, userId, profileText, tokens);
        fileRepo.write(databaseId, userId, "/profile/user.md", profileText, "Compressed user profile");
        return new ProfileContext(saved.profileText(), saved.tokenEstimate(), saved.version(),
                existing.isPresent(), existing.isPresent() ? "Compacted profile to token cap" : "Created compressed profile");
    }

    public AgenticLookup lookup(String databaseId, String userId, String query) {
        boolean shouldSearch = shouldSearch(query);
        if (!shouldSearch) {
            return new AgenticLookup(false, "none", List.of(), "Question did not require file-memory lookup");
        }
        List<AgenticMemoryFileRepository.FileRow> files = fileRepo.search(databaseId, query, 5);
        if (files.isEmpty()) {
            return new AgenticLookup(true, "memory.search", List.of(), "No matching memory files found");
        }
        return new AgenticLookup(true, "memory.search", files.stream().map(this::fileMap).toList(),
                "Assistant performed just-in-time memory file lookup");
    }

    public Map<String, Object> compactionCheck(ProfileContext profile, int conversationTokens) {
        boolean profileNeedsCompaction = profile.tokenEstimate() > PROFILE_TOKEN_CAP;
        boolean conversationNeedsCompaction = conversationTokens > 12000;
        return Map.of(
                "profileTokens", profile.tokenEstimate(),
                "profileTokenCap", PROFILE_TOKEN_CAP,
                "profileCompacted", profile.compacted(),
                "profileNeedsCompaction", profileNeedsCompaction,
                "conversationTokens", conversationTokens,
                "conversationNeedsCompaction", conversationNeedsCompaction,
                "policy", "Profile is extractively compacted; older conversation would be summarized when threshold is exceeded");
    }

    private boolean shouldSearch(String query) {
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return lower.contains("remember") || lower.contains("what did") || lower.contains("architecture")
                || lower.contains("project") || lower.contains("context") || lower.contains("why")
                || lower.contains("explain") || lower.contains("retrieve");
    }

    private String summarizeProfile(List<MemoryRepository.MemoryRow> memories) {
        if (memories.isEmpty()) {
            return """
                    # Compressed User Profile
                    No durable user profile facts have been stored yet.
                    """;
        }
        StringBuilder sb = new StringBuilder("# Compressed User Profile\n");
        int count = 0;
        for (MemoryRepository.MemoryRow row : memories) {
            if (row.content() == null || row.content().isBlank()) continue;
            sb.append("- [").append(row.memoryType()).append("] ")
                    .append(trim(row.content(), 220))
                    .append(" (importance ")
                    .append(String.format(Locale.ROOT, "%.2f", row.importance()))
                    .append(")\n");
            count++;
            if (tokenEstimator.estimate(sb.toString()) >= PROFILE_TOKEN_CAP || count >= 12) break;
        }
        return sb.toString();
    }

    private Map<String, Object> fileMap(AgenticMemoryFileRepository.FileRow row) {
        return Map.of(
                "path", row.path(),
                "summary", row.summary() == null ? "" : row.summary(),
                "content", trim(row.content(), 900),
                "version", row.version());
    }

    private String trim(String value, int max) {
        if (value == null) return "";
        String normalized = value.strip();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...";
    }

    public record ProfileContext(String text, int tokenEstimate, int version, boolean compacted, String reason) {}
    public record AgenticLookup(boolean attempted, String command, List<Map<String, Object>> files, String reason) {}
}
