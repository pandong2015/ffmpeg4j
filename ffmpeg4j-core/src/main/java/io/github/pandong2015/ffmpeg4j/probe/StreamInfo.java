package io.github.pandong2015.ffmpeg4j.probe;

import io.github.pandong2015.ffmpeg4j.model.MediaType;

/**
 * 单条媒体流的探测结果，对应 ffprobe {@code -show_streams} 的一个数组元素。
 *
 * <p>仅在语义适用时填充相应字段：视频流有 {@code width/height/帧率/pixelFormat/level/hasBFrames}，
 * 音频流有 {@code sampleRate/channels/sampleFormat/channelLayout}；不适用的对象型字段为 {@code null}。
 * 数字型缺失以哨兵填充（秒值 {@code 0.0}、{@code bitRate}/{@code nbFrames} 为 {@code -1}）。字段类型与
 * 专属性以真实 ffprobe JSON 锚定（{@code has_b_frames}/{@code level} 为裸整数，{@code bit_rate}/
 * {@code nb_frames}/{@code start_time}/{@code duration} 为带引号字符串，映射层宽松解析）。
 *
 * @param index             流序号（0 起）
 * @param type              流类型（复用 {@link MediaType}）
 * @param codecName         编解码器短名，如 {@code "h264"}、{@code "aac"}、{@code "subrip"}
 * @param codecLongName     编解码器长名；缺失为 {@code null}
 * @param width             视频宽（像素）；非视频或缺失为 {@code null}
 * @param height            视频高（像素）；非视频或缺失为 {@code null}
 * @param avgFrameRate      平均帧率有理数字符串，如 {@code "10/1"}；缺失为 {@code null}
 * @param rFrameRate        基准帧率有理数字符串，如 {@code "10/1"}；缺失为 {@code null}
 * @param sampleRate        音频采样率（Hz）；非音频或缺失为 {@code null}
 * @param channels          音频声道数；非音频或缺失为 {@code null}
 * @param profile           编码 profile，如 {@code "High"}/{@code "LC"}；缺失为 {@code null}
 * @param codecTag          {@code codec_tag_string}，如 {@code "avc1"}；缺失为 {@code null}
 * @param hasBFrames        {@code has_b_frames}（B 帧延迟数）；非视频或缺失为 {@code null}
 * @param pixelFormat       {@code pix_fmt}，如 {@code "yuv420p"}；非视频或缺失为 {@code null}
 * @param level             编码 level；非视频或缺失为 {@code null}
 * @param timeBase          {@code time_base}，如 {@code "1/12800"}；缺失为 {@code null}
 * @param startTimeSeconds  流起始时间（秒）；缺失为 {@code 0.0}
 * @param durationSeconds   流时长（秒）；缺失为 {@code 0.0}
 * @param bitRate           流码率（bit/s）；缺失为 {@code -1}
 * @param nbFrames          帧数（{@code nb_frames}）；缺失为 {@code -1}
 * @param sampleFormat      {@code sample_fmt}，如 {@code "fltp"}；非音频或缺失为 {@code null}
 * @param channelLayout     {@code channel_layout}，如 {@code "stereo"}；非音频或缺失为 {@code null}
 * @param sampleAspectRatio {@code sample_aspect_ratio}（SAR），如 {@code "1:1"}；缺失为 {@code null}
 * @param displayAspectRatio {@code display_aspect_ratio}（DAR），如 {@code "4:3"}；缺失为 {@code null}
 * @param attachedPic       是否为封面图流（{@code disposition.attached_pic == 1}）；缺失为 {@code false}
 * @param language          {@code tags.language}，如 {@code "und"}/{@code "eng"}；缺失为 {@code null}
 * @param codecTagHex       原始 {@code codec_tag} 十六进制串，如 {@code "0x31637661"}（与 {@code codecTag}=
 *                          {@code codec_tag_string} 并列，供须逐位复刻的下游）；缺失为 {@code null}
 * @param rawStartTime      {@code start_time} 的<em>原始定点串</em>，如 {@code "0.000000"}（byte-exact 保留精度与
 *                          尾零；缺失为 {@code null}，据此可区分「真实 0」与「缺失」——异于 {@code startTimeSeconds} 的 {@code 0.0} 哨兵）
 * @param rawDuration       {@code duration} 的<em>原始定点串</em>；byte-exact；缺失为 {@code null}
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
        Integer channels,
        String profile,
        String codecTag,
        Integer hasBFrames,
        String pixelFormat,
        Integer level,
        String timeBase,
        double startTimeSeconds,
        double durationSeconds,
        long bitRate,
        long nbFrames,
        String sampleFormat,
        String channelLayout,
        String sampleAspectRatio,
        String displayAspectRatio,
        boolean attachedPic,
        String language,
        String codecTagHex,
        String rawStartTime,
        String rawDuration) {

    /**
     * 便捷构造器：保留 v1.0 的 10 参签名（index/type/codec/分辨率/帧率/采样率/声道），新字段取缺失默认
     * （对象型 {@code null}、秒值 {@code 0.0}、{@code bitRate}/{@code nbFrames} 为 {@code -1}、{@code attachedPic}
     * 为 {@code false}，原始保真串 {@code codecTagHex}/{@code rawStartTime}/{@code rawDuration} 为 {@code null}）。
     * 使既有直接构造点（含测试）无需改动即可编译，扩字段保持源码兼容。
     */
    public StreamInfo(int index, MediaType type, String codecName, String codecLongName,
                      Integer width, Integer height, String avgFrameRate, String rFrameRate,
                      Integer sampleRate, Integer channels) {
        this(index, type, codecName, codecLongName, width, height, avgFrameRate, rFrameRate,
                sampleRate, channels,
                null, null, null, null, null, null, 0.0, 0.0, -1L, -1L, null, null, null, null, false, null,
                null, null, null);
    }

    /**
     * 便捷构造器：保留「原始保真字段扩充前」的 26 参签名，新增的 {@code codecTagHex}/{@code rawStartTime}/
     * {@code rawDuration} 取 {@code null}。使既有满参构造点（含测试）无需改动即可编译。
     */
    public StreamInfo(int index, MediaType type, String codecName, String codecLongName,
                      Integer width, Integer height, String avgFrameRate, String rFrameRate,
                      Integer sampleRate, Integer channels, String profile, String codecTag,
                      Integer hasBFrames, String pixelFormat, Integer level, String timeBase,
                      double startTimeSeconds, double durationSeconds, long bitRate, long nbFrames,
                      String sampleFormat, String channelLayout, String sampleAspectRatio,
                      String displayAspectRatio, boolean attachedPic, String language) {
        this(index, type, codecName, codecLongName, width, height, avgFrameRate, rFrameRate,
                sampleRate, channels, profile, codecTag, hasBFrames, pixelFormat, level, timeBase,
                startTimeSeconds, durationSeconds, bitRate, nbFrames, sampleFormat, channelLayout,
                sampleAspectRatio, displayAspectRatio, attachedPic, language, null, null, null);
    }

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
