package io.github.pandong2015.ffmpeg4j.task;

/**
 * 非致命任务警告的稳定代码。
 *
 * <p>调用方应按代码分支，不应解析可能调整的中文说明。
 */
public enum WarningCode {
    /** 进度通道不可用，媒体任务继续但不再提供进度。 */
    PROGRESS_UNAVAILABLE,
    /** ffmpeg 版本低于建议最低版本。 */
    VERSION_BELOW_MINIMUM,
    /** 可选媒体流不存在，任务按可选语义继续。 */
    OPTIONAL_STREAM_MISSING,
    /** 目标容器无法容纳字幕流，已将其丢弃。 */
    SUBTITLE_DROPPED,
    /** 默认 ABR 码率梯按源分辨率裁剪。 */
    ABR_LADDER_TRIMMED
}
