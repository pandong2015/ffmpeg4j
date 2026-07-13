package io.github.pandong2015.ffmpeg4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.pandong2015.ffmpeg4j.compiler.CompiledCommand;
import io.github.pandong2015.ffmpeg4j.compiler.GraphCompiler;

/**
 * L3「流即值」模型单元测试（任务 4.7）：不可变性、类型收窄、扇出值语义、字幕路径转义。
 */
class StreamModelTest {

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
    void applyingFiltersDoesNotMutateOriginalStream() {
        Input in = Input.of("in.mp4");
        VideoStream orig = in.video();
        Origin originBefore = orig.origin();
        MediaType typeBefore = orig.mediaType();

        // 连续对同一 Stream 应用多个滤镜
        VideoStream scaled = Filters.scale(orig, 1280, 720);
        VideoStream cropped = Filters.crop(orig, 640, 360);

        assertNotSame(orig, scaled, "滤镜应返回新 Stream");
        assertNotSame(orig, cropped);
        assertNotSame(scaled, cropped);
        // 原 Stream 的 origin/mediaType 前后同一实例、未变
        assertSame(originBefore, orig.origin(), "原 Stream 的 origin 不应改变");
        assertEquals(typeBefore, orig.mediaType());
        assertEquals(MediaType.VIDEO, orig.mediaType());
    }

    @Test
    void sameStreamConsumedTwiceInsertsSplitAndSharesSubchainOnce() {
        Input in = Input.of("in.mp4");
        VideoStream scaled = Filters.scale(in.video(), 640, 360);
        // 同一 scaled 被两条链消费 -> 值语义 -> 自动 split=2，共享子链只现一次
        VideoStream a = Filters.fps(scaled, 30);
        VideoStream b = Filters.format(scaled, "yuv420p");
        CompiledCommand cmd = compiler.compile(List.of(
                Output.to("a.mp4", a), Output.to("b.mp4", b)));
        String fc = cmd.filterComplex();

        assertTrue(fc.contains("split=2"), fc);
        assertEquals(1, count(fc, "scale=w=640:h=360"), fc);
    }

    @Test
    void audioFanOutInsertsAsplit() {
        Input in = Input.of("in.mp3");
        AudioStream base = Filters.volume(in.audio(), "2.0");
        AudioStream a = Filters.atempo(base, 1.5);
        AudioStream b = Filters.afade(base, "in", 0, 3);
        CompiledCommand cmd = compiler.compile(List.of(
                Output.to("a.m4a", a), Output.to("b.m4a", b)));
        String fc = cmd.filterComplex();

        assertTrue(fc.contains("asplit=2"), fc);
        assertEquals(1, count(fc, "volume=volume=2.0"), fc);
    }

    @Test
    void burnSubtitlesEscapesWindowsDriveColonSpacesAndCjk() {
        Input in = Input.of("in.mp4");
        // 含 Windows 盘符冒号 + 空格 + 中文的路径
        VideoStream out = Filters.burnSubtitles(in.video(), Path.of("C:/字 幕/movie.srt"));
        String fc = compiler.compile(Output.to("out.mp4", out)).filterComplex();

        // 冒号被转义为 \: ；空格与中文原样保留
        assertTrue(fc.contains("subtitles=filename=C\\:/字 幕/movie.srt"), fc);
    }

    @Test
    void rawFilterProductsCarryCorrectMediaType() {
        Input in = Input.of("in.mp4");
        // 类型收窄的 curated 视频滤镜签名只接受 VideoStream（由 javac 编译期保证，见下方注释）；
        // 运行期在此验证 rawFilter 逃生舱产物的 mediaType 正确。
        VideoStream v = Filters.rawFilterVideo(in.video(), "hflip");
        AudioStream a = Filters.rawFilterAudio(in.audio(), "aecho");

        assertEquals(MediaType.VIDEO, v.mediaType());
        assertEquals(MediaType.AUDIO, a.mediaType());

        // 编译期类型收窄示例（如取消注释将无法通过 javac）：
        //   Filters.scale(in.audio(), 1280, 720);   // 错配：scale 只接受 VideoStream
        //   Filters.volume(in.video(), "2.0");        // 错配：volume 只接受 AudioStream
    }

    @Test
    void trimAutoAppendsSetptsAndAtempoDecomposes() {
        Input in = Input.of("in.mp4");
        VideoStream vt = Filters.trim(in.video(), 5, 12);
        String vfc = compiler.compile(Output.to("v.mp4", vt)).filterComplex();
        assertTrue(vfc.contains("trim=start=5:end=12"), vfc);
        assertTrue(vfc.contains("setpts=PTS-STARTPTS"), vfc);

        AudioStream at = Filters.atempo(in.audio(), 4.0);
        String afc = compiler.compile(Output.to("a.m4a", at)).filterComplex();
        // 4.0 -> atempo=2.0,atempo=2.0
        assertEquals(2, count(afc, "atempo=tempo=2"), afc);
    }
}
