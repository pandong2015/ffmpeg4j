package io.github.pandong2015.ffmpeg4j.facade;

import java.time.Duration;
import java.util.function.Consumer;

import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;

/**
 * {@link Ffmpeg#remux} 的进阶选项。换容器为纯流复制（{@code -c copy}），本身无编解码器可调，
 * 仅暴露执行侧的 {@code onProgress}/{@code timeout}。不可变、wither 风格。
 */
public final class RemuxOptions {

    private final Consumer<Progress> onProgress;
    private final Duration timeout;

    private RemuxOptions(Consumer<Progress> onProgress, Duration timeout) {
        this.onProgress = onProgress;
        this.timeout = timeout;
    }

    public static RemuxOptions defaults() {
        return new RemuxOptions(null, null);
    }

    public RemuxOptions onProgress(Consumer<Progress> cb) {
        return new RemuxOptions(cb, timeout);
    }

    public RemuxOptions timeout(Duration t) {
        return new RemuxOptions(onProgress, t);
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
