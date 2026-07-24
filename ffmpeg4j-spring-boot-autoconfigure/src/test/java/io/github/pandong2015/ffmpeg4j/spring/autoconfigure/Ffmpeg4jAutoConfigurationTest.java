package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
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
            assertThat(context).hasBean(Ffmpeg4jAutoConfiguration.TASK_EXECUTOR_BEAN_NAME);
            ThreadPoolTaskExecutor executor = context.getBean(
                    Ffmpeg4jAutoConfiguration.TASK_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getQueueCapacity()).isEqualTo(64);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("ffmpeg4j-");
        });
    }

    @Test
    void 自定义线程池属性完整绑定到默认执行器() {
        runner.withPropertyValues(
                "ffmpeg4j.fail-fast=false",
                "ffmpeg4j.async.core-pool-size=2",
                "ffmpeg4j.async.max-pool-size=4",
                "ffmpeg4j.async.queue-capacity=8",
                "ffmpeg4j.async.thread-name-prefix=media-",
                "ffmpeg4j.async.await-termination=true",
                "ffmpeg4j.async.await-termination-period=20s"
        ).run(context -> {
            Ffmpeg4jProperties.Async async = context.getBean(Ffmpeg4jProperties.class).getAsync();
            assertThat(async.getAwaitTerminationPeriod()).isEqualTo(Duration.ofSeconds(20));
            ThreadPoolTaskExecutor executor = context.getBean(
                    Ffmpeg4jAutoConfiguration.TASK_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getQueueCapacity()).isEqualTo(8);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("media-");
        });
    }

    @Test
    void 非法线程池容量在启动期失败且指出属性() {
        runner.withPropertyValues(
                "ffmpeg4j.fail-fast=false",
                "ffmpeg4j.async.core-pool-size=4",
                "ffmpeg4j.async.max-pool-size=2"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("ffmpeg4j.async.max-pool-size");
        });

        runner.withPropertyValues(
                "ffmpeg4j.fail-fast=false",
                "ffmpeg4j.async.queue-capacity=-1"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("ffmpeg4j.async.queue-capacity");
        });
    }

    @Test
    void 空进度通道在属性初始化期失败() {
        Ffmpeg4jProperties properties = new Ffmpeg4jProperties();
        properties.getAsync().setProgressChannel(null);
        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ffmpeg4j.async.progress-channel");
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
        ThreadPoolTaskExecutor te = newExecutor();
        runner.withPropertyValues("ffmpeg4j.fail-fast=false")
                .withBean("appTaskExecutor", TaskExecutor.class, () -> te).run(context -> {
                    Ffmpeg4jAutoConfiguration.ClientWiring wiring = resolveWiring(context);
                    assertThat(wiring.runOptions().callbackExecutor()).isSameAs(te);
                    assertThat(wiring.asyncExecutor()).isSameAs(te);
                    assertThat(context).doesNotHaveBean(Ffmpeg4jAutoConfiguration.TASK_EXECUTOR_BEAN_NAME);
                });
        te.shutdown();
    }

    @Test
    void 多个用户执行器时Primary候选优先于默认执行器() {
        ThreadPoolTaskExecutor primary = newExecutor();
        ThreadPoolTaskExecutor other = newExecutor();
        runner.withPropertyValues("ffmpeg4j.fail-fast=false")
                .withBean("primaryExecutor", TaskExecutor.class, () -> primary, definition -> definition.setPrimary(true))
                .withBean("otherExecutor", TaskExecutor.class, () -> other)
                .run(context -> {
                    Ffmpeg4jAutoConfiguration.ClientWiring wiring = resolveWiring(context);
                    assertThat(wiring.runOptions().callbackExecutor()).isSameAs(primary);
                    assertThat(wiring.asyncExecutor()).isSameAs(primary);
                    assertThat(context).doesNotHaveBean(Ffmpeg4jAutoConfiguration.TASK_EXECUTOR_BEAN_NAME);
                });
        primary.shutdown();
        other.shutdown();
    }

    @Test
    void 未选中的惰性执行器不会被初始化() {
        ThreadPoolTaskExecutor primary = newExecutor();
        AtomicBoolean unusedCreated = new AtomicBoolean();
        runner.withPropertyValues("ffmpeg4j.fail-fast=false")
                .withBean("primaryExecutor", TaskExecutor.class, () -> primary,
                        definition -> definition.setPrimary(true))
                .withBean("unusedExecutor", TaskExecutor.class, () -> {
                    unusedCreated.set(true);
                    throw new IllegalStateException("不应初始化");
                }, definition -> definition.setLazyInit(true))
                .run(context -> {
                    assertThat(resolveWiring(context).asyncExecutor()).isSameAs(primary);
                    assertThat(context).doesNotHaveBean(Ffmpeg4jAutoConfiguration.TASK_EXECUTOR_BEAN_NAME);
                    assertThat(unusedCreated).isFalse();
                });
        primary.shutdown();
    }

    @Test
    void 关闭useSpringExecutor时不绑定callbackExecutor() {
        ThreadPoolTaskExecutor te = newExecutor();
        runner.withBean("appTaskExecutor", TaskExecutor.class, () -> te)
                .withPropertyValues("ffmpeg4j.fail-fast=false", "ffmpeg4j.async.use-spring-executor=false")
                .run(context -> {
                    assertThat(resolveWiring(context).runOptions().callbackExecutor()).isNull();
                    assertThat(resolveWiring(context).asyncExecutor()).isNull();
                    assertThat(context).doesNotHaveBean(Ffmpeg4jAutoConfiguration.TASK_EXECUTOR_BEAN_NAME);
                });
        te.shutdown();
    }

    @Test
    void 默认执行器饱和时快速拒绝() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        runner.withPropertyValues(
                "ffmpeg4j.fail-fast=false",
                "ffmpeg4j.async.core-pool-size=1",
                "ffmpeg4j.async.max-pool-size=1",
                "ffmpeg4j.async.queue-capacity=0"
        ).run(context -> {
            TaskExecutor executor = context.getBean(
                    Ffmpeg4jAutoConfiguration.TASK_EXECUTOR_BEAN_NAME, TaskExecutor.class);
            executor.execute(() -> {
                running.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> executor.execute(() -> { }))
                        .isInstanceOf(TaskRejectedException.class)
                        .hasMessageContaining("ExecutorService");
            } finally {
                release.countDown();
            }
        });
    }

    @Test
    void 容器关闭等待运行中与排队任务完成() {
        AtomicBoolean runningCompleted = new AtomicBoolean();
        AtomicBoolean queuedCompleted = new AtomicBoolean();
        runner.withPropertyValues(
                "ffmpeg4j.fail-fast=false",
                "ffmpeg4j.async.core-pool-size=1",
                "ffmpeg4j.async.max-pool-size=1",
                "ffmpeg4j.async.queue-capacity=1",
                "ffmpeg4j.async.await-termination=true",
                "ffmpeg4j.async.await-termination-period=2s"
        ).run(context -> {
            TaskExecutor executor = context.getBean(
                    Ffmpeg4jAutoConfiguration.TASK_EXECUTOR_BEAN_NAME, TaskExecutor.class);
            executor.execute(() -> {
                try {
                    Thread.sleep(100);
                    runningCompleted.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            executor.execute(() -> queuedCompleted.set(true));
        });
        assertThat(runningCompleted).isTrue();
        assertThat(queuedCompleted).isTrue();
    }

    private static Ffmpeg4jAutoConfiguration.ClientWiring resolveWiring(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
        ConfigurableApplicationContext source =
                (ConfigurableApplicationContext) context.getSourceApplicationContext();
        ConfigurableListableBeanFactory beanFactory = source.getBeanFactory();
        return Ffmpeg4jAutoConfiguration.resolveWiring(
                context.getBean(Ffmpeg4jProperties.class),
                beanFactory,
                context.getBean(FfmpegProgressBridge.class));
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
