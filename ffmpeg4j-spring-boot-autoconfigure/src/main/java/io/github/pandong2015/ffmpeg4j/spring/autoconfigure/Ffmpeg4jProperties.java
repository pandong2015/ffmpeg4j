package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import java.time.Duration;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * ffmpeg4j 的外部化配置（{@code @ConfigurationProperties(prefix = "ffmpeg4j")}）。
 *
 * <p>映射 core 的二进制发现顺序（显式路径优先，否则 PATH 发现）与 {@code RunOptions}（超时/取消/终止宽限期），
 * 并含启动 fail-fast、最低版本校验与异步派发开关。所有 {@code Duration} 支持 Spring 松弛绑定（{@code 30s} 或
 * {@code PT30S}）。
 */
@ConfigurationProperties(prefix = "ffmpeg4j")
public class Ffmpeg4jProperties implements InitializingBean {

    /** 显式 ffmpeg 二进制路径；留空则走 PATH 发现。与 {@link #ffprobePath} 须同配或同空。 */
    private String ffmpegPath;

    /** 显式 ffprobe 二进制路径；留空则走 PATH 发现。与 {@link #ffmpegPath} 须同配或同空。 */
    private String ffprobePath;

    /** 启动期是否 fail-fast 校验二进制存在（默认 true）：缺失即令上下文启动失败，而非延迟到首次调用。 */
    private boolean failFast = true;

    /** 门面默认超时；留空表示不设超时。映射进装配 {@code FfmpegClient} 的默认 {@code RunOptions}。 */
    private Duration defaultTimeout;

    /** 优雅取消（写 {@code q}）后升级到 SIGTERM 的宽限期（默认 5s）。 */
    private Duration cancelGracePeriod = Duration.ofSeconds(5);

    /** SIGTERM 后升级到 SIGKILL 的宽限期（默认 5s）。 */
    private Duration terminateGracePeriod = Duration.ofSeconds(5);

    /** 是否在启动 fail-fast 时对版本 &lt; 4.2 记录告警（默认 true）；无论如何均不因版本号硬失败。 */
    private boolean minVersionCheck = true;

    /** 异步与进度派发配置。 */
    @NestedConfigurationProperty
    private final Async async = new Async();

    public String getFfmpegPath() {
        return ffmpegPath;
    }

    public void setFfmpegPath(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    public String getFfprobePath() {
        return ffprobePath;
    }

    public void setFfprobePath(String ffprobePath) {
        this.ffprobePath = ffprobePath;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    public Duration getDefaultTimeout() {
        return defaultTimeout;
    }

    public void setDefaultTimeout(Duration defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
    }

    public Duration getCancelGracePeriod() {
        return cancelGracePeriod;
    }

    public void setCancelGracePeriod(Duration cancelGracePeriod) {
        this.cancelGracePeriod = cancelGracePeriod;
    }

    public Duration getTerminateGracePeriod() {
        return terminateGracePeriod;
    }

    public void setTerminateGracePeriod(Duration terminateGracePeriod) {
        this.terminateGracePeriod = terminateGracePeriod;
    }

    public boolean isMinVersionCheck() {
        return minVersionCheck;
    }

    public void setMinVersionCheck(boolean minVersionCheck) {
        this.minVersionCheck = minVersionCheck;
    }

    public Async getAsync() {
        return async;
    }

    @Override
    public void afterPropertiesSet() {
        async.validate();
    }

    /** 异步执行与进度事件派发配置。 */
    public static class Async {

        /** 是否把进度回调 {@code callbackExecutor} 接到 Spring {@code TaskExecutor}（默认 true）。 */
        private boolean useSpringExecutor = true;

        /** 进度递送通道：application-event（广播）/ listener（直投）/ both（默认 application-event）。 */
        private ProgressChannel progressChannel = ProgressChannel.APPLICATION_EVENT;

        /** 默认专用执行器的核心线程数。 */
        private int corePoolSize = 2;

        /** 默认专用执行器的最大线程数。 */
        private int maxPoolSize = 4;

        /** 默认专用执行器的有界等待队列容量。 */
        private int queueCapacity = 64;

        /** 默认专用执行器的线程名前缀。 */
        private String threadNamePrefix = "ffmpeg4j-";

        /** 容器关闭时是否等待已提交任务完成。 */
        private boolean awaitTermination = true;

        /** 容器关闭时等待已提交任务完成的最长期限。 */
        private Duration awaitTerminationPeriod = Duration.ofSeconds(30);

        /** 执行器饱和时的拒绝策略。 */
        private RejectionPolicy rejectionPolicy = RejectionPolicy.ABORT;

        public boolean isUseSpringExecutor() {
            return useSpringExecutor;
        }

        public void setUseSpringExecutor(boolean useSpringExecutor) {
            this.useSpringExecutor = useSpringExecutor;
        }

        public ProgressChannel getProgressChannel() {
            return progressChannel;
        }

        public void setProgressChannel(ProgressChannel progressChannel) {
            this.progressChannel = progressChannel;
        }

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }

        public boolean isAwaitTermination() {
            return awaitTermination;
        }

        public void setAwaitTermination(boolean awaitTermination) {
            this.awaitTermination = awaitTermination;
        }

        public Duration getAwaitTerminationPeriod() {
            return awaitTerminationPeriod;
        }

        public void setAwaitTerminationPeriod(Duration awaitTerminationPeriod) {
            this.awaitTerminationPeriod = awaitTerminationPeriod;
        }

        public RejectionPolicy getRejectionPolicy() {
            return rejectionPolicy;
        }

        public void setRejectionPolicy(RejectionPolicy rejectionPolicy) {
            this.rejectionPolicy = rejectionPolicy;
        }

        private void validate() {
            require(corePoolSize > 0, "ffmpeg4j.async.core-pool-size 必须大于 0");
            require(maxPoolSize > 0, "ffmpeg4j.async.max-pool-size 必须大于 0");
            require(maxPoolSize >= corePoolSize,
                    "ffmpeg4j.async.max-pool-size 必须大于等于 ffmpeg4j.async.core-pool-size");
            require(queueCapacity >= 0, "ffmpeg4j.async.queue-capacity 必须大于等于 0");
            require(threadNamePrefix != null && !threadNamePrefix.isBlank(),
                    "ffmpeg4j.async.thread-name-prefix 不得为空");
            require(awaitTerminationPeriod != null && !awaitTerminationPeriod.isNegative(),
                    "ffmpeg4j.async.await-termination-period 必须大于等于 0");
            require(rejectionPolicy != null, "ffmpeg4j.async.rejection-policy 不得为空");
            require(progressChannel != null, "ffmpeg4j.async.progress-channel 不得为空");
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new IllegalStateException(message);
            }
        }
    }

    /** 默认执行器饱和时允许使用的非静默拒绝策略。 */
    public enum RejectionPolicy {
        /** 快速拒绝并向提交方抛出可诊断异常。 */
        ABORT,
        /** 由提交线程执行；可能阻塞调用方，须显式启用。 */
        CALLER_RUNS
    }
}
