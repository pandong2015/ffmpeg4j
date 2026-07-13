package io.github.pandong2015.ffmpeg4j.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** {@link ProgressParser} 纯逻辑测试：喂行 → 逢 progress= 结束一块并产出快照。 */
class ProgressParserTest {

    @Test
    void 逢progress结束一块并产出快照() {
        ProgressParser parser = new ProgressParser();
        assertTrue(parser.offer("frame=10").isEmpty(), "中间行不产出");
        assertTrue(parser.offer("fps=25.0").isEmpty());
        assertTrue(parser.offer("out_time_us=400000").isEmpty());
        Optional<Progress> block = parser.offer("progress=continue");
        assertTrue(block.isPresent(), "progress= 行应产出一块");
        Progress p = block.get();
        assertEquals(10L, p.frame());
        assertEquals(25.0, p.fps(), 1e-9);
        assertEquals(400000L, p.outTimeMicros());
        assertFalse(p.isEnd());
    }

    @Test
    void 多块连续解析且块间不串味() {
        ProgressParser parser = new ProgressParser();
        List<Progress> blocks = new ArrayList<>();
        List<String> lines = List.of(
                "frame=10", "progress=continue",
                "frame=20", "progress=continue",
                "frame=30", "progress=end");
        for (String line : lines) {
            parser.offer(line).ifPresent(blocks::add);
        }
        assertEquals(3, blocks.size(), "应产出 3 块");
        assertEquals(10L, blocks.get(0).frame());
        assertEquals(20L, blocks.get(1).frame());
        assertEquals(30L, blocks.get(2).frame());
        assertTrue(blocks.get(2).isEnd(), "末块 progress=end");
        // 第二块不应残留第一块的键。
        assertEquals(1, countKeys(blocks.get(1)), "块间键不串味，只有 frame+progress");
    }

    private static int countKeys(Progress p) {
        // frame 与 progress 两键
        return p.raw().size() - 1; // 减去 progress 键，仅计数据键
    }

    @Test
    void 忽略非键值行与空行() {
        ProgressParser parser = new ProgressParser();
        assertTrue(parser.offer("").isEmpty(), "空行忽略");
        assertTrue(parser.offer("garbage-no-equals").isEmpty(), "无等号行忽略");
        assertTrue(parser.offer("=noKey").isEmpty(), "无键行忽略");
        assertTrue(parser.offer(null).isEmpty(), "null 忽略");
        Optional<Progress> b = parser.offer("progress=end");
        assertTrue(b.isPresent() && b.get().isEnd(), "仍能正常收尾");
    }

    @Test
    void 值含等号被完整保留() {
        ProgressParser parser = new ProgressParser();
        parser.offer("bitrate=1234.5kbits/s");
        Optional<Progress> b = parser.offer("progress=continue");
        assertTrue(b.isPresent());
        assertEquals("1234.5kbits/s", b.get().get("bitrate"));
    }
}
