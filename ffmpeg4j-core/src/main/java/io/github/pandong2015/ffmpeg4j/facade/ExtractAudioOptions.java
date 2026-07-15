package io.github.pandong2015.ffmpeg4j.facade;

import java.time.Duration;
import java.util.function.Consumer;

import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;

/**
 * {@link Ffmpeg#extractAudio} 的进阶选项。抽音频的编解码器由输出扩展名推导（见 {@link FacadeSupport}），
 * 本类额外暴露 {@code sampleRate}（{@code -ar}）/{@code channels}（{@code -ac}）与执行侧的
 * {@code onProgress}/{@code timeout}。不可变、wither 风格。
 *
 * <p>ASR 前置常需 16k 单声道 WAV：{@code ExtractAudioOptions.defaults().sampleRate(16000).channels(1)}。
 * 注意：一旦设定 {@code sampleRate}/{@code channels}，编译器会禁用 {@code -c:a copy}（copy 会静默忽略
 * {@code -ar}/{@code -ac}），改用重编码以真正重采样/改声道（见 {@link FacadeSupport#buildExtractAudio}）。
 */
public final class ExtractAudioOptions {

    private final Integer sampleRate;
    private final Integer channels;
    private final Consumer<Progress> onProgress;
    private final Duration timeout;

    private ExtractAudioOptions(Integer sampleRate, Integer channels,
                                Consumer<Progress> onProgress, Duration timeout) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.onProgress = onProgress;
        this.timeout = timeout;
    }

    public static ExtractAudioOptions defaults() {
        return new ExtractAudioOptions(null, null, null, null);
    }

    /** 目标采样率 {@code -ar}（Hz），须为正整数。设定后禁用 {@code copy}（见类文档）。 */
    public ExtractAudioOptions sampleRate(int hz) {
        if (hz <= 0) {
            throw new IllegalArgumentException("采样率须为正整数，实际 " + hz);
        }
        return new ExtractAudioOptions(hz, channels, onProgress, timeout);
    }

    /** 目标声道数 {@code -ac}，须为正整数。设定后禁用 {@code copy}（见类文档）。 */
    public ExtractAudioOptions channels(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("声道数须为正整数，实际 " + count);
        }
        return new ExtractAudioOptions(sampleRate, count, onProgress, timeout);
    }

    public ExtractAudioOptions onProgress(Consumer<Progress> cb) {
        return new ExtractAudioOptions(sampleRate, channels, cb, timeout);
    }

    public ExtractAudioOptions timeout(Duration t) {
        return new ExtractAudioOptions(sampleRate, channels, onProgress, t);
    }

    /** 目标采样率（{@code -ar}）；未设为 {@code null}。 */
    public Integer sampleRate() {
        return sampleRate;
    }

    /** 目标声道数（{@code -ac}）；未设为 {@code null}。 */
    public Integer channels() {
        return channels;
    }

    /** 是否请求了重采样/改声道——设了任一即 {@code true}，此时编译器须禁用 {@code -c:a copy}。 */
    boolean resampling() {
        return sampleRate != null || channels != null;
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
