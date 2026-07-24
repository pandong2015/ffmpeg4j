package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.task.TaskEvent;
import io.github.pandong2015.ffmpeg4j.task.TaskId;

import java.time.Instant;
import java.util.Objects;

/**
 * Spring 侧的统一任务生命周期事件。
 *
 * @param taskId    稳定任务标识
 * @param operation 低基数操作名
 * @param status    生命周期事件类型
 * @param timestamp 事件时间
 * @param progress  进度事件携带的快照
 * @param error     失败事件携带的原因
 */
public record FfmpegTaskEvent(
        TaskId taskId,
        String operation,
        TaskEvent.Type status,
        Instant timestamp,
        Progress progress,
        Throwable error) {

    /** 从 core 事件创建 Spring 事件。 */
    public FfmpegTaskEvent {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    static FfmpegTaskEvent from(TaskEvent event) {
        return new FfmpegTaskEvent(
                event.taskId(), event.operation(), event.type(), event.timestamp(),
                event.progress(), event.error());
    }
}
