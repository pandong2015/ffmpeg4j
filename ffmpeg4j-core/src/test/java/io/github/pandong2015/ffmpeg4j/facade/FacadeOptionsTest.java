package io.github.pandong2015.ffmpeg4j.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

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
    void transcode新增码控字段默认与wither() {
        TranscodeOptions base = TranscodeOptions.defaults();
        assertNull(base.videoFilter());
        assertNull(base.fps());
        assertNull(base.maxrate());
        assertNull(base.bufsize());
        assertNull(base.gop());
        assertEquals(List.of(), base.extraOutputArgs(), "extraOutputArgs 默认为空 List");

        TranscodeOptions o = base.fps(25).maxrate("2M").bufsize("4M").gop(50)
                .extraOutputArgs("-x265-params", "vbv-maxrate=2000")
                .videoFilter(v -> v);
        assertNull(base.fps(), "原对象不应被修改");
        assertEquals(25.0, o.fps(), 1e-9);
        assertEquals("2M", o.maxrate());
        assertEquals("4M", o.bufsize());
        assertEquals(50, o.gop());
        assertEquals(List.of("-x265-params", "vbv-maxrate=2000"), o.extraOutputArgs());
        assertNotNull(o.videoFilter());
    }

    @Test
    void transcode的fps与gop非正在构造期抛异常() {
        assertThrows(IllegalArgumentException.class, () -> TranscodeOptions.defaults().fps(0));
        assertThrows(IllegalArgumentException.class, () -> TranscodeOptions.defaults().fps(-1));
        assertThrows(IllegalArgumentException.class, () -> TranscodeOptions.defaults().gop(0));
        assertThrows(IllegalArgumentException.class, () -> TranscodeOptions.defaults().gop(-5));
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

    @Test
    void transcode流禁用与进阶码控字段默认与wither() {
        TranscodeOptions base = TranscodeOptions.defaults();
        assertFalse(base.disableVideo());
        assertFalse(base.disableAudio());
        assertNull(base.audioSampleRate());
        assertNull(base.strict());
        assertNull(base.x265Params());
        assertFalse(base.vbvDeriveBufsize());

        TranscodeOptions o = base.disableVideo(true).audioSampleRate(44100).strict("-2")
                .x265Params("vbv-maxrate=2000");
        assertFalse(base.disableVideo(), "原对象不可变");
        assertTrue(o.disableVideo());
        assertEquals(44100, o.audioSampleRate());
        assertEquals("-2", o.strict());
        assertEquals("vbv-maxrate=2000", o.x265Params());
        assertEquals("-2", base.strictExperimental().strict(), "strictExperimental=strict(-2)");
    }

    @Test
    void transcode的audioSampleRate非正在构造期抛异常() {
        assertThrows(IllegalArgumentException.class, () -> TranscodeOptions.defaults().audioSampleRate(0));
        assertThrows(IllegalArgumentException.class, () -> TranscodeOptions.defaults().audioSampleRate(-8000));
    }

    @Test
    void transcode的vbv标志由显式bufsize或二参清除() {
        assertTrue(TranscodeOptions.defaults().vbv("2M").vbvDeriveBufsize());
        assertEquals("2M", TranscodeOptions.defaults().vbv("2M").maxrate());
        assertFalse(TranscodeOptions.defaults().vbv("2M").bufsize("5M").vbvDeriveBufsize(),
                "显式 bufsize 应清除派生标志");
        assertFalse(TranscodeOptions.defaults().vbv("2M", "6M").vbvDeriveBufsize(),
                "vbv 二参应清除派生标志");
    }

    @Test
    void transcode所有wither往返保真不丢字段() {
        // 前向链设满全部字段（含 videoFilter/onProgress/disableVideo 等引用型/布尔字段）：
        // 若任一 wither 丢失早先设的字段（含把 Function/Consumer 塞成 null），末尾断言即失败。
        TranscodeOptions o = TranscodeOptions.defaults()
                .videoCodec("libx265").audioCodec("libopus").crf(20).preset("slow")
                .videoBitrate("3M").audioBitrate("192k").fps(30).maxrate("5M").bufsize("10M")
                .gop(60).forceKeyframesEverySeconds(2).extraOutputArgs("-movflags", "+faststart")
                .timeout(Duration.ofSeconds(45)).disableVideo(true).disableAudio(true).audioSampleRate(48000)
                .strict("1").x265Params("log-level=none").videoFilter(v -> v).onProgress(p -> { });
        assertTrue(o.disableVideo());
        assertNotNull(o.videoFilter(), "videoFilter 应经后续 wither 保真");
        assertNotNull(o.onProgress(), "onProgress 应经后续 wither 保真（全仓唯一覆盖点）");
        assertEquals("libx265", o.videoCodec());
        assertEquals("libopus", o.audioCodec());
        assertEquals(20, o.crf());
        assertEquals("slow", o.preset());
        assertEquals("3M", o.videoBitrate());
        assertEquals("192k", o.audioBitrate());
        assertEquals(30.0, o.fps(), 1e-9);
        assertEquals("5M", o.maxrate());
        assertEquals("10M", o.bufsize());
        assertEquals(60, o.gop());
        assertEquals(2.0, o.forceKeyframesEverySeconds(), 1e-9);
        assertEquals(List.of("-movflags", "+faststart"), o.extraOutputArgs());
        assertEquals(Duration.ofSeconds(45), o.timeout());
        assertTrue(o.disableAudio());
        assertEquals(48000, o.audioSampleRate());
        assertEquals("1", o.strict());
        assertEquals("log-level=none", o.x265Params());

        // 反向哨兵：在已满配对象上各调一个尾部 wither，断言未改动的新增字段仍在（捕获尾部 wither 丢字段）。
        TranscodeOptions p = o.timeout(Duration.ofSeconds(1));
        assertEquals("log-level=none", p.x265Params());
        assertEquals("1", p.strict());
        assertEquals(48000, p.audioSampleRate());
        assertTrue(p.disableAudio());
        assertNotNull(p.videoFilter(), "尾部 wither 不应丢 videoFilter");
        assertNotNull(p.onProgress(), "尾部 wither 不应丢 onProgress");
        TranscodeOptions q = o.x265Params("v2");
        assertEquals("1", q.strict());
        assertEquals(48000, q.audioSampleRate());
        assertEquals(Duration.ofSeconds(45), q.timeout());
    }
}
