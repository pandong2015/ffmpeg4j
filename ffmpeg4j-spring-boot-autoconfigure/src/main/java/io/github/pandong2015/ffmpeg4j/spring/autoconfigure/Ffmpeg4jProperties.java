package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import java.time.Duration;

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
public class Ffmpeg4jProperties {

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

    /** 异步执行与进度事件派发配置。 */
    public static class Async {

        /** 是否把进度回调 {@code callbackExecutor} 接到 Spring {@code TaskExecutor}（默认 true）。 */
        private boolean useSpringExecutor = true;

        /** 进度递送通道：application-event（广播）/ listener（直投）/ both（默认 application-event）。 */
        private ProgressChannel progressChannel = ProgressChannel.APPLICATION_EVENT;

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
    }
}
