package io.github.pandong2015.ffmpeg4j.probe;

import io.github.pandong2015.ffmpeg4j.model.MediaType;
import io.github.pandong2015.ffmpeg4j.probe.json.JsonValue;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 ffprobe 的 JSON（{@code -show_format -show_streams}）映射为结构化 {@link ProbeResult}。
 *
 * <p>纯函数、无副作用、不触碰进程，因此可被单测直接以样本 JSON 驱动。
 */
final class ProbeMapper {

    private ProbeMapper() {
    }

    static ProbeResult map(JsonValue root) {
        return new ProbeResult(mapFormat(root.opt("format")), mapStreams(root.opt("streams")));
    }

    private static FormatInfo mapFormat(JsonValue format) {
        return new FormatInfo(
                format.opt("format_name").asString(null),
                format.opt("format_long_name").asString(null),
                format.opt("duration").asDouble(0.0),
                format.opt("bit_rate").asLong(-1L),
                format.opt("size").asLong(-1L),
                format.opt("nb_streams").asInt(0));
    }

    private static List<StreamInfo> mapStreams(JsonValue streams) {
        List<StreamInfo> result = new ArrayList<>();
        if (!streams.isArray()) {
            return result;
        }
        for (JsonValue s : streams.asArray()) {
            MediaType type = mediaType(s.opt("codec_type").asString(null));
            if (type == null) {
                // 跳过 data/attachment 等非 v/a/s 流。
                continue;
            }
            result.add(new StreamInfo(
                    s.opt("index").asInt(-1),
                    type,
                    s.opt("codec_name").asString(null),
                    s.opt("codec_long_name").asString(null),
                    optInt(s, "width"),
                    optInt(s, "height"),
                    optString(s, "avg_frame_rate"),
                    optString(s, "r_frame_rate"),
                    optInt(s, "sample_rate"),
                    optInt(s, "channels")));
        }
        return result;
    }

    private static MediaType mediaType(String codecType) {
        if (codecType == null) {
            return null;
        }
        return switch (codecType) {
            case "video" -> MediaType.VIDEO;
            case "audio" -> MediaType.AUDIO;
            case "subtitle" -> MediaType.SUBTITLE;
            default -> null;
        };
    }

    /** 存在且非 null 才返回 Integer，否则 {@code null}（宽松解析字符串数字）。 */
    private static Integer optInt(JsonValue obj, String key) {
        JsonValue v = obj.opt(key);
        if (v.isNull()) {
            return null;
        }
        try {
            return v.asInt();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String optString(JsonValue obj, String key) {
        JsonValue v = obj.opt(key);
        return v.isNull() ? null : v.asString();
    }
}
