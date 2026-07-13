package io.github.pandong2015.ffmpeg4j.model;

/** 视频流。视频滤镜仅接受此类型，在 javac 编译期拦截音/字幕流的错配。 */
public record VideoStream(Origin origin) implements Stream {
    @Override
    public MediaType mediaType() {
        return MediaType.VIDEO;
    }
}
