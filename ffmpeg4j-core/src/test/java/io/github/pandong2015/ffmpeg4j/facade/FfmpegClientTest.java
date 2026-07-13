package io.github.pandong2015.ffmpeg4j.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.github.pandong2015.ffmpeg4j.compiler.CompiledCommand;
import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;
import io.github.pandong2015.ffmpeg4j.engine.RunResult;
import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;
import io.github.pandong2015.ffmpeg4j.probe.ProbeResult;

/**
 * {@link FfmpegClient} 实例门面测试：构造校验、默认 {@link RunOptions} 合并语义（纯逻辑），
 * 以及注入 env 真正生效的守卫式 E2E（缺 ffmpeg/ffprobe 则 {@code assumeTrue} 跳过而非失败）。
 * 与 {@link FacadeSupport} 同包，故可直接断言纯函数 {@code buildXxx}/合并 helper 的产物。
 */
class FfmpegClientTest {

    @Test
    void 构造器拒绝null环境() {
        assertThrows(NullPointerException.class,
                () -> new FfmpegClient(null, RunOptions.defaults()));
    }

    @Test
    void 合并默认RunOptions_调用点字段覆盖其余沿用() {
        // base：默认 + 60s 超时 + 9s 优雅取消宽限期（模拟 Spring 侧配置的默认 RunOptions）。
        RunOptions base = RunOptions.defaults()
                .timeout(Duration.ofSeconds(60))
                .cancelGracePeriod(Duration.ofSeconds(9));

        // 不覆盖任何字段 → 完全沿用 base。
        RunOptions merged = FacadeSupport.runOptions(base, null, null);
        assertEquals(Duration.ofSeconds(60), merged.timeout());
        assertEquals(Duration.ofSeconds(9), merged.cancelGracePeriod());
        assertNull(merged.onProgress());

        // 覆盖 timeout → 新值生效，宽限期沿用 base（体现「调用点覆盖、其余沿用」）。
        RunOptions overTimeout = FacadeSupport.runOptions(base, Duration.ofSeconds(30), null);
        assertEquals(Duration.ofSeconds(30), overTimeout.timeout());
        assertEquals(Duration.ofSeconds(9), overTimeout.cancelGracePeriod());

        // 覆盖 onProgress → 回调生效，timeout 与宽限期均沿用 base。
        Consumer<Progress> cb = p -> { };
        RunOptions overProgress = FacadeSupport.runOptions(base, null, cb);
        assertSame(cb, overProgress.onProgress());
        assertEquals(Duration.ofSeconds(60), overProgress.timeout());
        assertEquals(Duration.ofSeconds(9), overProgress.cancelGracePeriod());
    }

    @Test
    void 实例门面与静态门面共享同一命令构建() {
        // 静态 Ffmpeg 与实例 FfmpegClient 的 transcode 都经 FacadeSupport.buildTranscode 构建，
        // 故产出的 argv 由同一纯函数决定；此处直接断言该纯函数产物含预期编解码器。
        File in = new File("in.mp4");
        File out = new File("out.mp4");
        TranscodeOptions opts = TranscodeOptions.defaults().videoCodec("libx265").audioCodec("aac");
        CompiledCommand cmd = FacadeSupport.buildTranscode(in, out, opts);
        List<String> argv = cmd.argv();
        assertTrue(argv.contains("libx265"), "argv 应含视频编解码器 libx265：" + argv);
        assertTrue(argv.contains("aac"), "argv 应含音频编解码器 aac：" + argv);
    }

    @Test
    void 实例门面用注入env执行转码与探测() throws Exception {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg 不可用，跳过集成测试");
        assumeTrue(commandExists("ffprobe"), "ffprobe 不可用，跳过集成测试");

        Path dir = Files.createTempDirectory("ffmpeg4j-client");
        File src = dir.resolve("src.mp4").toFile();
        int code = new ProcessBuilder("ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=1:size=160x120:rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                "-shortest", src.getAbsolutePath())
                .redirectErrorStream(true).start().waitFor();
        assumeTrue(code == 0, "ffmpeg 生成素材失败，退出码 " + code);

        // 用真实发现的环境显式构造实例门面。
        FfmpegEnvironment env = FfmpegEnvironment.detect();
        FfmpegClient client = new FfmpegClient(env, RunOptions.defaults());

        // probe 经注入 env 的 ffprobe。
        ProbeResult probe = client.probe(src);
        assertTrue(probe.durationSeconds() > 0, "probe 应得到正时长");

        // transcode 经注入 env 的 ffmpeg 执行。
        File dst = dir.resolve("dst.mp4").toFile();
        RunResult res = client.transcode(src, dst, "libx264", "aac");
        assertEquals(0, res.exitCode(), "转码应成功退出");
        assertTrue(dst.length() > 0, "输出文件应非空");
    }

    @Test
    void 异步转码与探测返回CompletableFuture并完成() throws Exception {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg 不可用，跳过集成测试");
        assumeTrue(commandExists("ffprobe"), "ffprobe 不可用，跳过集成测试");

        Path dir = Files.createTempDirectory("ffmpeg4j-client-async");
        File src = dir.resolve("src.mp4").toFile();
        int code = new ProcessBuilder("ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=1:size=160x120:rate=10",
                "-shortest", src.getAbsolutePath())
                .redirectErrorStream(true).start().waitFor();
        assumeTrue(code == 0, "ffmpeg 生成素材失败，退出码 " + code);

        FfmpegClient client = new FfmpegClient(FfmpegEnvironment.detect(), RunOptions.defaults());

        // 异步探测：不阻塞调用线程，future 完成后携正时长。
        ProbeResult probe = client.probeAsync(src).get(30, TimeUnit.SECONDS);
        assertTrue(probe.durationSeconds() > 0, "异步 probe 应得到正时长");

        // 异步转码：future 完成后退出码 0、输出非空。
        File dst = dir.resolve("dst.mp4").toFile();
        CompletableFuture<RunResult> future = client.transcodeAsync(src, dst, "libx264", "aac");
        RunResult res = future.get(60, TimeUnit.SECONDS);
        assertEquals(0, res.exitCode(), "异步转码应成功退出");
        assertTrue(dst.length() > 0, "异步转码输出应非空");
    }

    private static boolean commandExists(String command) {
        try {
            Process p = new ProcessBuilder(command, "-version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
