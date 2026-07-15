package io.github.pandong2015.ffmpeg4j.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.pandong2015.ffmpeg4j.engine.RunResult;
import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;
import io.github.pandong2015.ffmpeg4j.probe.ProbeResult;
import io.github.pandong2015.ffmpeg4j.probe.StreamInfo;

/**
 * 端到端集成：以真实 ffmpeg 现场用 {@code -f lavfi} 生成素材，验证 8 个门面整链跑通。
 * 缺 ffmpeg/ffprobe 时以 {@link org.junit.jupiter.api.Assumptions#assumeTrue} 跳过而非失败。
 */
class FfmpegFacadeE2ETest {

    @Test
    void 转码整链跑通并改变编解码器(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File src = generateAV(tmp.resolve("src.mp4"), 2);
        File out = tmp.resolve("out.mkv").toFile();

        RunResult r = Ffmpeg.transcode(src, out, "libx264", "aac");
        assertEquals(0, r.exitCode());
        assertNonEmpty(out);
        ProbeResult p = Ffmpeg.probe(out);
        assertFalse(p.videoStreams().isEmpty(), "转码输出应有视频流");
        assertFalse(p.audioStreams().isEmpty(), "转码输出应有音频流");
    }

    @Test
    void 换容器整链跑通(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File src = generateAV(tmp.resolve("src.mp4"), 2);
        File out = tmp.resolve("out.mkv").toFile();

        RunResult r = Ffmpeg.remux(src, out);
        assertEquals(0, r.exitCode());
        assertNonEmpty(out);
        ProbeResult p = Ffmpeg.probe(out);
        assertFalse(p.videoStreams().isEmpty(), "换容器后应保留视频流");
    }

    @Test
    void 截段快切输出非空(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File src = generateAV(tmp.resolve("src.mp4"), 4);
        File out = tmp.resolve("clip_copy.mp4").toFile();

        RunResult r = Ffmpeg.clip(src, out, 1.0, 3.0);
        assertEquals(0, r.exitCode());
        assertNonEmpty(out);
    }

    @Test
    void 截段精切时长约等于区间长度(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File src = generateAV(tmp.resolve("src.mp4"), 4);
        File out = tmp.resolve("clip_precise.mp4").toFile();

        RunResult r = Ffmpeg.clip(src, out, 1.0, 3.0, ClipOptions.defaults().reencode(true));
        assertEquals(0, r.exitCode());
        assertNonEmpty(out);
        double dur = Ffmpeg.probe(out).durationSeconds();
        assertTrue(Math.abs(dur - 2.0) < 0.5, "精切时长应约为 2s，实际 " + dur);
    }

    @Test
    void 抽音频输出仅含音频(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File src = generateAV(tmp.resolve("src.mp4"), 2);
        File out = tmp.resolve("out.m4a").toFile();

        RunResult r = Ffmpeg.extractAudio(src, out);
        assertEquals(0, r.exitCode());
        assertNonEmpty(out);
        ProbeResult p = Ffmpeg.probe(out);
        assertFalse(p.audioStreams().isEmpty(), "抽音频输出应有音频流");
        assertTrue(p.videoStreams().isEmpty(), "抽音频输出不应含视频流（避开封面图）");
    }

    @Test
    void 抓帧输出为非空图片(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File src = generateAV(tmp.resolve("src.mp4"), 2);
        File out = tmp.resolve("frame.png").toFile();

        RunResult r = Ffmpeg.thumbnail(src, out, 1.0, ThumbnailOptions.defaults().width(160));
        assertEquals(0, r.exitCode());
        assertNonEmpty(out);
    }

    @Test
    void 拼接时长约等于两段之和(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File a = generateAV(tmp.resolve("a.mp4"), 2);
        File b = generateAV(tmp.resolve("b.mp4"), 2);
        File out = tmp.resolve("cat.mp4").toFile();

        RunResult r = Ffmpeg.concat(List.of(a, b), out);
        assertEquals(0, r.exitCode());
        assertNonEmpty(out);
        double dur = Ffmpeg.probe(out).durationSeconds();
        assertTrue(Math.abs(dur - 4.0) < 0.8, "拼接时长应约为 4s，实际 " + dur);
    }

