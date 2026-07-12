package io.github.pandong2015.ffmpeg4j.model;

/** 音频流。音频滤镜仅接受此类型，在 javac 编译期拦截视频/字幕流的错配。 */
public record AudioStream(Origin origin) implements Stream {
    @Override
    public MediaType mediaType() {
        return MediaType.AUDIO;
    }
}
