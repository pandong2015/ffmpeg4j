package io.github.pandong2015.ffmpeg4j.facade;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import io.github.pandong2015.ffmpeg4j.engine.Progress;

/**
 * {@link Ffmpeg#hlsAbr} 的进阶选项（不可变、wither 风格；<em>不用</em> record，理由同 {@link HlsVariant}）。
 *
 * <p><b>产物布局（ABR VOD）</b>：{@code outDir/<masterPlaylistName>}（默认 {@code master.m3u8}）+ 每档
 * {@code outDir/<变体目录>/index.m3u8} + {@code outDir/<变体目录>/<segmentTemplate>} + 启用 AES 时
 * {@code outDir/key/enc.key}。<b>变体目录名</b>：{@link HlsVariant#name()} 给值即用之，否则数字索引 {@code 0/1/2}。
 * 每档 playlist 与段共位 → <b>默认不注入 {@code -hls_base_url}</b>（与单码率相反；ffmpeg 8.0.1 实测 {@code %v} 不展开、
 * 共位布局天然自洽）。
 *
 * <p><b>码率梯</b>：{@link #variants} 内部 {@code null} 哨兵=未设 → 门面套用 {@link HlsLadder#defaults()} 并按源高度
 * <em>裁剪</em>（需 ffprobe）；显式给梯则<em>不裁</em>（尊重意图，仅 Javadoc 告警放大风险）。
 *
 * <p><b>恒转码 + 恒对齐</b>：ABR 每档不同分辨率/码率必经 filtergraph 重编码；跨档关键帧对齐
 * （{@code -force_key_frames expr:gte(t,n_forced*hlsTime)}）是无缝切码率的定义性行为，<b>恒开、不暴露开关</b>。
 * 无 {@code videoCodec}/{@code audioCodec}（下沉 {@link HlsVariant}）、无 {@code alignKeyframes}、无 {@code segmentUriPrefix}。
 *
 * <p><b>音频</b>：{@link #sharedAudio}（默认 {@code true}）= agroup 共享单音轨（编码取本类 {@link #audioBitrate} +
 * 首档 {@link HlsVariant#audioCodec()}），省 N× 存储、各档边界一致；{@code false} 则每档独立音频。
 */
public final class HlsAbrOptions {

    private static final Pattern SEGMENT_INDEX = Pattern.compile("%[0-9]*d");

    private final List<HlsVariant> variants; // 内部 null 哨兵=未设（门面用默认梯+probe 裁剪）
    private final double hlsTime;
    private final HlsKey key; // 可空
    private final String audioBitrate;
    private final String masterPlaylistName;
    private final String segmentTemplate;
    private final int startNumber;
    private final boolean sharedAudio;
    private final List<String> extraOutputArgs;
    private final Consumer<Progress> onProgress;
    private final Duration timeout;

    private HlsAbrOptions(List<HlsVariant> variants, double hlsTime, HlsKey key, String audioBitrate,
                          String masterPlaylistName, String segmentTemplate, int startNumber, boolean sharedAudio,
                          List<String> extraOutputArgs, Consumer<Progress> onProgress, Duration timeout) {
        this.variants = variants == null ? null : List.copyOf(variants);
        this.hlsTime = hlsTime;
        this.key = key;
        this.audioBitrate = audioBitrate;
        this.masterPlaylistName = masterPlaylistName;
        this.segmentTemplate = segmentTemplate;
        this.startNumber = startNumber;
        this.sharedAudio = sharedAudio;
        this.extraOutputArgs = List.copyOf(extraOutputArgs);
        this.onProgress = onProgress;
        this.timeout = timeout;
    }

    /** 默认：默认梯（null 哨兵，门面裁剪）、{@code hlsTime=6.0}、agroup 共享音频 {@code 128k}、{@code master.m3u8 + seg_%d.ts}、无 AES。 */
    public static HlsAbrOptions defaults() {
        return new HlsAbrOptions(null, 6.0, null, "128k", "master.m3u8", "seg_%d.ts", 0, true,
                List.of(), null, null);
    }

    private HlsAbrOptions with(List<HlsVariant> variants, double hlsTime, HlsKey key, String audioBitrate,
                              String masterPlaylistName, String segmentTemplate, int startNumber, boolean sharedAudio,
                              List<String> extraOutputArgs, Consumer<Progress> onProgress, Duration timeout) {
        return new HlsAbrOptions(variants, hlsTime, key, audioBitrate, masterPlaylistName, segmentTemplate,
                startNumber, sharedAudio, extraOutputArgs, onProgress, timeout);
    }

    /**
     * 显式码率梯（覆盖默认梯 + 关闭 probe 裁剪）。给 {@code null} 复位为「未设」哨兵（门面用默认梯裁剪）。
     * <b>显式梯不按源高度裁剪</b>——放大风险自负。
     */
    public HlsAbrOptions variants(List<HlsVariant> v) {
        if (v != null && v.isEmpty()) {
            throw new IllegalArgumentException("variants 不能为空 List（省略请传 null 走默认梯）");
        }
        return with(v, hlsTime, key, audioBitrate, masterPlaylistName, segmentTemplate, startNumber, sharedAudio,
                extraOutputArgs, onProgress, timeout);
    }

