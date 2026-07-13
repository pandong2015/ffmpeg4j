package io.github.pandong2015.ffmpeg4j.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.pandong2015.ffmpeg4j.FfmpegException;
import io.github.pandong2015.ffmpeg4j.compiler.CompiledCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * L1 引擎集成测试（需真实 ffmpeg，缺失则 {@code assumeTrue} 跳过而非失败）。
 * 素材以 {@code -f lavfi -i testsrc=...} 现场生成，不落外部依赖。
 *
 * <p>覆盖 5.11：大量 stderr 不挂起、优雅取消不损坏输出、FORCE 跳过收尾、tcp 进度 ffmpeg 启动即
 * 失败不挂起、超时被终止；另含 pipe 进度回调、Executor 派发、错误分类端到端。所有可能阻塞的用例均
 * 以 {@code assertTimeoutPreemptively} 兜底，杜绝整套挂起。
 */
class FfmpegExecutorTest {

    private FfmpegExecutor executor() {
        return new FfmpegExecutor(Path.of("ffmpeg"));
    }

    // testsrc 输出到磁盘文件，stdin/stdout 均空闲 → pipe 进度 + 优雅取消。
    private static CompiledCommand toFile(String testsrc, Path out, String... extra) {
        java.util.ArrayList<String> argv = new java.util.ArrayList<>(List.of(
                "ffmpeg", "-y", "-f", "lavfi", "-i", testsrc));
        argv.addAll(List.of(extra));
        argv.add(out.toString());
        return new CompiledCommand(List.copyOf(argv), null);
    }

    // —— pipe 进度 + 基础转码 —— //

    @Test
    void pipe进度_基础转码成功且回调被触发(@TempDir Path tmp) {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg 不可用，跳过集成测试");
        Path out = tmp.resolve("basic.mp4");
        AtomicInteger progressCount = new AtomicInteger();
        AtomicReference<Progress> last = new AtomicReference<>();

        RunOptions opts = RunOptions.defaults().onProgress(p -> {
            progressCount.incrementAndGet();
            last.set(p);
        });

        RunResult result = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> executor()
                .run(toFile("testsrc=duration=2:size=320x240:rate=25", out,
                        "-c:v", "mpeg4", "-pix_fmt", "yuv420p"), opts));

