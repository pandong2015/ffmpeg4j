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
 * {@code gop}（关键帧间隔<em>帧数</em>，派生 {@code -keyint_min}/{@code -g}/{@code -sc_threshold 0}）/
 * {@code forceKeyframesEverySeconds}（按<em>秒</em>强制关键帧，派生 {@code -force_key_frames expr:gte(t,n_forced*T)}）；
 * 音频/编码器进阶 {@code audioSampleRate}（{@code -ar}）/{@code strict}（{@code -strict}）/{@code x265Params}
 * （{@code -x265-params}）；流禁用 {@code disableVideo}（{@code -vn}）/{@code disableAudio}（{@code -an}）。
 *
 * <p>{@code videoFilter} 为可选的<em>单输入</em>视频滤镜链入口：给定时以 {@code input.video()}（必选映射）为起点
 * 应用该函数，视频经 filter_complex、音频仍用可选映射；不给定时保持双可选映射、既有 argv 逐字节不变。函数内可自建
 * 额外输入（如水印图 {@code Input.of(logo).withInputArgs("-loop","1").video()}）并叠加，编译器自动补出第二路 {@code -i}。
 *
 * <p>{@code extraOutputArgs} 为原始输出参数逃生舱（内容不参与类型校验），置于类型化码控之后（同键 ffmpeg 取后者）。
 * h264 的 VBV 用类型化 {@code maxrate}/{@code bufsize}（渲染为 {@code -maxrate}/{@code -bufsize}）；libx265 的
 * VBV 走类型化 {@link #x265Params(String)}（{@code -x265-params}）或 {@code extraOutputArgs}——库不自动翻译
 * {@code maxrate}/{@code bufsize}，避免耦合编解码器字符串解析。
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
    private final Double forceKeyframesEverySeconds;
    private final List<String> extraOutputArgs;
    private final Consumer<Progress> onProgress;
    private final Duration timeout;
    private final boolean disableVideo;
    private final boolean disableAudio;
    private final Integer audioSampleRate;
    private final String strict;
    private final String x265Params;
    private final boolean vbvDeriveBufsize;

    private TranscodeOptions(String videoCodec, String audioCodec, Integer crf, String preset,
                             String videoBitrate, String audioBitrate,
                             Function<VideoStream, VideoStream> videoFilter, Double fps,
                             String maxrate, String bufsize, Integer gop, Double forceKeyframesEverySeconds,
                             List<String> extraOutputArgs, Consumer<Progress> onProgress, Duration timeout,
                             boolean disableVideo, boolean disableAudio, Integer audioSampleRate,
                             String strict, String x265Params, boolean vbvDeriveBufsize) {
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
        this.forceKeyframesEverySeconds = forceKeyframesEverySeconds;
        this.extraOutputArgs = List.copyOf(extraOutputArgs);
        this.onProgress = onProgress;
        this.timeout = timeout;
        this.disableVideo = disableVideo;
        this.disableAudio = disableAudio;
        this.audioSampleRate = audioSampleRate;
        this.strict = strict;
        this.x265Params = x265Params;
        this.vbvDeriveBufsize = vbvDeriveBufsize;
    }

    /** 默认选项：视频 {@code libx264}、音频 {@code aac}，其余不设。 */
    public static TranscodeOptions defaults() {
        return new TranscodeOptions("libx264", "aac", null, null, null, null,
                null, null, null, null, null, null, List.of(), null, null,
                false, false, null, null, null, false);
    }

    public TranscodeOptions videoCodec(String v) {
        return new TranscodeOptions(v, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, forceKeyframesEverySeconds, extraOutputArgs, onProgress, timeout,
                disableVideo, disableAudio, audioSampleRate, strict, x265Params, vbvDeriveBufsize);
    }

    public TranscodeOptions audioCodec(String v) {
        return new TranscodeOptions(videoCodec, v, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, forceKeyframesEverySeconds, extraOutputArgs, onProgress, timeout,
                disableVideo, disableAudio, audioSampleRate, strict, x265Params, vbvDeriveBufsize);
    }

    public TranscodeOptions crf(Integer v) {
        return new TranscodeOptions(videoCodec, audioCodec, v, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, forceKeyframesEverySeconds, extraOutputArgs, onProgress, timeout,
                disableVideo, disableAudio, audioSampleRate, strict, x265Params, vbvDeriveBufsize);
    }

    public TranscodeOptions preset(String v) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, v, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, forceKeyframesEverySeconds, extraOutputArgs, onProgress, timeout,
                disableVideo, disableAudio, audioSampleRate, strict, x265Params, vbvDeriveBufsize);
    }

    public TranscodeOptions videoBitrate(String v) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, v, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, forceKeyframesEverySeconds, extraOutputArgs, onProgress, timeout,
                disableVideo, disableAudio, audioSampleRate, strict, x265Params, vbvDeriveBufsize);
    }

    public TranscodeOptions audioBitrate(String v) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, v,
                videoFilter, fps, maxrate, bufsize, gop, forceKeyframesEverySeconds, extraOutputArgs, onProgress, timeout,
                disableVideo, disableAudio, audioSampleRate, strict, x265Params, vbvDeriveBufsize);
    }

    /** 单输入视频滤镜链入口（见类文档）；给定后视频走必选映射、音频仍可选。 */
    public TranscodeOptions videoFilter(Function<VideoStream, VideoStream> fn) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                fn, fps, maxrate, bufsize, gop, forceKeyframesEverySeconds, extraOutputArgs, onProgress, timeout,
                disableVideo, disableAudio, audioSampleRate, strict, x265Params, vbvDeriveBufsize);
    }

    /** 输出帧率（{@code -r}）；须为正数。 */
    public TranscodeOptions fps(double v) {
        if (v <= 0) {
            throw new IllegalArgumentException("fps 须为正数，实际 " + v);
        }
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, v, maxrate, bufsize, gop, forceKeyframesEverySeconds, extraOutputArgs, onProgress, timeout,
                disableVideo, disableAudio, audioSampleRate, strict, x265Params, vbvDeriveBufsize);
    }

    /** VBV 峰值码率（{@code -maxrate}，如 {@code "2M"}/{@code "2000k"}）；h264 惯用，h265 走 {@link #x265Params}。 */
    public TranscodeOptions maxrate(String v) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, v, bufsize, gop, forceKeyframesEverySeconds, extraOutputArgs, onProgress, timeout,
                disableVideo, disableAudio, audioSampleRate, strict, x265Params, vbvDeriveBufsize);
    }

    /**
     * VBV 缓冲大小（{@code -bufsize}，如 {@code "4M"}）；通常配合 {@code maxrate}。显式设置 MUST 清除 {@link #vbv(String)}
     * 的待派生标志（用户显式优先）。<b>孤立 {@code bufsize}（未设 {@code maxrate}）通常是配置疏漏</b>——VBV 请配
     * {@code maxrate} 或改用 {@link #vbv(String)}；本库为保 argv 逐字节兼容<em>不</em>对此硬失败，仍逐字产出 {@code -bufsize}。
     */
    public TranscodeOptions bufsize(String v) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, v, gop, forceKeyframesEverySeconds, extraOutputArgs, onProgress, timeout,
                disableVideo, disableAudio, audioSampleRate, strict, x265Params, false);
    }

    /**
     * VBV 便利：设 {@code maxrate} 并标记「待派生 bufsize」——若届时未显式设置 {@code bufsize}，{@code buildTranscode}
     * 在 build 期依<em>最终</em> {@code maxrate} 派生 {@code bufsize = maxrate×2}（数值翻倍、保留 K/M 单位）。故
     * {@code vbv("2M").maxrate("3M")} 得 {@code bufsize=6M}（跟随最终值、无陈旧值）。显式 {@link #bufsize(String)} 会清除该标志。
     */
    public TranscodeOptions vbv(String maxrate) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, forceKeyframesEverySeconds, extraOutputArgs, onProgress, timeout,
                disableVideo, disableAudio, audioSampleRate, strict, x265Params, true);
    }

    /** VBV 便利：显式给定 {@code maxrate} 与 {@code bufsize}（清除待派生标志，不自动派生）。 */
    public TranscodeOptions vbv(String maxrate, String bufsize) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, forceKeyframesEverySeconds, extraOutputArgs, onProgress, timeout,
                disableVideo, disableAudio, audioSampleRate, strict, x265Params, false);
    }

    /** 关键帧间隔<em>帧数</em>（派生 {@code -keyint_min N -g N -sc_threshold 0}）；须为正整数。下游按 {@code fps*秒} 传入。 */
    public TranscodeOptions gop(int frames) {
        if (frames <= 0) {
            throw new IllegalArgumentException("gop 帧数须为正整数，实际 " + frames);
        }
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, frames, forceKeyframesEverySeconds, extraOutputArgs, onProgress, timeout,
                disableVideo, disableAudio, audioSampleRate, strict, x265Params, vbvDeriveBufsize);
    }

    /**
     * 按<em>秒</em>强制关键帧（派生 {@code -force_key_frames expr:gte(t,n_forced*seconds)}）；须为正数。
     *
     * <p>与帧基 {@link #gop(int)} <em>互补共存</em>（force 保证定时插入 IDR、{@code -g} 兜最大间隔），非二选一。
     * 与 {@link #gop(int)}（按帧数、随 fps 漂移）不同，本项按时间轴恒定，VFR 亦稳。{@code -force_key_frames}
     * 必然重编码，故与 {@code videoCodec("copy")} 冲突（编译期 {@code buildTranscode} fail-fast）。
     */
    public TranscodeOptions forceKeyframesEverySeconds(double seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("forceKeyframesEverySeconds 须为正数，实际 " + seconds);
        }
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, seconds, extraOutputArgs, onProgress, timeout,
                disableVideo, disableAudio, audioSampleRate, strict, x265Params, vbvDeriveBufsize);
    }

    /**
     * 禁用视频输出（{@code -vn}）：产 {@code -vn}、跳过 {@code -c:v} 及全部视频码控、输出只映射音频。用于从<em>含视频源</em>
     * 转码剥离视频的次选场景——<b>纯音频抽取首选 {@link Ffmpeg#extractAudio}</b>。与 {@link #videoFilter} 冲突（build 期 fail-fast）；
     * 与 {@code disableAudio} 同真（空输出）亦 fail-fast。启用时 crf/preset/{@code videoBitrate}/{@code fps}/{@code maxrate}/
     * {@code bufsize}/{@code gop}/{@code forceKeyframes}/{@code x265Params} 被静默跳过（无处生效）。
     */
    public TranscodeOptions disableVideo(boolean v) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, forceKeyframesEverySeconds, extraOutputArgs, onProgress, timeout,
                v, disableAudio, audioSampleRate, strict, x265Params, vbvDeriveBufsize);
    }

    /**
     * 禁用音频输出（{@code -an}）：产 {@code -an}、跳过 {@code -c:a} 及 {@code audioBitrate}/{@code audioSampleRate}、输出
     * 只映射视频。与 {@code disableVideo} 同真（空输出）时 build 期 fail-fast。
     */
    public TranscodeOptions disableAudio(boolean v) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, forceKeyframesEverySeconds, extraOutputArgs, onProgress, timeout,
                disableVideo, v, audioSampleRate, strict, x265Params, vbvDeriveBufsize);
    }

    /** 音频采样率（{@code -ar}，如 {@code 44100}）；须为正整数。渲染紧接 {@code -b:a}；{@code disableAudio} 时不产出。 */
    public TranscodeOptions audioSampleRate(int hz) {
        if (hz <= 0) {
            throw new IllegalArgumentException("audioSampleRate 须为正整数，实际 " + hz);
        }
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, forceKeyframesEverySeconds, extraOutputArgs, onProgress, timeout,
                disableVideo, disableAudio, hz, strict, x265Params, vbvDeriveBufsize);
    }

    /**
     * 编码器严格度（{@code -strict}）逃生舱：合法值 {@code -2..2} 或别名 {@code experimental}（=-2）/{@code unofficial}
     * （=-1）/{@code normal}（=0）/{@code strict}（=1）/{@code very}（=2）。渲染于全部类型化码控之后、{@code extraOutputArgs}
     * 之前，与音/视频禁用标志无关（编码器通用）。常见的「允许实验编码器」诉求可用便利 {@link #strictExperimental()}。
     */
    public TranscodeOptions strict(String level) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, forceKeyframesEverySeconds, extraOutputArgs, onProgress, timeout,
                disableVideo, disableAudio, audioSampleRate, level, x265Params, vbvDeriveBufsize);
    }

    /** 便利：{@code strict("-2")}——允许实验编码器（如旧版原生 aac）。 */
    public TranscodeOptions strictExperimental() {
        return strict("-2");
    }

    /**
     * libx265 参数（{@code -x265-params}，如 {@code "vbv-maxrate=2000:vbv-bufsize=4000"}）：让 h265 VBV 有类型化位置，
     * 免受 {@code extraOutputArgs} 整体替换 List 的脚枪。<b>仅对 libx265 有意义，库不校验 codec</b>、不把 {@code maxrate}/
     * {@code bufsize} 自动翻译为此项；libx264 的 {@code -x264-params} 仍走 {@code extraOutputArgs}。渲染紧接视频码控段尾
     * （{@code -bufsize} 之后、{@code -c:a} 之前）；{@code disableVideo} 时不产出。
     */
    public TranscodeOptions x265Params(String value) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, forceKeyframesEverySeconds, extraOutputArgs, onProgress, timeout,
                disableVideo, disableAudio, audioSampleRate, strict, value, vbvDeriveBufsize);
    }

    /** 原始输出参数逃生舱（内容不参与类型校验），置于类型化码控之后。 */
    public TranscodeOptions extraOutputArgs(String... args) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, forceKeyframesEverySeconds, List.of(args), onProgress, timeout,
                disableVideo, disableAudio, audioSampleRate, strict, x265Params, vbvDeriveBufsize);
    }

    public TranscodeOptions onProgress(Consumer<Progress> cb) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, forceKeyframesEverySeconds, extraOutputArgs, cb, timeout,
                disableVideo, disableAudio, audioSampleRate, strict, x265Params, vbvDeriveBufsize);
    }

    public TranscodeOptions timeout(Duration t) {
        return new TranscodeOptions(videoCodec, audioCodec, crf, preset, videoBitrate, audioBitrate,
                videoFilter, fps, maxrate, bufsize, gop, forceKeyframesEverySeconds, extraOutputArgs, onProgress, t,
                disableVideo, disableAudio, audioSampleRate, strict, x265Params, vbvDeriveBufsize);
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

    /** 按秒强制关键帧的间隔（秒）；未设为 {@code null}。 */
    public Double forceKeyframesEverySeconds() {
        return forceKeyframesEverySeconds;
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

    /** 是否禁用视频输出（{@code -vn}）。 */
    public boolean disableVideo() {
        return disableVideo;
    }

    /** 是否禁用音频输出（{@code -an}）。 */
    public boolean disableAudio() {
        return disableAudio;
    }

    /** 音频采样率（{@code -ar}）；未设为 {@code null}。 */
    public Integer audioSampleRate() {
        return audioSampleRate;
    }

    /** 编码器严格度（{@code -strict}）；未设为 {@code null}。 */
    public String strict() {
        return strict;
    }

    /** libx265 参数（{@code -x265-params}）；未设为 {@code null}。 */
    public String x265Params() {
        return x265Params;
    }

    /** 是否由 {@link #vbv(String)} 标记「待 build 期派生 bufsize=maxrate×2」（显式 {@code bufsize} 会清除之）。 */
    public boolean vbvDeriveBufsize() {
        return vbvDeriveBufsize;
    }

    /** 把 {@code onProgress}/{@code timeout} 映射为执行引擎的 {@link RunOptions}。 */
    RunOptions toRunOptions() {
        return FacadeSupport.runOptions(timeout, onProgress);
    }
}
