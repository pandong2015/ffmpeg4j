package io.github.pandong2015.ffmpeg4j.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * GifOptions 的默认值、校验与不可变 wither 语义锁定（纯值，无需 ffmpeg）。
 */
class GifOptionsTest {

    @Test
    void 默认值对齐type3() {
        GifOptions o = GifOptions.defaults();
        assertEquals(0.0, o.start(), 1e-9);
        assertEquals(15.0, o.fps(), 1e-9);
        assertNull(o.duration(), "默认无时长（到片尾）");
        assertNull(o.width(), "默认无 width（不加 scale）");
        assertNull(o.height());
        assertNull(o.scaleFlags(), "默认无 scaleFlags（与 type3 逐字节等价）");
    }

    @Test
    void wither返回新副本且不改原对象() {
        GifOptions base = GifOptions.defaults();
        GifOptions derived = base.fps(10).width(320).duration(1.5);
        assertNotSame(base, derived);
        assertEquals(15.0, base.fps(), 1e-9, "原对象不应被修改");
        assertNull(base.width());
        assertEquals(10.0, derived.fps(), 1e-9);
        assertEquals(320, derived.width());
        assertEquals(1.5, derived.duration(), 1e-9);
    }

    @Test
    void 非正参数在构造期抛异常() {
        assertThrows(IllegalArgumentException.class, () -> GifOptions.defaults().fps(0));
        assertThrows(IllegalArgumentException.class, () -> GifOptions.defaults().duration(0));
        assertThrows(IllegalArgumentException.class, () -> GifOptions.defaults().duration(-1));
        assertThrows(IllegalArgumentException.class, () -> GifOptions.defaults().width(0));
        assertThrows(IllegalArgumentException.class, () -> GifOptions.defaults().height(-2));
    }
}
