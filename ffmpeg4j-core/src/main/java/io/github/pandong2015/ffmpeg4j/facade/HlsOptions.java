package io.github.pandong2015.ffmpeg4j.facade;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;

/**
 * {@link Ffmpeg#hlsSegment} 的进阶选项。不可变、wither 风格（照抄 {@link TranscodeOptions}，<em>不用</em> record——
 * record 规范构造器是公开 API，加字段即二进制不兼容）。
 *
 * <p><b>产物布局（单码率 VOD）</b>：{@code outDir/<playlistName>}（默认 {@code index.m3u8}）+
 * {@code outDir/<segmentDir>/<segmentTemplate>}（默认 {@code ts/index%d.ts}）+ 启用 AES 时 {@code outDir/<keyDir>/<keyFileName>}
 * （默认 {@code key/enc.key}）。段与 playlist 分离，故段 URI 前缀经默认注入 {@code -hls_base_url <segmentDir>/} 保证
 * （ffmpeg 单播放列表对段 URI 取 basename、不隐式相对化）；{@code segmentUriPrefix}（CDN）设值时<em>覆盖</em>内部前缀。
 *
 * <p><b>默认 {@code -c copy}</b>（切片直拷、不 probe）；{@code hlsTime} 下段长受源关键帧支配（只是尽量），需均匀段用
 * {@link #alignKeyframes(boolean)}（转码 + 对齐，与 {@code copy} 冲突时编译期报错）。VOD 双标签
 * {@code -hls_playlist_type vod}/{@code -hls_list_size 0} 由库内部固定注入、不作字段。多码率/fMP4/live/密钥轮换等出界经
 * {@link #extraOutputArgs(String...)}。
 */
public final class HlsOptions {

    private static final Pattern SEGMENT_INDEX = Pattern.compile("%[0-9]*d");

    private final double hlsTime;
    private final String playlistName;
    private final String segmentDir;
    private final String segmentTemplate;
    private final String keyDir;
    private final String keyFileName;
    private final int startNumber;
    private final String videoCodec;
    private final String audioCodec;
    private final HlsKey key;
    private final boolean alignKeyframes;
    private final String segmentUriPrefix; // 可空
    private final boolean cleanSegmentDir;
    private final List<String> extraOutputArgs;
    private final Consumer<Progress> onProgress;
    private final Duration timeout;

    private HlsOptions(double hlsTime, String playlistName, String segmentDir, String segmentTemplate,
                       String keyDir, String keyFileName, int startNumber, String videoCodec, String audioCodec,
                       HlsKey key, boolean alignKeyframes, String segmentUriPrefix, boolean cleanSegmentDir,
                       List<String> extraOutputArgs, Consumer<Progress> onProgress, Duration timeout) {
        this.hlsTime = hlsTime;
        this.playlistName = playlistName;
        this.segmentDir = segmentDir;
        this.segmentTemplate = segmentTemplate;
        this.keyDir = keyDir;
        this.keyFileName = keyFileName;
        this.startNumber = startNumber;
        this.videoCodec = videoCodec;
        this.audioCodec = audioCodec;
        this.key = key;
        this.alignKeyframes = alignKeyframes;
        this.segmentUriPrefix = segmentUriPrefix;
        this.cleanSegmentDir = cleanSegmentDir;
        this.extraOutputArgs = List.copyOf(extraOutputArgs);
        this.onProgress = onProgress;
        this.timeout = timeout;
    }

    /** 默认：{@code -c copy}、{@code hlsTime=8.0}、布局 {@code index.m3u8 + ts/index%d.ts}、无 AES。 */
    public static HlsOptions defaults() {
        return new HlsOptions(8.0, "index.m3u8", "ts", "index%d.ts", "key", "enc.key", 0,
                "copy", "copy", null, false, null, false, List.of(), null, null);
    }

    private HlsOptions with(double hlsTime, String playlistName, String segmentDir, String segmentTemplate,
                            String keyDir, String keyFileName, int startNumber, String videoCodec, String audioCodec,
                            HlsKey key, boolean alignKeyframes, String segmentUriPrefix, boolean cleanSegmentDir,
                            List<String> extraOutputArgs, Consumer<Progress> onProgress, Duration timeout) {
        return new HlsOptions(hlsTime, playlistName, segmentDir, segmentTemplate, keyDir, keyFileName, startNumber,
                videoCodec, audioCodec, key, alignKeyframes, segmentUriPrefix, cleanSegmentDir,
                extraOutputArgs, onProgress, timeout);
    }

