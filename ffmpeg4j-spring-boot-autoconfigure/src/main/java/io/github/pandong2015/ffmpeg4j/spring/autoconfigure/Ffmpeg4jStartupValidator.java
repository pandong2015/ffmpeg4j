package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import java.util.logging.Logger;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;

import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;
import io.github.pandong2015.ffmpeg4j.env.FfmpegVersion;

/**
 * 启动期 fail-fast 校验器：仅当 {@code ffmpeg4j.fail-fast=true}（默认）时装配。
 *
 * <p>作为 {@link InitializingBean} 在 bean 初始化（上下文刷新）阶段<em>强制解析</em>惰性的
 * {@link FfmpegEnvironment} bean，从而触发 {@link FfmpegEnvironment#detect()}——二进制缺失/不可用即抛出
 * 可诊断异常令上下文<b>启动失败</b>，而非把问题推迟到首次调用门面。版本低于 4.2 仅记录告警、不阻断启动
 * （沿用 core 语义）。{@code fail-fast=false} 时本 bean 不装配，环境 bean 保持惰性、缺失延迟到实际调用暴露。
 */
public class Ffmpeg4jStartupValidator implements InitializingBean {

    private static final Logger LOG = Logger.getLogger(Ffmpeg4jStartupValidator.class.getName());

    private final ObjectProvider<FfmpegEnvironment> environmentProvider;
    private final Ffmpeg4jProperties properties;

    public Ffmpeg4jStartupValidator(ObjectProvider<FfmpegEnvironment> environmentProvider,
                                    Ffmpeg4jProperties properties) {
        this.environmentProvider = environmentProvider;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        // 强制解析惰性环境 bean：缺二进制即在此抛出，令上下文启动失败（fail-fast）。
        FfmpegEnvironment env = environmentProvider.getObject();
        FfmpegVersion version = env.version();
        if (properties.isMinVersionCheck() && version.isBelowMinimum()) {
            LOG.warning("ffmpeg4j 启动校验：发现 ffmpeg 版本 " + version + " 低于建议最低版本 "
                    + FfmpegVersion.MIN_FFMPEG_VERSION + "，部分特性可能不可用（仅告警，不阻断启动）。");
        } else {
            LOG.info("ffmpeg4j 启动校验通过：ffmpeg 版本 " + version + "，二进制 "
                    + env.binaries().ffmpegCommand() + " / " + env.binaries().ffprobeCommand() + "。");
        }
    }
}
