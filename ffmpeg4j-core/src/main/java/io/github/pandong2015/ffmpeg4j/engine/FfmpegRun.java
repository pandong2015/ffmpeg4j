package io.github.pandong2015.ffmpeg4j.engine;

/**
 * 一次异步 ffmpeg 执行的可等待、可取消句柄（{@link FfmpegExecutor#runAsync} 返回）。
 */
public interface FfmpegRun {

    /**
     * 阻塞等待结果。
     *
     * @return 成功或被主动取消时返回 {@link RunResult}。
     * @throws io.github.pandong2015.ffmpeg4j.FfmpegException 因错误非零退出或超时时抛出。
     */
    RunResult await();

    /** 等价于 {@link #cancel(CancelMode) cancel(GRACEFUL)}。 */
    void cancel();

    /**
     * 取消本次执行。{@link CancelMode#GRACEFUL} 走优雅阶梯（写 q → SIGTERM → SIGKILL，stdin 被
     * 占用时降级 SIGTERM）；{@link CancelMode#FORCE} 跳过优雅收尾直接 destroy/destroyForcibly。
     * 取消动作在后台推进，本方法立即返回。
     */
    void cancel(CancelMode mode);

    /** 进程是否已结束。 */
    boolean isDone();
}
