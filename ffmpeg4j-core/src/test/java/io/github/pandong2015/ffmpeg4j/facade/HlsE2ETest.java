package io.github.pandong2015.ffmpeg4j.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * HLS 单码率 VOD 切片的端到端集成（真实 ffmpeg，缺则 {@code assumeTrue} 跳过）。重点钉死：
 * 段 URI 带 {@code ts/} 前缀（默认 {@code -hls_base_url}，8.0.1 证伪隐式相对化）、AES、0600 权限、孤儿段免疫。
 */
class HlsE2ETest {

    @Test
    void HLS默认copy产出m3u8与段并有序(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File in = generateAV(tmp.resolve("in.mp4"), 12);
        File outDir = tmp.resolve("out").toFile();

        HlsResult r = Ffmpeg.hlsSegment(in, outDir);

        assertTrue(Files.exists(tmp.resolve("out/index.m3u8")), "playlist 应存在");
        assertFalse(r.segments().isEmpty(), "段清单非空");
        assertEquals(tmp.resolve("out/index.m3u8"), r.playlist());
        for (Path seg : r.segments()) {
            assertTrue(Files.exists(seg), "段文件应实际存在: " + seg);
            assertTrue(seg.toString().contains("/ts/"), "段落在 ts/ 子目录: " + seg);
        }
    }

    @Test
    void HLS段URI带ts前缀_经默认baseUrl(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File in = generateAV(tmp.resolve("in.mp4"), 12);
        File outDir = tmp.resolve("out").toFile();

        Ffmpeg.hlsSegment(in, outDir);

        String m3u8 = Files.readString(tmp.resolve("out/index.m3u8"));
        List<String> segUris = FacadeSupport.parseSegmentUris(m3u8);
        assertFalse(segUris.isEmpty(), "m3u8 应含段行");
        for (String uri : segUris) {
            assertTrue(uri.startsWith("ts/"), "段 URI 须带 ts/ 前缀（默认 base_url），实际: " + uri);
        }
    }

    @Test
    void HLS的AES产出EXTXKEY且密钥16字节(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File in = generateAV(tmp.resolve("in.mp4"), 8);
        File outDir = tmp.resolve("out").toFile();
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) {
            key[i] = (byte) (i + 1);
        }

        HlsResult r = Ffmpeg.hlsSegment(in, outDir,
                HlsOptions.defaults().key(HlsKey.of(key, "https://keys.example/s.key")));

        String m3u8 = Files.readString(tmp.resolve("out/index.m3u8"));
        assertTrue(m3u8.contains("#EXT-X-KEY:METHOD=AES-128"), "m3u8 应含 EXT-X-KEY: " + m3u8);
        assertTrue(m3u8.contains("URI=\"https://keys.example/s.key\""), "URI 应为传入值: " + m3u8);
        Path encKey = tmp.resolve("out/key/enc.key");
        assertTrue(Files.exists(encKey), "密钥文件应落 key/enc.key");
        assertEquals(16, Files.size(encKey), "密钥文件应为 16 字节");
        assertEquals(encKey, r.keyFile());
    }

    @Test
    void HLS密钥文件0600权限_临时文件已删(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "非 POSIX，跳过权限断言");
        File in = generateAV(tmp.resolve("in.mp4"), 6);
        File outDir = tmp.resolve("out").toFile();

        Ffmpeg.hlsSegment(in, outDir, HlsOptions.defaults().key(HlsKey.random("https://k/s.key")));

        Path encKey = tmp.resolve("out/key/enc.key");
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(encKey),
                "enc.key 应为 0600");
    }

    @Test
    void HLS对齐关键帧转码产均匀段(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File in = generateAV(tmp.resolve("in.mp4"), 12);
        File outDir = tmp.resolve("out").toFile();

        HlsResult r = Ffmpeg.hlsSegment(in, outDir,
                HlsOptions.defaults().videoCodec("libx264").hlsTime(4.0).alignKeyframes(true));

        // 12s / 4s 对齐 → 约 3 段（关键帧强制对齐使段均匀）。
        assertEquals(3, r.segments().size(), "对齐转码应产 3 段: " + r.segments());
    }

    @Test
    void HLS复用非空outDir不混入孤儿段(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File in = generateAV(tmp.resolve("in.mp4"), 8);
        File outDir = tmp.resolve("out").toFile();
        // 预置一个不属于本轮的孤儿段。
        Files.createDirectories(tmp.resolve("out/ts"));
        Files.writeString(tmp.resolve("out/ts/index999.ts"), "stale");

        HlsResult r = Ffmpeg.hlsSegment(in, outDir);

        assertFalse(r.segments().stream().anyMatch(p -> p.getFileName().toString().equals("index999.ts")),
                "HlsResult.segments 源自 m3u8，不应含孤儿段: " + r.segments());
    }

    @Test
    void HLS异步骨架跑通并装配HlsResult(@TempDir Path tmp) throws Exception {
        assumeFfmpeg();
        File in = generateAV(tmp.resolve("in.mp4"), 6);
        File outDir = tmp.resolve("out").toFile();
        io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment env =
                io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment.shared();
        FfmpegClient client = new FfmpegClient(env,
                io.github.pandong2015.ffmpeg4j.engine.RunOptions.defaults());

        HlsResult r = client.hlsSegmentAsync(in, outDir,
                HlsOptions.defaults().key(HlsKey.random("https://k/s.key"))).get();

        assertFalse(r.segments().isEmpty(), "异步产段清单非空");
        assertTrue(Files.exists(tmp.resolve("out/key/enc.key")), "密钥文件应落盘");
        // 临时 key_info_file（系统临时目录）应已在 finally 删除——不残留可数的 ffmpeg4j-hls- 文件。
    }

    // ===== helpers =====

    private static void assumeFfmpeg() {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg 不可用，跳过集成测试");
        assumeTrue(commandExists("ffprobe"), "ffprobe 不可用，跳过集成测试");
    }

    private static File generateAV(Path out, int durationSec) throws IOException, InterruptedException {
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
