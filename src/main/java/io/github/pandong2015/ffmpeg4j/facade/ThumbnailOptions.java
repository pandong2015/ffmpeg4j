package io.github.pandong2015.ffmpeg4j.facade;

import java.time.Duration;
import java.util.function.Consumer;

import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;

/**
 * {@link Ffmpeg#thumbnail} 的进阶选项。不可变、wither 风格。
 *
 * <p>{@code width}/{@code height} 为可选缩放目标：只给其一时另一维传 {@code -1} 让 ffmpeg 按比例推导；
 * {@code quality} 映射为 {@code -q:v}（越小越好，用于 mjpeg/png 输出）。
 */
public final class ThumbnailOptions {

    private final Integer width;
    private final Integer height;
    private final Integer quality;
    private final Consumer<Progress> onProgress;
    private final Duration timeout;

    private ThumbnailOptions(Integer width, Integer height, Integer quality,
                             Consumer<Progress> onProgress, Duration timeout) {
        this.width = width;
        this.height = height;
        this.quality = quality;
        this.onProgress = onProgress;
        this.timeout = timeout;
    }

    public static ThumbnailOptions defaults() {
        return new ThumbnailOptions(null, null, null, null, null);
    }

    public ThumbnailOptions width(Integer v) {
        return new ThumbnailOptions(v, height, quality, onProgress, timeout);
    }

    public ThumbnailOptions height(Integer v) {
        return new ThumbnailOptions(width, v, quality, onProgress, timeout);
    }

    public ThumbnailOptions quality(Integer v) {
        return new ThumbnailOptions(width, height, v, onProgress, timeout);
    }

    public ThumbnailOptions onProgress(Consumer<Progress> cb) {
        return new ThumbnailOptions(width, height, quality, cb, timeout);
    }

    public ThumbnailOptions timeout(Duration t) {
        return new ThumbnailOptions(width, height, quality, onProgress, t);
    }

    public Integer width() {
        return width;
    }

    public Integer height() {
        return height;
    }

    public Integer quality() {
        return quality;
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
