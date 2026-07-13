package io.github.pandong2015.ffmpeg4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.pandong2015.ffmpeg4j.compiler.CompiledCommand;
import io.github.pandong2015.ffmpeg4j.compiler.GraphCompiler;

/**
 * 软字幕流操作（任务 4.5）：mux/透传/抽取 + srt↔vtt↔ass 转换，走 {@code -map} + {@code -c:s}。
 */
class SubtitleCodecTest {

    private final GraphCompiler compiler = new GraphCompiler("ffmpeg");

    @Test
    void ffmpegNames() {
        assertEquals("copy", SubtitleCodec.COPY.ffmpegName());
        assertEquals("srt", SubtitleCodec.SRT.ffmpegName());
        assertEquals("webvtt", SubtitleCodec.WEBVTT.ffmpegName());
        assertEquals("ass", SubtitleCodec.ASS.ffmpegName());
        assertEquals("mov_text", SubtitleCodec.MOV_TEXT.ffmpegName());
    }

    @Test
    void muxSubtitleCopyMapsStreamAndSetsCodec() {
        Input in = Input.of("in.mkv");
        Output o = Output.to("out.mkv", in.video(), in.audio(), in.subtitle())
                .subtitleCodec(SubtitleCodec.COPY);
        CompiledCommand cmd = compiler.compile(o);

        assertTrue(cmd.argv().contains("0:s:0"), cmd.render());
        assertTrue(cmd.render().contains("-c:s copy"), cmd.render());
        // 软字幕不进 filtergraph
        assertNull(cmd.filterComplex(), cmd.render());
    }

    @Test
    void extractSubtitleToSrt() {
        Input in = Input.of("in.mkv");
        Output o = Output.to("subs.srt", in.subtitle()).subtitleCodec(SubtitleCodec.SRT);
        CompiledCommand cmd = compiler.compile(o);

        assertTrue(cmd.argv().contains("0:s:0"), cmd.render());
        assertTrue(cmd.render().contains("-c:s srt"), cmd.render());
    }

    @Test
    void convertSubtitleToWebVtt() {
        Input in = Input.of("in.mkv");
        Output o = Output.to("subs.vtt", in.subtitle()).subtitleCodec(SubtitleCodec.WEBVTT);
        CompiledCommand cmd = compiler.compile(o);

        assertTrue(cmd.argv().contains("0:s:0"), cmd.render());
        assertTrue(cmd.render().contains("-c:s webvtt"), cmd.render());
    }

    @Test
    void movTextForMp4Container() {
        Input in = Input.of("in.mkv");
        Output o = Output.to("out.mp4", in.video(), in.audio(), in.subtitle())
                .subtitleCodec(SubtitleCodec.MOV_TEXT);
        CompiledCommand cmd = compiler.compile(o);

        assertTrue(cmd.argv().contains("0:s:0"), cmd.render());
        assertTrue(cmd.render().contains("-c:s mov_text"), cmd.render());
    }

    @Test
    void subtitleCodecAppearsOutputSideBeforePath() {
        Input in = Input.of("in.mkv");
        Output o = Output.to("out.mp4", in.subtitle()).subtitleCodec(SubtitleCodec.MOV_TEXT);
        CompiledCommand cmd = compiler.compile(o);

        int cs = cmd.argv().indexOf("-c:s");
        int path = cmd.argv().indexOf("out.mp4");
        assertTrue(cs >= 0 && path >= 0 && cs < path, cmd.render());
    }

    @Test
    void subtitleCodecIsImmutableAndRejectsNull() {
        Input in = Input.of("in.mkv");
        Output base = Output.to("out.mp4", in.subtitle());
        Output derived = base.subtitleCodec(SubtitleCodec.SRT);

        // 不可变：原 Output 不被修改
        assertTrue(base.outputArgs().isEmpty(), "原 Output 的 outputArgs 应保持为空");
        assertEquals(2, derived.outputArgs().size());
        assertThrows(NullPointerException.class, () -> base.subtitleCodec(null));
    }
}
