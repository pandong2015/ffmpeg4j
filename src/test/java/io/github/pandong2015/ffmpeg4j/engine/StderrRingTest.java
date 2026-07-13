package io.github.pandong2015.ffmpeg4j.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link StderrRing} 纯逻辑测试：有界留尾（约 50 行）、丢弃最旧、tail 拼接。 */
class StderrRingTest {

    @Test
    void 默认保留约50行留最新() {
        StderrRing ring = new StderrRing();
        for (int i = 1; i <= 120; i++) {
            ring.add("line-" + i);
        }
        assertEquals(50, ring.size(), "默认应保留 50 行");
        assertEquals("line-71", ring.lines().get(0), "最旧应为第 71 行");
        assertEquals("line-120", ring.lines().get(49), "最新应为第 120 行");
        assertTrue(ring.tail().endsWith("line-120"), "tail 末尾为最新行");
        assertFalse(ring.tail().contains("line-70"), "第 70 行已被挤出");
    }

    @Test
    void 未满时全部保留() {
        StderrRing ring = new StderrRing();
        ring.add("a");
        ring.add("b");
        assertEquals(2, ring.size());
        assertEquals("a\nb", ring.tail(), "两行以换行拼接");
    }

    @Test
    void 自定义容量() {
        StderrRing ring = new StderrRing(3);
        for (int i = 1; i <= 5; i++) {
            ring.add("x" + i);
        }
        assertEquals(3, ring.size());
        assertEquals("x3\nx4\nx5", ring.tail());
    }

    @Test
    void null行被忽略() {
        StderrRing ring = new StderrRing(3);
        ring.add(null);
        ring.add("ok");
        assertEquals(1, ring.size());
        assertEquals("ok", ring.tail());
    }

    @Test
    void 非法容量拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new StderrRing(0));
        assertThrows(IllegalArgumentException.class, () -> new StderrRing(-1));
    }
}
