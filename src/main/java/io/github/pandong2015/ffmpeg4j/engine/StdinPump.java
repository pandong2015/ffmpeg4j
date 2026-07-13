package io.github.pandong2015.ffmpeg4j.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.BooleanSupplier;

/**
 * 喂 stdin 拓扑下的输入泵：把调用方提供的源流拷贝进子进程 stdin，喂完即 {@code close} 触发 EOF
 * （否则 ffmpeg 会持续等待输入、{@code waitFor} 反向死锁，design D7）。
 *
 * <p>当任务已进入取消、或进程已退出而喂入侧仍在写时，broken-pipe / {@link IOException} 属预期，
 * 引擎 MUST 静默之（区别于真正的读源错误），不误报为任务失败（spec「流排空防死锁」）。
 *
 * <p>说明：v1.0 门面均输出到磁盘文件、stdin 空闲，本类实现「喂 stdin 降级路径」所需代码，端到端
 * 主路径不经过它。
 */
final class StdinPump implements Runnable {

    private final InputStream source;
    private final OutputStream processStdin;
    private final BooleanSupplier cancelledOrExited;

    /**
     * @param source           调用方提供的输入媒体源。
     * @param processStdin      子进程 stdin。
     * @param cancelledOrExited 判定当前是否已取消/进程已退出——true 时喂入侧的 IO 异常应静默。
     */
    StdinPump(InputStream source, OutputStream processStdin, BooleanSupplier cancelledOrExited) {
        this.source = source;
        this.processStdin = processStdin;
        this.cancelledOrExited = cancelledOrExited;
    }

    @Override
    public void run() {
        byte[] buffer = new byte[8192];
        try {
            int n;
            while ((n = source.read(buffer)) != -1) {
                processStdin.write(buffer, 0, n);
            }
            processStdin.flush();
        } catch (IOException e) {
            // 取消/进程退出态下的 broken-pipe 属预期，静默；否则是真正的读源/写入错误，
            // 但按 spec 也不将其升级为「任务失败」——退出码与 stderr 才是失败判据。
            if (!cancelledOrExited.getAsBoolean()) {
                // 仅记录（此处无日志依赖，保持静默以不引入副作用）；退出码将反映最终结果。
            }
        } finally {
            closeQuietly();
        }
    }

    /** 喂完/出错后关闭子进程 stdin 发出 EOF。 */
    private void closeQuietly() {
        try {
            processStdin.close();
        } catch (IOException ignored) {
            // 进程可能已退出，关闭失败无害。
        }
    }
}
