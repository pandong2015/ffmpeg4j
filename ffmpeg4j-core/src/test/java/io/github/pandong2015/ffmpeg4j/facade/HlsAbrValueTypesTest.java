package io.github.pandong2015.ffmpeg4j.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * ABR 值类型的脱进程单测：{@link HlsVariant} wither/校验/派生、{@link HlsLadder} 默认梯与源高度裁剪、
 * {@link HlsAbrOptions} 默认/wither/哨兵。
 */
class HlsAbrValueTypesTest {

    // ===== HlsVariant =====

    @Test
    void 变体工厂与默认值() {
        HlsVariant v = HlsVariant.of(720, "3000k");
        assertEquals(720, v.height());
        assertNull(v.width(), "width 默认 null（→ scale=-2:h）");
        assertEquals("3000k", v.videoBitrate());
        assertEquals("128k", v.audioBitrate());
        assertEquals("libx264", v.videoCodec());
        assertEquals("aac", v.audioCodec());
        assertNull(v.name(), "name 默认 null（→ 目录数字索引）");
        assertNull(v.crf());
        assertNull(v.preset());
    }

    @Test
    void 变体派生maxrate与bufsize() {
        assertEquals("5350k", HlsVariant.of(1080, "5000k").effectiveMaxrate());
        assertEquals("7500k", HlsVariant.of(1080, "5000k").effectiveBufsize());
        assertEquals("856k", HlsVariant.of(360, "800k").effectiveMaxrate());
        assertEquals("1200k", HlsVariant.of(360, "800k").effectiveBufsize());
    }

    @Test
    void 变体显式maxrate覆盖派生() {
        HlsVariant v = HlsVariant.of(720, "3000k").maxrate("4000k").bufsize("6000k");
        assertEquals("4000k", v.effectiveMaxrate());
        assertEquals("6000k", v.effectiveBufsize());
    }

    @Test
    void 变体wither不可变() {
        HlsVariant base = HlsVariant.of(720, "3000k");
        HlsVariant withName = base.name("hd").width(1280).crf(23).preset("fast");
        assertNull(base.name(), "原对象不被修改");
        assertEquals("hd", withName.name());
        assertEquals(Integer.valueOf(1280), withName.width());
        assertEquals(Integer.valueOf(23), withName.crf());
        assertEquals("fast", withName.preset());
    }

    @Test
    void 变体非法参数即时报错() {
        assertThrows(IllegalArgumentException.class, () -> HlsVariant.of(0, "3000k"));
        assertThrows(IllegalArgumentException.class, () -> HlsVariant.of(-1, "3000k"));
        assertThrows(IllegalArgumentException.class, () -> HlsVariant.of(720, "abc"));
        assertThrows(IllegalArgumentException.class, () -> HlsVariant.of(720, "3000k").width(0));
    }

    @Test
    void 变体非法name拦截var_stream_map元字符与百分v() {
        HlsVariant v = HlsVariant.of(720, "3000k");
        assertThrows(IllegalArgumentException.class, () -> v.name("has space"));
        assertThrows(IllegalArgumentException.class, () -> v.name("a,b"));
        assertThrows(IllegalArgumentException.class, () -> v.name("a:b"));
        assertThrows(IllegalArgumentException.class, () -> v.name("a/b"));
        assertThrows(IllegalArgumentException.class, () -> v.name("a\\b"));
        assertThrows(IllegalArgumentException.class, () -> v.name("stream_%v"));
    }

    // ===== HlsLadder =====

    @Test
    void 默认梯档位与派生值精确() {
        List<HlsVariant> d = HlsLadder.defaults();
        assertEquals(4, d.size());
        assertEquals(List.of(1080, 720, 480, 360), d.stream().map(HlsVariant::height).toList());
        assertEquals(List.of("5000k", "3000k", "1500k", "800k"),
                d.stream().map(HlsVariant::videoBitrate).toList());
        assertEquals("3210k", d.get(1).effectiveMaxrate());
        assertEquals("4500k", d.get(1).effectiveBufsize());
        assertEquals("1605k", d.get(2).effectiveMaxrate());
        assertEquals("2250k", d.get(2).effectiveBufsize());
    }

    @Test
    void 源高度裁剪剔除放大档() {
        assertEquals(List.of(720, 480, 360),
                HlsLadder.cropToSourceHeight(720).stream().map(HlsVariant::height).toList());
        assertEquals(List.of(1080, 720, 480, 360),
                HlsLadder.cropToSourceHeight(1080).stream().map(HlsVariant::height).toList());
        assertEquals(List.of(1080, 720, 480, 360),
                HlsLadder.cropToSourceHeight(2160).stream().map(HlsVariant::height).toList());
    }

    @Test
    void 极小源兜底单档取偶不放大() {
        List<HlsVariant> tiny = HlsLadder.cropToSourceHeight(240);
        assertEquals(1, tiny.size());
        assertEquals(240, tiny.get(0).height());
        assertEquals("800k", tiny.get(0).videoBitrate(), "复用最低档码率");

        List<HlsVariant> odd = HlsLadder.cropToSourceHeight(241);
        assertEquals(240, odd.get(0).height(), "取偶");
    }

    // ===== HlsAbrOptions =====

    @Test
    void 选项默认值与哨兵() {
        HlsAbrOptions o = HlsAbrOptions.defaults();
        assertEquals(6.0, o.hlsTime());
        assertEquals("128k", o.audioBitrate());
        assertEquals("master.m3u8", o.masterPlaylistName());
        assertEquals("seg_%d.ts", o.segmentTemplate());
        assertTrue(o.sharedAudio());
        assertFalse(o.variantsExplicit(), "默认未显式设梯");
        assertNull(o.variantsOrNull());
        assertEquals(4, o.variants().size(), "访问器在未设时返回默认梯视图");
    }

    @Test
    void 选项wither不可变与显式梯哨兵() {
        HlsAbrOptions base = HlsAbrOptions.defaults();
        HlsAbrOptions custom = base.variants(List.of(HlsVariant.of(720, "3000k"))).hlsTime(4.0).sharedAudio(false);
        assertEquals(6.0, base.hlsTime(), "原对象不变");
        assertFalse(base.variantsExplicit());
        assertEquals(4.0, custom.hlsTime());
        assertTrue(custom.variantsExplicit());
        assertFalse(custom.sharedAudio());
        assertEquals(1, custom.variants().size());
    }

    @Test
    void 选项非法参数即时报错() {
        HlsAbrOptions o = HlsAbrOptions.defaults();
        assertThrows(IllegalArgumentException.class, () -> o.hlsTime(0));
        assertThrows(IllegalArgumentException.class, () -> o.segmentTemplate("seg.ts"));
        assertThrows(IllegalArgumentException.class, () -> o.masterPlaylistName("a/b"));
        assertThrows(IllegalArgumentException.class, () -> o.startNumber(-1));
        assertThrows(IllegalArgumentException.class, () -> o.variants(List.of()));
    }
}
