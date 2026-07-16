package io.github.pandong2015.ffmpeg4j.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * HLS ABR 多码率梯 VOD 的端到端集成（真实 ffmpeg 8.0.1，缺则 {@code assumeTrue} 跳过）。钉死：默认梯 probe 裁剪后
 * 产 master + 各档目录/段、段 URI=basename（无 base_url）、跨档 {@code #EXTINF} 对齐、agroup 单音频 rendition、
 * AES 单密钥每档 {@code #EXT-X-KEY}（段被加密、master 无 KEY）。
 */
class HlsAbrE2ETest {

    @Test
    void 默认梯按源高度裁剪产master与各档段(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File in = generateAV(tmp.resolve("in.mp4"), 1280, 720, 8);
        File outDir = tmp.resolve("out").toFile();

        HlsAbrResult r = Ffmpeg.hlsAbr(in, outDir);

        assertTrue(Files.exists(tmp.resolve("out/master.m3u8")), "master 应存在");
        assertEquals(tmp.resolve("out/master.m3u8"), r.master());
        assertEquals(3, r.variants().size(), "720 源默认梯裁剪后 3 档（720/480/360）: " + r.variants());

        String master = Files.readString(r.master());
        assertEquals(3, countOccur(master, "#EXT-X-STREAM-INF"), "master 含 3 行 STREAM-INF: " + master);
        assertEquals(3, countOccur(master, "RESOLUTION="), "每档带 RESOLUTION: " + master);

        for (HlsVariantResult v : r.variants()) {
            assertTrue(Files.exists(v.playlist()), "各档 index.m3u8 应存在: " + v.playlist());
            assertFalse(v.segments().isEmpty(), "各档段清单非空: " + v.name());
            assertTrue(v.height() > 0 && v.width() > 0, "各档 RESOLUTION 解析出正值: " + v);
            for (Path seg : v.segments()) {
                assertTrue(Files.exists(seg), "段文件应实际存在: " + seg);
            }
        }
    }

    @Test
    void 段URI为basename且共位无baseUrl(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File in = generateAV(tmp.resolve("in.mp4"), 1280, 720, 8);
        File outDir = tmp.resolve("out").toFile();

        HlsAbrResult r = Ffmpeg.hlsAbr(in, outDir);

        for (HlsVariantResult v : r.variants()) {
            String m3u8 = Files.readString(v.playlist());
            for (String uri : FacadeSupport.parseSegmentUris(m3u8)) {
                assertFalse(uri.contains("/"), "段 URI 应为 basename（无 base_url 前缀）: " + uri);
                Path seg = v.playlist().getParent().resolve(uri);
                assertTrue(Files.exists(seg), "basename 段应共位于该档目录: " + seg);
            }
        }
    }

    @Test
    void 各视频档EXTINF序列逐档一致_关键帧对齐(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File in = generateAV(tmp.resolve("in.mp4"), 1280, 720, 12);
        File outDir = tmp.resolve("out").toFile();

        HlsAbrResult r = Ffmpeg.hlsAbr(in, outDir);

        List<Double> ref = extinfDurations(Files.readString(r.variants().get(0).playlist()));
        assertTrue(ref.size() >= 2, "应有多段以验证对齐: " + ref);
        for (HlsVariantResult v : r.variants()) {
            List<Double> got = extinfDurations(Files.readString(v.playlist()));
            assertEquals(ref, got, "各视频档 EXTINF 序列应逐档一致（跨档关键帧对齐）: " + v.name());
        }
    }

    @Test
    void agroup单音频rendition并master含EXTXMEDIA(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File in = generateAV(tmp.resolve("in.mp4"), 1280, 720, 8);
        File outDir = tmp.resolve("out").toFile();

        HlsAbrResult r = Ffmpeg.hlsAbr(in, outDir);

        assertNotNull(r.audioRendition(), "agroup 默认应单列音频 rendition");
        assertTrue(Files.exists(r.audioRendition().playlist()), "音频 rendition playlist 存在");
        assertFalse(r.audioRendition().segments().isEmpty(), "音频段非空");
        String master = Files.readString(r.master());
        assertTrue(master.contains("#EXT-X-MEDIA:TYPE=AUDIO"), "master 含音频 MEDIA 行: " + master);
        assertTrue(master.contains("AUDIO=\""), "STREAM-INF 挂 AUDIO 组: " + master);
    }

    @Test
    void N为1的显式单档仍产master(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File in = generateAV(tmp.resolve("in.mp4"), 640, 360, 6);
        File outDir = tmp.resolve("out").toFile();

        HlsAbrResult r = Ffmpeg.hlsAbr(in, outDir,
                HlsAbrOptions.defaults().variants(List.of(HlsVariant.of(360, "800k"))));

        assertTrue(Files.exists(tmp.resolve("out/master.m3u8")), "N=1 仍产 master");
        assertEquals(1, r.variants().size());
        assertFalse(r.variants().get(0).segments().isEmpty());
    }

    @Test
    void AES单密钥每档含EXTXKEY且段被加密_master无KEY(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File in = generateAV(tmp.resolve("in.mp4"), 1280, 720, 8);
        File outDir = tmp.resolve("out").toFile();
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) {
            key[i] = (byte) (i + 7);
        }

        HlsAbrResult r = Ffmpeg.hlsAbr(in, outDir,
                HlsAbrOptions.defaults().key(HlsKey.of(key, "https://keys.example/abr.key")));

        Path encKey = tmp.resolve("out/key/enc.key");
        assertTrue(Files.exists(encKey), "单 enc.key 落 key/");
        assertEquals(16, Files.size(encKey), "密钥 16 字节");
        assertEquals(encKey, r.keyFile());

        String master = Files.readString(r.master());
        assertFalse(master.contains("#EXT-X-KEY"), "master 不含 KEY 行: " + master);

        for (HlsVariantResult v : r.variants()) {
            String m3u8 = Files.readString(v.playlist());
            assertTrue(m3u8.contains("#EXT-X-KEY:METHOD=AES-128"), "各档含 EXT-X-KEY: " + v.name());
            // TS 明文首字节为同步字节 0x47；加密后应被打乱。
            byte[] first = Files.readAllBytes(v.segments().get(0));
            assertTrue(first.length > 0 && (first[0] & 0xFF) != 0x47, "段应被加密（首字节非 TS 同步字节）: " + v.name());
        }
    }

    @Test
    void 异步骨架跑通并装配HlsAbrResult(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File in = generateAV(tmp.resolve("in.mp4"), 640, 360, 6);
        File outDir = tmp.resolve("out").toFile();
        FfmpegClient client = new FfmpegClient(
                io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment.shared(),
                io.github.pandong2015.ffmpeg4j.engine.RunOptions.defaults());

        HlsAbrResult r = client.hlsAbrAsync(in, outDir,
                HlsAbrOptions.defaults().variants(List.of(HlsVariant.of(360, "800k")))).get();

        assertEquals(1, r.variants().size());
        assertTrue(Files.exists(r.master()));
        assertNull(r.keyFile(), "无 AES 时 keyFile 为 null");
    }

    // ===== helpers =====

    private static void assumeFfmpeg() {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg 不可用，跳过集成测试");
        assumeTrue(commandExists("ffprobe"), "ffprobe 不可用，跳过集成测试");
    }

    private static File generateAV(Path out, int w, int h, int durationSec) throws IOException, InterruptedException {
        List<String> cmd = List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=" + durationSec + ":size=" + w + "x" + h + ":rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=" + durationSec,
                "-shortest", out.toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        int code = p.waitFor();
        assumeTrue(code == 0, "ffmpeg 生成素材失败，退出码 " + code);
        return out.toFile();
    }

    /** 解析 m3u8 中各 {@code #EXTINF:<dur>,} 的时长序列。 */
    private static List<Double> extinfDurations(String m3u8) {
        List<Double> out = new ArrayList<>();
        for (String raw : m3u8.split("\n")) {
            String line = raw.strip();
            if (line.startsWith("#EXTINF:")) {
                String num = line.substring("#EXTINF:".length());
                int comma = num.indexOf(',');
                if (comma >= 0) {
                    num = num.substring(0, comma);
                }
                out.add(Double.parseDouble(num.strip()));
            }
        }
        return out;
    }

    private static int countOccur(String haystack, String needle) {
        int c = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            c++;
            idx += needle.length();
        }
        return c;
    }

    private static boolean commandExists(String command) {
        try {
            Process p = new ProcessBuilder(command, "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
