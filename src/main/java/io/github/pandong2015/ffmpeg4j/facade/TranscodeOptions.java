package io.github.pandong2015.ffmpeg4j.facade;

import java.time.Duration;
import java.util.function.Consumer;

import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;

/**
 * {@link Ffmpeg#transcode} 的进阶选项。不可变、wither 风格：每个 {@code xxx(value)} 返回带该改动的
 * 新副本，同名无参 {@code xxx()} 为只读访问器。
 *
 * <p>覆盖强制转码的编解码器与质量控制：{@code videoCodec}/{@code audioCodec}/{@code crf}/{@code preset}/
 * {@code videoBitrate}/{@code audioBitrate}，以及执行侧的 {@code onProgress}/{@code timeout}。
 */
public final class TranscodeOptions {

    private final String videoCodec;
    private final String audioCodec;
    private final Integer crf;
    private final String preset;
    private final String videoBitrate;
    private final String audioBitrate;
    private final Consumer<Progress> onProgress;
    private final Duration timeout;

    private TranscodeOptions(String videoCodec, String audioCodec, Integer crf, String preset,
                             String videoBitrate, String audioBitrate,
                             Consumer<Progress> onProgress, Duration timeout) {
        this.videoCodec = videoCodec;
        this.audioCodec = audioCodec;
        this.crf = crf;
        this.preset = preset;
        this.videoBitrate = videoBitrate;
        this.audioBitrate = audioBitrate;
        this.onProgress = onProgress;
        this.timeout = timeout;
    }

    /** 默认选项：视频 {@code libx264}、音频 {@code aac}，其余不设。 */
    public static TranscodeOptions defaults() {
        return new TranscodeOptions("libx264", "aac", null, null, null, null, null, null);
    }

    public TranscodeOptions videoCodec(String v) {
        return new TranscodeOptions(v, audioCodec, crf, preset, videoBitrate, audioBitrate, onProgress, timeout);
    }

    public TranscodeOptions audioCodec(String v) {
        return new TranscodeOptions(videoCodec, v, crf, preset, videoBitrate, audioBitrate, onProgress, timeout);
    }

    public TranscodeOptions crf(Integer v) {
        return new TranscodeOptions(videoCodec, audioCodec, v, preset, videoBitrate, audioBitrate, onProgress, timeout);
    }

    public TranscodeOptions preset(String v) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, v, videoBitrate, audioBitrate, onProgress, timeout);
    }

    public TranscodeOptions videoBitrate(String v) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, v, audioBitrate, onProgress, timeout);
    }

    public TranscodeOptions audioBitrate(String v) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, v, onProgress, timeout);
    }

    public TranscodeOptions onProgress(Consumer<Progress> cb) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate, cb, timeout);
    }

    public TranscodeOptions timeout(Duration t) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate, onProgress, t);
    }

    public String videoCodec() {
        return videoCodec;
    }

    public String audioCodec() {
        return audioCodec;
    }

    public Integer crf() {
        return crf;
    }

    public String preset() {
        return preset;
    }

    public String videoBitrate() {
        return videoBitrate;
    }

    public String audioBitrate() {
        return audioBitrate;
    }

    public Consumer<Progress> onProgress() {
        return onProgress;
    }

    public Duration timeout() {
        return timeout;
    }

    /** 把 {@code onProgress}/{@code timeout} 映射为执行引擎的 {@link RunOptions}。 */
    RunOptions toRunOptions() {
        return FacadeSupport.runOptions(timeout, onProgress);
    }
}
