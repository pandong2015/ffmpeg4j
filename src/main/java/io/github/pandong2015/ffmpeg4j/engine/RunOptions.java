package io.github.pandong2015.ffmpeg4j.engine;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 不可变、wither 风格的运行选项。每个 {@code xxx(value)} 方法返回带该改动的<em>新副本</em>，
 * 同名无参 {@code xxx()} 为只读访问器（按参数个数重载，互不冲突）。
 *
 * <p>默认值：无超时（{@code null}）、无进度回调、回调在进度 pump 线程触发、优雅取消等待
 * {@code cancelGracePeriod=5s}、SIGTERM 后等待 {@code terminateGracePeriod=5s} 再 SIGKILL。
 */
public final class RunOptions {

    private static final Duration DEFAULT_CANCEL_GRACE = Duration.ofSeconds(5);
    private static final Duration DEFAULT_TERMINATE_GRACE = Duration.ofSeconds(5);

    private final Duration timeout;                 // null = 不设超时
    private final Consumer<Progress> onProgress;    // null = 无回调
    private final Executor callbackExecutor;        // null = 在 pump 线程触发
    private final Duration cancelGracePeriod;
    private final Duration terminateGracePeriod;

    private RunOptions(Duration timeout,
                       Consumer<Progress> onProgress,
                       Executor callbackExecutor,
                       Duration cancelGracePeriod,
                       Duration terminateGracePeriod) {
        this.timeout = timeout;
        this.onProgress = onProgress;
        this.callbackExecutor = callbackExecutor;
        this.cancelGracePeriod = cancelGracePeriod;
        this.terminateGracePeriod = terminateGracePeriod;
    }

    /** 默认选项。 */
    public static RunOptions defaults() {
        return new RunOptions(null, null, null, DEFAULT_CANCEL_GRACE, DEFAULT_TERMINATE_GRACE);
    }

    // —— wither（返回新副本）—— //

    /** 设置任务超时；{@code null} 表示不设超时。 */
    public RunOptions timeout(Duration t) {
        if (t != null && (t.isNegative() || t.isZero())) {
            throw new IllegalArgumentException("timeout 必须为正");
        }
        return new RunOptions(t, onProgress, callbackExecutor, cancelGracePeriod, terminateGracePeriod);
    }

    /**
     * 设置进度回调；{@code null} 表示清除回调。
     *
     * <p><b>重要</b>：默认回调在进度 pump 线程（pipe 模式下即 stdout 的唯一排空者）上<em>同步</em>触发，
     * 故回调<b>必须非阻塞、快速返回</b>——若在其中做阻塞 IO、等锁、向已满队列投递等重活，会停住排空、
     * 令 ffmpeg 写 {@code -progress} 阻塞、进程不退出（无超时时 {@code run()} 将永久挂起）。需做重活时用
     * {@link #callbackExecutor(Executor)} 把派发移出 pump 线程。回调抛出的异常/Error 会被吞并记录，不影响排空。
     */
    public RunOptions onProgress(Consumer<Progress> cb) {
        return new RunOptions(timeout, cb, callbackExecutor, cancelGracePeriod, terminateGracePeriod);
    }

    /** 设置回调派发的 {@link Executor}；{@code null} 表示在进度 pump 线程直接触发。 */
    public RunOptions callbackExecutor(Executor exec) {
        return new RunOptions(timeout, onProgress, exec, cancelGracePeriod, terminateGracePeriod);
    }

    /** 优雅取消写 {@code q} 后等待多久再升级到 SIGTERM。 */
    public RunOptions cancelGracePeriod(Duration d) {
        Objects.requireNonNull(d, "cancelGracePeriod");
        if (d.isNegative()) {
            throw new IllegalArgumentException("cancelGracePeriod 不能为负");
        }
        return new RunOptions(timeout, onProgress, callbackExecutor, d, terminateGracePeriod);
    }

    /** SIGTERM 后等待多久再升级到 SIGKILL。 */
    public RunOptions terminateGracePeriod(Duration d) {
        Objects.requireNonNull(d, "terminateGracePeriod");
        if (d.isNegative()) {
            throw new IllegalArgumentException("terminateGracePeriod 不能为负");
        }
        return new RunOptions(timeout, onProgress, callbackExecutor, cancelGracePeriod, d);
    }

    // —— 只读访问器（无参重载）—— //

    /** 超时时长；{@code null} 表示不设超时。 */
    public Duration timeout() {
        return timeout;
    }

    /** 进度回调；{@code null} 表示无回调。 */
    public Consumer<Progress> onProgress() {
        return onProgress;
    }

    /** 回调派发 Executor；{@code null} 表示在 pump 线程触发。 */
    public Executor callbackExecutor() {
        return callbackExecutor;
    }

    /** 优雅取消宽限期。 */
    public Duration cancelGracePeriod() {
        return cancelGracePeriod;
    }

    /** SIGTERM 升级到 SIGKILL 的宽限期。 */
    public Duration terminateGracePeriod() {
        return terminateGracePeriod;
    }
}