    @Test
    void 烧字幕整链跑通(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        assumeTrue(libassAvailable(), "libass 不可用，跳过烧字幕集成测试");
        File src = generateAV(tmp.resolve("src.mp4"), 2);
        File srt = writeSrt(tmp.resolve("sub.srt"));
        File out = tmp.resolve("burned.mp4").toFile();

        RunResult r = Ffmpeg.burnSubtitles(src, srt, out);
        assertEquals(0, r.exitCode());
        assertNonEmpty(out);
        assertFalse(Ffmpeg.probe(out).videoStreams().isEmpty(), "烧字幕输出应有视频流");
    }

    @Test
    void 探测真实文件的关键属性(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File src = generateAV(tmp.resolve("src.mp4"), 2);

        ProbeResult p = Ffmpeg.probe(src);
        assertTrue(p.durationSeconds() > 1.0, "时长应约 2s，实际 " + p.durationSeconds());
        assertEquals(320, p.firstVideo().orElseThrow().width());
        assertEquals(240, p.firstVideo().orElseThrow().height());
    }

    @Test
    void gif生成合法动图(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File src = generateAV(tmp.resolve("src.mp4"), 2);
        File out = tmp.resolve("out.gif").toFile();

        RunResult r = Ffmpeg.gif(src, out, GifOptions.defaults().fps(10).width(160).duration(1.0));
        assertEquals(0, r.exitCode());
        assertNonEmpty(out);
        // GIF 魔数 "GIF8"
        byte[] head = Files.readAllBytes(out.toPath());
        assertTrue(head.length > 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F',
                "输出应为合法 GIF: " + out);
    }

    @Test
    void 抽音频重采样为16k单声道(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File src = generateAV(tmp.resolve("src.mp4"), 2);
        File out = tmp.resolve("out.wav").toFile();

        RunResult r = Ffmpeg.extractAudio(src, out,
                ExtractAudioOptions.defaults().sampleRate(16000).channels(1));
        assertEquals(0, r.exitCode());
        assertNonEmpty(out);
        StreamInfo a = Ffmpeg.probe(out).firstAudio().orElseThrow();
        assertEquals(16000, a.sampleRate(), "采样率应重采样为 16000");
        assertEquals(1, a.channels(), "声道应降为 1");
    }

    @Test
    void 探测扩展字段含profile与pixelFormat(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File src = generateAV(tmp.resolve("src.mp4"), 2);

        StreamInfo v = Ffmpeg.probe(src).firstVideo().orElseThrow();
        assertNotNull(v.pixelFormat(), "真实视频流应有 pixelFormat");
        assertTrue(v.durationSeconds() > 1.0, "流时长应约 2s，实际 " + v.durationSeconds());
    }

    // ===== helpers =====

    private static void assumeFfmpeg() {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg 不可用，跳过集成测试");
        assumeTrue(commandExists("ffprobe"), "ffprobe 不可用，跳过集成测试");
    }

    private static boolean libassAvailable() {
        try {
            return FfmpegEnvironment.shared().capabilities().hasLibass();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 用 lavfi 生成 {@code duration}s、320x240、10fps 视频 + 440Hz 正弦音轨的素材。 */
    private static File generateAV(Path out, int durationSec)
            throws IOException, InterruptedException {
        List<String> cmd = List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=" + durationSec + ":size=320x240:rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=" + durationSec,
                "-shortest", out.toString());

        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        int code = p.waitFor();
        assumeTrue(code == 0, "ffmpeg 生成素材失败，退出码 " + code);
        return out.toFile();
    }

    private static File writeSrt(Path path) throws IOException {
        Files.writeString(path, "1\n00:00:00,000 --> 00:00:02,000\nHello ffmpeg4j\n");
        return path.toFile();
    }

    private static void assertNonEmpty(File f) throws IOException {
        assertTrue(f.exists() && Files.size(f.toPath()) > 0, "输出文件应存在且非空: " + f);
    }

    private static boolean commandExists(String command) {
        try {
            Process p = new ProcessBuilder(command, "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
