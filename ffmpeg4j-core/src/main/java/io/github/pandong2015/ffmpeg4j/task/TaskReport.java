package io.github.pandong2015.ffmpeg4j.task;

import java.util.Objects;
import java.util.List;

/**
 * 一次任务的不可变终态报告。
 *
 * @param taskId    任务标识
 * @param operation 操作名
 * @param status    唯一终态
 * @param result    成功结果，仅完成态非空
 * @param error     失败原因，仅失败态可非空
 * @param warnings  按发生顺序排列的非致命警告
 * @param <T>       结果类型
 */
public record TaskReport<T>(
        TaskId taskId,
        String operation,
        TaskStatus status,
        T result,
        Throwable error,
        List<FfmpegWarning> warnings) {

    /** 校验报告一定处于终态且字段相互一致。 */
    public TaskReport {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(status, "status");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        if (operation.isBlank()) {
            throw new IllegalArgumentException("operation 不能为空白");
        }
        if (!status.terminal()) {
            throw new IllegalArgumentException("TaskReport 只能表示终态");
        }
        if (status == TaskStatus.COMPLETED && result == null) {
            throw new IllegalArgumentException("完成报告必须包含 result");
        }
        if (status != TaskStatus.COMPLETED && result != null) {
            throw new IllegalArgumentException("非完成报告不能包含 result");
        }
        if (status == TaskStatus.FAILED && error == null) {
            throw new IllegalArgumentException("失败报告必须包含 error");
        }
        if (status != TaskStatus.FAILED && error != null) {
            throw new IllegalArgumentException("只有失败报告可包含 error");
        }
    }

    /** 保留结构化警告引入前的五参数构造方式。 */
    public TaskReport(TaskId taskId, String operation, TaskStatus status, T result, Throwable error) {
        this(taskId, operation, status, result, error, List.of());
    }
}
