package io.github.pandong2015.ffmpeg4j.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.github.pandong2015.ffmpeg4j.model.AudioNormTarget;

/**
 * Options 的不可变 wither 语义与默认值锁定（纯值，无需 ffmpeg）。
 */
class FacadeOptionsTest {

    @Test
    void transcode默认值与wither不修改原对象() {
        TranscodeOptions base = TranscodeOptions.defaults();
        assertEquals("libx264", base.videoCodec());
        assertEquals("aac", base.audioCodec());
        assertNull(base.crf());

        TranscodeOptions derived = base.crf(20).preset("slow");
        assertNull(base.crf(), "原对象不应被修改（不可变）");
        assertEquals(20, derived.crf());
        assertEquals("slow", derived.preset());
    }

    @Test
    void clip默认快切且精切编解码器缺省() {
        ClipOptions base = ClipOptions.defaults();
        assertFalse(base.reencode(), "默认应为快切 copy");
        assertEquals("libx264", base.videoCodec());
        assertEquals("aac", base.audioCodec());
        assertTrue(base.reencode(true).reencode());
    }

    @Test
    void concat默认策略为注入且可设音频目标() {
        ConcatOptions base = ConcatOptions.defaults();
        assertEquals(ConcatOptions.OnMissingStream.INJECT_SILENCE_OR_BLANK, base.onMissingStream());
        assertNull(base.audioTarget());
        AudioNormTarget at = AudioNormTarget.stereo48k();
        assertSame(at, base.audioTarget(at).audioTarget());
    }

    @Test
    void burnSubtitles默认视频重编码音频copy() {
        BurnSubtitlesOptions base = BurnSubtitlesOptions.defaults();
        assertEquals("libx264", base.videoCodec());
        assertEquals("copy", base.audioCodec());
        assertNull(base.forceStyle());
    }

    @Test
    void timeout映射进runOptions() {
        TranscodeOptions o = TranscodeOptions.defaults().timeout(Duration.ofSeconds(30));
        assertEquals(Duration.ofSeconds(30), o.toRunOptions().timeout());
        assertNull(TranscodeOptions.defaults().toRunOptions().timeout());
    }
}
