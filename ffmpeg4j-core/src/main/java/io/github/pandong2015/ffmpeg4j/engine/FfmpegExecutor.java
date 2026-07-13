package io.github.pandong2015.ffmpeg4j.engine;

import io.github.pandong2015.ffmpeg4j.compiler.CompiledCommand;
import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;

import java.nio.file.Path;
import java.util.Objects;

/**
 * L1 执行引擎门面：以预装的 ffmpeg 二进制执行 {@link CompiledCommand}（路线 A，无 JNI）。
 *
 * <p>提供阻塞式 {@link #run} 与异步 {@link #runAsync} 双 API。运行时会把编译产物 {@code argv[0]}
 * 的占位 "ffmpeg" 替换为解析后的二进制路径，并注入 {@code -progress}（据 IO 拓扑自适应 pipe/tcp），
 * 采集机器可读进度、以取消阶梯优雅收尾、并在非零退出时抛出带原因的
 * {@link io.github.pandong2015.ffmpeg4j.FfmpegException}。
 *
 * <p>本类无状态、线程安全，可复用于多次执行。
 */
public final class FfmpegExecutor {

    private final String ffmpegBinary;

    /** 以 L0 环境层解析出的 ffmpeg 二进制构造。 */
    public FfmpegExecutor(FfmpegEnvironment env) {
        Objects.requireNonNull(env, "env");
        this.ffmpegBinary = env.binaries().ffmpegCommand();
    }

    /** 以显式 ffmpeg 二进制路径构造（便捷）。 */
    public FfmpegExecutor(Path ffmpegBinary) {
        Objects.requireNonNull(ffmpegBinary, "ffmpegBinary");
        this.ffmpegBinary = ffmpegBinary.toString();
    }

    /** 以默认选项阻塞执行。 */
    public RunResult run(CompiledCommand cmd) {
        return run(cmd, RunOptions.defaults());
    }

    /**
     * 阻塞执行并返回结果。
     *
     * @throws io.github.pandong2015.ffmpeg4j.FfmpegException 因错误非零退出或超时时抛出。
     */
    public RunResult run(CompiledCommand cmd, RunOptions opts) {
        return newRun(cmd, opts).await();
    }

    /** 异步执行，返回可等待/可取消的句柄。 */
    public FfmpegRun runAsync(CompiledCommand cmd, RunOptions opts) {
        return newRun(cmd, opts);
    }

    private FfmpegRunImpl newRun(CompiledCommand cmd, RunOptions opts) {
        Objects.requireNonNull(cmd, "cmd");
        return new FfmpegRunImpl(ffmpegBinary, cmd, opts == null ? RunOptions.defaults() : opts);
    }
}
