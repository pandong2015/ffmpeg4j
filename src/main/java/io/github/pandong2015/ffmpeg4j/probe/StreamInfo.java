package io.github.pandong2015.ffmpeg4j.probe;

import io.github.pandong2015.ffmpeg4j.model.MediaType;

/**
 * 单条媒体流的探测结果，对应 ffprobe {@code -show_streams} 的一个数组元素。
 *
 * <p>仅在语义适用时填充相应字段：视频流有 {@code width/height/帧率}，音频流有
 * {@code sampleRate/channels}；不适用的字段为 {@code null}。
 *
 * @param index          流序号（0 起）
 * @param type           流类型（复用 {@link MediaType}）
 * @param codecName      编解码器短名，如 {@code "h264"}、{@code "aac"}、{@code "subrip"}
 * @param codecLongName  编解码器长名；缺失为 {@code null}
 * @param width          视频宽（像素）；非视频或缺失为 {@code null}
 * @param height         视频高（像素）；非视频或缺失为 {@code null}
 * @param avgFrameRate   平均帧率有理数字符串，如 {@code "10/1"}；缺失为 {@code null}
 * @param rFrameRate     基准帧率有理数字符串，如 {@code "10/1"}；缺失为 {@code null}
 * @param sampleRate     音频采样率（Hz）；非音频或缺失为 {@code null}
 * @param channels       音频声道数；非音频或缺失为 {@code null}
 */
public record StreamInfo(
        int index,
        MediaType type,
        String codecName,
        String codecLongName,
        Integer width,
        Integer height,
        String avgFrameRate,
        String rFrameRate,
        Integer sampleRate,
        Integer channels) {

    public boolean isVideo() {
        return type == MediaType.VIDEO;
    }

    public boolean isAudio() {
        return type == MediaType.AUDIO;
    }

    public boolean isSubtitle() {
        return type == MediaType.SUBTITLE;
    }

    /** 平均帧率的浮点值（解析 {@code num/den}）；无法解析时返回 {@code 0}。 */
    public double avgFrameRateFps() {
        return parseRational(avgFrameRate);
    }

    /** 基准帧率的浮点值（解析 {@code num/den}）；无法解析时返回 {@code 0}。 */
    public double rFrameRateFps() {
        return parseRational(rFrameRate);
    }

    private static double parseRational(String r) {
        if (r == null || r.isBlank()) {
            return 0.0;
        }
        int slash = r.indexOf('/');
        try {
            if (slash < 0) {
                return Double.parseDouble(r.trim());
            }
            double num = Double.parseDouble(r.substring(0, slash).trim());
            double den = Double.parseDouble(r.substring(slash + 1).trim());
            return den == 0.0 ? 0.0 : num / den;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
