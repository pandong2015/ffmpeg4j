package io.github.pandong2015.ffmpeg4j.engine;

import java.util.function.Consumer;

/**
 * pipe 进度通道：stdout 空闲时，进度经 {@code -progress pipe:1} 写到子进程 stdout，本通道即在专职
 * 线程里排空并逐行解析。此模式下 stdout 由本通道独占消费（引擎不再另设 stdout 丢弃泵）。
 */
final class PipeProgressChannel implements ProgressChannel {

    private volatile Thread reader;

    @Override
    public String progressArg() {
        return "pipe:1";
    }

    @Override
    public void start(Process process, Consumer<String> lineConsumer) {
        StreamPump pump = new StreamPump(process.getInputStream(), lineConsumer);
        Thread t = new Thread(pump, "ffmpeg-progress-pipe");
        t.setDaemon(true);
        this.reader = t;
        t.start();
    }

    @Override
    public void close() {
        // stdout 会随进程退出而 EOF，读线程自然结束；无需额外动作。
    }

    @Override
    public void awaitReaders(long millis) {
        Thread t = reader;
        if (t != null) {
            try {
                t.join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
