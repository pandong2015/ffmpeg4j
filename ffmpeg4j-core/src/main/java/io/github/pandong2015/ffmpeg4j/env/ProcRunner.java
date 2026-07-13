package io.github.pandong2015.ffmpeg4j.env;

import io.github.pandong2015.ffmpeg4j.FfmpegException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 本包内部使用的极简同步进程执行器，专供 {@code -version}/{@code -filters} 这类短命的探测调用。
 *
 * <p>刻意保持轻量：合并 stderr 到 stdout、后台线程排空输出以规避管道死锁、带超时。真正的媒体处理
 * 由 L1 引擎负责，本类不承担长任务或进度管道。
 */
final class ProcRunner {

    /** 默认探测超时；{@code -filters} 会输出较多内容，但仍应在数秒内完成。 */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private ProcRunner() {
    }

    /** 一次探测调用的结果：退出码 + 合并后的（stdout+stderr）文本输出。 */
    record Result(int exitCode, String output) {
    }

    /** 以默认超时执行命令。 */
    static Result run(List<String> command) {
        return run(command, DEFAULT_TIMEOUT);
    }

    /**
     * 同步执行一条短命命令并捕获其合并输出。
     *
     * @throws FfmpegException 当二进制无法启动、执行超时或被中断时抛出（使用 {@code (message, cause)} 构造）。
     */
    static Result run(List<String> command, Duration timeout) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        final Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new FfmpegException(
                    "无法启动进程 " + command.get(0) + " —— 请确认该二进制已安装且可执行（" + e.getMessage() + "）", e);
        }

        // 后台排空合并流，避免子进程写满管道缓冲区而死锁。
        CompletableFuture<String> readerFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });

        try {
            boolean finished = proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new FfmpegException(
                        "进程执行超时（>" + timeout.toMillis() + "ms）: " + command,
                        new TimeoutException("process timed out"));
            }
            String output = readerFuture.get(2, TimeUnit.SECONDS);
            return new Result(proc.exitValue(), output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new FfmpegException("等待进程结束时被中断: " + command, e);
        } catch (ExecutionException | TimeoutException e) {
            proc.destroyForcibly();
            throw new FfmpegException("读取进程输出失败: " + command, e);
        }
    }
}
