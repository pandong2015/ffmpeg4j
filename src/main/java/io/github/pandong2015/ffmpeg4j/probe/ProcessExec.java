package io.github.pandong2015.ffmpeg4j.probe;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 极简、短生命周期的子进程执行助手（仅供 probe 切片内部使用）。
 *
 * <p>ffprobe 调用非常轻量，无需 L1 引擎；这里只负责启动进程、并发抽干 stdout/stderr
 * 以避免管道缓冲区写满导致的死锁，并回收退出码。
 */
final class ProcessExec {

    private ProcessExec() {
    }

    /** 进程执行结果：退出码、标准输出全文、标准错误全文（均按 UTF-8 解码）。 */
    record Result(int exitCode, String stdout, String stderr) {
    }

    static Result run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).start();
        // 不向子进程写入任何内容，立即关闭其 stdin。
        process.getOutputStream().close();

        StreamCollector out = new StreamCollector(process.getInputStream());
        StreamCollector err = new StreamCollector(process.getErrorStream());
        Thread outThread = new Thread(out, "ffprobe-stdout");
        Thread errThread = new Thread(err, "ffprobe-stderr");
        outThread.start();
        errThread.start();

        int exitCode = process.waitFor();
        outThread.join();
        errThread.join();

        return new Result(exitCode, out.text(), err.text());
    }

    /** 在独立线程里把一个流全部读入内存并按 UTF-8 解码。 */
    private static final class StreamCollector implements Runnable {
        private final InputStream stream;
        private volatile String text = "";

        StreamCollector(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            try (InputStream in = stream) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                // 抽干失败仅影响诊断文本，不应打断退出码回收；保留已读到的部分。
            }
        }

        String text() {
            return text;
        }
    }
}
