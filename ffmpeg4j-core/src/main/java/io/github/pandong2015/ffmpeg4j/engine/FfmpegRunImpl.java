package io.github.pandong2015.ffmpeg4j.engine;

import io.github.pandong2015.ffmpeg4j.FfmpegException;
import io.github.pandong2015.ffmpeg4j.compiler.CompiledCommand;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 引擎核心：一次 ffmpeg 执行的启动、排空、进度采集、取消阶梯与结果/异常组装。
 * {@link FfmpegExecutor#run} 内部构造并 {@link #await()}；{@link FfmpegExecutor#runAsync} 直接返回本对象。
 *
 * <p>运行时改写：把 {@link CompiledCommand} 的占位 {@code argv[0]}（字面量 "ffmpeg"）替换为解析后的
 * 二进制路径，并在其后注入 {@code -progress <pipe:1|tcp://…>}（不重复添加 {@code -y}，不加
 * {@code -nostdin}——优雅取消需向 stdin 写 {@code q}）。
 */
final class FfmpegRunImpl implements FfmpegRun {

    private static final System.Logger LOG = System.getLogger(FfmpegRunImpl.class.getName());
    private static final long READER_JOIN_MS = 2000L;

    private final RunOptions opts;
    private final IoTopology topology;
    private final ProgressChannel channel;
    private final List<String> effectiveCommand;
    private final StderrRing stderrRing = new StderrRing();
    private final ProgressParser parser = new ProgressParser();

    private final Process process;
    private final Thread stderrThread;
    private final Thread stdoutDiscardThread;   // 仅 tcp 模式；否则为 null
    private volatile Thread timeoutThread;

    private volatile Progress lastProgress = Progress.empty();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private volatile boolean timedOut;

    // await 一次性护栏
    private final Object awaitLock = new Object();
    private boolean finished;
    private RunResult result;
    private RuntimeException failure;

    FfmpegRunImpl(String binaryPath, CompiledCommand cmd, RunOptions opts) {
        this.opts = opts == null ? RunOptions.defaults() : opts;
        List<String> raw = cmd.argv();
        if (raw == null || raw.isEmpty()) {
            throw new FfmpegException("编译产物 argv 为空，无法执行", null);
        }
        this.topology = IoTopology.derive(raw);
        this.channel = ProgressChannel.forTopology(topology);
        this.effectiveCommand = buildEffectiveCommand(binaryPath, raw, channel.progressArg());
        this.process = spawn();
        // stderr 恒有：留尾环形缓冲。
        this.stderrThread = daemon("ffmpeg-stderr", new StreamPump(process.getErrorStream(), stderrRing::add));
        this.stderrThread.start();
        // stdout：tcp 模式传媒体，需丢弃排空防死锁，进度另经 tcp；pipe 模式 stdout 即进度。
        if (topology.stdoutMedia()) {
            this.stdoutDiscardThread = daemon("ffmpeg-stdout-discard",
                    StreamPump.discarding(process.getInputStream()));
            this.stdoutDiscardThread.start();
        } else {
            this.stdoutDiscardThread = null;
        }
        channel.start(process, this::onProgressLine);
        // stdin 处理：
        //  - 空闲拓扑（v1.0 主路径）：保持 stdin 打开，供优雅取消写 q。
        //  - 喂输入拓扑但本迭代公共 API 未提供输入源：立即 close 子进程 stdin 发出 EOF，避免 ffmpeg
        //    永久等待输入而 waitFor 反向死锁；真实喂输入由 {@link StdinPump} 边喂边在耗尽/取消时 close。
        if (topology.stdinFed()) {
            closeStdinQuietly();
        }
        if (this.opts.timeout() != null) {
            startTimeoutWatcher(this.opts.timeout().toMillis());
        }
    }

    private void closeStdinQuietly() {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // 进程可能已退出，关闭失败无害。
        }
    }

    // 包私有：命令改写为不依赖进程启动的纯函数，供无 ffmpeg 环境下的单测直接验证。
    static List<String> buildEffectiveCommand(String binaryPath, List<String> raw, String progressArg) {
        List<String> out = new ArrayList<>(raw.size() + 2);
        out.add(binaryPath);
        if (progressArg != null) {
            out.add("-progress");
            out.add(progressArg);
        }
        // 跳过占位 argv[0]，其余原样附加（-y 等已由编译器给出）。
        out.addAll(raw.subList(1, raw.size()));
        return List.copyOf(out);
    }

    private Process spawn() {
        ProcessBuilder pb = new ProcessBuilder(effectiveCommand);
        try {
            return pb.start();
        } catch (IOException e) {
            channel.close();
            throw new FfmpegException(
                    "无法启动 ffmpeg 进程 " + effectiveCommand.get(0)
                            + " —— 请确认该二进制已安装且可执行（" + e.getMessage() + "）",
                    e);
        }
    }

    // —— 进度 —— //

    private void onProgressLine(String line) {
        parser.offer(line).ifPresent(this::dispatch);
    }

    private void dispatch(Progress p) {
        lastProgress = p;
        Consumer<Progress> cb = opts.onProgress();
        if (cb == null) {
            return;
        }
        Executor exec = opts.callbackExecutor();
        if (exec == null) {
            safeInvoke(cb, p);
            return;
        }
        try {
            exec.execute(() -> safeInvoke(cb, p));
        } catch (RejectedExecutionException rejected) {
            // Executor 已关闭：丢弃该次派发，不影响排空。
        }
    }

    private static void safeInvoke(Consumer<Progress> cb, Progress p) {
        try {
            cb.accept(p);
        } catch (Throwable userError) {
            // 用户回调抛出任何东西（含 Error）都不得中断进度 pump——pipe 模式下该线程是 stdout 的唯一
            // 排空者，其死亡会令子进程 write() 阻塞、waitFor() 永久挂起。捕获 Throwable 记录后继续排空。
            LOG.log(System.Logger.Level.WARNING, "进度回调抛出异常，已忽略", userError);
        }
    }

    // —— 取消阶梯 —— //

    @Override
    public void cancel() {
        cancel(CancelMode.GRACEFUL);
    }

    @Override
    public void cancel(CancelMode mode) {
        CancelMode m = mode == null ? CancelMode.GRACEFUL : mode;
        if (!cancelRequested.compareAndSet(false, true)) {
            return; // 已在取消中（或已被超时看门狗抢占），幂等。
        }
        launchCancelLadder(m);
    }

    /** 后台推进取消阶梯，立即返回。调用前须已赢得 {@link #cancelRequested} 的 CAS。 */
    private void launchCancelLadder(CancelMode mode) {
        daemon("ffmpeg-cancel", () -> runCancelLadder(mode)).start();
    }

    private void runCancelLadder(CancelMode mode) {
        try {
            if (!process.isAlive()) {
                return;
            }
            if (mode == CancelMode.GRACEFUL) {
                if (topology.stdinFed()) {
                    // stdin 被输入媒体占用，无法写 q → 自动降级 SIGTERM（并记录）。
                    LOG.log(System.Logger.Level.INFO,
                            "stdin 被输入媒体占用，无法写 q，取消降级为 SIGTERM");
                } else if (writeQuit() && waitFor(opts.cancelGracePeriod().toMillis())) {
                    return; // 优雅收尾成功。
                }
            }
            // SIGTERM
            process.destroy();
            if (waitFor(opts.terminateGracePeriod().toMillis())) {
                return;
            }
            // SIGKILL
            process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } catch (RuntimeException e) {
            // 宽限期 Duration 溢出（toMillis）等任何运行时异常都不得让取消线程静默死亡而进程永不终止：
            // 兜底强杀，保证 await() 有界返回。
            LOG.log(System.Logger.Level.WARNING, "取消阶梯遇异常，强制终止进程兜底", e);
            process.destroyForcibly();
        }
    }

    /** 向 stdin 写 {@code q} 触发 ffmpeg 收尾；stdin 不可写（已关闭/broken）返回 false。 */
    private boolean writeQuit() {
        try {
            OutputStream stdin = process.getOutputStream();
            stdin.write("q\n".getBytes(StandardCharsets.US_ASCII));
            stdin.flush();
            return true;
        } catch (IOException e) {
            // stdin 已关闭/broken-pipe：无法优雅，落到 SIGTERM。
            return false;
        }
    }

    private boolean waitFor(long millis) throws InterruptedException {
        if (millis <= 0) {
            return !process.isAlive();
        }
        return process.waitFor(millis, TimeUnit.MILLISECONDS);
    }

    // —— 超时（复用取消阶梯）—— //

    private void startTimeoutWatcher(long millis) {
        Thread t = daemon("ffmpeg-timeout", () -> {
            try {
                if (!process.waitFor(millis, TimeUnit.MILLISECONDS)) {
                    // 用同一把 CAS 抢占取消权：仅当用户尚未主动取消时才判为超时并发起取消阶梯。
                    // 这样「先到的用户取消」不会被「后到的超时」覆盖——否则本应返回 RunResult 的用户
                    // 取消会被误报为超时异常（timedOut 只在超时确实抢先时才为真）。
                    if (cancelRequested.compareAndSet(false, true)) {
                        timedOut = true;
                        launchCancelLadder(CancelMode.GRACEFUL); // 超时到达即走取消阶梯，先优雅再升级。
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        this.timeoutThread = t;
        t.start();
    }

    // —— 等待与结果 —— //

    @Override
    public RunResult await() {
        synchronized (awaitLock) {
            if (finished) {
                if (failure != null) {
                    throw failure;
                }
                return result;
            }
            try {
                doAwait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                failure = new FfmpegException("等待 ffmpeg 结束时被中断", e);
            } finally {
                // 无论 doAwait 正常返回还是在 waitFor 处被中断，都释放进度通道（幂等）：置位 closed 以
                // 终止 tcp accept 空转线程、关闭 ServerSocket 释放绑定端口，杜绝「被中断的 tcp 运行」
                // 泄漏端口/线程。中断分支下 doAwait 内的 channel.close() 会被跳过，故此处兜底必需。
                channel.close();
            }
            finished = true;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    private void doAwait() throws InterruptedException {
        int code = process.waitFor();
        // 进程已退出：超时看门狗不再需要，中断其 waitFor 阻塞，避免设了大 timeout 的短任务遗留 daemon 空等。
        Thread tw = timeoutThread;
        if (tw != null) {
            tw.interrupt();
        }
        // 进程已退出：关闭进度通道以解开可能阻塞的 accept/read，然后汇合各读线程。
        channel.close();
        channel.awaitReaders(READER_JOIN_MS);
        joinQuietly(stderrThread);
        joinQuietly(stdoutDiscardThread);
        String tail = stderrRing.tail();

        switch (classifyTermination(code, timedOut, cancelRequested.get(), tail, channel.progressArg())) {
            case TIMEOUT -> failure = new FfmpegException(code, effectiveCommand, tail,
                    "任务超时（超过配置的超时时间，已按取消阶梯终止）");
            // 调用方主动取消：非零退出属预期，返回结果而非抛异常。
            case CANCELLED, SUCCESS -> result = new RunResult(code, lastProgress, effectiveCommand);
            // 库内部管道故障（如 -progress 回环通道 Connection refused）：依 spec 5.10 MUST NOT
            // 外泄为媒体类 FfmpegException——记录后按内部路径以 RunResult 正常返回，不抛异常。
            case INTERNAL -> {
                LOG.log(System.Logger.Level.WARNING,
                        "ffmpeg 因库内部管道故障非零退出（退出码 " + code + "，"
                                + ErrorPatterns.reasonFor(tail, channel.progressArg()).orElse("progress 管道故障")
                                + "），不外泄为媒体错误");
                result = new RunResult(code, lastProgress, effectiveCommand);
            }
            // 因错误非零退出：按已知模式尽力解析原因后抛出结构化异常。
            case MEDIA_FAILURE -> failure = new FfmpegException(code, effectiveCommand, tail,
                    ErrorPatterns.reasonFor(tail).orElse(null));
        }
    }

    /** doAwait 的终局归类。 */
    enum Termination { TIMEOUT, CANCELLED, SUCCESS, INTERNAL, MEDIA_FAILURE }

    /**
     * 依退出码与超时/取消/内部标志对本次执行的终局做归类（纯逻辑，供测试锁定分支契约）。
     *
     * <p>优先级：先判超时（{@code timedOut} 仅在超时确实抢先于用户取消时为真，见超时看门狗的 CAS）；
     * 再判用户取消（返回结果而非异常）；退出码 0 为成功；非零则区分「库内部管道故障」（不外泄为媒体
     * 错误）与真实媒体失败。
     */
    static Termination classifyTermination(int code, boolean timedOut, boolean cancelRequested, String tail) {
        return classifyTermination(code, timedOut, cancelRequested, tail, null);
    }

    static Termination classifyTermination(
            int code, boolean timedOut, boolean cancelRequested, String tail, String progressArg) {
        if (timedOut) {
            return Termination.TIMEOUT;
        }
        if (cancelRequested) {
            return Termination.CANCELLED;
        }
        if (code == 0) {
            return Termination.SUCCESS;
        }
        if (ErrorPatterns.isInternal(tail, progressArg)) {
            return Termination.INTERNAL;
        }
        return Termination.MEDIA_FAILURE;
    }

    @Override
    public boolean isDone() {
        return finished || !process.isAlive();
    }

    // 供测试/内部观察。
    IoTopology topology() {
        return topology;
    }

    List<String> effectiveCommand() {
        return effectiveCommand;
    }

    // —— 线程助手 —— //

    private static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private static void joinQuietly(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(READER_JOIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
