package io.github.pandong2015.ffmpeg4j.env;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析后的 ffmpeg 版本号，语义化为 {@code major.minor.patch}，并可与最低建议版本比较。
 *
 * <p>能容忍多种发行版变体，例如：
 * <ul>
 *   <li>{@code ffmpeg version 8.0.1 Copyright ...}</li>
 *   <li>{@code ffmpeg version 4.4.2-0ubuntu0.22.04.1 ...}（截取前导 {@code 4.4.2}）</li>
 *   <li>{@code ffmpeg version n4.4 ...}（去掉前缀 {@code n}）</li>
 *   <li>裸版本串 {@code 3.4} / {@code 8.0.1}（用于单元测试）</li>
 *   <li>git 快照 {@code N-109407-g8e4e762a2c ...}（无法解析数字 → {@link #isKnown()} 为 {@code false}）</li>
 * </ul>
 *
 * <p><b>关键语义：</b>版本低于 {@link #MIN_FFMPEG_VERSION} 只应触发一次可诊断的 WARNING 并继续，
 * <em>绝不</em>据此硬失败（真实功能下限约 2.3）。二进制缺失才是硬错误，那由 {@link FfmpegBinaries} 负责。
 */
public record FfmpegVersion(int major, int minor, int patch, boolean known, String raw)
        implements Comparable<FfmpegVersion> {

    /** 建议的最低 ffmpeg 版本；低于此仅告警不失败。 */
    public static final String MIN_FFMPEG_VERSION = "4.2";

    /** {@link #MIN_FFMPEG_VERSION} 的语义化形式，供比较使用。 */
    public static final FfmpegVersion MINIMUM = new FfmpegVersion(4, 2, 0, true, MIN_FFMPEG_VERSION);

    // 允许可选前缀 n/N，随后为 major[.minor[.patch]]；在 token 起始处匹配，遇到非数字（如 '-'）即停止。
    private static final Pattern VERSION =
            Pattern.compile("[nN]?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    /**
     * 解析 {@code ffmpeg -version} 的输出，或单行、甚至裸版本串。
     *
     * @param versionOutput 版本输出（可为完整多行、单行或裸版本串）
     * @return 解析结果；无法解析出数字版本时返回 {@link #isKnown()} 为 {@code false} 的实例（不抛异常）
     */
    public static FfmpegVersion parse(String versionOutput) {
        if (versionOutput == null || versionOutput.isBlank()) {
            return unknown("");
        }
        String token = extractVersionToken(firstNonBlankLine(versionOutput));
        Matcher m = VERSION.matcher(token);
        if (!m.lookingAt()) {
            return unknown(token);
        }
        int major = Integer.parseInt(m.group(1));
        int minor = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
        int patch = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
        return new FfmpegVersion(major, minor, patch, true, token);
    }

    /** 便捷工厂：由三段数字构造一个「已知」版本。 */
    public static FfmpegVersion of(int major, int minor, int patch) {
        return new FfmpegVersion(major, minor, patch, true, major + "." + minor + "." + patch);
    }

    private static FfmpegVersion unknown(String raw) {
        return new FfmpegVersion(-1, -1, -1, false, raw == null ? "" : raw);
    }

    private static String firstNonBlankLine(String text) {
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return "";
    }

    /** 从一行中提取版本 token：定位关键字 {@code version} 之后的首个空白分隔片段。 */
    private static String extractVersionToken(String line) {
        String s = line.trim();
        int idx = s.toLowerCase(java.util.Locale.ROOT).indexOf("version");
        if (idx >= 0) {
            s = s.substring(idx + "version".length()).trim();
        }
        int sp = indexOfWhitespace(s);
        return sp >= 0 ? s.substring(0, sp) : s;
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 是否成功解析出数字版本；git 快照等无数字版本返回 {@code false}。 */
    public boolean isKnown() {
        return known;
    }

    /**
     * 是否严格低于 {@link #MIN_FFMPEG_VERSION}。
     *
     * <p>未知版本（如 git 快照）一律视为「不低于」，因为它们通常是 bleeding-edge，不应触发告警。
     */
    public boolean isBelowMinimum() {
        return known && compareTo(MINIMUM) < 0;
    }

    /** 是否不低于给定版本。 */
    public boolean isAtLeast(FfmpegVersion other) {
        return compareTo(other) >= 0;
    }

    @Override
    public int compareTo(FfmpegVersion other) {
        int c = Integer.compare(major, other.major);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minor, other.minor);
        if (c != 0) {
            return c;
        }
        return Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return known ? major + "." + minor + "." + patch : "unknown(" + raw + ")";
    }
}
