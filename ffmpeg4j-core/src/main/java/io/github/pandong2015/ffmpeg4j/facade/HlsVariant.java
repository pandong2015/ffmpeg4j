package io.github.pandong2015.ffmpeg4j.facade;

import java.util.regex.Pattern;

/**
 * ABR 码率梯的单档变体（不可变、wither 风格；<em>不用</em> record——record 规范构造器是公开 API，
 * 加字段即二进制不兼容，与全库 Options/值类型惯例一致）。
 *
 * <p>必填 {@code height} + {@code videoBitrate}；可选 {@code width}（默认 {@code null} → 编码走
 * {@code scale=-2:h} 保比偶宽，H.264 要求偶数）、{@code maxrate}/{@code bufsize}（{@code null} 时由
 * {@code videoBitrate} 派生 ≈1.07×/1.5×）、{@code audioBitrate}（默认 {@code 128k}）、{@code videoCodec}
 * （默认 {@code libx264}）/{@code audioCodec}（默认 {@code aac}）、{@code crf}、{@code preset}、{@code name}
 * （默认 {@code null} → 变体目录回退数字索引 {@code 0/1/2}；给值才作 {@code var_stream_map} 的 {@code name:}
 * 与目录名）。
 *
 * <p><b>agroup 下</b>（{@link HlsAbrOptions#sharedAudio(boolean) sharedAudio=true}，默认）：全档共享单音轨，
 * 音频编码取 {@link HlsAbrOptions#audioBitrate}/{@code audioCodec}，本类 per-variant 的
 * {@link #audioBitrate}/{@link #audioCodec} <em>不生效</em>；仅 {@code sharedAudio(false)} 每档独立音频时才逐档下发。
 *
 * <p>wither 即时 {@link IllegalArgumentException}：{@code height<=0}、非法 bitrate、{@code name} 含
 * {@code %v} 或 {@code var_stream_map} 结构元字符（空格/逗号/冒号/路径分隔符）——实测这类 {@code name} 会撕裂
 * {@code var_stream_map} 致 exit 0 却零产物。
 */
public final class HlsVariant {

    /** 合法 bitrate：纯数字（可带小数）+ 可选单位 k/K/M/m/g/G（如 {@code 5000k}/{@code 5M}/{@code 800000}）。 */
    private static final Pattern BITRATE = Pattern.compile("\\d+(\\.\\d+)?[kKmMgG]?");

    private final int height;
    private final Integer width; // 可空：null → scale=-2:h
    private final String videoBitrate;
    private final String maxrate; // 可空：null → 由 videoBitrate 派生
    private final String bufsize; // 可空：null → 由 videoBitrate 派生
    private final String audioBitrate;
    private final String videoCodec;
    private final String audioCodec;
    private final Integer crf; // 可空
    private final String preset; // 可空
    private final String name; // 可空：null → 目录回退数字索引

    private HlsVariant(int height, Integer width, String videoBitrate, String maxrate, String bufsize,
                       String audioBitrate, String videoCodec, String audioCodec, Integer crf, String preset,
                       String name) {
        this.height = height;
        this.width = width;
        this.videoBitrate = videoBitrate;
        this.maxrate = maxrate;
        this.bufsize = bufsize;
        this.audioBitrate = audioBitrate;
        this.videoCodec = videoCodec;
        this.audioCodec = audioCodec;
        this.crf = crf;
        this.preset = preset;
        this.name = name;
    }

    /**
     * 构造一档变体：必填目标高度与视频码率。
     *
     * @param height       目标高度（像素，须为正）
     * @param videoBitrate 视频目标码率（{@code -b:v}，如 {@code 5000k}）
     */
    public static HlsVariant of(int height, String videoBitrate) {
        requirePositiveHeight(height);
        requireBitrate("videoBitrate", videoBitrate);
        return new HlsVariant(height, null, videoBitrate, null, null, "128k", "libx264", "aac", null, null, null);
    }

