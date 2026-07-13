package io.github.pandong2015.ffmpeg4j.engine;

/**
 * 取消模式。
 *
 * <ul>
 *   <li>{@link #GRACEFUL}：优雅取消——先向 stdin 写 {@code q} 让 ffmpeg flush/finalize 输出
 *       （避免损坏 mp4 的 moov），等待后仍存活再依次升级到 SIGTERM、SIGKILL。stdin 被输入媒体
 *       占用而无法写 {@code q} 时自动降级为 SIGTERM。</li>
 *   <li>{@link #FORCE}：强制取消——跳过写 {@code q} 与优雅收尾，直接 {@code destroy()}/
 *       {@code destroyForcibly()}；输出可能未 finalize。</li>
 * </ul>
 */
public enum CancelMode {
    /** 优雅取消（写 q → SIGTERM → SIGKILL）。 */
    GRACEFUL,
    /** 强制取消（直接 SIGTERM → SIGKILL，跳过优雅收尾）。 */
    FORCE
}
