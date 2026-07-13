package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import io.github.pandong2015.ffmpeg4j.facade.FfmpegClient;

/**
 * {@link FfmpegObservabilityAutoConfiguration} 的条件装配测试。缺 actuator/micrometer 的分支用
 * {@link FilteredClassLoader} 模拟（无需 ffmpeg）；需实例化门面/健康探测的分支以 {@code assumeTrue} 守卫。
 */
class FfmpegObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    Ffmpeg4jAutoConfiguration.class, FfmpegObservabilityAutoConfiguration.class))
            .withPropertyValues("ffmpeg4j.fail-fast=false");

    @Test
    void actuator在场时装配health与info() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FfmpegHealthIndicator.class);
            assertThat(context).hasSingleBean(FfmpegInfoContributor.class);
        });
    }

    @Test
    void 无actuator时不装配health与info也不报错() {
        runner.withClassLoader(new FilteredClassLoader(HealthIndicator.class, InfoContributor.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FfmpegHealthIndicator.class);
                    assertThat(context).doesNotHaveBean(FfmpegInfoContributor.class);
                });
    }

    @Test
    void 无micrometer时门面回退且上下文正常启动() {
        runner.withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FfmpegClient.class);
                });
    }

    @Test
    void 存在MeterRegistry时门面为MeteredFfmpegClient并注册gauge() {
        assumeTrue(commandExists("ffmpeg") && commandExists("ffprobe"), "需要 ffmpeg/ffprobe 实例化门面");
        runner.withBean(SimpleMeterRegistry.class).run(context -> {
            FfmpegClient client = context.getBean(FfmpegClient.class);
            assertThat(client).isInstanceOf(MeteredFfmpegClient.class);
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            assertThat(registry.find(MeteredFfmpegClient.ACTIVE).gauge()).isNotNull();
        });
    }

    @Test
    void 健康探测返回带版本详情且不抛异常() {
        assumeTrue(commandExists("ffmpeg") && commandExists("ffprobe"), "需要 ffmpeg/ffprobe 探测");
        runner.run(context -> {
            FfmpegHealthIndicator health = context.getBean(FfmpegHealthIndicator.class);
            var h = health.health();
            assertThat(h.getDetails()).containsKey("ffmpegVersion");
        });
    }

    private static boolean commandExists(String command) {
        try {
            Process p = new ProcessBuilder(command, "-version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