    private HlsVariant with(int height, Integer width, String videoBitrate, String maxrate, String bufsize,
                            String audioBitrate, String videoCodec, String audioCodec, Integer crf, String preset,
                            String name) {
        return new HlsVariant(height, width, videoBitrate, maxrate, bufsize, audioBitrate, videoCodec, audioCodec,
                crf, preset, name);
    }

    /** 显式输出宽度（默认 {@code null} → {@code scale=-2:h} 按比缩放并保证偶宽）；须为正。 */
    public HlsVariant width(int v) {
        if (v <= 0) {
            throw new IllegalArgumentException("width 须为正数，实际 " + v);
        }
        return with(height, v, videoBitrate, maxrate, bufsize, audioBitrate, videoCodec, audioCodec, crf, preset, name);
    }

    /** VBV 峰值码率（{@code -maxrate}）；默认 {@code null} → 由 {@code videoBitrate} 派生 ≈1.07×。 */
    public HlsVariant maxrate(String v) {
        requireBitrate("maxrate", v);
        return with(height, width, videoBitrate, v, bufsize, audioBitrate, videoCodec, audioCodec, crf, preset, name);
    }

    /** VBV 缓冲大小（{@code -bufsize}）；默认 {@code null} → 由 {@code videoBitrate} 派生 ≈1.5×。 */
    public HlsVariant bufsize(String v) {
        requireBitrate("bufsize", v);
        return with(height, width, videoBitrate, maxrate, v, audioBitrate, videoCodec, audioCodec, crf, preset, name);
    }

    /** per-variant 音频码率（默认 {@code 128k}；agroup 下不生效，见类注释）。 */
    public HlsVariant audioBitrate(String v) {
        requireBitrate("audioBitrate", v);
        return with(height, width, videoBitrate, maxrate, bufsize, v, videoCodec, audioCodec, crf, preset, name);
    }

    /** 视频编解码器（默认 {@code libx264}）。 */
    public HlsVariant videoCodec(String v) {
        requireNonBlank("videoCodec", v);
        return with(height, width, videoBitrate, maxrate, bufsize, audioBitrate, v, audioCodec, crf, preset, name);
    }

    /** per-variant 音频编解码器（默认 {@code aac}；agroup 下不生效）。 */
    public HlsVariant audioCodec(String v) {
        requireNonBlank("audioCodec", v);
        return with(height, width, videoBitrate, maxrate, bufsize, audioBitrate, videoCodec, v, crf, preset, name);
    }

    /** 恒定质量因子（{@code -crf}）；默认 {@code null}（不下发）。 */
    public HlsVariant crf(int v) {
        return with(height, width, videoBitrate, maxrate, bufsize, audioBitrate, videoCodec, audioCodec, v, preset, name);
    }

    /** 编码预设（{@code -preset}，如 {@code fast}）；默认 {@code null}（不下发）。 */
    public HlsVariant preset(String v) {
        requireNonBlank("preset", v);
        return with(height, width, videoBitrate, maxrate, bufsize, audioBitrate, videoCodec, audioCodec, crf, v, name);
    }

    /**
     * 变体名（默认 {@code null} → 目录回退数字索引 {@code 0/1/2}）。给值即作 {@code var_stream_map} 的
     * {@code name:} 与变体目录名。<b>不得</b>含 {@code %v} 或 {@code var_stream_map} 结构元字符
     * （空格/逗号/冒号/路径分隔符 {@code /}、{@code \}）。
     */
    public HlsVariant name(String v) {
        requireLegalName(v);
        return with(height, width, videoBitrate, maxrate, bufsize, audioBitrate, videoCodec, audioCodec, crf, preset, v);
    }

    // ===== 访问器 =====

    public int height() {
        return height;
    }

    /** 显式宽度；未设为 {@code null}。 */
    public Integer width() {
        return width;
    }

