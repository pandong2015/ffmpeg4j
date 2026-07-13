package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

/**
 * 进度直投监听器：当 {@code ffmpeg4j.async.progress-channel} 为 {@code listener} 或 {@code both} 时，
 * 容器中每个此类型 bean 都会收到进度回调（经绑定的 {@code TaskExecutor} 派发，绝不占进度 pump 线程）。
 *
 * <p>相较 {@code application-event} 广播，直投更聚焦、开销更低；两者可经配置切换或并用。监听器抛出的异常
 * 会被桥接层隔离（记录而不上抛），不影响后续进度块与任务本身。
 */
@FunctionalInterface
public interface FfmpegProgressListener {

    /** 收到一条进度快照。实现须快速返回，不应做长阻塞（虽在 executor 线程，但会占用其线程）。 */
    void onProgress(FfmpegProgressEvent event);
}