    /** 目标分段时长（秒，去尾零渲染为 {@code -hls_time} 与对齐表达式的 {@code T}）；须为正。默认 6.0。 */
    public HlsAbrOptions hlsTime(double v) {
        if (v <= 0) {
            throw new IllegalArgumentException("hlsTime 须为正数，实际 " + v);
        }
        return with(variants, v, key, audioBitrate, masterPlaylistName, segmentTemplate, startNumber, sharedAudio,
                extraOutputArgs, onProgress, timeout);
    }

    /** AES-128 密钥（默认 {@code null}=不加密）；单密钥覆盖全档，复用 {@link HlsKey}（B2/B1）。 */
    public HlsAbrOptions key(HlsKey v) {
        return with(variants, hlsTime, v, audioBitrate, masterPlaylistName, segmentTemplate, startNumber, sharedAudio,
                extraOutputArgs, onProgress, timeout);
    }

    /** agroup 共享音轨的目标码率（默认 {@code 128k}；仅 {@link #sharedAudio}=true 时生效）。 */
    public HlsAbrOptions audioBitrate(String v) {
        return with(variants, hlsTime, key, v, masterPlaylistName, segmentTemplate, startNumber, sharedAudio,
                extraOutputArgs, onProgress, timeout);
    }

    /** master 播放列表文件名（落 outDir 根，默认 {@code master.m3u8}）；不得含路径分隔符。 */
    public HlsAbrOptions masterPlaylistName(String v) {
        requireNoPathSeparator("masterPlaylistName", v);
        return with(variants, hlsTime, key, audioBitrate, v, segmentTemplate, startNumber, sharedAudio,
                extraOutputArgs, onProgress, timeout);
    }

    /** 分段文件名模板（默认 {@code seg_%d.ts}）；须含序号占位符 {@code %d}/{@code %0Nd}。 */
    public HlsAbrOptions segmentTemplate(String v) {
        if (v == null || !SEGMENT_INDEX.matcher(v).find()) {
            throw new IllegalArgumentException("segmentTemplate 须含序号占位符 %d/%0Nd，实际 " + v);
        }
        return with(variants, hlsTime, key, audioBitrate, masterPlaylistName, v, startNumber, sharedAudio,
                extraOutputArgs, onProgress, timeout);
    }

    /** 分段起始序号（{@code -start_number}）；须非负。 */
    public HlsAbrOptions startNumber(int v) {
        if (v < 0) {
            throw new IllegalArgumentException("startNumber 须非负，实际 " + v);
        }
        return with(variants, hlsTime, key, audioBitrate, masterPlaylistName, segmentTemplate, v, sharedAudio,
                extraOutputArgs, onProgress, timeout);
    }

    /** 音频模式：{@code true}（默认）=agroup 共享单音轨；{@code false}=每档独立音频复制。 */
    public HlsAbrOptions sharedAudio(boolean v) {
        return with(variants, hlsTime, key, audioBitrate, masterPlaylistName, segmentTemplate, startNumber, v,
                extraOutputArgs, onProgress, timeout);
    }

    /** 原始输出参数逃生舱（内容不参与类型校验），置于类型化 {@code -hls_*} 之后（同键 ffmpeg 取后者）。 */
    public HlsAbrOptions extraOutputArgs(String... args) {
        return with(variants, hlsTime, key, audioBitrate, masterPlaylistName, segmentTemplate, startNumber, sharedAudio,
                List.of(args), onProgress, timeout);
    }

    public HlsAbrOptions onProgress(Consumer<Progress> cb) {
        return with(variants, hlsTime, key, audioBitrate, masterPlaylistName, segmentTemplate, startNumber, sharedAudio,
                extraOutputArgs, cb, timeout);
    }

    public HlsAbrOptions timeout(Duration t) {
        return with(variants, hlsTime, key, audioBitrate, masterPlaylistName, segmentTemplate, startNumber, sharedAudio,
                extraOutputArgs, onProgress, t);
    }

    private static void requireNoPathSeparator(String field, String v) {
        if (v == null || v.isEmpty() || v.indexOf('/') >= 0 || v.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(field + " 不得为空或含路径分隔符，实际 " + v);
        }
    }

    // ===== 访问器 =====

    /**
     * 码率梯（只读视图）：显式设过则返回之，否则返回 {@link HlsLadder#defaults()} 默认梯（<em>未裁剪</em>）。
     * 裁剪判定读内部哨兵（{@link #variantsExplicit()}），不经本访问器。
     */
    public List<HlsVariant> variants() {
        return variants != null ? variants : HlsLadder.defaults();
    }

    /** 内部：是否显式设过码率梯（true=不裁剪；false=默认梯+probe 裁剪）。 */
    boolean variantsExplicit() {
        return variants != null;
    }

    /** 内部：显式码率梯（未设为 {@code null}）——门面据此区分默认/显式。 */
    List<HlsVariant> variantsOrNull() {
        return variants;
    }

    public double hlsTime() {
        return hlsTime;
    }

    public HlsKey key() {
        return key;
    }

    public String audioBitrate() {
        return audioBitrate;
    }

    public String masterPlaylistName() {
        return masterPlaylistName;
    }

    public String segmentTemplate() {
        return segmentTemplate;
    }

    public int startNumber() {
        return startNumber;
    }

    public boolean sharedAudio() {
        return sharedAudio;
    }

    public List<String> extraOutputArgs() {
        return extraOutputArgs;
    }

    public Consumer<Progress> onProgress() {
        return onProgress;
    }

    public Duration timeout() {
        return timeout;
    }
}
