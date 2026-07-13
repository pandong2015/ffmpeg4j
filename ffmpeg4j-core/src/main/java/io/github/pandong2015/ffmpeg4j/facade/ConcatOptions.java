package io.github.pandong2015.ffmpeg4j.facade;

import java.time.Duration;
import java.util.function.Consumer;

import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;
import io.github.pandong2015.ffmpeg4j.model.AudioNormTarget;

/**
 * {@link Ffmpeg#concat} 的进阶选项。不可变、wither 风格。
 *
 * <p>拼接前必做归一化：{@code width}/{@code height}/{@code fps}/{@code pixelFormat} 为视频归一目标，
 * 任一为 {@code null} 时从首段 probe 推导；{@code audioTarget} 为音频归一目标，{@code null} 时用
 * {@link AudioNormTarget#stereo48k()}。{@code onMissingStream} 决定流集合异构（某段缺音轨/视轨）时的策略。
 */
public final class ConcatOptions {

    /** 流集合异构时的处理策略。 */
    public enum OnMissingStream {
        /** 为缺音段注入限时静音、为缺视段注入限时纯色，使各段轨数一致。 */
        INJECT_SILENCE_OR_BLANK,
        /** 直接抛出可诊断异常，拒绝产出注定失败的命令行。 */
        REJECT
    }

    private final Integer width;
    private final Integer height;
    private final Double fps;
    private final String pixelFormat;
    private final AudioNormTarget audioTarget;
    private final OnMissingStream onMissingStream;
    private final Consumer<Progress> onProgress;
    private final Duration timeout;

    private ConcatOptions(Integer width, Integer height, Double fps, String pixelFormat,
                          AudioNormTarget audioTarget, OnMissingStream onMissingStream,
                          Consumer<Progress> onProgress, Duration timeout) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.pixelFormat = pixelFormat;
        this.audioTarget = audioTarget;
        this.onMissingStream = onMissingStream;
        this.onProgress = onProgress;
        this.timeout = timeout;
    }

    /** 默认：归一目标全部从首段推导；缺流策略为注入静音/纯色。 */
    public static ConcatOptions defaults() {
        return new ConcatOptions(null, null, null, null, null,
                OnMissingStream.INJECT_SILENCE_OR_BLANK, null, null);
    }

    public ConcatOptions width(Integer v) {
        return new ConcatOptions(v, height, fps, pixelFormat, audioTarget, onMissingStream, onProgress, timeout);
    }

    public ConcatOptions height(Integer v) {
        return new ConcatOptions(width, v, fps, pixelFormat, audioTarget, onMissingStream, onProgress, timeout);
    }

    public ConcatOptions fps(Double v) {
        return new ConcatOptions(width, height, v, pixelFormat, audioTarget, onMissingStream, onProgress, timeout);
    }

    public ConcatOptions pixelFormat(String v) {
        return new ConcatOptions(width, height, fps, v, audioTarget, onMissingStream, onProgress, timeout);
    }

    public ConcatOptions audioTarget(AudioNormTarget v) {
        return new ConcatOptions(width, height, fps, pixelFormat, v, onMissingStream, onProgress, timeout);
    }

    public ConcatOptions onMissingStream(OnMissingStream v) {
        return new ConcatOptions(width, height, fps, pixelFormat, audioTarget, v, onProgress, timeout);
    }

    public ConcatOptions onProgress(Consumer<Progress> cb) {
        return new ConcatOptions(width, height, fps, pixelFormat, audioTarget, onMissingStream, cb, timeout);
    }

    public ConcatOptions timeout(Duration t) {
        return new ConcatOptions(width, height, fps, pixelFormat, audioTarget, onMissingStream, onProgress, t);
    }

    public Integer width() {
        return width;
    }

    public Integer height() {
        return height;
    }

    public Double fps() {
        return fps;
    }

    public String pixelFormat() {
        return pixelFormat;
    }

    public AudioNormTarget audioTarget() {
        return audioTarget;
    }

    public OnMissingStream onMissingStream() {
        return onMissingStream;
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
