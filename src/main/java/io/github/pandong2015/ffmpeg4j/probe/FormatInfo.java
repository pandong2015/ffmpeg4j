package io.github.pandong2015.ffmpeg4j.probe;

/**
 * 容器（format）层面的探测结果，对应 ffprobe {@code -show_format} 输出。
 *
 * @param formatName     短格式名，如 {@code "mov,mp4,m4a,3gp,3g2,mj2"}；缺失为 {@code null}
 * @param formatLongName 长格式名，如 {@code "QuickTime / MOV"}；缺失为 {@code null}
 * @param durationSeconds 时长（秒）；未知为 {@code 0}
 * @param bitRate        总码率（bit/s）；未知为 {@code -1}
 * @param size           文件字节数；未知为 {@code -1}
 * @param nbStreams      流数量；未知为 {@code 0}
 */
public record FormatInfo(
        String formatName,
        String formatLongName,
        double durationSeconds,
        long bitRate,
        long size,
        int nbStreams) {
}
