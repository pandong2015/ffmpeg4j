package io.github.pandong2015.ffmpeg4j.facade;

import java.time.Duration;
import java.util.function.Consumer;

import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;

/**
 * {@link Ffmpeg#clip} 的进阶选项。不可变、wither 风格。
 *
 * <p>{@code reencode} 决定截取方式：{@code false}（默认）为快切——输入侧 {@code -ss} 做关键帧快 seek
 * 且 {@code -c copy} 不重编码，起点按最近关键帧对齐；{@code true} 为精切——输出侧 {@code -ss}/{@code -t}
 * 配合重编码得到帧级精确区间。{@code videoCodec}/{@code audioCodec} 仅在精切时生效。
 */
public final class ClipOptions {

    private final boolean reencode;
    private final String videoCodec;
    private final String audioCodec;
    private final Consumer<Progress> onProgress;
    private final Duration timeout;

    private ClipOptions(boolean reencode, String videoCodec, String audioCodec,
                        Consumer<Progress> onProgress, Duration timeout) {
        this.reencode = reencode;
        this.videoCodec = videoCodec;
        this.audioCodec = audioCodec;
        this.onProgress = onProgress;
        this.timeout = timeout;
    }

    /** 默认：快切（{@code reencode=false}）；精切时的编解码器缺省为 {@code libx264}/{@code aac}。 */
    public static ClipOptions defaults() {
        return new ClipOptions(false, "libx264", "aac", null, null);
    }

    public ClipOptions reencode(boolean v) {
        return new ClipOptions(v, videoCodec, audioCodec, onProgress, timeout);
    }

    public ClipOptions videoCodec(String v) {
        return new ClipOptions(reencode, v, audioCodec, onProgress, timeout);
    }

    public ClipOptions audioCodec(String v) {
        return new ClipOptions(reencode, videoCodec, v, onProgress, timeout);
    }

    public ClipOptions onProgress(Consumer<Progress> cb) {
        return new ClipOptions(reencode, videoCodec, audioCodec, cb, timeout);
    }

    public ClipOptions timeout(Duration t) {
        return new ClipOptions(reencode, videoCodec, audioCodec, onProgress, t);
    }

    public boolean reencode() {
        return reencode;
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
