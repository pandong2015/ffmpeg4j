package io.github.pandong2015.ffmpeg4j.facade;

import java.time.Duration;
import java.util.function.Consumer;

import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;

/**
 * {@link Ffmpeg#gif} 的进阶选项。不可变、wither 风格。
 *
 * <p>默认对齐 type3 风格：{@code start=0}、{@code fps=15}；{@code duration} 可选（未设=从 {@code start} 到片尾）、
 * {@code width} 可选（未设=不加 {@code scale}、保原分辨率）、{@code height} 缺省 {@code -1} 按比例。
 * {@code scaleFlags} 可选（如 {@code "lanczos"}）：缺省不加 flags，与原 type3 命令逐字节等价；仅显式设定才追加。
 * 时间裁剪的 {@code -ss}/{@code -t} 均置于输入侧（见 {@link FacadeSupport#buildGif}）。
 */
public final class GifOptions {

    private final double start;
    private final Double duration;
    private final double fps;
    private final Integer width;
    private final Integer height;
    private final String scaleFlags;
    private final Consumer<Progress> onProgress;
    private final Duration timeout;

    private GifOptions(double start, Double duration, double fps, Integer width, Integer height,
                       String scaleFlags, Consumer<Progress> onProgress, Duration timeout) {
        this.start = start;
        this.duration = duration;
        this.fps = fps;
        this.width = width;
        this.height = height;
        this.scaleFlags = scaleFlags;
        this.onProgress = onProgress;
        this.timeout = timeout;
    }

    /** 默认：{@code start=0}、{@code fps=15}，其余不设。 */
    public static GifOptions defaults() {
        return new GifOptions(0.0, null, 15.0, null, null, null, null, null);
    }

    /** 起始秒（置于输入侧 {@code -ss}）。 */
    public GifOptions start(double sec) {
        return new GifOptions(sec, duration, fps, width, height, scaleFlags, onProgress, timeout);
    }

    /** 截取时长（秒，置于输入侧 {@code -t}）；须为正数。 */
    public GifOptions duration(double sec) {
        if (sec <= 0) {
            throw new IllegalArgumentException("GIF 截取时长须为正数，实际 " + sec);
        }
        return new GifOptions(start, sec, fps, width, height, scaleFlags, onProgress, timeout);
    }

    /** 帧率；须为正数。 */
    public GifOptions fps(double v) {
        if (v <= 0) {
            throw new IllegalArgumentException("GIF 帧率须为正数，实际 " + v);
        }
        return new GifOptions(start, duration, v, width, height, scaleFlags, onProgress, timeout);
    }

    /** 目标宽度；须为正整数。未设则不加 {@code scale}（保原分辨率）。 */
    public GifOptions width(int px) {
        if (px <= 0) {
            throw new IllegalArgumentException("GIF 宽度须为正整数，实际 " + px);
        }
        return new GifOptions(start, duration, fps, px, height, scaleFlags, onProgress, timeout);
    }

    /** 目标高度；须为正整数。仅与 {@code width} 配合时生效；未设则 {@code -1} 按比例。 */
    public GifOptions height(int px) {
        if (px <= 0) {
            throw new IllegalArgumentException("GIF 高度须为正整数，实际 " + px);
        }
        return new GifOptions(start, duration, fps, width, px, scaleFlags, onProgress, timeout);
    }

    /** 缩放算法 flags（如 {@code "lanczos"}）；缺省不加（与 type3 逐字节等价）。 */
    public GifOptions scaleFlags(String flags) {
        return new GifOptions(start, duration, fps, width, height, flags, onProgress, timeout);
    }

    public GifOptions onProgress(Consumer<Progress> cb) {
        return new GifOptions(start, duration, fps, width, height, scaleFlags, cb, timeout);
    }

    public GifOptions timeout(Duration t) {
        return new GifOptions(start, duration, fps, width, height, scaleFlags, onProgress, t);
    }

    public double start() {
        return start;
    }

    /** 截取时长（秒）；未设为 {@code null}（表示到片尾）。 */
    public Double duration() {
        return duration;
    }

    public double fps() {
        return fps;
    }

    /** 目标宽度；未设为 {@code null}（不加 scale）。 */
    public Integer width() {
        return width;
    }

    /** 目标高度；未设为 {@code null}（-1 按比例）。 */
    public Integer height() {
        return height;
    }

    /** 缩放 flags；未设为 {@code null}。 */
    public String scaleFlags() {
        return scaleFlags;
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
