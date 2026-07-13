package io.github.pandong2015.ffmpeg4j.model;

/**
 * 视频汇聚归一化目标：分辨率、帧率、像素格式。不含 SAR 字段——归一化链恒把 SAR 归到 1
 * （见 {@link Normalization#normalizeVideo}），故无需外部指定。
 *
 * @param width       目标宽（像素）
 * @param height      目标高（像素）
 * @param fps         目标帧率；整数值渲染为整数（如 {@code 30} 而非 {@code 30.0}）
 * @param pixelFormat 目标像素格式（如 {@code yuv420p}）
 */
public record VideoNormTarget(int width, int height, double fps, String pixelFormat) {
}
