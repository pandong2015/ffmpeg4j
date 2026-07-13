package io.github.pandong2015.ffmpeg4j.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.pandong2015.ffmpeg4j.model.AudioStream;
import io.github.pandong2015.ffmpeg4j.model.Filters;
import io.github.pandong2015.ffmpeg4j.model.Input;
import io.github.pandong2015.ffmpeg4j.model.Output;
import io.github.pandong2015.ffmpeg4j.model.VideoStream;

class GraphCompilerTest {

    private final GraphCompiler compiler = new GraphCompiler("ffmpeg");

    private static int count(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    @Test
    void directChainProducesFilterComplexAndMap() {
        Input in = Input.of("in.mp4");
        VideoStream scaled = Filters.scale(in.video(), 1280, 720);
        CompiledCommand cmd = compiler.compile(Output.to("out.mp4", scaled));

        assertNotNull(cmd.filterComplex());
        assertTrue(cmd.filterComplex().contains("scale=w=1280:h=720"), cmd.filterComplex());
        // 有一路 -filter_complex 输出被 -map
        assertTrue(cmd.argv().contains("-filter_complex"));
        assertTrue(cmd.argv().contains("-i"));
        assertTrue(cmd.render().contains("-map"));
    }

    @Test
    void passthroughHasNoFilterComplex() {
        Input in = Input.of("in.mkv");
        // 直接映射输入的视频与音频，不接任何滤镜
        CompiledCommand cmd = compiler.compile(Output.to("out.mp4", in.video(), in.audio()));

        assertNull(cmd.filterComplex(), "无滤镜时不应有 filter_complex");
        assertTrue(cmd.argv().contains("0:v:0"), cmd.render());
        assertTrue(cmd.argv().contains("0:a:0"), cmd.render());
        assertFalse(cmd.argv().contains("-filter_complex"));
    }

    @Test
    void fanOutInsertsSplit() {
        Input in = Input.of("in.mp4");
        VideoStream scaled = Filters.scale(in.video(), 640, 360);
        // 同一 scaled 被两条链消费 -> 值语义 -> 自动 split=2
        VideoStream a = Filters.fps(scaled, 30);
        VideoStream b = Filters.format(scaled, "yuv420p");
        CompiledCommand cmd = compiler.compile(List.of(
                Output.to("a.mp4", a), Output.to("b.mp4", b)));

        String fc = cmd.filterComplex();
        assertTrue(fc.contains("split=2"), fc);
        // 去重：scale 子链只出现一次
        assertEquals(1, count(fc, "scale=w=640:h=360"), fc);
    }

    @Test
    void overlayUsesTwoInputs() {
        Input main = Input.of("main.mp4");
        Input logo = Input.of("logo.png");
        VideoStream out = Filters.overlay(main.video(), logo.video(), "(W-w)/2", "(H-h)/2");
        CompiledCommand cmd = compiler.compile(Output.to("out.mp4", out));

        assertEquals(2, count(cmd.render(), "-i "), cmd.render());
        assertTrue(cmd.filterComplex().contains("overlay=x=(W-w)/2:y=(H-h)/2"), cmd.filterComplex());
    }

    @Test
    void trimAutoAppendsSetpts() {
        Input in = Input.of("in.mp4");
        VideoStream out = Filters.trim(in.video(), 5, 12);
        String fc = compiler.compile(Output.to("out.mp4", out)).filterComplex();

        assertTrue(fc.contains("trim=start=5:end=12"), fc);
        assertTrue(fc.contains("setpts=PTS-STARTPTS"), fc);
    }

    @Test
    void atempoDecomposesAboveRange() {
        Input in = Input.of("in.mp3");
        AudioStream out = Filters.atempo(in.audio(), 4.0);
        String fc = compiler.compile(Output.to("out.mp3", out)).filterComplex();

        // 4.0 -> atempo=2.0,atempo=2.0（链）
        assertEquals(2, count(fc, "atempo=tempo=2"), fc);
    }

    @Test
    void drawTextEscapesColon() {
        Input in = Input.of("in.mp4");
        VideoStream out = Filters.drawText(in.video(), "12:30", null, 48, "white", "10", "10");
        String fc = compiler.compile(Output.to("out.mp4", out)).filterComplex();

        assertTrue(fc.contains("text=12\\:30"), fc);
    }

    @Test
    void burnSubtitlesEscapesPathColon() {
        Input in = Input.of("in.mp4");
        VideoStream out = Filters.burnSubtitles(in.video(), Path.of("C:/subs.srt"));
        String fc = compiler.compile(Output.to("out.mp4", out)).filterComplex();

        assertTrue(fc.contains("subtitles=filename=C\\:/subs.srt"), fc);
    }

    @Test
    void danglingConcatAudioRejected() {
        Input a = Input.of("a.mp4");
        Input b = Input.of("b.mp4");
        Filters.ConcatResult r = Filters.concat(List.of(
                new Filters.Segment(a.video(), a.audio()),
                new Filters.Segment(b.video(), b.audio())));
        // 只映射视频输出，音频输出悬空 -> 编译期报错
        assertThrows(GraphCompileException.class,
                () -> compiler.compile(Output.to("out.mp4", r.video())));
    }

    @Test
    void concatBothOutputsMapped() {
        Input a = Input.of("a.mp4");
        Input b = Input.of("b.mp4");
        Filters.ConcatResult r = Filters.concat(List.of(
                new Filters.Segment(a.video(), a.audio()),
                new Filters.Segment(b.video(), b.audio())));
        CompiledCommand cmd = compiler.compile(Output.to("out.mp4", r.video(), r.audio()));

        assertTrue(cmd.filterComplex().contains("concat=n=2:v=1:a=1"), cmd.filterComplex());
        assertEquals(2, count(cmd.render(), "-i "), cmd.render());
    }
}
