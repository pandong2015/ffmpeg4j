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
 * @param rawStartTime      {@code start_time} 的<em>原始定点串</em>（byte-exact；缺失为 {@code null}，据此区分「真实 0」与「缺失」）
 * @param rawDuration       {@code duration} 的<em>原始定点串</em>（byte-exact；缺失为 {@code null}）
 */
public record FormatInfo(
        String formatName,
        String formatLongName,
        double durationSeconds,
        long bitRate,
        long size,
        int nbStreams,
        int nbPrograms,
        double startTimeSeconds,
        String rawStartTime,
        String rawDuration) {

    /**
     * 便捷构造器：保留 v1.0 的 6 参签名，新字段 {@code nbPrograms}/{@code startTimeSeconds} 取缺失默认
     * （{@code 0}），原始保真串 {@code rawStartTime}/{@code rawDuration} 取 {@code null}。使既有直接构造点
     * （含测试）无需改动即可编译。
     */
    public FormatInfo(String formatName, String formatLongName, double durationSeconds,
                      long bitRate, long size, int nbStreams) {
        this(formatName, formatLongName, durationSeconds, bitRate, size, nbStreams, 0, 0.0, null, null);
    }

    /**
     * 便捷构造器：保留「原始保真字段扩充前」的 8 参签名，新增的 {@code rawStartTime}/{@code rawDuration}
     * 取 {@code null}。使既有满参构造点无需改动即可编译。
     */
    public FormatInfo(String formatName, String formatLongName, double durationSeconds,
                      long bitRate, long size, int nbStreams, int nbPrograms, double startTimeSeconds) {
        this(formatName, formatLongName, durationSeconds, bitRate, size, nbStreams, nbPrograms,
                startTimeSeconds, null, null);
    }
}
