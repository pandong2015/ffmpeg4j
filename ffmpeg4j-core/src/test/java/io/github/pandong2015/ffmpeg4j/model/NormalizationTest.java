package io.github.pandong2015.ffmpeg4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.pandong2015.ffmpeg4j.compiler.CompiledCommand;
import io.github.pandong2015.ffmpeg4j.compiler.GraphCompiler;

/**
 * 汇聚归一化（任务 3.8）与其编译产物回归（任务 3.11「含 setsar」）测试。
 */
class NormalizationTest {

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
    void videoChainHasScaleSetsarFpsFormatInOrder() {
        Input in = Input.of("in.mp4");
        VideoStream out = Normalization.normalizeVideo(
                in.video(), new VideoNormTarget(1280, 720, 30, "yuv420p"));
        String fc = compiler.compile(Output.to("out.mp4", out)).filterComplex();

        assertTrue(fc.contains("scale=w=1280:h=720"), fc);
        assertTrue(fc.contains("setsar=1"), fc);
        assertTrue(fc.contains("fps=fps=30"), fc);
        assertTrue(fc.contains("format=pix_fmts=yuv420p"), fc);
        // setsar 紧接 scale 之后（顺序：scale -> setsar -> fps -> format）
        assertTrue(fc.indexOf("scale=w=1280:h=720") < fc.indexOf("setsar=1"), fc);
        assertTrue(fc.indexOf("setsar=1") < fc.indexOf("fps=fps=30"), fc);
        assertTrue(fc.indexOf("fps=fps=30") < fc.indexOf("format=pix_fmts=yuv420p"), fc);
    }

    @Test
    void integerFpsRendersWithoutDecimal() {
        Input in = Input.of("in.mp4");
        VideoStream out = Normalization.normalizeVideo(
                in.video(), new VideoNormTarget(640, 360, 25, "yuv420p"));
        String fc = compiler.compile(Output.to("out.mp4", out)).filterComplex();
        assertTrue(fc.contains("fps=fps=25"), fc);
        assertTrue(!fc.contains("fps=fps=25.0"), fc);
    }

    @Test
    void audioChainHasAresampleThenAformat() {
        Input in = Input.of("in.mp4");
        AudioStream out = Normalization.normalizeAudio(in.audio(), AudioNormTarget.stereo48k());
        String fc = compiler.compile(Output.to("out.m4a", out)).filterComplex();

        assertTrue(fc.contains("aresample=48000"), fc);
        assertTrue(fc.contains("aformat=sample_fmts=fltp:channel_layouts=stereo"), fc);
        assertTrue(fc.indexOf("aresample=48000") < fc.indexOf("aformat="), fc);
    }

    @Test
    void stereo48kDefaults() {
        AudioNormTarget t = AudioNormTarget.stereo48k();
        assertEquals(48000, t.sampleRate());
        assertEquals("fltp", t.sampleFormat());
        assertEquals("stereo", t.channelLayout());
    }

    @Test
    void normalizeSegmentsDoesNotMutateInputAndReturnsNewSegments() {
        Input a = Input.of("a.mp4");
        Filters.Segment seg = new Filters.Segment(a.video(), a.audio());
        List<Filters.Segment> in = List.of(seg);
        List<Filters.Segment> out = Normalization.normalizeSegments(
                in, new VideoNormTarget(1280, 720, 30, "yuv420p"), AudioNormTarget.stereo48k());

        assertEquals(1, out.size());
        assertNotSame(seg, out.get(0), "应返回新 Segment");
        // 原 Segment 的视频仍是原始输入流（未被就地替换）
        assertSame(seg.video(), in.get(0).video());
    }

    @Test
    void normalizeSegmentsThenConcatContainsAllStages() {
        Input a = Input.of("a.mp4");
        Input b = Input.of("b.mp4");
        List<Filters.Segment> segs = Normalization.normalizeSegments(
                List.of(new Filters.Segment(a.video(), a.audio()),
                        new Filters.Segment(b.video(), b.audio())),
                new VideoNormTarget(1280, 720, 30, "yuv420p"),
                AudioNormTarget.stereo48k());
        Filters.ConcatResult r = Filters.concat(segs);
        CompiledCommand cmd = compiler.compile(Output.to("out.mp4", r.video(), r.audio()));
        String fc = cmd.filterComplex();

        assertTrue(fc.contains("scale=w=1280:h=720"), fc);
        assertTrue(fc.contains("setsar=1"), fc);
        assertTrue(fc.contains("fps=fps=30"), fc);
        assertTrue(fc.contains("format=pix_fmts=yuv420p"), fc);
        assertTrue(fc.contains("aresample=48000"), fc);
        assertTrue(fc.contains("aformat=sample_fmts=fltp:channel_layouts=stereo"), fc);
        assertTrue(fc.contains("concat=n=2:v=1:a=1"), fc);
        // 两段各一次 setsar/aresample（归一化对每段生效）
        assertEquals(2, count(fc, "setsar=1"), fc);
        assertEquals(2, count(fc, "aresample=48000"), fc);
    }

    @Test
    void silenceAndBlankAreZeroInputSourcesConsumableByConcat() {
        Input a = Input.of("a.mp4");
        VideoNormTarget vt = new VideoNormTarget(1280, 720, 30, "yuv420p");
        AudioNormTarget at = AudioNormTarget.stereo48k();
        // seg0：真实视频 + 静音占位；seg1：纯色占位 + 真实音频
        Filters.ConcatResult r = Filters.concat(List.of(
                new Filters.Segment(Normalization.normalizeVideo(a.video(), vt), Normalization.silence(at)),
                new Filters.Segment(Normalization.blank(vt, "black"), Normalization.normalizeAudio(a.audio(), at))));
        CompiledCommand cmd = compiler.compile(Output.to("out.mp4", r.video(), r.audio()));
        String fc = cmd.filterComplex();

        // 零输入 source 滤镜被编入 filter_complex 且可被 concat 消费
        assertTrue(fc.contains("anullsrc=r=48000:cl=stereo"), fc);
        assertTrue(fc.contains("color=c=black:s=1280x720:r=30"), fc);
        assertTrue(fc.contains("concat=n=2:v=1:a=1"), fc);
    }

    @Test
    void silenceAndBlankAreZeroInputNodes() {
        AudioStream s = Normalization.silence(AudioNormTarget.stereo48k());
        VideoStream b = Normalization.blank(new VideoNormTarget(640, 360, 24, "yuv420p"), "white");
        Origin.FilterOrigin so = (Origin.FilterOrigin) s.origin();
        Origin.FilterOrigin bo = (Origin.FilterOrigin) b.origin();
        assertTrue(so.node().inputs().isEmpty(), "anullsrc 应为零输入 source");
        assertTrue(bo.node().inputs().isEmpty(), "color 应为零输入 source");
        assertEquals(MediaType.AUDIO, s.mediaType());
        assertEquals(MediaType.VIDEO, b.mediaType());
    }
}
