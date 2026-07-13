package io.github.pandong2015.ffmpeg4j.env;

import io.github.pandong2015.ffmpeg4j.FfmpegException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 能力探测测试：滤镜列表解析、信号→能力→前置校验的纯逻辑，以及有 ffmpeg 时的端到端探测。
 */
class FfmpegCapabilitiesTest {

    private static final String FILTERS_SAMPLE = String.join("\n",
            "Filters:",
            "  T.. = Timeline support",
            "  .S. = Slice threading",
            "  A = Audio input/output",
            " TS aap               AA->A      Apply Affine Projection algorithm.",
            " .. anull             A->A       Pass the source unchanged to the output.",
            " .. subtitles         V->V       Render text subtitles onto input video.",
            " .. ass               V->V       Render ASS subtitles onto input video.",
            " .. drawtext          V->V       Draw text on top of video frames.",
            " .. null              V->V       Pass the source unchanged to the output.");

    @Test
    void parsesFilterNamesFromListing() {
        Set<String> names = FfmpegCapabilities.parseFilterNames(FILTERS_SAMPLE);
        assertTrue(names.contains("aap"));
        assertTrue(names.contains("subtitles"));
        assertTrue(names.contains("ass"));
        assertTrue(names.contains("drawtext"));
        assertTrue(names.contains("null"));
        // 图例/表头行不含 -> ，不应被当作滤镜名。
        assertFalse(names.contains("Timeline"));
        assertFalse(names.contains("Filters:"));
    }

    @Test
    void capabilitiesDerivedFromFilterSignals() {
        FfmpegCapabilities caps = FfmpegCapabilities.fromSignals(
                FfmpegVersion.of(8, 0, 1), "configuration: --enable-gpl",
                List.of("subtitles", "ass", "drawtext"));
        assertTrue(caps.hasLibass());
        assertTrue(caps.hasLibfreetype());
        assertDoesNotThrow(caps::requireLibass);
        assertDoesNotThrow(caps::requireLibfreetype);
    }

    @Test
    void capabilitiesDerivedFromConfigurationFlags() {
        FfmpegCapabilities caps = FfmpegCapabilities.fromSignals(
                FfmpegVersion.of(8, 0, 1),
                "configuration: --enable-gpl --enable-libass --enable-libfreetype",
                List.of());
        assertTrue(caps.hasLibass());
        assertTrue(caps.hasLibfreetype());
    }

    @Test
    void missingBuildFlagsMakeRequireThrowNamingTheFlag() {
        FfmpegCapabilities caps = FfmpegCapabilities.fromSignals(
                FfmpegVersion.of(8, 0, 1), "configuration: --enable-gpl", List.of("anull", "scale"));
        assertFalse(caps.hasLibass());
        assertFalse(caps.hasLibfreetype());

        FfmpegException assErr = assertThrows(FfmpegException.class, caps::requireLibass);
        assertTrue(assErr.getMessage().contains("--enable-libass"),
                "字幕烧录缺失应点名 --enable-libass，实际: " + assErr.getMessage());

        FfmpegException ftErr = assertThrows(FfmpegException.class, caps::requireLibfreetype);
        assertTrue(ftErr.getMessage().contains("--enable-libfreetype"),
                "drawText 缺失应点名 --enable-libfreetype，实际: " + ftErr.getMessage());
    }

    @Test
    void probesRealFfmpegWhenAvailable() {
        FfmpegBinaries binaries;
        try {
            binaries = FfmpegBinaries.locate();
        } catch (FfmpegException e) {
            assumeTrue(false, "PATH 上没有 ffmpeg，跳过能力探测集成用例: " + e.getMessage());
            return;
        }
        FfmpegCapabilities caps = FfmpegCapabilities.probe(binaries);
        assertNotNull(caps.version());
        assertTrue(caps.version().isKnown(), "已安装的 ffmpeg 应能解析出数字版本");
        assertFalse(caps.version().isBelowMinimum(), "8.x 不应低于 4.2");

        // 布尔应自洽：有能力则 require 不抛，无能力则 require 抛出可诊断异常。
        if (caps.hasLibass()) {
            assertDoesNotThrow(caps::requireLibass);
        } else {
            assertThrows(FfmpegException.class, caps::requireLibass);
        }
        if (caps.hasLibfreetype()) {
            assertDoesNotThrow(caps::requireLibfreetype);
        } else {
            assertThrows(FfmpegException.class, caps::requireLibfreetype);
        }
    }
}
