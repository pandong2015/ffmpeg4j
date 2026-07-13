package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import io.github.pandong2015.ffmpeg4j.engine.RunResult;
import io.github.pandong2015.ffmpeg4j.facade.FfmpegClient;
import io.github.pandong2015.ffmpeg4j.probe.ProbeResult;

/**
 * 端到端冒烟：以 {@code @SpringBootTest} 真实启动上下文（经 {@code @EnableAutoConfiguration} 触发 starter
 * 自动配置），注入 {@link FfmpegClient} 跑一次真实转码与探测。
 *
 * <p>上下文以 {@code fail-fast=false} 启动，故无 ffmpeg 环境也能刷新；真正调用门面的断言以
 * {@code assumeTrue} 守卫，缺 ffmpeg/ffprobe 则跳过而非失败（{@code FfmpegClient} 惰性、经 ObjectProvider
 * 在确认 ffmpeg 在场后才解析，避免上下文启动即 fork）。
 */
@SpringBootTest(properties = "ffmpeg4j.fail-fast=false")
class FfmpegFacadeSmokeTest {

    @Autowired
    private ObjectProvider<FfmpegClient> clientProvider;

    @Test
    void 注入的FfmpegClient真实转码与探测() throws Exception {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg 不可用，跳过冒烟");
        assumeTrue(commandExists("ffprobe"), "ffprobe 不可用，跳过冒烟");

        FfmpegClient client = clientProvider.getObject();
        assertThat(client).isNotNull();

        Path dir = Files.createTempDirectory("ffmpeg4j-smoke");
        File src = dir.resolve("src.mp4").toFile();
        int code = new ProcessBuilder("ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=1:size=160x120:rate=10",
                "-shortest", src.getAbsolutePath())
                .redirectErrorStream(true).start().waitFor();
        assumeTrue(code == 0, "ffmpeg 生成素材失败，退出码 " + code);

        ProbeResult probe = client.probe(src);
        assertThat(probe.durationSeconds()).isGreaterThan(0);

        File dst = dir.resolve("dst.mp4").toFile();
        RunResult res = client.transcode(src, dst, "libx264", "aac");
        assertThat(res.exitCode()).isZero();
        assertThat(dst.length()).isGreaterThan(0);
    }

    private static boolean commandExists(String command) {
        try {
            Process p = new ProcessBuilder(command, "-version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
    }
}
