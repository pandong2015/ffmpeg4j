package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

/**
 * 进度递送通道选择（{@code ffmpeg4j.async.progress-channel}）。
 *
 * <ul>
 *   <li>{@link #APPLICATION_EVENT}：把进度桥接为 Spring 应用事件 {@code FfmpegProgressEvent} 经
 *       {@code ApplicationEventPublisher} 广播（松耦合、多订阅者，默认）。</li>
 *   <li>{@link #LISTENER}：直接回调容器中注入的 {@code FfmpegProgressListener} bean（聚焦、低开销）。</li>
 *   <li>{@link #BOTH}：两条通道都递送。</li>
 * </ul>
 *
 * <p>无论哪条通道，进度均经绑定的 {@code TaskExecutor} 异步派发，绝不占用进度 pump 线程。
 */
public enum ProgressChannel {
    APPLICATION_EVENT,
    LISTENER,
    BOTH
}
