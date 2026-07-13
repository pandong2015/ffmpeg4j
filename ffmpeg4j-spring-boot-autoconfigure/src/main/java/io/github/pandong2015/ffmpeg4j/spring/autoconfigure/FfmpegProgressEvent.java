package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import io.github.pandong2015.ffmpeg4j.engine.Progress;

/**
 * ffmpeg4j 进度事件：携当前 {@link Progress} 快照与可选任务标识。
 *
 * <p>经 Spring {@code ApplicationEventPublisher} 广播（{@code progress-channel=application-event}/{@code both}），
 * 由 {@code @EventListener} 订阅。事件的构造与发布一律经绑定的 {@code TaskExecutor} 异步派发，绝不占用进度
 * pump 线程。
 *
 * @param jobId    任务标识（可为 {@code null}——默认门面路径不生成 jobId）
 * @param progress 当前进度快照
 */
public record FfmpegProgressEvent(String jobId, Progress progress) {
}
