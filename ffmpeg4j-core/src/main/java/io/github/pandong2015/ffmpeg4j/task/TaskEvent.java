package io.github.pandong2015.ffmpeg4j.task;

import io.github.pandong2015.ffmpeg4j.engine.Progress;

import java.time.Instant;
import java.util.Objects;

/**
 * 不可变的任务生命周期事件。
 *
 * @param taskId    任务标识
 * @param operation 低基数操作名
 * @param type      事件类型
 * @param timestamp 事件产生时间
 * @param progress  仅 {@link Type#PROGRESS} 使用的进度快照
 * @param error     仅 {@link Type#FAILED} 使用的失败原因
 */
public record TaskEvent(
        TaskId taskId,
        String operation,
        Type type,
        Instant timestamp,
        Progress progress,
        Throwable error) {

    /** 生命周期事件类型。 */
    public enum Type {
        /** 任务实际开始。 */
        STARTED,
        /** 收到一份进度快照。 */
        PROGRESS,
        /** 已请求取消。 */
        CANCELLING,
        /** 成功完成。 */
        COMPLETED,
        /** 执行失败。 */
        FAILED,
        /** 取消收尾完成。 */
        CANCELLED
    }

    /** 校验事件字段间的一致性。 */
    public TaskEvent {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(timestamp, "timestamp");
        if (operation.isBlank()) {
            throw new IllegalArgumentException("operation 不能为空白");
        }
        if ((type == Type.PROGRESS) != (progress != null)) {
            throw new IllegalArgumentException("只有 PROGRESS 事件必须携带 progress");
        }
        if (type == Type.FAILED && error == null) {
            throw new IllegalArgumentException("FAILED 事件必须携带 error");
        }
        if (type != Type.FAILED && error != null) {
            throw new IllegalArgumentException("只有 FAILED 事件可携带 error");
        }
    }
}
