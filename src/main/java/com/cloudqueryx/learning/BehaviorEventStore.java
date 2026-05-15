package com.cloudqueryx.learning;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class BehaviorEventStore {

    private final List<BehaviorEvent> events = new CopyOnWriteArrayList<>();
    private final Map<String, List<BehaviorEvent>> byUser = new ConcurrentHashMap<>();
    private final Map<String, List<BehaviorEvent>> byType = new ConcurrentHashMap<>();

    public void ingest(BehaviorEvent event) {
        events.add(event);
        byUser.computeIfAbsent(event.userId(), k -> new CopyOnWriteArrayList<>()).add(event);
        byType.computeIfAbsent(event.eventType(), k -> new CopyOnWriteArrayList<>()).add(event);
    }

    public List<BehaviorEvent> queryByUser(String userId, int limit) {
        List<BehaviorEvent> userEvents = byUser.getOrDefault(userId, List.of());
        return userEvents.stream()
                .sorted(Comparator.comparing(BehaviorEvent::timestamp).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<BehaviorEvent> queryByType(String eventType, int limit) {
        List<BehaviorEvent> typeEvents = byType.getOrDefault(eventType, List.of());
        return typeEvents.stream()
                .sorted(Comparator.comparing(BehaviorEvent::timestamp).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<BehaviorEvent> queryByTimeRange(Instant from, Instant to) {
        return events.stream()
                .filter(e -> !e.timestamp().isBefore(from) && !e.timestamp().isAfter(to))
                .sorted(Comparator.comparing(BehaviorEvent::timestamp))
                .collect(Collectors.toList());
    }

    public Map<String, Long> getEventTypeCounts(String userId) {
        return byUser.getOrDefault(userId, List.of()).stream()
                .collect(Collectors.groupingBy(BehaviorEvent::eventType, Collectors.counting()));
    }

    public int size() {
        return events.size();
    }
}
