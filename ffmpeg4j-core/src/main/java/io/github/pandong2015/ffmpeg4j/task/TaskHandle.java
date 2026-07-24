package io.github.pandong2015.ffmpeg4j.task;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 可查询、可取消的任务句柄。
 *
 * @param <T> 任务结果类型
 */
public final class TaskHandle<T> {

    private final TaskId taskId;
    private final String operation;
    private final CompletableFuture<TaskReport<T>> completion;
    private final Supplier<TaskStatus> status;
    private final BooleanSupplier cancel;

    /** 由任务执行管线创建句柄。 */
    public TaskHandle(
            TaskId taskId,
            String operation,
            CompletableFuture<TaskReport<T>> completion,
            Supplier<TaskStatus> status,
            BooleanSupplier cancel) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.completion = Objects.requireNonNull(completion, "completion");
        this.status = Objects.requireNonNull(status, "status");
        this.cancel = Objects.requireNonNull(cancel, "cancel");
        if (operation.isBlank()) {
            throw new IllegalArgumentException("operation 不能为空白");
        }
    }

    /** 任务标识。 */
    public TaskId taskId() {
        return taskId;
    }

    /** 低基数操作名。 */
    public String operation() {
        return operation;
    }

    /** 最终以成功、失败或取消报告正常收口的 completion。 */
    public CompletableFuture<TaskReport<T>> completion() {
        return completion;
    }

    /** 当前状态快照。 */
    public TaskStatus status() {
        return status.get();
    }

    /** 请求取消；仅首次赢得终态竞争前的请求返回 {@code true}。 */
    public boolean cancel() {
        return cancel.getAsBoolean();
    }
}
