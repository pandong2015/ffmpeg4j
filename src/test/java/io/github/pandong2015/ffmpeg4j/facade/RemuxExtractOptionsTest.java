package io.github.pandong2015.ffmpeg4j.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Duration;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.github.pandong2015.ffmpeg4j.engine.Progress;

/**
 * RemuxOptions / ExtractAudioOptions 的默认值与不可变 wither 语义锁定（纯值，无需 ffmpeg）。
 * 两类结构对称：仅暴露执行侧的 onProgress/timeout，默认均为 null。
 */
class RemuxExtractOptionsTest {

    // ---------- RemuxOptions ----------

    @Test
    void remux默认值onProgress与timeout均为null() {
        RemuxOptions base = RemuxOptions.defaults();
        assertNull(base.onProgress(), "换容器无编解码器可调，回调默认应缺省");
        assertNull(base.timeout(), "默认无超时");
    }

    @Test
    void remuxOnProgress返回新副本且不修改原对象() {
        RemuxOptions base = RemuxOptions.defaults();
        Consumer<Progress> cb = p -> {
        };
        RemuxOptions derived = base.onProgress(cb);

        assertNotSame(base, derived, "wither 必须返回新副本，不能原地修改");
        assertNull(base.onProgress(), "原对象不应被修改（不可变）");
        assertSame(cb, derived.onProgress(), "新副本应携带传入的回调引用");
        assertNull(derived.timeout(), "onProgress 不应影响未设置的 timeout");
    }

    @Test
    void remuxTimeout返回新副本且不修改原对象() {
        RemuxOptions base = RemuxOptions.defaults();
        Duration t = Duration.ofSeconds(30);
        RemuxOptions derived = base.timeout(t);

        assertNotSame(base, derived, "wither 必须返回新副本，不能原地修改");
        assertNull(base.timeout(), "原对象不应被修改（不可变）");
        assertSame(t, derived.timeout(), "新副本应携带传入的 timeout");
        assertNull(derived.onProgress(), "timeout 不应影响未设置的 onProgress");
    }

    @Test
    void remux链式设置同时保留两字段() {
        Consumer<Progress> cb = p -> {
        };
        Duration t = Duration.ofSeconds(5);
        RemuxOptions derived = RemuxOptions.defaults().onProgress(cb).timeout(t);

        assertSame(cb, derived.onProgress(), "链式设置后 onProgress 应保留");
        assertSame(t, derived.timeout(), "链式设置后 timeout 应保留");
    }

    @Test
    void remuxTimeout映射进runOptions() {
        RemuxOptions o = RemuxOptions.defaults().timeout(Duration.ofSeconds(30));
        assertEquals(Duration.ofSeconds(30), o.timeout());
        assertNull(RemuxOptions.defaults().toRunOptions().timeout(), "默认无超时应映射为 null");
    }

    // ---------- ExtractAudioOptions ----------

    @Test
    void extract默认值onProgress与timeout均为null() {
        ExtractAudioOptions base = ExtractAudioOptions.defaults();
        assertNull(base.onProgress(), "编解码器由扩展名推导，本类回调默认应缺省");
        assertNull(base.timeout(), "默认无超时");
    }

    @Test
    void extractOnProgress返回新副本且不修改原对象() {
        ExtractAudioOptions base = ExtractAudioOptions.defaults();
        Consumer<Progress> cb = p -> {
        };
        ExtractAudioOptions derived = base.onProgress(cb);

        assertNotSame(base, derived, "wither 必须返回新副本，不能原地修改");
        assertNull(base.onProgress(), "原对象不应被修改（不可变）");
        assertSame(cb, derived.onProgress(), "新副本应携带传入的回调引用");
        assertNull(derived.timeout(), "onProgress 不应影响未设置的 timeout");
    }

    @Test
    void extractTimeout返回新副本且不修改原对象() {
        ExtractAudioOptions base = ExtractAudioOptions.defaults();
        Duration t = Duration.ofSeconds(30);
        ExtractAudioOptions derived = base.timeout(t);

        assertNotSame(base, derived, "wither 必须返回新副本，不能原地修改");
        assertNull(base.timeout(), "原对象不应被修改（不可变）");
        assertSame(t, derived.timeout(), "新副本应携带传入的 timeout");
        assertNull(derived.onProgress(), "timeout 不应影响未设置的 onProgress");
    }

    @Test
    void extract链式设置同时保留两字段() {
        Consumer<Progress> cb = p -> {
        };
        Duration t = Duration.ofSeconds(5);
        ExtractAudioOptions derived = ExtractAudioOptions.defaults().onProgress(cb).timeout(t);

        assertSame(cb, derived.onProgress(), "链式设置后 onProgress 应保留");
        assertSame(t, derived.timeout(), "链式设置后 timeout 应保留");
    }

    @Test
    void extractTimeout映射进runOptions() {
        ExtractAudioOptions o = ExtractAudioOptions.defaults().timeout(Duration.ofSeconds(30));
        assertEquals(Duration.ofSeconds(30), o.timeout());
        assertNull(ExtractAudioOptions.defaults().toRunOptions().timeout(), "默认无超时应映射为 null");
    }
}
