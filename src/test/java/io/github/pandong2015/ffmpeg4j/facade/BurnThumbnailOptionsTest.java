package io.github.pandong2015.ffmpeg4j.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Duration;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.github.pandong2015.ffmpeg4j.engine.Progress;

/**
 * {@link BurnSubtitlesOptions} 与 {@link ThumbnailOptions} 的不可变 wither 语义、默认值与 toRunOptions 映射。
 *
 * <p>聚焦 {@code FacadeOptionsTest} 未覆盖的 wither（forceStyle/videoCodec/audioCodec/onProgress/timeout、
 * width/height/quality）：每个 wither 都返回新副本且不修改原对象；纯值对象，无需启动 ffmpeg。
 */
class BurnThumbnailOptionsTest {

    // ---------- BurnSubtitlesOptions ----------

    @Test
    void burnForceStyle以wither设置且原对象不变() {
        BurnSubtitlesOptions base = BurnSubtitlesOptions.defaults();
        BurnSubtitlesOptions derived = base.forceStyle("FontName=Arial,FontSize=24");

        assertNull(base.forceStyle(), "原对象不应被修改（不可变），forceStyle 仍为默认 null");
        assertEquals("FontName=Arial,FontSize=24", derived.forceStyle());
        // 其余字段应从原对象原样承继
        assertEquals("libx264", derived.videoCodec(), "forceStyle wither 不应触碰 videoCodec");
        assertEquals("copy", derived.audioCodec(), "forceStyle wither 不应触碰 audioCodec");
    }

    @Test
    void burnVideoCodec以wither设置且原对象不变() {
        BurnSubtitlesOptions base = BurnSubtitlesOptions.defaults();
        BurnSubtitlesOptions derived = base.videoCodec("libx265");

        assertEquals("libx264", base.videoCodec(), "原对象 videoCodec 仍应为默认 libx264");
        assertEquals("libx265", derived.videoCodec());
        assertEquals("copy", derived.audioCodec(), "videoCodec wither 不应改动 audioCodec");
    }

    @Test
    void burnAudioCodec以wither设置且原对象不变() {
        BurnSubtitlesOptions base = BurnSubtitlesOptions.defaults();
        BurnSubtitlesOptions derived = base.audioCodec("aac");

        assertEquals("copy", base.audioCodec(), "原对象 audioCodec 仍应为默认 copy");
        assertEquals("aac", derived.audioCodec());
        assertEquals("libx264", derived.videoCodec(), "audioCodec wither 不应改动 videoCodec");
    }

    @Test
    void burnOnProgress以wither设置且保留回调身份() {
        BurnSubtitlesOptions base = BurnSubtitlesOptions.defaults();
        Consumer<Progress> cb = p -> { };
        BurnSubtitlesOptions derived = base.onProgress(cb);

        assertNull(base.onProgress(), "原对象 onProgress 仍应为 null（不可变）");
        assertSame(cb, derived.onProgress(), "wither 应原样保存传入的回调引用");
    }

    @Test
    void burnTimeout以wither设置且原对象不变() {
        BurnSubtitlesOptions base = BurnSubtitlesOptions.defaults();
        BurnSubtitlesOptions derived = base.timeout(Duration.ofSeconds(90));

        assertNull(base.timeout(), "原对象 timeout 仍应为 null（不可变）");
        assertEquals(Duration.ofSeconds(90), derived.timeout());
    }

    @Test
    void burnTimeout映射进runOptions() {
        BurnSubtitlesOptions o = BurnSubtitlesOptions.defaults().timeout(Duration.ofSeconds(45));
        assertEquals(Duration.ofSeconds(45), o.toRunOptions().timeout(), "timeout 应透传进 RunOptions");
        assertNull(BurnSubtitlesOptions.defaults().toRunOptions().timeout(), "缺省无 timeout 时 RunOptions 亦为 null");
    }

