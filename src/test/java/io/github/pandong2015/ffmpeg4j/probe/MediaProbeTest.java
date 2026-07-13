package io.github.pandong2015.ffmpeg4j.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.pandong2015.ffmpeg4j.FfmpegException;
import io.github.pandong2015.ffmpeg4j.model.MediaType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaProbeTest {

    // 一段贴近真实 ffprobe -show_format -show_streams 的样本，含视频+音频+字幕三种流。
    private static final String SAMPLE_JSON = """
            {
              "streams": [
                {
                  "index": 0,
                  "codec_name": "h264",
                  "codec_long_name": "H.264 / AVC / MPEG-4 AVC",
                  "codec_type": "video",
                  "width": 320,
                  "height": 240,
                  "r_frame_rate": "10/1",
                  "avg_frame_rate": "10/1",
                  "duration": "1.000000",
                  "bit_rate": "80000"
                },
                {
                  "index": 1,
                  "codec_name": "aac",
                  "codec_long_name": "AAC (Advanced Audio Coding)",
                  "codec_type": "audio",
                  "sample_rate": "44100",
                  "channels": 2,
                  "channel_layout": "stereo",
                  "duration": "1.000000",
                  "bit_rate": "128000"
                },
                {
                  "index": 2,
                  "codec_name": "subrip",
                  "codec_long_name": "SubRip subtitle",
                  "codec_type": "subtitle"
                },
                {
                  "index": 3,
                  "codec_name": "bin_data",
                  "codec_type": "data"
                }
              ],
              "format": {
                "filename": "sample.mp4",
                "nb_streams": 4,
                "format_name": "mov,mp4,m4a,3gp,3g2,mj2",
                "format_long_name": "QuickTime / MOV",
                "duration": "1.000000",
                "size": "45678",
                "bit_rate": "365424"
              }
            }
            """;

    @Test
    void mapsFormatLevelMetadata() {
        ProbeResult r = MediaProbe.fromJson(SAMPLE_JSON);
        FormatInfo f = r.format();
        assertEquals("mov,mp4,m4a,3gp,3g2,mj2", f.formatName());
        assertEquals("QuickTime / MOV", f.formatLongName());
        assertEquals(1.0, f.durationSeconds(), 1e-9);
        assertEquals(365424L, f.bitRate());
        assertEquals(45678L, f.size());
        assertEquals(4, f.nbStreams());
        assertEquals(1.0, r.durationSeconds(), 1e-9);
    }

    @Test
    void mapsAllThreeStreamTypesAndSkipsDataStream() {
        ProbeResult r = MediaProbe.fromJson(SAMPLE_JSON);
        // data 流被跳过，只保留 v/a/s 三条
        assertEquals(3, r.streams().size());
        assertEquals(1, r.videoStreams().size());
        assertEquals(1, r.audioStreams().size());
        assertEquals(1, r.subtitleStreams().size());
    }

    @Test
    void mapsVideoStreamFields() {
        StreamInfo v = MediaProbe.fromJson(SAMPLE_JSON).firstVideo().orElseThrow();
        assertEquals(0, v.index());
        assertEquals(MediaType.VIDEO, v.type());
        assertTrue(v.isVideo());
        assertEquals("h264", v.codecName());
        assertEquals(320, v.width());
        assertEquals(240, v.height());
        assertEquals("10/1", v.avgFrameRate());
        assertEquals(10.0, v.avgFrameRateFps(), 1e-9);
        assertEquals(10.0, v.rFrameRateFps(), 1e-9);
        // 视频流没有音频字段
        assertNull(v.sampleRate());
        assertNull(v.channels());
    }

    @Test
    void mapsAudioStreamFields() {
        StreamInfo a = MediaProbe.fromJson(SAMPLE_JSON).firstAudio().orElseThrow();
        assertEquals(1, a.index());
        assertEquals(MediaType.AUDIO, a.type());
        assertEquals("aac", a.codecName());
        assertEquals(44100, a.sampleRate());
        assertEquals(2, a.channels());
        // 音频流没有视频字段
        assertNull(a.width());
        assertNull(a.height());
    }

    @Test
    void mapsSubtitleStreamFields() {
        StreamInfo s = MediaProbe.fromJson(SAMPLE_JSON).subtitleStreams().get(0);
        assertEquals(2, s.index());
        assertEquals(MediaType.SUBTITLE, s.type());
        assertTrue(s.isSubtitle());
        assertEquals("subrip", s.codecName());
        assertNull(s.width());
        assertNull(s.sampleRate());
    }

    // ---------------------------------------------------------------------
    // 集成测试：需要 PATH 上有 ffmpeg / ffprobe，缺失则跳过而非失败。
    // ---------------------------------------------------------------------

    @Test
    void probesRealGeneratedFile(@TempDir Path tmp) throws Exception {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg 不可用，跳过集成测试");
        assumeTrue(commandExists("ffprobe"), "ffprobe 不可用，跳过集成测试");

        Path out = tmp.resolve("testsrc.mp4");
        generateTestVideo(out);
        assertTrue(Files.exists(out) && Files.size(out) > 0, "测试视频未生成");

        ProbeResult r = MediaProbe.probe(out);

        assertTrue(r.durationSeconds() > 0.5, "时长应约为 1s，实际 " + r.durationSeconds());
        StreamInfo video = r.firstVideo().orElseThrow(() -> new AssertionError("应有视频流"));
        assertEquals(MediaType.VIDEO, video.type());
        assertEquals(320, video.width());
        assertEquals(240, video.height());
        assertNotNull(video.codecName());
    }

    @Test
    void nonexistentPathThrowsDiagnosableError() {
        assumeTrue(commandExists("ffprobe"), "ffprobe 不可用，跳过集成测试");

        Path missing = Path.of("/nonexistent/definitely-not-here-4732.mp4");
        FfmpegException ex = assertThrows(FfmpegException.class, () -> MediaProbe.probe(missing));

        // 应携带 ffprobe 的失败诊断信息：非零退出码 + stderr 尾部 + 可读原因
        assertFalse(ex.exitCode() == 0, "退出码应为非零");
        assertFalse(ex.stderrTail().isBlank(), "stderr 尾部应非空: " + ex.stderrTail());
        assertNotNull(ex.reason(), "应解析出可读原因");
        assertFalse(ex.command().isEmpty(), "应记录执行的命令");
    }

    // --- helpers ---------------------------------------------------------

    private static void generateTestVideo(Path out) throws IOException, InterruptedException {
        List<String> cmd = List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi",
                "-i", "testsrc=duration=1:size=320x240:rate=10",
                out.toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        int code = p.waitFor();
        assumeTrue(code == 0, "ffmpeg 生成测试文件失败，退出码 " + code);
    }

    private static boolean commandExists(String command) {
        try {
            Process p = new ProcessBuilder(command, "-version")
                    .redirectErrorStream(true)
                    .start();
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
