package io.github.pandong2015.ffmpeg4j.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 通用「排空」Runnable：在专职线程里逐行读取子进程的一路输出并交给消费者，持续消费直到流结束，
 * 以避免管道缓冲区写满导致子进程 {@code write()} 阻塞、进而 {@code waitFor()} 永久挂起（design D7）。
 *
 * <p>用途：stderr（→ {@link StderrRing}）、pipe 进度（→ 解析）、tcp 模式下 stdout 媒体的丢弃排空。
 * {@link BufferedReader#readLine()} 会把 {@code \r}/{@code \n}/{@code \r\n} 均视作行分隔，故 ffmpeg
 * 用 {@code \r} 刷新的 stderr 状态行也会被逐段读出——对留尾/丢弃均无害。
 */
final class StreamPump implements Runnable {

    private final InputStream in;
    private final Consumer<String> lineConsumer;

    StreamPump(InputStream in, Consumer<String> lineConsumer) {
        this.in = in;
        this.lineConsumer = lineConsumer;
    }

    /** 丢弃式排空（仅防死锁、不关心内容），用于 tcp 模式下 stdout 传出的媒体。 */
    static StreamPump discarding(InputStream in) {
        return new StreamPump(in, line -> {
        });
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    lineConsumer.accept(line);
                } catch (Throwable consumerError) {
                    // 消费者抛出任何东西（含 Error，如用户进度回调里的 AssertionError/StackOverflowError）
                    // 都不得中断排空——排空线程的存活是防死锁的硬约束，不能依赖回调只抛 RuntimeException；
                    // 吞掉继续读，否则管道缓冲写满后子进程 write() 阻塞、waitFor() 永久挂起。
                }
            }
        } catch (IOException e) {
            // 流被关闭 / 进程已退出：正常收尾，静默。
        }
    }
}