    @Test
    void burn链式wither叠加互不覆盖() {
        BurnSubtitlesOptions o = BurnSubtitlesOptions.defaults()
                .forceStyle("Bold=1")
                .videoCodec("libx265")
                .audioCodec("aac")
                .timeout(Duration.ofSeconds(10));

        assertEquals("Bold=1", o.forceStyle());
        assertEquals("libx265", o.videoCodec());
        assertEquals("aac", o.audioCodec());
        assertEquals(Duration.ofSeconds(10), o.timeout());
    }

    // ---------- ThumbnailOptions ----------

    @Test
    void thumbnail默认全为null() {
        ThumbnailOptions base = ThumbnailOptions.defaults();
        assertNull(base.width(), "默认无缩放宽");
        assertNull(base.height(), "默认无缩放高");
        assertNull(base.quality(), "默认无 -q:v 质量");
        assertNull(base.onProgress());
        assertNull(base.timeout());
    }

    @Test
    void thumbnailWidth以wither设置且原对象不变() {
        ThumbnailOptions base = ThumbnailOptions.defaults();
        ThumbnailOptions derived = base.width(320);

        assertNull(base.width(), "原对象 width 仍应为 null（不可变）");
        assertEquals(320, derived.width());
        assertNull(derived.height(), "只设 width 时 height 仍为 null，交由 ffmpeg 按比例推导");
    }

    @Test
    void thumbnailHeight以wither设置且原对象不变() {
        ThumbnailOptions base = ThumbnailOptions.defaults();
        ThumbnailOptions derived = base.height(240);

        assertNull(base.height(), "原对象 height 仍应为 null（不可变）");
        assertEquals(240, derived.height());
        assertNull(derived.width(), "只设 height 时 width 仍为 null");
    }

    @Test
    void thumbnailQuality以wither设置且原对象不变() {
        ThumbnailOptions base = ThumbnailOptions.defaults();
        ThumbnailOptions derived = base.quality(2);

        assertNull(base.quality(), "原对象 quality 仍应为 null（不可变）");
        assertEquals(2, derived.quality());
    }

    @Test
    void thumbnailOnProgress以wither设置且保留回调身份() {
        ThumbnailOptions base = ThumbnailOptions.defaults();
        Consumer<Progress> cb = p -> { };
        ThumbnailOptions derived = base.onProgress(cb);

        assertNull(base.onProgress(), "原对象 onProgress 仍应为 null（不可变）");
        assertSame(cb, derived.onProgress(), "wither 应原样保存传入的回调引用");
    }

    @Test
    void thumbnailTimeout以wither设置且原对象不变() {
        ThumbnailOptions base = ThumbnailOptions.defaults();
        ThumbnailOptions derived = base.timeout(Duration.ofSeconds(15));

        assertNull(base.timeout(), "原对象 timeout 仍应为 null（不可变）");
        assertEquals(Duration.ofSeconds(15), derived.timeout());
    }

    @Test
    void thumbnailTimeout映射进runOptions() {
        ThumbnailOptions o = ThumbnailOptions.defaults().timeout(Duration.ofSeconds(20));
        assertEquals(Duration.ofSeconds(20), o.toRunOptions().timeout(), "timeout 应透传进 RunOptions");
        assertNull(ThumbnailOptions.defaults().toRunOptions().timeout(), "缺省无 timeout 时 RunOptions 亦为 null");
    }

    @Test
    void thumbnail链式wither叠加互不覆盖() {
        ThumbnailOptions o = ThumbnailOptions.defaults()
                .width(640)
                .height(-1)
                .quality(3)
                .timeout(Duration.ofSeconds(5));

        assertEquals(640, o.width());
        assertEquals(-1, o.height(), "另一维给 -1 让 ffmpeg 按比例推导");
        assertEquals(3, o.quality());
        assertEquals(Duration.ofSeconds(5), o.timeout());
    }
}
