package io.github.pandong2015.ffmpeg4j.facade;

import io.github.pandong2015.ffmpeg4j.engine.RunOptions;
import io.github.pandong2015.ffmpeg4j.env.FfmpegBinaries;
import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;
import io.github.pandong2015.ffmpeg4j.task.TaskReport;
import io.github.pandong2015.ffmpeg4j.task.TaskStatus;
import io.github.pandong2015.ffmpeg4j.task.WarningCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegClientTaskApiTest {

    @TempDir
    Path tempDir;

    @Test
    void remux任务报告暴露可选流缺失与字幕丢弃警告() throws Exception {
        FfmpegClient client = fakeClient("""
                {"format":{"duration":"1.0"},"streams":[
                  {"index":0,"codec_type":"video","codec_name":"h264","width":640,"height":360},
                  {"index":1,"codec_type":"subtitle","codec_name":"hdmv_pgs_subtitle"}
                ]}
                """);
        File input = Files.createFile(tempDir.resolve("input.mkv")).toFile();
        File output = tempDir.resolve("output.mp4").toFile();

        TaskReport<?> report = client.remuxTask(input, output, RemuxOptions.defaults())
                .completion().join();

        assertEquals(TaskStatus.COMPLETED, report.status());
        assertEquals(List.of(
                        WarningCode.OPTIONAL_STREAM_MISSING,
                        WarningCode.SUBTITLE_DROPPED),
                report.warnings().stream().map(warning -> warning.code()).toList());
    }

    @Test
    void abr准备失败后的报告仍暴露脸梯裁剪警告() throws Exception {
        FfmpegClient client = fakeClient("""
                {"format":{"duration":"1.0"},"streams":[
                  {"index":0,"codec_type":"video","codec_name":"h264","width":640,"height":360}
                ]}
                """);
        File input = Files.createFile(tempDir.resolve("abr-input.mp4")).toFile();
        File output = Files.createDirectory(tempDir.resolve("hls")).toFile();

        TaskReport<?> report = client.hlsAbrTask(input, output, HlsAbrOptions.defaults())
                .completion().join();

        assertEquals(TaskStatus.FAILED, report.status());
        assertTrue(report.warnings().stream()
                .anyMatch(warning -> warning.code() == WarningCode.ABR_LADDER_TRIMMED));
    }

    @Test
    void 旧异步入口被拒绝时返回原future的异常完成而非同步抛出() {
        RejectedExecutionException rejection = new RejectedExecutionException("容量已满");
        Executor rejecting = command -> {
            throw rejection;
        };
        FfmpegEnvironment environment = new FfmpegEnvironment(
                new FfmpegBinaries(Path.of("ffmpeg"), Path.of("ffprobe")), null);
        FfmpegClient client = new FfmpegClient(environment, RunOptions.defaults(), rejecting);
        File input = new File("input.mp4");
        File output = new File("output.mp4");

        List<CompletableFuture<?>> futures = assertDoesNotThrow(() -> List.of(
                client.transcodeAsync(input, output, TranscodeOptions.defaults()),
                client.hlsSegmentAsync(input, output, HlsOptions.defaults()),
                client.hlsAbrAsync(input, output, HlsAbrOptions.defaults()),
                client.probeAsync(input)));

        for (CompletableFuture<?> future : futures) {
            assertTrue(future.isCompletedExceptionally());
            CompletionException failure = assertThrows(CompletionException.class, future::join);
            assertSame(rejection, failure.getCause());
        }
    }

    private FfmpegClient fakeClient(String probeJson) throws Exception {
        Path ffmpeg = executable("fake-ffmpeg.sh", "#!/bin/sh\nexit 0\n");
        String escaped = probeJson.replace("'", "'\"'\"'");
        Path ffprobe = executable(
                "fake-ffprobe.sh", "#!/bin/sh\nprintf '%s' '" + escaped + "'\n");
        FfmpegEnvironment environment = new FfmpegEnvironment(
                new FfmpegBinaries(ffmpeg, ffprobe), null);
        return new FfmpegClient(environment, RunOptions.defaults(), Runnable::run);
    }

    private Path executable(String name, String content) throws Exception {
        Path path = tempDir.resolve(name);
        Files.writeString(path, content);
        assertTrue(path.toFile().setExecutable(true), "测试脚本应可执行");
        return path;
    }
}
