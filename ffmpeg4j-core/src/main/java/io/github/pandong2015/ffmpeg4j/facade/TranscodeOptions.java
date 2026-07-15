package io.github.pandong2015.ffmpeg4j.facade;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;
import io.github.pandong2015.ffmpeg4j.model.VideoStream;

/**
 * {@link Ffmpeg#transcode} 的进阶选项。不可变、wither 风格：每个 {@code xxx(value)} 返回带该改动的
 * 新副本，同名无参 {@code xxx()} 为只读访问器。
 *
 * <p>覆盖强制转码的编解码器与质量/码控：{@code videoCodec}/{@code audioCodec}/{@code crf}/{@code preset}/
 * {@code videoBitrate}/{@code audioBitrate}，以及码控 {@code fps}（{@code -r}）/{@code maxrate}/{@code bufsize}/
 * {@code gop}（关键帧间隔<em>帧数</em>，派生 {@code -keyint_min}/{@code -g}/{@code -sc_threshold 0}）。
 *
 * <p>{@code videoFilter} 为可选的<em>单输入</em>视频滤镜链入口：给定时以 {@code input.video()}（必选映射）为起点
 * 应用该函数，视频经 filter_complex、音频仍用可选映射；不给定时保持双可选映射、既有 argv 逐字节不变。函数内可自建
 * 额外输入（如水印图 {@code Input.of(logo).withInputArgs("-loop","1").video()}）并叠加，编译器自动补出第二路 {@code -i}。
 *
 * <p>{@code extraOutputArgs} 为原始输出参数逃生舱（内容不参与类型校验），置于类型化码控之后（同键 ffmpeg 取后者）。
 * h264 的 VBV 用类型化 {@code maxrate}/{@code bufsize}（渲染为 {@code -maxrate}/{@code -bufsize}）；libx265 的
 * VBV 走 {@code extraOutputArgs("-x265-params","vbv-maxrate=...")}——库不自动翻译，避免耦合编解码器字符串解析。
 */
public final class TranscodeOptions {

    private final String videoCodec;
    private final String audioCodec;
    private final Integer crf;
    private final String preset;
    private final String videoBitrate;
    private final String audioBitrate;
    private final Function<VideoStream, VideoStream> videoFilter;
    private final Double fps;
    private final String maxrate;
    private final String bufsize;
    private final Integer gop;
    private final List<String> extraOutputArgs;
    private final Consumer<Progress> onProgress;
    private final Duration timeout;

    private TranscodeOptions(String videoCodec, String audioCodec, Integer crf, String preset,
                             String videoBitrate, String audioBitrate,
                             Function<VideoStream, VideoStream> videoFilter, Double fps,
                             String maxrate, String bufsize, Integer gop, List<String> extraOutputArgs,
                             Consumer<Progress> onProgress, Duration timeout) {
        this.videoCodec = videoCodec;
        this.audioCodec = audioCodec;
        this.crf = crf;
        this.preset = preset;
        this.videoBitrate = videoBitrate;
        this.audioBitrate = audioBitrate;
        this.videoFilter = videoFilter;
        this.fps = fps;
        this.maxrate = maxrate;
        this.bufsize = bufsize;
        this.gop = gop;
        this.extraOutputArgs = List.copyOf(extraOutputArgs);
        this.onProgress = onProgress;
        this.timeout = timeout;
    }

    /** 默认选项：视频 {@code libx264}、音频 {@code aac}，其余不设。 */
    public static TranscodeOptions defaults() {
        return new TranscodeOptions("libx264", "aac", null, null, null, null,
                null, null, null, null, null, List.of(), null, null);
    }

    public TranscodeOptions videoCodec(String v) {
        return new TranscodeOptions(v, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, extraOutputArgs, onProgress, timeout);
    }

    public TranscodeOptions audioCodec(String v) {
        return new TranscodeOptions(videoCodec, v, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, extraOutputArgs, onProgress, timeout);
    }

    public TranscodeOptions crf(Integer v) {
        return new TranscodeOptions(videoCodec, audioCodec, v, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, extraOutputArgs, onProgress, timeout);
    }

    public TranscodeOptions preset(String v) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, v, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, extraOutputArgs, onProgress, timeout);
    }

    public TranscodeOptions videoBitrate(String v) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, v, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, extraOutputArgs, onProgress, timeout);
    }

    public TranscodeOptions audioBitrate(String v) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, v,
                videoFilter, fps, maxrate, bufsize, gop, extraOutputArgs, onProgress, timeout);
    }

    /** 单输入视频滤镜链入口（见类文档）；给定后视频走必选映射、音频仍可选。 */
    public TranscodeOptions videoFilter(Function<VideoStream, VideoStream> fn) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                fn, fps, maxrate, bufsize, gop, extraOutputArgs, onProgress, timeout);
    }

    /** 输出帧率（{@code -r}）；须为正数。 */
    public TranscodeOptions fps(double v) {
        if (v <= 0) {
            throw new IllegalArgumentException("fps 须为正数，实际 " + v);
        }
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, v, maxrate, bufsize, gop, extraOutputArgs, onProgress, timeout);
    }

    /** VBV 峰值码率（{@code -maxrate}，如 {@code "2M"}/{@code "2000k"}）；h264 惯用，h265 走 extraOutputArgs。 */
    public TranscodeOptions maxrate(String v) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, v, bufsize, gop, extraOutputArgs, onProgress, timeout);
    }

    /** VBV 缓冲大小（{@code -bufsize}，如 {@code "4M"}）；通常配合 {@code maxrate}。 */
    public TranscodeOptions bufsize(String v) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, v, gop, extraOutputArgs, onProgress, timeout);
    }

    /** 关键帧间隔<em>帧数</em>（派生 {@code -keyint_min N -g N -sc_threshold 0}）；须为正整数。下游按 {@code fps*秒} 传入。 */
    public TranscodeOptions gop(int frames) {
        if (frames <= 0) {
            throw new IllegalArgumentException("gop 帧数须为正整数，实际 " + frames);
        }
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, frames, extraOutputArgs, onProgress, timeout);
    }

    /** 原始输出参数逃生舱（内容不参与类型校验），置于类型化码控之后。 */
    public TranscodeOptions extraOutputArgs(String... args) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, List.of(args), onProgress, timeout);
    }

    public TranscodeOptions onProgress(Consumer<Progress> cb) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, extraOutputArgs, cb, timeout);
    }

    public TranscodeOptions timeout(Duration t) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, extraOutputArgs, onProgress, t);
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

    public Function<VideoStream, VideoStream> videoFilter() {
        return videoFilter;
    }

    public Double fps() {
        return fps;
    }

    public String maxrate() {
        return maxrate;
    }

    public String bufsize() {
        return bufsize;
    }

    public Integer gop() {
        return gop;
    }

    /** 原始输出参数（不可变）；未设为空 List。 */
    public List<String> extraOutputArgs() {
        return extraOutputArgs;
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
