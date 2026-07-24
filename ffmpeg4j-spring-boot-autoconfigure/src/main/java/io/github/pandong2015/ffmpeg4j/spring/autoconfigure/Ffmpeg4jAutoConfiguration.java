package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.Logger;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.github.pandong2015.ffmpeg4j.engine.FfmpegExecutor;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;
import io.github.pandong2015.ffmpeg4j.env.FfmpegBinaries;
import io.github.pandong2015.ffmpeg4j.env.FfmpegCapabilities;
import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;
import io.github.pandong2015.ffmpeg4j.facade.FfmpegClient;

/**
 * ffmpeg4j 的 Spring Boot 自动配置：装配 {@link FfmpegEnvironment}、{@link FfmpegExecutor}、
 * {@link FfmpegClient} 三个层级递进的 bean，并据 {@link Ffmpeg4jProperties} 外部化配置。
 *
 * <p><b>惰性 + fail-fast</b>：env/executor/client 三 bean 均 {@code @Lazy}，故 {@code fail-fast=false} 时
 * 上下文刷新不触发环境探测（缺 ffmpeg 也能启动，延迟到实际调用暴露）；{@code fail-fast=true}（默认）时额外
 * 装配 {@link Ffmpeg4jStartupValidator}，在启动期强制解析环境 bean → 缺二进制即启动失败。
 *
 * <p><b>用户可覆盖</b>：每个 bean 均 {@code @ConditionalOnMissingBean}，用户在自身配置中声明同类型 bean 即优先生效。
 */
@AutoConfiguration
@EnableConfigurationProperties(Ffmpeg4jProperties.class)
public class Ffmpeg4jAutoConfiguration {

    private static final Logger LOG = Logger.getLogger(Ffmpeg4jAutoConfiguration.class.getName());
    static final String TASK_EXECUTOR_BEAN_NAME = "ffmpeg4jTaskExecutor";