    /** 目标分段时长（秒，去尾零渲染为 {@code -hls_time}）；须为正数。{@code copy} 下只是尽量（段长受源关键帧支配）。 */
    public HlsOptions hlsTime(double v) {
        if (v <= 0) {
            throw new IllegalArgumentException("hlsTime 须为正数，实际 " + v);
        }
        return with(v, playlistName, segmentDir, segmentTemplate, keyDir, keyFileName, startNumber,
                videoCodec, audioCodec, key, alignKeyframes, segmentUriPrefix, cleanSegmentDir, extraOutputArgs, onProgress, timeout);
    }

    /** 播放列表文件名（落 outDir 根）。 */
    public HlsOptions playlistName(String v) {
        return with(hlsTime, v, segmentDir, segmentTemplate, keyDir, keyFileName, startNumber,
                videoCodec, audioCodec, key, alignKeyframes, segmentUriPrefix, cleanSegmentDir, extraOutputArgs, onProgress, timeout);
    }

    /** 分段子目录名（默认 {@code ts}）；不得含路径分隔符。 */
    public HlsOptions segmentDir(String v) {
        requireNoPathSeparator("segmentDir", v);
        return with(hlsTime, playlistName, v, segmentTemplate, keyDir, keyFileName, startNumber,
                videoCodec, audioCodec, key, alignKeyframes, segmentUriPrefix, cleanSegmentDir, extraOutputArgs, onProgress, timeout);
    }

    /** 分段文件名模板（默认 {@code index%d.ts}）；须含 printf 序号占位符 {@code %d}/{@code %0Nd}（否则各段覆写同一文件）。 */
    public HlsOptions segmentTemplate(String v) {
        if (v == null || !SEGMENT_INDEX.matcher(v).find()) {
            throw new IllegalArgumentException("segmentTemplate 须含序号占位符 %d/%0Nd，实际 " + v);
        }
        return with(hlsTime, playlistName, segmentDir, v, keyDir, keyFileName, startNumber,
                videoCodec, audioCodec, key, alignKeyframes, segmentUriPrefix, cleanSegmentDir, extraOutputArgs, onProgress, timeout);
    }

    /** 密钥子目录名（默认 {@code key}，与 {@code segmentDir} 分离）；不得含路径分隔符。 */
    public HlsOptions keyDir(String v) {
        requireNoPathSeparator("keyDir", v);
        return with(hlsTime, playlistName, segmentDir, segmentTemplate, v, keyFileName, startNumber,
                videoCodec, audioCodec, key, alignKeyframes, segmentUriPrefix, cleanSegmentDir, extraOutputArgs, onProgress, timeout);
    }

    /** 密钥文件名（默认 {@code enc.key}）。 */
    public HlsOptions keyFileName(String v) {
        return with(hlsTime, playlistName, segmentDir, segmentTemplate, keyDir, v, startNumber,
                videoCodec, audioCodec, key, alignKeyframes, segmentUriPrefix, cleanSegmentDir, extraOutputArgs, onProgress, timeout);
    }

    /** 分段起始序号（{@code -start_number}）；须非负。 */
    public HlsOptions startNumber(int v) {
        if (v < 0) {
            throw new IllegalArgumentException("startNumber 须非负，实际 " + v);
        }
        return with(hlsTime, playlistName, segmentDir, segmentTemplate, keyDir, keyFileName, v,
                videoCodec, audioCodec, key, alignKeyframes, segmentUriPrefix, cleanSegmentDir, extraOutputArgs, onProgress, timeout);
    }

    /** 视频编解码器（默认 {@code copy}）；给具体编码器即转码路径。 */
    public HlsOptions videoCodec(String v) {
        return with(hlsTime, playlistName, segmentDir, segmentTemplate, keyDir, keyFileName, startNumber,
                v, audioCodec, key, alignKeyframes, segmentUriPrefix, cleanSegmentDir, extraOutputArgs, onProgress, timeout);
    }

    /** 音频编解码器（默认 {@code copy}）。 */
    public HlsOptions audioCodec(String v) {
        return with(hlsTime, playlistName, segmentDir, segmentTemplate, keyDir, keyFileName, startNumber,
                videoCodec, v, key, alignKeyframes, segmentUriPrefix, cleanSegmentDir, extraOutputArgs, onProgress, timeout);
    }

