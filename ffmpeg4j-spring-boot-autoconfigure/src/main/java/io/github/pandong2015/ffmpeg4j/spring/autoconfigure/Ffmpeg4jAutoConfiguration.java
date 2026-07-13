package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;

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
                                                       Ffmpeg4jProperties properties) {
        return new FfmpegProgressBridge(publisher, listeners, properties.getAsync().getProgressChannel());
    }

    @Bean
    @ConditionalOnMissingBean
    @Lazy
    public FfmpegClient ffmpegClient(FfmpegEnvironment ffmpegEnvironment,
                                     Ffmpeg4jProperties properties,
                                     ObjectProvider<TaskExecutor> taskExecutors,
                                     FfmpegProgressBridge progressBridge) {
        RunOptions runOptions = buildDefaultRunOptions(properties);
        Executor asyncExecutor = null;
        if (properties.getAsync().isUseSpringExecutor()) {
            TaskExecutor taskExecutor = taskExecutors.getIfUnique();
            if (taskExecutor != null) {
                // TaskExecutor is-a java.util.concurrent.Executor：既作 core 回调派发器，也作异步门面执行器。
                // 进度回调因此在 executor 线程触发，进而 publishEvent/listener 不占进度 pump 线程（呼应 core 铁律）。
                asyncExecutor = taskExecutor;
                runOptions = runOptions
                        .callbackExecutor(taskExecutor)
                        .onProgress(progressBridge.asConsumer());
            } else {
                LOG.info("ffmpeg4j：未找到唯一的 Spring TaskExecutor，进度回调将走 core 默认（pump 线程），"
                        + "进度事件桥接暂关闭；如需事件请提供一个（@Primary）TaskExecutor bean。");
            }
        }
        return new FfmpegClient(ffmpegEnvironment, runOptions, asyncExecutor);
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
}
