package io.github.pandong2015.ffmpeg4j.env;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯字符串层面的版本解析与比较测试（不启动任何进程）。
 */
class FfmpegVersionTest {

    @Test
    void parsesFullVersionLine() {
        FfmpegVersion v = FfmpegVersion.parse(
                "ffmpeg version 8.0.1 Copyright (c) 2000-2025 the FFmpeg developers");
        assertTrue(v.isKnown());
        assertEquals(8, v.major());
        assertEquals(0, v.minor());
        assertEquals(1, v.patch());
        assertFalse(v.isBelowMinimum(), "8.0.1 不应低于 4.2");
    }

    @Test
    void parsesMultiLineVersionOutput() {
        String output = String.join("\n",
                "ffmpeg version 4.4.2-0ubuntu0.22.04.1 Copyright (c) 2000-2021 the FFmpeg developers",
                "built with gcc 11 (Ubuntu 11.2.0)",
                "configuration: --prefix=/usr --enable-gpl --enable-libass");
        FfmpegVersion v = FfmpegVersion.parse(output);
        assertTrue(v.isKnown());
        assertEquals(4, v.major());
        assertEquals(4, v.minor());
        assertEquals(2, v.patch());
        assertFalse(v.isBelowMinimum());
    }

    @Test
    void stripsLeadingNPrefix() {
        FfmpegVersion v = FfmpegVersion.parse("ffmpeg version n4.4 Copyright");
        assertTrue(v.isKnown());
        assertEquals(4, v.major());
        assertEquals(4, v.minor());
        assertEquals(0, v.patch());
    }

    @Test
    void parsesBareVersionStrings() {
        FfmpegVersion threeFour = FfmpegVersion.parse("3.4");
        assertTrue(threeFour.isKnown());
        assertEquals(3, threeFour.major());
        assertEquals(4, threeFour.minor());
        assertEquals(0, threeFour.patch());

        FfmpegVersion eight = FfmpegVersion.parse("8.0.1");
        assertEquals(8, eight.major());
        assertEquals(0, eight.minor());
        assertEquals(1, eight.patch());
    }

    @Test
    void belowMinimumWarnsButDoesNotHardFail() {
        // 关键语义：低于 4.2 → isBelowMinimum() 为 true（调用方据此告警并继续）。
        assertTrue(FfmpegVersion.parse("3.4").isBelowMinimum(), "3.4 < 4.2");
        assertTrue(FfmpegVersion.parse("2.3").isBelowMinimum(), "2.3 < 4.2");
        assertTrue(FfmpegVersion.parse("4.1.9").isBelowMinimum(), "4.1.9 < 4.2");
    }

    @Test
    void atOrAboveMinimumIsNotBelow() {
        assertFalse(FfmpegVersion.parse("4.2").isBelowMinimum(), "4.2 == 4.2，不算低于");
        assertFalse(FfmpegVersion.parse("4.2.1").isBelowMinimum());
        assertFalse(FfmpegVersion.parse("8.0.1").isBelowMinimum());
    }

    @Test
    void gitSnapshotIsUnknownAndNotBelowMinimum() {
        FfmpegVersion v = FfmpegVersion.parse(
                "ffmpeg version N-109407-g8e4e762a2c Copyright (c) 2000-2023");
        assertFalse(v.isKnown(), "git 快照无法解析出数字版本");
        assertFalse(v.isBelowMinimum(), "未知版本不应触发低版本告警");
    }

    @Test
    void nullOrBlankIsUnknown() {
        assertFalse(FfmpegVersion.parse(null).isKnown());
        assertFalse(FfmpegVersion.parse("   ").isKnown());
        assertFalse(FfmpegVersion.parse(null).isBelowMinimum());
    }

    @Test
    void comparisonOrdersNumerically() {
        assertTrue(FfmpegVersion.of(4, 2, 0).compareTo(FfmpegVersion.of(4, 1, 9)) > 0);
        assertTrue(FfmpegVersion.of(3, 4, 0).compareTo(FfmpegVersion.of(4, 2, 0)) < 0);
        assertEquals(0, FfmpegVersion.of(8, 0, 1).compareTo(FfmpegVersion.of(8, 0, 1)));
        assertTrue(FfmpegVersion.of(8, 0, 1).isAtLeast(FfmpegVersion.MINIMUM));
    }

    @Test
    void minConstantMatchesDeclaredFloor() {
        assertEquals("4.2", FfmpegVersion.MIN_FFMPEG_VERSION);
        assertEquals(4, FfmpegVersion.MINIMUM.major());
        assertEquals(2, FfmpegVersion.MINIMUM.minor());
    }
}
