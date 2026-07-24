package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import io.github.pandong2015.ffmpeg4j.FfmpegException;
import io.github.pandong2015.ffmpeg4j.task.FfmpegWarning;
import io.github.pandong2015.ffmpeg4j.task.TaskHandle;
import io.github.pandong2015.ffmpeg4j.task.TaskReport;
import io.github.pandong2015.ffmpeg4j.task.TaskStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 任务句柄的终态计量器。
 *
 * <p>从提交调用开始计时，以 {@link TaskReport} 为唯一收口；不维护 active，也不记录 lifecycle/rejected，
 * 避免与生命周期观察器重复。警告标签只含 operation/code。
 */
final class TaskReportMetrics {

    static final String WARNINGS = "ffmpeg4j.task.warnings";

    private final MeterRegistry registry;

    TaskReportMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    <T> TaskHandle<T> observe(String operation, Supplier<TaskHandle<T>> submission) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(submission, "submission");
        Timer.Sample sample = Timer.start(registry);
        AtomicBoolean finished = new AtomicBoolean();
        try {
            TaskHandle<T> handle = submission.get();
            handle.completion().thenAccept(report -> finish(report, sample, finished));
            return handle;
        } catch (RuntimeException | Error failure) {
            finishSubmissionFailure(operation, failure, sample, finished);
            throw failure;
        }
    }

    private void finish(TaskReport<?> report, Timer.Sample sample, AtomicBoolean finished) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        String result = switch (report.status()) {
            case COMPLETED -> "success";
            case CANCELLED -> "cancelled";
            case FAILED -> "failure";
            default -> throw new IllegalArgumentException("TaskReport 必须是终态：" + report.status());
        };
        if (report.status() == TaskStatus.FAILED) {
            recordFailure(report.operation(), report.error());
        }
        for (FfmpegWarning warning : report.warnings()) {
            registry.counter(WARNINGS,
                    "operation", report.operation(),
                    "code", warning.code().name()).increment();
        }
        sample.stop(timer(report.operation(), result));
    }

    private void finishSubmissionFailure(
            String operation, Throwable failure, Timer.Sample sample, AtomicBoolean finished) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        recordFailure(operation, failure);
        sample.stop(timer(operation, "failure"));
    }

    private Timer timer(String operation, String result) {
        return Timer.builder(MeteredFfmpegClient.TIMER)
                .tag("operation", operation)
                .tag("result", result)
                .register(registry);
    }

    private void recordFailure(String operation, Throwable failure) {
        String reason = "error";
        if (failure instanceof FfmpegException ffmpegFailure) {
            String structured = ffmpegFailure.reason();
            reason = structured == null || structured.isBlank() ? "unknown" : structured;
        }
        registry.counter(MeteredFfmpegClient.ERRORS,
                "operation", operation,
                "reason", reason).increment();
    }
}
