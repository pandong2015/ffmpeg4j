package io.github.pandong2015.ffmpeg4j.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.github.pandong2015.ffmpeg4j.probe.ProbeResult;
import io.github.pandong2015.ffmpeg4j.probe.StreamInfo;

/**
 * 关键路径的真机端到端补充：此前这三条仅有离线 argv 断言（{@link FacadeCommandBuildTest}），
 * 缺真实 ffmpeg 跑通的验证。均以 {@code -f lavfi} 现场生成素材，缺 ffmpeg/ffprobe 或缺相应
 * 构建开关（libmp3lame / mov_text 编码器）时以 {@link org.junit.jupiter.api.Assumptions#assumeTrue}
 * 跳过而非失败。
 *
 * <ol>
 *   <li>concat 异构注入：一段含音+视 + 一段仅视频（无音轨），验证 resolveSegmentAudio 的静音注入
 *       （anullsrc + atrim 限时）后输出时长≈两段之和、且音视流俱在。</li>
 *   <li>remux 字幕分派：带 subrip 字幕的 mkv → mp4，验证文本字幕被转为 mov_text。</li>
 *   <li>extractAudio 重编码：抽音频到 .mp3，验证走 libmp3lame 分支、输出仅含音频且时长匹配。</li>
 * </ol>
 */
class FfmpegFacadeKeyPathE2ETest {

    // ===== ① concat 异构注入（缺音段静音注入）=====

    @Test
    void concat异构一段无音轨时注入静音并保留音视双流(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File av = generateAV(tmp.resolve("av.mp4"), 2);            // 含音+视
        File videoOnly = generateVideoOnly(tmp.resolve("vo.mp4"), 2); // 仅视频，无音轨
        File out = tmp.resolve("cat_hetero.mp4").toFile();

        RunResult r = Ffmpeg.concat(List.of(av, videoOnly), out);
        assertEquals(0, r.exitCode());
        assertNonEmpty(out);

        ProbeResult p = Ffmpeg.probe(out);
        assertFalse(p.videoStreams().isEmpty(), "异构拼接输出应有视频流");
        assertFalse(p.audioStreams().isEmpty(), "缺音段注入静音后输出应有音频流");
        double dur = p.durationSeconds();
        assertTrue(Math.abs(dur - 4.0) < 0.8, "拼接时长应约为 4s（两段之和），实际 " + dur);
    }

    // ===== ② remux 字幕分派（subrip → mov_text）=====

    @Test
    void remux把mkv文本字幕转为mp4的movText(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File srt = writeSrt(tmp.resolve("sub.srt"));
        File mkv = generateMkvWithSubtitle(tmp.resolve("with_sub.mkv"), 2, srt);

        // 若素材生成后 mkv 里没有可识别的文本字幕流，说明本机不支持该路径，跳过。
        ProbeResult srcProbe = Ffmpeg.probe(mkv);
        assumeTrue(!srcProbe.subtitleStreams().isEmpty()
                        && isTextSubtitle(srcProbe.subtitleStreams().get(0).codecName()),
                "生成的 mkv 无文本字幕流，跳过 remux 字幕分派测试");

        File out = tmp.resolve("out.mp4").toFile();
        RunResult r = Ffmpeg.remux(mkv, out);
        assertEquals(0, r.exitCode());
        assertNonEmpty(out);

        ProbeResult p = Ffmpeg.probe(out);
        assertFalse(p.subtitleStreams().isEmpty(), "remux 到 mp4 后应保留文本字幕流");
        assertEquals("mov_text", p.subtitleStreams().get(0).codecName(),
                "mp4 目标下文本字幕应被转为 mov_text");
    }

    // ===== ③ extractAudio 重编码到 mp3（libmp3lame 分支）=====

