package io.github.pandong2015.ffmpeg4j.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** HlsOptions：不可变 wither 与 build 期 fail-fast 校验（脱进程）。 */
class HlsOptionsTest {

    @Test
    void 默认值() {
        HlsOptions o = HlsOptions.defaults();
        assertEquals(8.0, o.hlsTime());
        assertEquals("index.m3u8", o.playlistName());
        assertEquals("ts", o.segmentDir());
        assertEquals("index%d.ts", o.segmentTemplate());
        assertEquals("key", o.keyDir());
        assertEquals("enc.key", o.keyFileName());
        assertEquals("copy", o.videoCodec());
        assertEquals("copy", o.audioCodec());
        assertNull(o.key());
        assertFalse(o.alignKeyframes());
        assertNull(o.segmentUriPrefix());
        assertTrue(o.extraOutputArgs().isEmpty());
    }

    @Test
    void wither返回新副本原实例不变() {
        HlsOptions base = HlsOptions.defaults();
        HlsOptions changed = base.hlsTime(6.0).segmentDir("v0").alignKeyframes(true);
        assertEquals(8.0, base.hlsTime(), "原实例不变");
        assertEquals("ts", base.segmentDir());
        assertFalse(base.alignKeyframes());
        assertEquals(6.0, changed.hlsTime());
        assertEquals("v0", changed.segmentDir());
        assertTrue(changed.alignKeyframes());
    }

    @Test
    void hlsTime非正数即抛() {
        assertThrows(IllegalArgumentException.class, () -> HlsOptions.defaults().hlsTime(0));
        assertThrows(IllegalArgumentException.class, () -> HlsOptions.defaults().hlsTime(-1));
    }

    @Test
    void segmentTemplate须含序号占位符() {
        assertThrows(IllegalArgumentException.class, () -> HlsOptions.defaults().segmentTemplate("segment.ts"));
        // 合法：%d 与零填充 %0Nd
        HlsOptions.defaults().segmentTemplate("seg%d.ts");
        HlsOptions.defaults().segmentTemplate("seg_%05d.ts");
    }

    @Test
    void 子目录名不得含路径分隔符() {
        assertThrows(IllegalArgumentException.class, () -> HlsOptions.defaults().segmentDir("a/b"));
        assertThrows(IllegalArgumentException.class, () -> HlsOptions.defaults().keyDir("a\\b"));
        assertThrows(IllegalArgumentException.class, () -> HlsOptions.defaults().segmentDir(""));
    }

    @Test
    void startNumber须非负() {
        assertThrows(IllegalArgumentException.class, () -> HlsOptions.defaults().startNumber(-1));
        assertEquals(3, HlsOptions.defaults().startNumber(3).startNumber());
    }
}
