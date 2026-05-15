package com.cloudqueryx.distributed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

public class TaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskScheduler.class);

    private final ClusterManager clusterManager;
    private final ExecutorService executorService;
    private final Map<String, TaskState> taskStates = new ConcurrentHashMap<>();

    public TaskScheduler(ClusterManager clusterManager, int threadPoolSize) {
        this.clusterManager = clusterManager;
        this.executorService = Executors.newFixedThreadPool(threadPoolSize);
    }

    public List<TaskAssignment> schedule(List<QueryFragmenter.QueryFragment> fragments) {
        List<ClusterManager.WorkerInfo> workers = clusterManager.getActiveWorkers();
        if (workers.isEmpty()) {
            log.warn("No active workers available, executing locally");
            return fragments.stream()
                    .map(f -> new TaskAssignment(f, null))
                    .toList();
        }

        List<TaskAssignment> assignments = new ArrayList<>();
        for (int i = 0; i < fragments.size(); i++) {
            QueryFragmenter.QueryFragment fragment = fragments.get(i);
            ClusterManager.WorkerInfo worker;

            if (fragment.type() == QueryFragmenter.QueryFragment.FragmentType.LEAF) {
                worker = workers.get(i % workers.size());
            } else {
                worker = clusterManager.selectLeastLoadedWorker();
            }

            TaskAssignment assignment = new TaskAssignment(fragment, worker);
            assignments.add(assignment);
            taskStates.put(fragment.fragmentId(), new TaskState(assignment));

            log.debug("Assigned fragment {} to worker {}",
                    fragment.fragmentId().substring(0, 8),
                    worker != null ? worker.nodeId : "local");
        }

        return assignments;
    }

    public CompletableFuture<Void> submitTask(TaskAssignment assignment, Runnable task) {
        return CompletableFuture.runAsync(() -> {
            String fragId = assignment.fragment().fragmentId();
            taskStates.get(fragId).state = TaskState.State.RUNNING;
            try {
                task.run();
                taskStates.get(fragId).state = TaskState.State.COMPLETED;
            } catch (Exception e) {
                taskStates.get(fragId).state = TaskState.State.FAILED;
                taskStates.get(fragId).error = e.getMessage();
                throw e;
            }
        }, executorService);
    }

    public TaskState getTaskState(String fragmentId) {
        return taskStates.get(fragmentId);
    }

    public boolean allTasksCompleted(List<TaskAssignment> assignments) {
        return assignments.stream()
                .map(a -> taskStates.get(a.fragment().fragmentId()))
                .allMatch(s -> s != null && s.state == TaskState.State.COMPLETED);
    }

    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public record TaskAssignment(
            QueryFragmenter.QueryFragment fragment,
            ClusterManager.WorkerInfo worker
    ) {
        public boolean isLocal() {
            return worker == null;
        }
    }

    public static class TaskState {
        public volatile State state;
        public volatile String error;
        public final TaskAssignment assignment;
        public final long createdAt;

        TaskState(TaskAssignment assignment) {
            this.assignment = assignment;
            this.state = State.PENDING;
            this.createdAt = System.currentTimeMillis();
        }

        public enum State {
            PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
        }
    }
}