    @Test
    void 抽音频到mp3走libmp3lame且时长匹配(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        assumeTrue(encoderAvailable("libmp3lame"), "libmp3lame 不可用，跳过抽 mp3 测试");
        File src = generateAV(tmp.resolve("src.mp4"), 2);
        File out = tmp.resolve("out.mp3").toFile();

        RunResult r = Ffmpeg.extractAudio(src, out);
        assertEquals(0, r.exitCode());
        assertNonEmpty(out);

        ProbeResult p = Ffmpeg.probe(out);
        assertFalse(p.audioStreams().isEmpty(), "抽音频输出应有音频流");
        assertTrue(p.videoStreams().isEmpty(), "抽 mp3 输出不应含视频流");
        assertEquals("mp3", p.audioStreams().get(0).codecName(), "mp3 输出的音频编解码器应为 mp3");
        double dur = p.durationSeconds();
        assertTrue(Math.abs(dur - 2.0) < 0.6, "抽音频时长应约为 2s，实际 " + dur);
    }

    // ===== helpers =====

    private static void assumeFfmpeg() {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg 不可用，跳过集成测试");
        assumeTrue(commandExists("ffprobe"), "ffprobe 不可用，跳过集成测试");
    }

    /** 用 lavfi 生成 {@code duration}s、320x240、10fps 视频 + 440Hz 正弦音轨的素材。 */
    private static File generateAV(Path out, int durationSec) throws IOException, InterruptedException {
        List<String> cmd = List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=" + durationSec + ":size=320x240:rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=" + durationSec,
                "-shortest", out.toString());
        runFfmpeg(cmd);
        return out.toFile();
    }

    /** 用 lavfi 生成仅含视频、无音轨的素材（触发 concat 异构的缺音注入路径）。 */
    private static File generateVideoOnly(Path out, int durationSec) throws IOException, InterruptedException {
        List<String> cmd = List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=" + durationSec + ":size=320x240:rate=10",
                out.toString());
        runFfmpeg(cmd);
        return out.toFile();
    }

    /** 生成一个带 subrip 字幕流的 mkv（视频 + 音频 + 由 .srt 复用为软字幕）。 */
    private static File generateMkvWithSubtitle(Path out, int durationSec, File srt)
            throws IOException, InterruptedException {
        List<String> cmd = List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=" + durationSec + ":size=320x240:rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=" + durationSec,
                "-i", srt.toString(),
                "-map", "0:v", "-map", "1:a", "-map", "2:s",
                "-c:v", "libx264", "-c:a", "aac", "-c:s", "srt",
                out.toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        int code = p.waitFor();
        // 生成失败（如无 libx264）时跳过而非失败——该素材是测试前置条件。
        assumeTrue(code == 0, "生成带字幕 mkv 失败（可能缺 libx264/srt 支持），退出码 " + code);
        return out.toFile();
    }

    private static void runFfmpeg(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        int code = p.waitFor();
        assumeTrue(code == 0, "ffmpeg 生成素材失败，退出码 " + code);
    }

    private static File writeSrt(Path path) throws IOException {
        Files.writeString(path, "1\n00:00:00,000 --> 00:00:02,000\nHello ffmpeg4j\n");
        return path.toFile();
    }

    private static boolean isTextSubtitle(String codec) {
        if (codec == null) {
            return false;
        }
        String c = codec.toLowerCase();
        return c.equals("subrip") || c.equals("srt") || c.equals("ass") || c.equals("webvtt")
                || c.equals("mov_text") || c.equals("text");
    }

    /** 探测某编码器是否可用（{@code ffmpeg -encoders} 输出含该名）。 */
    private static boolean encoderAvailable(String encoder) {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-encoders")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            for (String line : out.split("\\R")) {
                String[] tokens = line.trim().split("\\s+");
                if (tokens.length >= 2 && tokens[1].equals(encoder)) {
                    return true;
                }
            }
            return false;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
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

    // 保留 StreamInfo 导入的显式使用点，避免未使用告警并锁定 probe 访问器签名。
    @SuppressWarnings("unused")
    private static String describe(StreamInfo s) {
        return s.type() + ":" + s.codecName();
    }
}
