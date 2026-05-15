package com.cloudqueryx.benchmark;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class BenchmarkRunnerTest {

    @Test
    void sqlBenchmarkRuns() {
        BenchmarkRunner runner = new BenchmarkRunner();
        runner.generateSqlData(100, 500, 10);
        assertThatCode(() -> runner.runSqlBenchmarks()).doesNotThrowAnyException();
    }

    @Test
    void vectorBenchmarkRuns() {
        BenchmarkRunner runner = new BenchmarkRunner();
        runner.generateVectorData(100, 128);
        assertThatCode(() -> runner.runVectorBenchmarks()).doesNotThrowAnyException();
    }

    @Test
    void memoryBenchmarkRuns() {
        BenchmarkRunner runner = new BenchmarkRunner();
        runner.generateMemoryData(50);
        assertThatCode(() -> runner.runMemoryBenchmarks()).doesNotThrowAnyException();
    }

    @Test
    void printReportDoesNotThrow() {
        BenchmarkRunner runner = new BenchmarkRunner();
        runner.generateSqlData(10, 20, 5);
        runner.generateVectorData(10, 128);
        runner.generateMemoryData(10);
        runner.runSqlBenchmarks();
        runner.runVectorBenchmarks();
        runner.runMemoryBenchmarks();
        assertThatCode(runner::printReport).doesNotThrowAnyException();
    }

    @Test
    void benchmarkResultRecord() {
        var result = new BenchmarkRunner.BenchmarkResult("test", 42, 100, 1000);
        assertThat(result.name()).isEqualTo("test");
        assertThat(result.timeMs()).isEqualTo(42);
        assertThat(result.rowsReturned()).isEqualTo(100);
        assertThat(result.rowsScanned()).isEqualTo(1000);
    }
}
