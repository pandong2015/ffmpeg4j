package io.github.pandong2015.ffmpeg4j.model;

/**
 * 类型化的软字幕编码设置，映射到 ffmpeg 的 {@code -c:s <name>}。
 *
 * <p>软字幕流经 {@link Output#to} 直接 {@code -map}（编译器把输入字幕流渲染为 {@code 0:s:0}），
 * 再由 {@link Output#subtitleCodec} 附加编码：
 * <ul>
 *   <li>透传/复用（mux）：映射字幕流 + {@link #COPY}</li>
 *   <li>抽取：{@code Output.to("x.srt", input.subtitle()).subtitleCodec(SRT)}</li>
 *   <li>转换（srt↔vtt↔ass）：{@code subtitleCodec(WEBVTT)} 等</li>
 * </ul>
 * {@link #MOV_TEXT} 为 MP4/MOV 容器所需的字幕编码。
 */
public enum SubtitleCodec {

    /** 原样复制，不转码。 */
    COPY("copy"),
    /** SubRip（.srt）。 */
    SRT("srt"),
    /** WebVTT（.vtt）。 */
    WEBVTT("webvtt"),
    /** Advanced SubStation Alpha（.ass）。 */
    ASS("ass"),
    /** MP4/MOV 内嵌字幕（3GPP Timed Text）。 */
    MOV_TEXT("mov_text");

    private final String ffmpegName;

    SubtitleCodec(String ffmpegName) {
        this.ffmpegName = ffmpegName;
    }

    /** ffmpeg 侧的编码名（用于 {@code -c:s <name>}）。 */
    public String ffmpegName() {
        return ffmpegName;
    }
}
