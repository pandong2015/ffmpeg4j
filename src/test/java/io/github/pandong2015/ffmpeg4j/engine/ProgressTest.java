package io.github.pandong2015.ffmpeg4j.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** {@link Progress} 便捷访问器与缺失默认值的纯逻辑测试。 */
class ProgressTest {

    private static Progress of(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return new Progress(m);
    }

    @Test
    void 解析常见字段() {
        Progress p = of(
                "frame", "150",
                "fps", "30.00",
                "total_size", "123456",
                "out_time_us", "5000000",
                "out_time", "00:00:05.000000",
                "dup_frames", "1",
                "drop_frames", "2",
                "speed", "1.75x",
                "progress", "continue");
        assertEquals(150L, p.frame(), "frame 应解析");
        assertEquals(30.0, p.fps(), 1e-9, "fps 应解析");
        assertEquals(123456L, p.totalSize(), "total_size 应解析");
        assertEquals(5_000_000L, p.outTimeMicros(), "out_time_us 应优先");
        assertEquals(5000L, p.outTimeMillis(), "毫秒应为微秒/1000");
        assertEquals(1L, p.dupFrames());
        assertEquals(2L, p.dropFrames());
        assertEquals(1.75, p.speed(), 1e-9, "speed 去掉尾部 x");
        assertFalse(p.isEnd(), "continue 不是 end");
        assertEquals("continue", p.progressState());
    }

    @Test
    void out_time_ms在ffmpeg中实为微秒() {
        // 无 out_time_us，仅 out_time_ms：应按微秒解释。
        Progress p = of("out_time_ms", "2500000", "progress", "continue");
        assertEquals(2_500_000L, p.outTimeMicros(), "out_time_ms 按微秒解释");
        assertEquals(2500L, p.outTimeMillis());
    }

    @Test
    void 退回解析out_time文本() {
        Progress p = of("out_time", "00:01:02.500000", "progress", "continue");
        long expected = (62L * 1_000_000L) + 500_000L;
        assertEquals(expected, p.outTimeMicros(), "应从 HH:MM:SS.ffffff 解析");
    }

    @Test
    void 字段缺失给合理默认() {
        Progress p = Progress.empty();
        assertEquals(0L, p.frame(), "缺失 frame 默认 0");
        assertEquals(0.0, p.fps(), 1e-9, "缺失 fps 默认 0");
        assertEquals(-1L, p.outTimeMicros(), "缺失时间默认 -1");
        assertEquals(-1L, p.outTimeMillis(), "缺失时间默认 -1");
        assertEquals(-1L, p.totalSize(), "缺失/未知大小默认 -1");
        assertEquals(0.0, p.speed(), 1e-9, "缺失速度默认 0");
        assertFalse(p.isEnd(), "缺失 progress 非 end");
        assertNull(p.progressState());
        assertNull(p.get("frame"));
    }

    @Test
    void NA值不抛异常且给默认() {
        Progress p = of("total_size", "N/A", "speed", "N/A", "out_time", "N/A", "progress", "continue");
        assertEquals(-1L, p.totalSize(), "N/A 大小默认 -1");
        assertEquals(0.0, p.speed(), 1e-9, "N/A 速度默认 0");
        assertEquals(-1L, p.outTimeMicros(), "N/A 时间默认 -1");
    }

    @Test
    void isEnd识别收尾块() {
        assertTrue(of("progress", "end").isEnd(), "progress=end 即收尾");
    }

    @Test
    void 构造为不可变拷贝() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("frame", "1");
        Progress p = new Progress(m);
        m.put("frame", "999"); // 改动原 map 不应影响快照
        assertEquals(1L, p.frame(), "快照应与源 map 解耦");
        assertThrows(UnsupportedOperationException.class, () -> p.raw().put("x", "y"),
                "raw 应为不可变视图");
    }
}
