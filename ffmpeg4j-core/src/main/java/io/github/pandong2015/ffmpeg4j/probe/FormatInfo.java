package io.github.pandong2015.ffmpeg4j.probe;

/**
 * 容器（format）层面的探测结果，对应 ffprobe {@code -show_format} 输出。
 *
 * @param formatName        短格式名，如 {@code "mov,mp4,m4a,3gp,3g2,mj2"}；缺失为 {@code null}
 * @param formatLongName    长格式名，如 {@code "QuickTime / MOV"}；缺失为 {@code null}
 * @param durationSeconds   时长（秒）；未知为 {@code 0}
 * @param bitRate           总码率（bit/s）；未知为 {@code -1}
 * @param size              文件字节数；未知为 {@code -1}
 * @param nbStreams         流数量；未知为 {@code 0}
 * @param nbPrograms        节目数（{@code nb_programs}）；未知为 {@code 0}
 * @param startTimeSeconds  容器起始时间（秒，{@code start_time}）；未知为 {@code 0}
 */
public record FormatInfo(
        String formatName,
        String formatLongName,
        double durationSeconds,
        long bitRate,
        long size,
        int nbStreams,
        int nbPrograms,
        double startTimeSeconds) {

    /**
     * 便捷构造器：保留 v1.0 的 6 参签名，新字段 {@code nbPrograms}/{@code startTimeSeconds} 取缺失默认
     * （{@code 0}）。使既有直接构造点（含测试）无需改动即可编译。
     */
    public FormatInfo(String formatName, String formatLongName, double durationSeconds,
                      long bitRate, long size, int nbStreams) {
        this(formatName, formatLongName, durationSeconds, bitRate, size, nbStreams, 0, 0.0);
    }
}
