package com.cloudqueryx.distributed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ClusterManager {

    private static final Logger log = LoggerFactory.getLogger(ClusterManager.class);

    private final Map<String, WorkerInfo> workers = new ConcurrentHashMap<>();
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);
    private final String coordinatorId;

    public ClusterManager(String coordinatorId) {
        this.coordinatorId = coordinatorId;
    }

    public void registerWorker(String nodeId, String host, int port) {
        WorkerInfo info = new WorkerInfo(nodeId, host, port);
        workers.put(nodeId, info);
        log.info("Worker registered: {} at {}:{}", nodeId, host, port);
    }

    public void deregisterWorker(String nodeId) {
        workers.remove(nodeId);
        log.info("Worker deregistered: {}", nodeId);
    }

    public void updateWorkerHealth(String nodeId, double cpuPercent,
                                    long memoryUsed, long memoryTotal, int activeTasks) {
        WorkerInfo info = workers.get(nodeId);
        if (info != null) {
            info.lastHeartbeat = System.currentTimeMillis();
            info.cpuPercent = cpuPercent;
            info.memoryUsed = memoryUsed;
            info.memoryTotal = memoryTotal;
            info.activeTasks = activeTasks;
        }
    }

    public List<WorkerInfo> getActiveWorkers() {
        long now = System.currentTimeMillis();
        return workers.values().stream()
                .filter(w -> now - w.lastHeartbeat < 30_000)
                .toList();
    }

    public WorkerInfo selectWorker() {
        List<WorkerInfo> active = getActiveWorkers();
        if (active.isEmpty()) return null;

        int idx = roundRobinCounter.getAndIncrement() % active.size();
        return active.get(idx);
    }

    public WorkerInfo selectLeastLoadedWorker() {
        return getActiveWorkers().stream()
                .min(Comparator.comparingInt(w -> w.activeTasks))
                .orElse(null);
    }

    public List<WorkerInfo> selectWorkers(int count) {
        List<WorkerInfo> active = new ArrayList<>(getActiveWorkers());
        active.sort(Comparator.comparingInt(w -> w.activeTasks));
        return active.subList(0, Math.min(count, active.size()));
    }

    public int getWorkerCount() {
        return getActiveWorkers().size();
    }

    public String getCoordinatorId() {
        return coordinatorId;
    }

    public void checkWorkerHealth() {
        long now = System.currentTimeMillis();
        List<String> staleWorkers = new ArrayList<>();
        for (var entry : workers.entrySet()) {
            if (now - entry.getValue().lastHeartbeat > 30_000) {
                staleWorkers.add(entry.getKey());
            }
        }
        for (String id : staleWorkers) {
            workers.remove(id);
            log.warn("Worker {} removed due to heartbeat timeout", id);
        }
    }

    public static class WorkerInfo {
        public final String nodeId;
        public final String host;
        public final int port;
        public volatile long lastHeartbeat;
        public volatile double cpuPercent;
        public volatile long memoryUsed;
        public volatile long memoryTotal;
        public volatile int activeTasks;

        WorkerInfo(String nodeId, String host, int port) {
            this.nodeId = nodeId;
            this.host = host;
            this.port = port;
            this.lastHeartbeat = System.currentTimeMillis();
        }

        public String address() {
            return host + ":" + port;
        }

        @Override
        public String toString() {
            return String.format("Worker[%s @ %s:%d, cpu=%.1f%%, tasks=%d]",
                    nodeId, host, port, cpuPercent, activeTasks);
        }
    }
}