    @Bean
    @ConditionalOnMissingBean
    @Lazy
    public FfmpegEnvironment ffmpegEnvironment(Ffmpeg4jProperties properties) {
        return buildEnvironment(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @Lazy
    public FfmpegExecutor ffmpegExecutor(FfmpegEnvironment ffmpegEnvironment) {
        return new FfmpegExecutor(ffmpegEnvironment);
    }

    @Bean
    @ConditionalOnMissingBean
    public FfmpegProgressBridge ffmpeg4jProgressBridge(ApplicationEventPublisher publisher,
                                                       ObjectProvider<FfmpegProgressListener> listeners,
                                                       ObjectProvider<FfmpegTaskListener> taskListeners,
                                                       ConfigurableListableBeanFactory beanFactory,
                                                       Ffmpeg4jProperties properties) {
        Executor eventExecutor = properties.getAsync().isUseSpringExecutor()
                ? resolveTaskExecutor(beanFactory) : Runnable::run;
        return new FfmpegProgressBridge(
                publisher, listeners, taskListeners,
                properties.getAsync().getProgressChannel(),
                eventExecutor == null ? Runnable::run : eventExecutor);
    }

    @Bean(name = TASK_EXECUTOR_BEAN_NAME)
    @ConditionalOnMissingBean(name = TASK_EXECUTOR_BEAN_NAME)
    @Conditional(NoResolvableUserTaskExecutorCondition.class)
    @ConditionalOnProperty(prefix = "ffmpeg4j.async", name = "use-spring-executor",
            havingValue = "true", matchIfMissing = true)
    public Ffmpeg4jTaskExecutor ffmpeg4jTaskExecutor(Ffmpeg4jProperties properties) {
        Ffmpeg4jProperties.Async async = properties.getAsync();
        Ffmpeg4jTaskExecutor executor = new Ffmpeg4jTaskExecutor();
        executor.setCorePoolSize(async.getCorePoolSize());
        executor.setMaxPoolSize(async.getMaxPoolSize());
        executor.setQueueCapacity(async.getQueueCapacity());
        executor.setThreadNamePrefix(async.getThreadNamePrefix());
        executor.setWaitForTasksToCompleteOnShutdown(async.isAwaitTermination());
        executor.setAwaitTerminationMillis(async.getAwaitTerminationPeriod().toMillis());
        executor.setRejectedExecutionHandler(switch (async.getRejectionPolicy()) {
            case ABORT -> new ThreadPoolExecutor.AbortPolicy();
            case CALLER_RUNS -> (task, pool) -> {
                if (pool.isShutdown()) {
                    throw new RejectedExecutionException("ffmpeg4j 执行器已关闭，拒绝新任务");
                }
                task.run();
            };
        });
        return executor;
    }

    @Bean
    @ConditionalOnMissingBean
    @Lazy
    public FfmpegClient ffmpegClient(FfmpegEnvironment ffmpegEnvironment,
                                     Ffmpeg4jProperties properties,
                                     ConfigurableListableBeanFactory beanFactory,
                                     FfmpegProgressBridge progressBridge) {
        ClientWiring wiring = resolveWiring(properties, beanFactory, progressBridge);
        return new FfmpegClient(
                ffmpegEnvironment, wiring.runOptions(), wiring.asyncExecutor(),
                progressBridge.asTaskConsumer());
    }

    /**
     * 解析门面执行的运行选项与异步执行器：当 {@code use-spring-executor=true} 且存在唯一 Spring
     * {@link TaskExecutor} 时，把它设为 core 回调派发器（进度回调移出 pump 线程）与异步门面执行器，并挂载进度
     * 桥接为 {@code onProgress}；否则回退 core 默认（不硬失败）。供基础与带指标的门面 bean 复用。
     */
    static ClientWiring resolveWiring(Ffmpeg4jProperties properties,
                                      ConfigurableListableBeanFactory beanFactory,
                                      FfmpegProgressBridge progressBridge) {
        RunOptions runOptions = buildDefaultRunOptions(properties);
        Executor asyncExecutor = null;
        if (properties.getAsync().isUseSpringExecutor()) {
            TaskExecutor taskExecutor = resolveTaskExecutor(beanFactory);
            if (taskExecutor != null) {
                asyncExecutor = taskExecutor;
                runOptions = runOptions
                        .callbackExecutor(taskExecutor)
                        .onProgress(progressBridge.asConsumer());
            } else {
                LOG.warning("ffmpeg4j：启用了 Spring 执行器，但未找到用户候选或默认 ffmpeg4jTaskExecutor；"
                        + "异步门面与进度回调将保留 core 默认。");
            }
        }
        return new ClientWiring(runOptions, asyncExecutor);
    }

    static TaskExecutor resolveTaskExecutor(ConfigurableListableBeanFactory beanFactory) {
        String[] names = beanFactory.getBeanNamesForType(TaskExecutor.class, true, false);
        List<String> userCandidates = Arrays.stream(names)
                .filter(name -> !isLibraryDefault(beanFactory, name))
                .toList();
        if (userCandidates.size() == 1) {
            return beanFactory.getBean(userCandidates.get(0), TaskExecutor.class);
        }
        List<String> primaryCandidates = userCandidates.stream()
                .filter(name -> isPrimary(beanFactory, name))
                .toList();
        if (primaryCandidates.size() == 1) {
            return beanFactory.getBean(primaryCandidates.get(0), TaskExecutor.class);
        }
        if (!userCandidates.isEmpty()) {
            LOG.info("ffmpeg4j：存在多个非唯一且无单一 @Primary 的用户 TaskExecutor，使用专用默认执行器。");
        }
        return Arrays.stream(names)
                .filter(name -> isLibraryDefault(beanFactory, name))
                .findFirst()
                .map(name -> beanFactory.getBean(name, TaskExecutor.class))
                .orElse(null);
    }

    private static boolean isLibraryDefault(ConfigurableListableBeanFactory beanFactory, String beanName) {
        Class<?> type = beanFactory.getType(beanName, false);
        return type != null && Ffmpeg4jTaskExecutor.class.isAssignableFrom(type);
    }

    private static boolean isPrimary(ConfigurableListableBeanFactory beanFactory, String beanName) {
        if (!beanFactory.containsBeanDefinition(beanName)) {
            return false;
        }
        BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
        return definition.isPrimary();
    }

    /** 门面 bean 的接线结果：合并后的默认 {@link RunOptions} 与异步执行器（可为 {@code null}）。 */
    record ClientWiring(RunOptions runOptions, Executor asyncExecutor) {
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ffmpeg4j", name = "fail-fast", havingValue = "true", matchIfMissing = true)
    public Ffmpeg4jStartupValidator ffmpeg4jStartupValidator(ObjectProvider<FfmpegEnvironment> ffmpegEnvironment,
                                                             Ffmpeg4jProperties properties) {
        return new Ffmpeg4jStartupValidator(ffmpegEnvironment, properties);
    }

    /**
     * 据配置构造默认 {@link RunOptions}（超时/取消/终止宽限期）。
     * {@code callbackExecutor} 由异步配置（{@code async.use-spring-executor}）在后续叠加。
     */
    static RunOptions buildDefaultRunOptions(Ffmpeg4jProperties properties) {
        RunOptions ro = RunOptions.defaults();
        if (properties.getDefaultTimeout() != null) {
            ro = ro.timeout(properties.getDefaultTimeout());
        }
        if (properties.getCancelGracePeriod() != null) {
            ro = ro.cancelGracePeriod(properties.getCancelGracePeriod());
        }
        if (properties.getTerminateGracePeriod() != null) {
            ro = ro.terminateGracePeriod(properties.getTerminateGracePeriod());
        }
        return ro;
    }

    /**
     * 据配置构造环境：显式路径优先（{@code ffmpeg-path}/{@code ffprobe-path} 须同配），否则 PATH 发现；
     * 只配其一视为可诊断的配置错误。
     */
    static FfmpegEnvironment buildEnvironment(Ffmpeg4jProperties properties) {
        String ffmpeg = trimToNull(properties.getFfmpegPath());
        String ffprobe = trimToNull(properties.getFfprobePath());
        if (ffmpeg != null && ffprobe != null) {
            FfmpegBinaries binaries = FfmpegBinaries.of(Path.of(ffmpeg), Path.of(ffprobe));
            return new FfmpegEnvironment(binaries, FfmpegCapabilities.probe(binaries));
        }
        if (ffmpeg == null && ffprobe == null) {
            return FfmpegEnvironment.detect();
        }
        throw new IllegalStateException(
                "ffmpeg4j.ffmpeg-path 与 ffmpeg4j.ffprobe-path 须同时配置或同时留空；当前只配置了其一"
                        + "（ffmpeg-path=" + properties.getFfmpegPath()
                        + ", ffprobe-path=" + properties.getFfprobePath() + "）。");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 用类型标记库创建的默认实例，避免它参与用户候选的唯一性判断。 */
    static final class Ffmpeg4jTaskExecutor extends ThreadPoolTaskExecutor {
    }

    /**
     * 仅按 bean 类型与定义元数据判断是否已有可解析的用户执行器，绝不实例化候选 bean。
     */
    static final class NoResolvableUserTaskExecutorCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
            if (beanFactory == null) {
                return true;
            }
            String[] candidates = beanFactory.getBeanNamesForType(TaskExecutor.class, true, false);
            if (candidates.length == 1) {
                return false;
            }
            long primaryCount = Arrays.stream(candidates)
                    .filter(name -> isPrimary(beanFactory, name))
                    .count();
            return primaryCount != 1;
        }
    }
}
