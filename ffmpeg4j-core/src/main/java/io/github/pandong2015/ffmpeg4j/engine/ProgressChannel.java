package io.github.pandong2015.ffmpeg4j.engine;

import io.github.pandong2015.ffmpeg4j.task.FfmpegWarning;
import io.github.pandong2015.ffmpeg4j.task.TaskWarningCollector;
import io.github.pandong2015.ffmpeg4j.task.WarningCode;

import java.io.Closeable;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * 进度采集通道抽象。依 IO 拓扑自适应：stdout 空闲时走 {@code -progress pipe:1}（读子进程 stdout），
 * stdout 传媒体时走 {@code -progress tcp://127.0.0.1:<port>}（本地 {@code ServerSocket} 接进度）。
 *
 * <p>生命周期：先 {@link #progressArg()} 取注入参数（tcp 模式须 bind-before-spawn，故绑定发生在
 * 通道构造时），进程启动后 {@link #start(Process, Consumer)} 开始消费，进程退出（{@code waitFor}
 * 返回）后 {@link #close()} 释放资源、随后 {@link #awaitReaders(long)} 汇合读线程。
 */
interface ProgressChannel extends Closeable {

    System.Logger LOG = System.getLogger(ProgressChannel.class.getName());

    /**
     * 要注入到 {@code -progress} 之后的参数（如 {@code pipe:1}、{@code tcp://127.0.0.1:1234}）；
     * 返回 {@code null} 表示本次不注入 {@code -progress}（如 tcp 绑定失败降级为无进度）。
     */
    String progressArg();

    /** 开始消费进度；每解析出一行交给 {@code lineConsumer}。 */
    void start(Process process, Consumer<String> lineConsumer);

    /** 释放通道资源（幂等）；进程退出后调用以解开可能阻塞的 accept/read。 */
    @Override
    void close();

    /** 汇合内部读线程，最多等待 {@code millis} 毫秒。 */
    void awaitReaders(long millis);

    /**
     * 依拓扑选择通道。stdout 传媒体需 tcp；tcp 绑定失败属库内部管道故障（5.10），
     * MUST NOT 外泄为媒体错误——此处记录并降级为「无进度」。
     */
    static ProgressChannel forTopology(IoTopology topology) {
        return forTopology(topology, TcpProgressChannel::new);
    }

    /**
     * 依拓扑选择通道，并允许测试注入 tcp 通道工厂，避免测试环境必须具备端口绑定权限。
     */
    static ProgressChannel forTopology(IoTopology topology, TcpChannelFactory tcpFactory) {
        if (!topology.stdoutMedia()) {
            return new PipeProgressChannel();
        }
        try {
            return tcpFactory.create();
        } catch (IOException e) {
            TaskWarningCollector.add(new FfmpegWarning(
                    WarningCode.PROGRESS_UNAVAILABLE,
                    "进度 TCP 通道不可用，任务将继续但不再提供进度",
                    java.util.Map.of("reason", e.getClass().getSimpleName())));
            LOG.log(System.Logger.Level.WARNING,
                    "进度 tcp 端口绑定失败，降级为无进度（内部管道故障，不外泄为媒体错误）", e);
            return NoProgressChannel.INSTANCE;
        }
    }

    /** 创建已完成 bind-before-spawn 的 tcp 进度通道。 */
    @FunctionalInterface
    interface TcpChannelFactory {
        ProgressChannel create() throws IOException;
    }

    /** 无进度通道：不注入 {@code -progress}，全部为空操作（tcp 绑定失败时的降级）。 */
    final class NoProgressChannel implements ProgressChannel {
        static final NoProgressChannel INSTANCE = new NoProgressChannel();

        private NoProgressChannel() {
        }

        @Override
        public String progressArg() {
            return null;
        }

        @Override
        public void start(Process process, Consumer<String> lineConsumer) {
        }

        @Override
        public void close() {
        }

        @Override
        public void awaitReaders(long millis) {
        }
    }
}
