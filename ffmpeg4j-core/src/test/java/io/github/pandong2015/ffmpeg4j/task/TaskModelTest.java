package io.github.pandong2015.ffmpeg4j.task;

import io.github.pandong2015.ffmpeg4j.engine.RunResult;
import io.github.pandong2015.ffmpeg4j.facade.FfmpegClient;
import io.github.pandong2015.ffmpeg4j.facade.TranscodeOptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskModelTest {

    @Test
    void 自动标识唯一且显式标识拒绝空白() {
        assertNotEquals(TaskId.random(), TaskId.random());
        assertThrows(IllegalArgumentException.class, () -> new TaskId("  "));
    }

    @Test
    void 旧门面签名和RunResult结构保持不变() throws Exception {
        assertEquals(RunResult.class,
                FfmpegClient.class.getMethod(
                        "transcode", File.class, File.class, TranscodeOptions.class)
                        .getReturnType());
        assertEquals(CompletableFuture.class,
                FfmpegClient.class.getMethod(
                        "transcodeAsync", File.class, File.class, TranscodeOptions.class)
                        .getReturnType());
        assertEquals(
                Arrays.asList("exitCode", "lastProgress", "command"),
                Arrays.stream(RunResult.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList());
    }
}
