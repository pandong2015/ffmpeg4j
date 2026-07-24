package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import io.github.pandong2015.ffmpeg4j.FfmpegException;
import io.github.pandong2015.ffmpeg4j.task.FfmpegWarning;
import io.github.pandong2015.ffmpeg4j.task.TaskHandle;
import io.github.pandong2015.ffmpeg4j.task.TaskId;
import io.github.pandong2015.ffmpeg4j.task.TaskReport;
import io.github.pandong2015.ffmpeg4j.task.TaskStatus;
import io.github.pandong2015.ffmpeg4j.task.WarningCode;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskReportMetricsTest {

    @Test
    void 成功与warning从同一报告收口且无taskId标签() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskReportMetrics metrics = new TaskReportMetrics(registry);
        CompletableFuture<TaskReport<String>> completion = new CompletableFuture<>();
        TaskHandle<String> handle = metrics.observe("transcode",
                () -> handle("success-high-cardinality-id", "transcode", completion));

        completion.complete(report(
                "success-high-cardinality-id", "transcode", TaskStatus.COMPLETED, "ok", null,
                List.of(new FfmpegWarning(
                        WarningCode.PROGRESS_UNAVAILABLE,
                        "任意消息",
                        Map.of("endpoint", "tcp://127.0.0.1:54321")))));

        assertThat(handle.completion().join().result()).isEqualTo("ok");
        assertTimer(registry, "transcode", "success");
        assertThat(registry.get(TaskReportMetrics.WARNINGS)
                .tags("operation", "transcode", "code", "PROGRESS_UNAVAILABLE")
                .counter().count()).isOne();
        assertNoTaskIdTag(registry);
    }

    @Test
    void 普通媒体失败按结构化reason计量() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskReportMetrics metrics = new TaskReportMetrics(registry);
        CompletableFuture<TaskReport<String>> completion = new CompletableFuture<>();
        metrics.observe("remux", () -> handle("failed", "remux", completion));
        FfmpegException failure = new FfmpegException(1, List.of("ffmpeg"), "tail", "unknown-filter");

        completion.complete(report(
                "failed", "remux", TaskStatus.FAILED, null, failure, List.of()));

        assertTimer(registry, "remux", "failure");
        assertThat(registry.get(MeteredFfmpegClient.ERRORS)
                .tags("operation", "remux", "reason", "unknown-filter")
                .counter().count()).isOne();
    }

    @Test
    void 取消记录cancelled且不误记error() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskReportMetrics metrics = new TaskReportMetrics(registry);
        CompletableFuture<TaskReport<String>> completion = new CompletableFuture<>();
        metrics.observe("hlsAbr", () -> handle("cancelled", "hlsAbr", completion));

        completion.complete(report(
                "cancelled", "hlsAbr", TaskStatus.CANCELLED, null, null, List.of()));

        assertTimer(registry, "hlsAbr", "cancelled");
        assertThat(registry.find(MeteredFfmpegClient.ERRORS)
                .tag("operation", "hlsAbr").counter()).isNull();
    }

    @Test
    void 报告式拒绝记录failure和error但不重复rejected指标() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskReportMetrics metrics = new TaskReportMetrics(registry);
        CompletableFuture<TaskReport<String>> completion = CompletableFuture.completedFuture(report(
                "rejected", "transcode", TaskStatus.FAILED, null,
                new RejectedExecutionException("full"), List.of()));

        metrics.observe("transcode", () -> handle("rejected", "transcode", completion));

        assertTimer(registry, "transcode", "failure");
        assertThat(registry.get(MeteredFfmpegClient.ERRORS)
                .tags("operation", "transcode", "reason", "error")
                .counter().count()).isOne();
        assertThat(registry.find(MeteredFfmpegClient.REJECTED).counter()).isNull();
    }

    @Test
    void 同步提交抛拒绝也完整收口() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskReportMetrics metrics = new TaskReportMetrics(registry);

        assertThatThrownBy(() -> metrics.<String>observe("transcode", () -> {
            throw new RejectedExecutionException("full");
        })).isInstanceOf(RejectedExecutionException.class);

        assertTimer(registry, "transcode", "failure");
        assertThat(registry.get(MeteredFfmpegClient.ERRORS)
                .tags("operation", "transcode", "reason", "error")
                .counter().count()).isOne();
    }

    private static TaskHandle<String> handle(
            String id, String operation, CompletableFuture<TaskReport<String>> completion) {
        return new TaskHandle<>(
                new TaskId(id), operation, completion, () -> TaskStatus.RUNNING, () -> false);
    }

    private static TaskReport<String> report(
            String id, String operation, TaskStatus status, String result, Throwable error,
            List<FfmpegWarning> warnings) {
        return new TaskReport<>(new TaskId(id), operation, status, result, error, warnings);
    }

    private static void assertTimer(
            SimpleMeterRegistry registry, String operation, String result) {
        assertThat(registry.get(MeteredFfmpegClient.TIMER)
                .tags("operation", operation, "result", result)
                .timer().count()).isOne();
    }

    private static void assertNoTaskIdTag(SimpleMeterRegistry registry) {
        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getKey())
                .doesNotContain("taskId", "message", "details");
    }
}
