package io.github.pandong2015.ffmpeg4j.facade;

import java.time.Duration;
import java.util.function.Consumer;

import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;

/**
 * {@link Ffmpeg#extractAudio} 的进阶选项。抽音频的编解码器由输出扩展名推导（见 {@link FacadeSupport}），
 * 本类仅暴露执行侧的 {@code onProgress}/{@code timeout}。不可变、wither 风格。
 */
public final class ExtractAudioOptions {

    private final Consumer<Progress> onProgress;
    private final Duration timeout;

    private ExtractAudioOptions(Consumer<Progress> onProgress, Duration timeout) {
        this.onProgress = onProgress;
        this.timeout = timeout;
    }

    public static ExtractAudioOptions defaults() {
        return new ExtractAudioOptions(null, null);
    }

    public ExtractAudioOptions onProgress(Consumer<Progress> cb) {
        return new ExtractAudioOptions(cb, timeout);
    }

    public ExtractAudioOptions timeout(Duration t) {
        return new ExtractAudioOptions(onProgress, t);
    }

    public Consumer<Progress> onProgress() {
        return onProgress;
    }

    public Duration timeout() {
        return timeout;
    }

    RunOptions toRunOptions() {
        return FacadeSupport.runOptions(timeout, onProgress);
    }
}
