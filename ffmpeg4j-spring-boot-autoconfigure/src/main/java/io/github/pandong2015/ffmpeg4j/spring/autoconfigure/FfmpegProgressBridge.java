package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import io.github.pandong2015.ffmpeg4j.engine.Progress;

/**
 * 进度桥接层：把 core 的进度回调分发到 Spring 侧的两条可切换通道——广播 {@link FfmpegProgressEvent}
 * 与直投 {@link FfmpegProgressListener}——由 {@link ProgressChannel} 选择。
 *
 * <p><b>线程语义</b>：本桥接<em>本身不切换线程</em>。它被安装为 core {@code RunOptions.onProgress} 回调，
 * 而 autoconfigure 同时把 {@code callbackExecutor} 设为 Spring {@code TaskExecutor}——故 core 会在该 executor
 * 线程上调用本桥接，{@code publishEvent}/listener 天然不占进度 pump 线程（呼应 core「回调必须非阻塞、绝不占
 * pump 线程」的铁律）。任一事件消费者/监听器抛出的异常均被隔离（记录而不上抛），不影响后续进度块与任务本身。
 */
public class FfmpegProgressBridge {

    private static final Logger LOG = Logger.getLogger(FfmpegProgressBridge.class.getName());

    private final ApplicationEventPublisher publisher;
    private final ObjectProvider<FfmpegProgressListener> listeners;
    private final ProgressChannel channel;

    public FfmpegProgressBridge(ApplicationEventPublisher publisher,
                                ObjectProvider<FfmpegProgressListener> listeners,
                                ProgressChannel channel) {
        this.publisher = publisher;
        this.listeners = listeners;
        this.channel = channel;
    }

    /** 作为 {@code onProgress} 回调分发一条进度快照到配置的通道。 */
    public void dispatch(Progress progress) {
        FfmpegProgressEvent event = new FfmpegProgressEvent(null, progress);
        if (channel == ProgressChannel.APPLICATION_EVENT || channel == ProgressChannel.BOTH) {
            try {
                publisher.publishEvent(event);
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "ffmpeg4j 进度事件发布失败（已隔离，不影响任务）", t);
            }
        }
        if (channel == ProgressChannel.LISTENER || channel == ProgressChannel.BOTH) {
            for (FfmpegProgressListener listener : listeners) {
                try {
                    listener.onProgress(event);
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "ffmpeg4j 进度 listener 抛异常（已隔离，不影响任务）", t);
                }
            }
        }
    }

    /** 适配为 core 的 {@code onProgress} 回调。 */
    public Consumer<Progress> asConsumer() {
        return this::dispatch;
    }

    /** 当前生效的进度通道。 */
    public ProgressChannel channel() {
        return channel;
    }
}
