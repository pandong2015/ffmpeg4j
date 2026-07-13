package io.github.pandong2015.ffmpeg4j.probe;

import io.github.pandong2015.ffmpeg4j.model.MediaType;

import java.util.List;
import java.util.Optional;

/**
 * 一次媒体探测的完整结果：容器信息 + 各媒体流信息。
 *
 * @param format  容器层信息
 * @param streams 各流信息（按 ffprobe 输出顺序，通常即流序号顺序）
 */
public record ProbeResult(FormatInfo format, List<StreamInfo> streams) {

    public ProbeResult {
        streams = streams == null ? List.of() : List.copyOf(streams);
    }

    /** 便捷：容器时长（秒）。 */
    public double durationSeconds() {
        return format.durationSeconds();
    }

    /** 指定类型的全部流。 */
    public List<StreamInfo> streams(MediaType type) {
        return streams.stream().filter(s -> s.type() == type).toList();
    }

    public List<StreamInfo> videoStreams() {
        return streams(MediaType.VIDEO);
    }

    public List<StreamInfo> audioStreams() {
        return streams(MediaType.AUDIO);
    }

    public List<StreamInfo> subtitleStreams() {
        return streams(MediaType.SUBTITLE);
    }

    public Optional<StreamInfo> firstVideo() {
        return videoStreams().stream().findFirst();
    }

    public Optional<StreamInfo> firstAudio() {
        return audioStreams().stream().findFirst();
    }
}
