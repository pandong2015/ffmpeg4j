package io.github.pandong2015.ffmpeg4j.env;

import io.github.pandong2015.ffmpeg4j.FfmpegException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 二进制发现测试：显式路径校验、覆盖项无效时的可诊断报错，以及（有 ffmpeg 时）PATH 解析。
 */
class FfmpegBinariesTest {

    @Test
    void explicitNonexistentPathThrowsDiagnosableError() {
        FfmpegException ex = assertThrows(FfmpegException.class, () ->
                FfmpegBinaries.of(Path.of("/nonexistent/ffmpeg-xyz"), Path.of("/nonexistent/ffprobe-xyz")));
        // 必须明确指向「未找到」而非模糊的启动失败。
        assertTrue(ex.getMessage().contains("未找到") || ex.getMessage().contains("不存在"),
                "报错应明确指出二进制缺失，实际: " + ex.getMessage());
    }

    @Test
    void invalidSystemPropertyOverrideThrows() {
        String prev = System.getProperty(FfmpegBinaries.PROP_FFMPEG);
        System.setProperty(FfmpegBinaries.PROP_FFMPEG, "/definitely/not/here/ffmpeg-xyz");
        try {
            FfmpegException ex = assertThrows(FfmpegException.class, FfmpegBinaries::locate);
            assertTrue(ex.getMessage().contains("ffmpeg"),
                    "报错应点名 ffmpeg，实际: " + ex.getMessage());
        } finally {
            if (prev == null) {
                System.clearProperty(FfmpegBinaries.PROP_FFMPEG);
            } else {
                System.setProperty(FfmpegBinaries.PROP_FFMPEG, prev);
            }
        }
    }

    @Test
    void locatesRealBinariesWhenOnPath() {
        FfmpegBinaries binaries;
        try {
            binaries = FfmpegBinaries.locate();
        } catch (FfmpegException e) {
            assumeTrue(false, "PATH 上没有 ffmpeg/ffprobe，跳过集成用例: " + e.getMessage());
            return;
        }
        assertNotNull(binaries.ffmpeg());
        assertNotNull(binaries.ffprobe());
        assertTrue(binaries.ffmpegCommand().endsWith("ffmpeg")
                        || binaries.ffmpegCommand().contains("ffmpeg"),
                "ffmpeg 命令路径异常: " + binaries.ffmpegCommand());
        assertEquals(binaries.ffmpeg().toString(), binaries.ffmpegCommand());
    }
}
