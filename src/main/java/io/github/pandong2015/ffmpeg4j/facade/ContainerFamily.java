package io.github.pandong2015.ffmpeg4j.facade;

import java.util.Set;

/**
 * 由输出文件扩展名判定的目标容器族，供 {@link FacadeSupport} 做 remux 的字幕分派与 extractAudio 的
 * 音频编解码器推导。这是纯值/纯函数工具，不触发任何进程，便于无 ffmpeg 环境断言 argv。
 */
enum ContainerFamily {

    /** MP4 / MOV 家族（mp4/m4v/mov/m4a）：文本字幕须转 {@code mov_text}，图形字幕须丢弃。 */
    MP4_MOV,
    /** Matroska / WebM（mkv/webm）：字幕基本可 {@code -c:s copy}。 */
    MATROSKA,
    /** 其他容器：字幕保守 {@code copy}。 */
    OTHER;

    /** 文本字幕编解码器短名集合（可转 {@code mov_text} 进 mp4）。 */
    private static final Set<String> TEXT_SUBTITLES = Set.of(
            "subrip", "srt", "ass", "ssa", "webvtt", "vtt", "mov_text", "text", "stl");

    /** 图形字幕编解码器短名集合（mp4 下须丢弃）。 */
    private static final Set<String> GRAPHIC_SUBTITLES = Set.of(
            "hdmv_pgs_subtitle", "pgssub", "dvd_subtitle", "dvdsub",
            "dvb_subtitle", "dvbsub", "xsub");

    /** 由文件名/路径的扩展名判定容器族。 */
    static ContainerFamily of(String pathOrName) {
        String ext = extension(pathOrName);
        return switch (ext) {
            case "mp4", "m4v", "mov", "m4a", "3gp", "3g2" -> MP4_MOV;
            case "mkv", "webm" -> MATROSKA;
            default -> OTHER;
        };
    }

    /** 该编解码器短名是否为文本字幕。 */
    static boolean isTextSubtitle(String codecName) {
        return codecName != null && TEXT_SUBTITLES.contains(codecName.toLowerCase());
    }

    /** 该编解码器短名是否为图形字幕。 */
    static boolean isGraphicSubtitle(String codecName) {
        return codecName != null && GRAPHIC_SUBTITLES.contains(codecName.toLowerCase());
    }

    /** 提取小写扩展名（不含点）；无扩展名返回空串。 */
    static String extension(String pathOrName) {
        if (pathOrName == null) {
            return "";
        }
        String name = pathOrName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase();
    }
}
