package io.github.pandong2015.ffmpeg4j.engine;

import java.util.Map;

/**
 * 一次进度块的不可变快照，直接以 {@code -progress} 输出的原始 {@code key=value} 映射为载体。
 *
 * <p>ffmpeg 的 {@code -progress} 每输出一块以 {@code progress=continue} 或 {@code progress=end}
 * 结尾，常见键：{@code frame}/{@code fps}/{@code bitrate}/{@code total_size}/{@code out_time_us}/
 * {@code out_time_ms}/{@code out_time}/{@code dup_frames}/{@code drop_frames}/{@code speed}/
 * {@code progress}。便捷访问器在字段缺失或值为 {@code N/A} 时返回合理默认，绝不抛异常。
 *
 * <p>注意：ffmpeg 的 {@code out_time_ms} 历史上单位其实是<em>微秒</em>（与 {@code out_time_us} 同），
 * 本类据实处理——{@link #outTimeMicros()} 会优先读 {@code out_time_us}、退回 {@code out_time_ms}
 * （均按微秒解释），再退回解析 {@code out_time} 文本。
 *
 * @param raw 原始键值对（构造即防御性拷贝为不可变）。
 */
public record Progress(Map<String, String> raw) {

    private static final Progress EMPTY = new Progress(Map.of());

    public Progress {
        raw = raw == null ? Map.of() : Map.copyOf(raw);
    }

    /** 空进度（尚无任何进度块时的占位，所有访问器返回默认值）。 */
    public static Progress empty() {
        return EMPTY;
    }

    /** 原始值访问；缺失返回 {@code null}。 */
    public String get(String key) {
        return raw.get(key);
    }

    /** 是否包含某键。 */
    public boolean has(String key) {
        return raw.containsKey(key);
    }

    /** 已处理帧数；缺失返回 {@code 0}。 */
    public long frame() {
        return asLong("frame", 0L);
    }

    /** 瞬时编码帧率；缺失返回 {@code 0.0}。 */
    public double fps() {
        return asDouble("fps", 0.0);
    }

    /** 已处理时间（微秒）；缺失/无法解析返回 {@code -1}。 */
    public long outTimeMicros() {
        long us = asLong("out_time_us", Long.MIN_VALUE);
        if (us != Long.MIN_VALUE) {
            return us;
        }
        // out_time_ms 在 ffmpeg 中同样以微秒计（历史命名遗留）。
        long ms = asLong("out_time_ms", Long.MIN_VALUE);
        if (ms != Long.MIN_VALUE) {
            return ms;
        }
        long parsed = parseClock(raw.get("out_time"));
        return parsed >= 0 ? parsed : -1L;
    }

    /** 已处理时间（毫秒）；缺失/无法解析返回 {@code -1}。 */
    public long outTimeMillis() {
        long us = outTimeMicros();
        return us < 0 ? -1L : us / 1000L;
    }

    /** 已写出的总字节数；缺失/{@code N/A} 返回 {@code -1}。 */
    public long totalSize() {
        return asLong("total_size", -1L);
    }

    /** 编码速度倍率（去掉尾部 {@code x}）；缺失/{@code N/A} 返回 {@code 0.0}。 */
    public double speed() {
        String v = raw.get("speed");
        if (v == null) {
            return 0.0;
        }
        v = v.trim();
        if (v.endsWith("x") || v.endsWith("X")) {
            v = v.substring(0, v.length() - 1).trim();
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** 重复帧数；缺失返回 {@code 0}。 */
    public long dupFrames() {
        return asLong("dup_frames", 0L);
    }

    /** 丢弃帧数；缺失返回 {@code 0}。 */
    public long dropFrames() {
        return asLong("drop_frames", 0L);
    }

    /** {@code progress} 键的原始状态（{@code continue}/{@code end}）；缺失返回 {@code null}。 */
    public String progressState() {
        return raw.get("progress");
    }

    /** 是否为收尾块（{@code progress=end}）。 */
    public boolean isEnd() {
        return "end".equals(raw.get("progress"));
    }

    private long asLong(String key, long def) {
        String v = raw.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private double asDouble(String key, double def) {
        String v = raw.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 解析 {@code HH:MM:SS.ffffff} 形式的时间为微秒；无法解析返回 {@code -1}。 */
    private static long parseClock(String v) {
        if (v == null) {
            return -1L;
        }
        v = v.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("N/A")) {
            return -1L;
        }
        try {
            String[] parts = v.split(":");
            if (parts.length != 3) {
                return -1L;
            }
            long hours = Long.parseLong(parts[0].trim());
            long minutes = Long.parseLong(parts[1].trim());
            double seconds = Double.parseDouble(parts[2].trim());
            double total = (hours * 3600 + minutes * 60) + seconds;
            return Math.round(total * 1_000_000d);
        } catch (RuntimeException e) {
            return -1L;
        }
    }
}
