package io.github.pandong2015.ffmpeg4j.facade;

/**
 * {@link ThumbnailOptions} 的 seek 模式：决定 {@code -ss} 置于输入侧还是输出侧。
 */
public enum SeekMode {

    /**
     * 输入侧关键帧快 seek（{@code -ss} 置于 {@code -i} 之前）：快，但落到最近关键帧、截图时间点可能偏移。
     * 为默认，保持 v1.0 的历史行为。
     */
    INPUT_FAST,

    /**
     * 输出侧精确 seek（{@code -ss} 置于 {@code -i} 之后）：解码到目标时刻再取帧，时间点精确，
     * 与「输出侧 seek」逐字节对齐。大文件较慢（需从头解码）。
     */
    OUTPUT_ACCURATE
}
