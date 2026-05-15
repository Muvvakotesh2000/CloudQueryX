package com.cloudqueryx.distributed;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class DistributedTest {

    @Test
    void clusterManagerRegisterAndSelect() {
        ClusterManager cm = new ClusterManager("coord-1");
        assertThat(cm.getWorkerCount()).isEqualTo(0);
        assertThat(cm.selectWorker()).isNull();

        cm.registerWorker("w1", "10.0.0.1", 9091);
        cm.registerWorker("w2", "10.0.0.2", 9092);
        assertThat(cm.getWorkerCount()).isEqualTo(2);

        ClusterManager.WorkerInfo selected = cm.selectWorker();
        assertThat(selected).isNotNull();
        assertThat(selected.nodeId).isIn("w1", "w2");
    }

    @Test
    void clusterManagerLeastLoaded() {
        ClusterManager cm = new ClusterManager("coord-1");
        cm.registerWorker("w1", "10.0.0.1", 9091);
        cm.registerWorker("w2", "10.0.0.2", 9092);
        cm.updateWorkerHealth("w1", 50.0, 1000, 2000, 5);
        cm.updateWorkerHealth("w2", 30.0, 500, 2000, 1);

        ClusterManager.WorkerInfo least = cm.selectLeastLoadedWorker();
        assertThat(least.nodeId).isEqualTo("w2");
    }

    @Test
    void clusterManagerDeregister() {
        ClusterManager cm = new ClusterManager("coord-1");
        cm.registerWorker("w1", "10.0.0.1", 9091);
        assertThat(cm.getWorkerCount()).isEqualTo(1);

        cm.deregisterWorker("w1");
        assertThat(cm.getWorkerCount()).isEqualTo(0);
    }

    @Test
    void clusterManagerSelectMultiple() {
        ClusterManager cm = new ClusterManager("coord-1");
        cm.registerWorker("w1", "10.0.0.1", 9091);
        cm.registerWorker("w2", "10.0.0.2", 9092);
        cm.registerWorker("w3", "10.0.0.3", 9093);

        List<ClusterManager.WorkerInfo> selected = cm.selectWorkers(2);
        assertThat(selected).hasSize(2);
    }

    @Test
    void clusterManagerRoundRobin() {
        ClusterManager cm = new ClusterManager("coord-1");
        cm.registerWorker("w1", "10.0.0.1", 9091);
        cm.registerWorker("w2", "10.0.0.2", 9092);

        String first = cm.selectWorker().nodeId;
        String second = cm.selectWorker().nodeId;
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void workerInfoAddress() {
        ClusterManager cm = new ClusterManager("coord-1");
        cm.registerWorker("w1", "10.0.0.1", 9091);
        ClusterManager.WorkerInfo w = cm.getActiveWorkers().get(0);
        assertThat(w.address()).isEqualTo("10.0.0.1:9091");
    }

    @Test
    void taskSchedulerLocalFallback() {
        ClusterManager cm = new ClusterManager("coord-1");
        TaskScheduler scheduler = new TaskScheduler(cm, 2);

        QueryFragmenter fragmenter = new QueryFragmenter();
        QueryFragmenter.QueryFragment frag = new QueryFragmenter.QueryFragment(
                "test-frag", null, 0, QueryFragmenter.QueryFragment.FragmentType.ROOT);
        List<TaskScheduler.TaskAssignment> assignments = scheduler.schedule(List.of(frag));

        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).isLocal()).isTrue();

        scheduler.shutdown();
    }

    @Test
    void taskSchedulerWithWorkers() {
        ClusterManager cm = new ClusterManager("coord-1");
        cm.registerWorker("w1", "10.0.0.1", 9091);
        TaskScheduler scheduler = new TaskScheduler(cm, 2);

        QueryFragmenter.QueryFragment frag = new QueryFragmenter.QueryFragment(
                "test-frag", null, 0, QueryFragmenter.QueryFragment.FragmentType.LEAF);
        List<TaskScheduler.TaskAssignment> assignments = scheduler.schedule(List.of(frag));

        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).isLocal()).isFalse();
        assertThat(assignments.get(0).worker().nodeId).isEqualTo("w1");

        scheduler.shutdown();
    }

    @Test
    void taskSubmitAndComplete() throws Exception {
        ClusterManager cm = new ClusterManager("coord-1");
        cm.registerWorker("w1", "10.0.0.1", 9091);
        TaskScheduler scheduler = new TaskScheduler(cm, 2);

        QueryFragmenter.QueryFragment frag = new QueryFragmenter.QueryFragment(
                "test-frag", null, 0, QueryFragmenter.QueryFragment.FragmentType.LEAF);
        List<TaskScheduler.TaskAssignment> assignments = scheduler.schedule(List.of(frag));
        TaskScheduler.TaskAssignment assignment = assignments.get(0);

        scheduler.submitTask(assignment, () -> {}).get();

        assertThat(scheduler.getTaskState("test-frag").state)
                .isEqualTo(TaskScheduler.TaskState.State.COMPLETED);
        assertThat(scheduler.allTasksCompleted(assignments)).isTrue();

        scheduler.shutdown();
    }
}
