package io.github.pandong2015.ffmpeg4j.model;

/**
 * ffmpeg 流类型三态，对齐 ffmpeg 的 {@code v}/{@code a}/{@code s} 流类型。
 */
public enum MediaType {
    VIDEO,
    AUDIO,
    SUBTITLE;

    /** ffmpeg 流说明符字母：v/a/s。 */
    public String specifierLetter() {
        return switch (this) {
            case VIDEO -> "v";
            case AUDIO -> "a";
            case SUBTITLE -> "s";
        };
    }
}
