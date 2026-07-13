package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.github.pandong2015.ffmpeg4j.engine.FfmpegExecutor;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;
import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;
import io.github.pandong2015.ffmpeg4j.facade.FfmpegClient;

/**
 * {@link Ffmpeg4jAutoConfiguration} 的切片装配测试（{@link ApplicationContextRunner}）。
 *
 * <p>多数用例不需要真实 ffmpeg：{@code fail-fast=false} 时 bean 惰性、上下文无 ffmpeg 也能刷新；
 * 显式 bogus 路径与「只配其一」用例在触及二进制前即失败。真正需要 ffmpeg 的用例以 {@code assumeTrue} 守卫跳过。
 */
class Ffmpeg4jAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Ffmpeg4jAutoConfiguration.class));

    @Test
    void 默认装配三bean且failFast关闭时无ffmpeg也能启动() {
        // fail-fast=false → 不装配启动校验器 → 惰性 env/executor/client 不被强制解析 → 无需真实 ffmpeg。
        runner.withPropertyValues("ffmpeg4j.fail-fast=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FfmpegEnvironment.class);
            assertThat(context).hasSingleBean(FfmpegExecutor.class);
            assertThat(context).hasSingleBean(FfmpegClient.class);
            assertThat(context).doesNotHaveBean(Ffmpeg4jStartupValidator.class);
        });
    }

    @Test
    void failFast开启且二进制缺失时上下文启动失败() {
        // fail-fast 默认 true → 装配校验器 → 启动期强制解析 env → 指向不存在的二进制即失败（无需真实 ffmpeg）。
        runner.withPropertyValues(
                "ffmpeg4j.ffmpeg-path=/nonexistent/ffmpeg4j/ffmpeg",
                "ffmpeg4j.ffprobe-path=/nonexistent/ffmpeg4j/ffprobe"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 只配置其一路径视为配置错误() {
        // 只给 ffmpeg-path 不给 ffprobe-path → buildEnvironment 抛可诊断配置错误，fail-fast 下令启动失败。
        runner.withPropertyValues("ffmpeg4j.ffmpeg-path=/some/ffmpeg").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("须同时配置或同时留空");
        });
    }

    @Test
    void 用户自定义FfmpegClientbean时自动装配退让() {
        // FfmpegCapabilities 构造器包私有，无法在无 ffmpeg 时造 stub env，故用真实 detect() 构造用户 client（守卫跳过）。
        assumeTrue(commandExists("ffmpeg") && commandExists("ffprobe"), "需要 ffmpeg/ffprobe 构造用户 client");
        FfmpegClient custom = new FfmpegClient(FfmpegEnvironment.detect(), RunOptions.defaults());
        runner.withBean(FfmpegClient.class, () -> custom).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FfmpegClient.class);
            assertThat(context.getBean(FfmpegClient.class)).isSameAs(custom);
        });
    }

    @Test
    void failFast默认开启时真实装配校验器与门面() {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg 不可用，跳过真实装配用例");
        assumeTrue(commandExists("ffprobe"), "ffprobe 不可用，跳过真实装配用例");
        // fail-fast 默认 true 且 ffmpeg 在场 → 校验器强制 detect 成功 → 上下文正常刷新、三 bean + 校验器就位。
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(Ffmpeg4jStartupValidator.class);
            assertThat(context).hasSingleBean(FfmpegClient.class);
            assertThat(context).hasSingleBean(FfmpegEnvironment.class);
        });
    }

    @Test
    void 存在TaskExecutor时callbackExecutor绑定它() {
        assumeTrue(commandExists("ffmpeg") && commandExists("ffprobe"), "需要 ffmpeg/ffprobe 实例化 client");
        ThreadPoolTaskExecutor te = newExecutor();
        runner.withBean("appTaskExecutor", TaskExecutor.class, () -> te).run(context -> {
            // 取 client bean 会强制解析 env（ffmpeg 在场）；断言默认 RunOptions 的 callbackExecutor 即该 TaskExecutor。
            FfmpegClient client = context.getBean(FfmpegClient.class);
            assertThat(client.defaultRunOptions().callbackExecutor()).isSameAs(te);
        });
        te.shutdown();
    }

    @Test
    void 关闭useSpringExecutor时不绑定callbackExecutor() {
        assumeTrue(commandExists("ffmpeg") && commandExists("ffprobe"), "需要 ffmpeg/ffprobe 实例化 client");
        ThreadPoolTaskExecutor te = newExecutor();
        runner.withBean("appTaskExecutor", TaskExecutor.class, () -> te)
                .withPropertyValues("ffmpeg4j.async.use-spring-executor=false")
                .run(context -> {
                    FfmpegClient client = context.getBean(FfmpegClient.class);
                    assertThat(client.defaultRunOptions().callbackExecutor()).isNull();
                });
        te.shutdown();
    }

    private static ThreadPoolTaskExecutor newExecutor() {
        ThreadPoolTaskExecutor te = new ThreadPoolTaskExecutor();
        te.setCorePoolSize(1);
        te.initialize();
        return te;
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
