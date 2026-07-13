package io.github.pandong2015.ffmpeg4j.model;

/**
 * 字幕流。curated 滤镜下字幕流不进 filtergraph（软字幕走 {@code -map}+{@code -c:s}，硬字幕以文件为源）；
 * 若经 {@code rawFilter} 让字幕流进 filtergraph 且被扇出，编译器会报编译期错误（无字幕版 split）。
 */
public record SubtitleStream(Origin origin) implements Stream {
    @Override
    public MediaType mediaType() {
        return MediaType.SUBTITLE;
    }
}