        assertEquals(0, result.exitCode(), "转码应成功退出 0");
        assertTrue(Files.exists(out) && sizeOf(out) > 0, "输出文件应生成");
        assertTrue(progressCount.get() >= 1, "至少应触发一次进度回调，实际 " + progressCount.get());
        assertNotNull(last.get(), "应捕获到进度快照");
        // 引擎注入了机器可读进度通道。
        assertTrue(result.command().contains("-progress") && result.command().contains("pipe:1"),
                "命令应含 -progress pipe:1");
        assertFalse(result.command().contains("-nostdin"), "不应注入 -nostdin（优雅取消需 stdin）");
    }

    @Test
    void 回调可派发到用户Executor而非pump线程(@TempDir Path tmp) throws Exception {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg 不可用，跳过集成测试");
        Path out = tmp.resolve("cbexec.mp4");
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cb-exec-thread");
            t.setDaemon(true);
            return t;
        });
        AtomicReference<String> callbackThread = new AtomicReference<>();
        AtomicInteger count = new AtomicInteger();
        try {
            RunOptions opts = RunOptions.defaults()
                    .callbackExecutor(exec)
                    .onProgress(p -> {
                        callbackThread.set(Thread.currentThread().getName());
                        count.incrementAndGet();
                    });
            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> executor()
                    .run(toFile("testsrc=duration=2:size=320x240:rate=25", out,
                            "-c:v", "mpeg4", "-pix_fmt", "yuv420p"), opts));
        } finally {
            exec.shutdown();
        }
        assumeTrue(count.get() >= 1, "无进度块则无法断言派发线程");
        assertEquals("cb-exec-thread", callbackThread.get(),
                "回调应在用户 Executor 线程派发，而非进度 pump 线程");
    }

    // —— 大量 stderr 不挂起 —— //

    @Test
    void 大量stderr日志不挂起(@TempDir Path tmp) {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg 不可用，跳过集成测试");
        Path out = tmp.resolve("noisy.mp4");
        // -loglevel debug 产出远超管道缓冲的 stderr；引擎持续排空则不挂起。
        RunResult result = assertTimeoutPreemptively(Duration.ofSeconds(60), () -> executor()
                .run(toFile("testsrc=duration=3:size=640x480:rate=30", out,
                        "-loglevel", "debug", "-c:v", "mpeg4", "-pix_fmt", "yuv420p")));
        assertEquals(0, result.exitCode(), "大量 stderr 下仍应正常完成");
        assertTrue(sizeOf(out) > 0, "输出应生成");
    }

    // —— 优雅取消不损坏输出 —— //

    @Test
    void 优雅取消不损坏输出(@TempDir Path tmp) throws Exception {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg 不可用，跳过集成测试");
        assumeTrue(commandExists("ffprobe"), "ffprobe 不可用，无法校验输出完整性");
        Path out = tmp.resolve("graceful.mp4");
        RunOptions opts = RunOptions.defaults()
                .cancelGracePeriod(Duration.ofSeconds(8))
                .terminateGracePeriod(Duration.ofSeconds(3));

        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            FfmpegRun run = executor().runAsync(slow(out, 30), opts);
            Thread.sleep(1500);      // 让其编码一段
            run.cancel();            // 默认优雅：写 q，ffmpeg finalize moov
            RunResult r = run.await();
            assertTrue(run.isDone(), "取消后应结束");
            assertTrue(sizeOf(out) > 0, "输出文件应有内容，退出码 " + r.exitCode());
        });

        // moov 已 finalize：ffprobe 能读出正时长即证明输出未损坏。
        double dur = probeDuration(out);
        assertTrue(dur > 0.0, "优雅取消后输出应可被 ffprobe 解析出正时长，实际 " + dur);
    }

    // —— FORCE 跳过优雅收尾 —— //

    @Test
    void FORCE取消跳过优雅收尾且快速终止(@TempDir Path tmp) {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg 不可用，跳过集成测试");
        Path out = tmp.resolve("force.mp4");
        // cancelGracePeriod 故意设很大：若 FORCE 误走优雅等待，将超 12s 而触发抢占超时失败。
        RunOptions opts = RunOptions.defaults()
                .cancelGracePeriod(Duration.ofSeconds(30))
                .terminateGracePeriod(Duration.ofSeconds(3));

        assertTimeoutPreemptively(Duration.ofSeconds(12), () -> {
            FfmpegRun run = executor().runAsync(slow(out, 30), opts);
            Thread.sleep(1000);
            run.cancel(CancelMode.FORCE);   // 跳过写 q 与优雅收尾，直接 destroy/destroyForcibly
            RunResult r = run.await();       // 主动取消 → 返回结果而非抛异常
            assertTrue(run.isDone(), "FORCE 后应快速结束");
            assertNotNull(r, "取消返回 RunResult");
        });
    }

    // —— tcp 进度：ffmpeg 启动即失败不挂起 —— //

    @Test
    void tcp进度_ffmpeg启动即失败不挂起(@TempDir Path tmp) {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg 不可用，跳过集成测试");
        // 输出到 pipe:1 → stdout 传媒体 → 引擎走 tcp 进度；坏编码器令 ffmpeg 在建立进度连接前即失败。
        CompiledCommand cmd = new CompiledCommand(List.of(
                "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=320x240:rate=10",
                "-c:v", "definitely_not_a_codec_xyz", "-f", "mpegts", "pipe:1"), null);

        FfmpegException ex = assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
                assertThrows(FfmpegException.class, () -> executor().run(cmd),
                        "启动即失败应抛媒体异常，但不得挂起"));
        assertFalse(ex.exitCode() == 0, "退出码应非零");
        assertFalse(ex.stderrTail().isBlank(), "应保留 stderr 尾部");
    }

    // —— 超时被终止 —— //

    @Test
    void 超时按取消阶梯终止(@TempDir Path tmp) {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg 不可用，跳过集成测试");
        Path out = tmp.resolve("timeout.mp4");
        RunOptions opts = RunOptions.defaults()
                .timeout(Duration.ofSeconds(1))
                .cancelGracePeriod(Duration.ofMillis(500))
                .terminateGracePeriod(Duration.ofSeconds(2));

        FfmpegException ex = assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                assertThrows(FfmpegException.class, () -> executor().run(slow(out, 30), opts),
                        "超时应抛出异常"));
        assertNotNull(ex.reason());
        assertTrue(ex.reason().contains("超时"), "原因应指明超时，实际: " + ex.reason());
    }

    // —— 错误分类端到端 —— //

    @Test
    void 未知编码器错误可诊断(@TempDir Path tmp) {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg 不可用，跳过集成测试");
        Path out = tmp.resolve("err.mp4");
        FfmpegException ex = assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
                assertThrows(FfmpegException.class, () -> executor()
                        .run(toFile("testsrc=duration=1:size=320x240:rate=10", out,
                                "-c:v", "definitely_not_a_codec_xyz"))));
        assertFalse(ex.exitCode() == 0, "退出码应非零");
        assertFalse(ex.stderrTail().isBlank(), "stderr 尾部应非空");
        assertFalse(ex.command().isEmpty(), "应记录执行的命令");
        assertNotNull(ex.reason(), "应解析出可读原因");
        assertTrue(ex.reason().contains("编码器"), "原因应指向编码器不可用，实际: " + ex.reason());
    }

    // --- helpers ---------------------------------------------------------

    /** 用 -re 实时读入的慢任务，便于中途取消/超时。 */
    private static CompiledCommand slow(Path out, int seconds) {
        return new CompiledCommand(List.of(
                "ffmpeg", "-y", "-re", "-f", "lavfi",
                "-i", "testsrc=duration=" + seconds + ":size=320x240:rate=25",
                "-c:v", "mpeg4", "-pix_fmt", "yuv420p", out.toString()), null);
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static double probeDuration(Path file) throws Exception {
        Process p = new ProcessBuilder("ffprobe", "-v", "error",
                "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", file.toString())
                .start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.getErrorStream().readAllBytes();
        p.waitFor();
        try {
            return Double.parseDouble(out);
        } catch (NumberFormatException e) {
            return -1.0;
        }
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
