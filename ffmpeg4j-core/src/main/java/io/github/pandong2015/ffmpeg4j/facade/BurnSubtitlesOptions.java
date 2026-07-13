package io.github.pandong2015.ffmpeg4j.facade;

import java.time.Duration;
import java.util.function.Consumer;

import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;

/**
 * {@link Ffmpeg#burnSubtitles} 的进阶选项。不可变、wither 风格。
 *
 * <p>字幕烧录必然重编码视频，故有 {@code videoCodec}（缺省 {@code libx264}）；音频不受滤镜影响，
 * {@code audioCodec} 缺省 {@code copy} 直接透传。{@code forceStyle} 传入 ASS 风格覆盖串（如
 * {@code "FontName=Arial,FontSize=24"}）。</p>
 */
public final class BurnSubtitlesOptions {

    private final String forceStyle;
    private final String videoCodec;
    private final String audioCodec;
    private final Consumer<Progress> onProgress;
    private final Duration timeout;

    private BurnSubtitlesOptions(String forceStyle, String videoCodec, String audioCodec,
                                 Consumer<Progress> onProgress, Duration timeout) {
        this.forceStyle = forceStyle;
        this.videoCodec = videoCodec;
        this.audioCodec = audioCodec;
        this.onProgress = onProgress;
        this.timeout = timeout;
    }

    /** 默认：无 {@code forceStyle}、视频 {@code libx264}、音频 {@code copy}。 */
    public static BurnSubtitlesOptions defaults() {
        return new BurnSubtitlesOptions(null, "libx264", "copy", null, null);
    }

    public BurnSubtitlesOptions forceStyle(String v) {
        return new BurnSubtitlesOptions(v, videoCodec, audioCodec, onProgress, timeout);
    }

    public BurnSubtitlesOptions videoCodec(String v) {
        return new BurnSubtitlesOptions(forceStyle, v, audioCodec, onProgress, timeout);
    }

    public BurnSubtitlesOptions audioCodec(String v) {
        return new BurnSubtitlesOptions(forceStyle, videoCodec, v, onProgress, timeout);
    }

    public BurnSubtitlesOptions onProgress(Consumer<Progress> cb) {
        return new BurnSubtitlesOptions(forceStyle, videoCodec, audioCodec, cb, timeout);
    }

    public BurnSubtitlesOptions timeout(Duration t) {
        return new BurnSubtitlesOptions(forceStyle, videoCodec, audioCodec, onProgress, t);
    }

    public String forceStyle() {
        return forceStyle;
    }

    public String videoCodec() {
        return videoCodec;
    }

    public String audioCodec() {
        return audioCodec;
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