    /** AES-128 密钥（默认 {@code null}=不加密）；见 {@link HlsKey}（B2/B1）。 */
    public HlsOptions key(HlsKey v) {
        return with(hlsTime, playlistName, segmentDir, segmentTemplate, keyDir, keyFileName, startNumber,
                videoCodec, audioCodec, v, alignKeyframes, segmentUriPrefix, cleanSegmentDir, extraOutputArgs, onProgress, timeout);
    }

    /**
     * 段边界与关键帧对齐（默认 {@code false}）。{@code true} 时以 {@code T=hlsTime} 注入
     * {@code -force_key_frames expr:gte(t,n_forced*hlsTime)}，得均匀且可独立解码的分段。<b>需重编码</b>——
     * 与 {@code videoCodec("copy")} 冲突时编译期（{@code buildHls}）报错。
     */
    public HlsOptions alignKeyframes(boolean v) {
        return with(hlsTime, playlistName, segmentDir, segmentTemplate, keyDir, keyFileName, startNumber,
                videoCodec, audioCodec, key, v, segmentUriPrefix, cleanSegmentDir, extraOutputArgs, onProgress, timeout);
    }

    /**
     * 段 URI 的 CDN 前缀（{@code -hls_base_url}）；设值时<em>覆盖</em>内部默认的 {@code <segmentDir>/}。注意
     * {@code -hls_base_url} 对段 URI 取 basename、<em>不叠加</em> {@code segmentDir}——需保留子目录须自带（如 {@code https://cdn/ts/}）。
     */
    public HlsOptions segmentUriPrefix(String v) {
        return with(hlsTime, playlistName, segmentDir, segmentTemplate, keyDir, keyFileName, startNumber,
                videoCodec, audioCodec, key, alignKeyframes, v, cleanSegmentDir, extraOutputArgs, onProgress, timeout);
    }

    /** 运行前清空 {@code segmentDir}（默认 {@code false}）；避免复用非空 outDir 时残留上次分段。 */
    public HlsOptions cleanSegmentDir(boolean v) {
        return with(hlsTime, playlistName, segmentDir, segmentTemplate, keyDir, keyFileName, startNumber,
                videoCodec, audioCodec, key, alignKeyframes, segmentUriPrefix, v, extraOutputArgs, onProgress, timeout);
    }

    /** 原始输出参数逃生舱（内容不参与类型校验），置于类型化 {@code -hls_*} 之后（同键 ffmpeg 取后者）。 */
    public HlsOptions extraOutputArgs(String... args) {
        return with(hlsTime, playlistName, segmentDir, segmentTemplate, keyDir, keyFileName, startNumber,
                videoCodec, audioCodec, key, alignKeyframes, segmentUriPrefix, cleanSegmentDir, List.of(args), onProgress, timeout);
    }

    public HlsOptions onProgress(Consumer<Progress> cb) {
        return with(hlsTime, playlistName, segmentDir, segmentTemplate, keyDir, keyFileName, startNumber,
                videoCodec, audioCodec, key, alignKeyframes, segmentUriPrefix, cleanSegmentDir, extraOutputArgs, cb, timeout);
    }

    public HlsOptions timeout(Duration t) {
        return with(hlsTime, playlistName, segmentDir, segmentTemplate, keyDir, keyFileName, startNumber,
                videoCodec, audioCodec, key, alignKeyframes, segmentUriPrefix, cleanSegmentDir, extraOutputArgs, onProgress, t);
    }

    private static void requireNoPathSeparator(String field, String v) {
        if (v == null || v.isEmpty() || v.indexOf('/') >= 0 || v.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(field + " 不得为空或含路径分隔符，实际 " + v);
        }
    }

    public double hlsTime() {
        return hlsTime;
    }

    public String playlistName() {
        return playlistName;
    }

    public String segmentDir() {
        return segmentDir;
    }

    public String segmentTemplate() {
        return segmentTemplate;
    }

    public String keyDir() {
        return keyDir;
    }

    public String keyFileName() {
        return keyFileName;
    }

    public int startNumber() {
        return startNumber;
    }

    public String videoCodec() {
        return videoCodec;
    }

    public String audioCodec() {
        return audioCodec;
    }

    public HlsKey key() {
        return key;
    }

    public boolean alignKeyframes() {
        return alignKeyframes;
    }

    /** 段 URI 前缀（{@code -hls_base_url} 覆盖值）；未设为 {@code null}（用内部默认 {@code <segmentDir>/}）。 */
    public String segmentUriPrefix() {
        return segmentUriPrefix;
    }

    public boolean cleanSegmentDir() {
        return cleanSegmentDir;
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