    public String videoBitrate() {
        return videoBitrate;
    }

    /** 显式 {@code maxrate}；未设为 {@code null}（用 {@link #effectiveMaxrate()} 取派生值）。 */
    public String maxrate() {
        return maxrate;
    }

    /** 显式 {@code bufsize}；未设为 {@code null}（用 {@link #effectiveBufsize()} 取派生值）。 */
    public String bufsize() {
        return bufsize;
    }

    public String audioBitrate() {
        return audioBitrate;
    }

    public String videoCodec() {
        return videoCodec;
    }

    public String audioCodec() {
        return audioCodec;
    }

    /** {@code -crf} 值；未设为 {@code null}。 */
    public Integer crf() {
        return crf;
    }

    /** {@code -preset} 值；未设为 {@code null}。 */
    public String preset() {
        return preset;
    }

    /** 变体名；未设为 {@code null}（目录回退数字索引）。 */
    public String name() {
        return name;
    }

    /** {@code -maxrate} 生效值：显式优先，否则由 {@code videoBitrate} 派生（≈1.07×，向上取整到整 kbps）。 */
    public String effectiveMaxrate() {
        return maxrate != null ? maxrate : deriveBitrate(videoBitrate, 1.07);
    }

    /** {@code -bufsize} 生效值：显式优先，否则由 {@code videoBitrate} 派生（≈1.5×）。 */
    public String effectiveBufsize() {
        return bufsize != null ? bufsize : deriveBitrate(videoBitrate, 1.5);
    }

    // ===== 校验与派生 =====

    private static void requirePositiveHeight(int height) {
        if (height <= 0) {
            throw new IllegalArgumentException("height 须为正数，实际 " + height);
        }
    }

    private static void requireBitrate(String field, String v) {
        if (v == null || !BITRATE.matcher(v).matches()) {
            throw new IllegalArgumentException(field + " 非法（须形如 5000k/5M/800000），实际 " + v);
        }
    }

    private static void requireNonBlank(String field, String v) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    private static void requireLegalName(String v) {
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("name 不能为空（省略请传 null 走数字索引，但 wither 需具体值）");
        }
        if (v.contains("%v")) {
            throw new IllegalArgumentException("name 不得含 %v（8.0.1 实测不展开，会让各档写进同一字面目录互相覆盖）：" + v);
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == ' ' || c == ',' || c == ':' || c == '/' || c == '\\') {
                throw new IllegalArgumentException(
                        "name 不得含 var_stream_map 结构元字符（空格/逗号/冒号/路径分隔符）：" + v);
            }
        }
    }

    /**
     * 由基准码率派生一个成比例码率并渲染为整 kbps 字符串（如 {@code 5000k}×1.07→{@code 5350k}）。数值部分统一折算到
     * kbps 后按 {@code factor} 缩放、四舍五入。纯数字（无单位）视为 bit/s（÷1000 得 kbps）。
     */
    private static String deriveBitrate(String bitrate, double factor) {
        double kbps = toKbps(bitrate);
        long scaled = Math.round(kbps * factor);
        return scaled + "k";
    }

    /** 把 bitrate 字符串折算到 kbps（k=×1，M=×1000，G=×1e6，无单位=÷1000）。 */
    private static double toKbps(String bitrate) {
        char last = bitrate.charAt(bitrate.length() - 1);
        double num;
        double factor;
        if (Character.isDigit(last)) {
            num = Double.parseDouble(bitrate);
            factor = 1.0 / 1000.0; // bit/s → kbps
        } else {
            num = Double.parseDouble(bitrate.substring(0, bitrate.length() - 1));
            factor = switch (Character.toLowerCase(last)) {
                case 'k' -> 1.0;
                case 'm' -> 1000.0;
                case 'g' -> 1_000_000.0;
                default -> throw new IllegalArgumentException("未知码率单位：" + last);
            };
        }
        return num * factor;
    }
}
