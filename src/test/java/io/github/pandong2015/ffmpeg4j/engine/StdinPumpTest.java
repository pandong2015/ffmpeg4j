package io.github.pandong2015.ffmpeg4j.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * {@link StdinPump} 单测（无需 ffmpeg）：喂完即 close 触发 EOF；取消/退出态下 broken-pipe 静默。
 */
class StdinPumpTest {

    @Test
    void 喂完输入后关闭sink触发EOF() throws Exception {
        byte[] payload = "hello-ffmpeg-stdin".getBytes(StandardCharsets.UTF_8);
        // 用一个记录「是否被关闭」的内存 sink 模拟子进程 stdin。
        RecordingOutputStream sink = new RecordingOutputStream();
        StdinPump pump = new StdinPump(new ByteArrayInputStream(payload), sink, () -> false);

        Thread t = new Thread(pump, "stdin-pump-test");
        t.start();
        t.join(2000);

        assertArrayEquals(payload, sink.written(), "应把源全部写入 sink");
        assertEquals(1, sink.closeCount(), "喂完后应 close 一次以发出 EOF");
    }

    @Test
    void 取消或退出态下brokenPipe静默不抛() {
        byte[] payload = new byte[64_000];
        // sink 一写入即抛 IOException 模拟 broken-pipe；cancelledOrExited=true 表示应静默。
        OutputStream brokenSink = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("broken pipe");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("broken pipe");
            }
        };
        AtomicBoolean cancelled = new AtomicBoolean(true);
        StdinPump pump = new StdinPump(new ByteArrayInputStream(payload), brokenSink, cancelled::get);

        // run() 不应向上抛出任何异常（broken-pipe 被静默）。
        assertDoesNotThrow(pump::run, "取消/退出态下 broken-pipe 须静默");
    }

    /** 记录写入内容与 close 次数的内存 OutputStream。 */
    private static final class RecordingOutputStream extends OutputStream {
        private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        private int closeCount;

        @Override
        public synchronized void write(int b) {
            buf.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            buf.write(b, off, len);
        }

        @Override
        public synchronized void close() {
            closeCount++;
        }

        synchronized byte[] written() {
            return buf.toByteArray();
        }

        synchronized int closeCount() {
            return closeCount;
        }
    }
}
