package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.task.TaskId;

import java.time.Instant;

/**
 * ffmpeg4j 进度事件：携当前 {@link Progress} 快照与任务上下文。
 *
 * <p>经 Spring {@code ApplicationEventPublisher} 广播（{@code progress-channel=application-event}/{@code both}），
 * 由 {@code @EventListener} 订阅。事件的构造与发布一律经绑定的 {@code TaskExecutor} 异步派发，绝不占用进度
 * pump 线程。
 *
 * @param taskId    任务 API 的稳定标识；旧门面进度事件为 {@code null}
 * @param operation 操作名；旧门面进度事件为 {@code null}
 * @param timestamp 事件时间
 * @param progress  当前进度快照
 */
public record FfmpegProgressEvent(
        TaskId taskId,
        String operation,
        Instant timestamp,
        Progress progress) {

    /** 兼容旧门面进度事件的构造方式。 */
    public FfmpegProgressEvent(String jobId, Progress progress) {
        this(jobId == null ? null : new TaskId(jobId), null, Instant.now(), progress);
    }

    /** 兼容旧访问器；任务 API 返回 taskId 字符串，旧门面返回 {@code null}。 */
    public String jobId() {
        return taskId == null ? null : taskId.value();
    }
}
