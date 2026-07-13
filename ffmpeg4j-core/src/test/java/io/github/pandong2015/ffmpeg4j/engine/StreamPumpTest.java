package io.github.pandong2015.ffmpeg4j.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link StreamPump} 纯逻辑测试（无需 ffmpeg）：重点锁定「消费者抛出任何东西都不得中断排空」这一
 * 防死锁硬约束——pipe 模式下进度 pump 线程是 stdout 的唯一排空者，其死亡会令子进程 write() 阻塞、
 * waitFor() 永久挂起。
 */
class StreamPumpTest {

    private static InputStream utf8(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 逐行排空全部读出() {
        List<String> seen = new ArrayList<>();
        new StreamPump(utf8("a\nb\nc\n"), seen::add).run();
        assertEquals(List.of("a", "b", "c"), seen, "应逐行读出全部内容");
    }

    @Test
    void 消费者抛出RuntimeException不中断排空() {
        List<String> seen = new ArrayList<>();
        StreamPump pump = new StreamPump(utf8("l1\nl2\nl3\n"), line -> {
            seen.add(line);
            if (line.equals("l1")) {
                throw new IllegalStateException("模拟回调抛 RuntimeException");
            }
        });
        assertDoesNotThrow(pump::run, "RuntimeException 不得逸出 run");
        assertEquals(List.of("l1", "l2", "l3"), seen, "异常后须继续读完余下行");
    }

    @Test
    void 消费者抛出Error也不中断排空_防死锁() {
        // finding: 用户进度回调抛出非 RuntimeException 的 Throwable（如 AssertionError）不得杀死排空线程。
        List<String> seen = new ArrayList<>();
        StreamPump pump = new StreamPump(utf8("l1\nl2\nl3\n"), line -> {
            seen.add(line);
            if (line.equals("l1")) {
                throw new AssertionError("模拟用户回调抛 Error");
            }
        });
        assertDoesNotThrow(pump::run, "Error 亦不得逸出 run，否则排空线程死亡→死锁");
        assertEquals(List.of("l1", "l2", "l3"), seen, "抛 Error 后仍须读完全部行");
    }

    @Test
    void 丢弃式排空不抛异常() {
        assertDoesNotThrow(() -> StreamPump.discarding(utf8("x\ny\n")).run());
    }
}
