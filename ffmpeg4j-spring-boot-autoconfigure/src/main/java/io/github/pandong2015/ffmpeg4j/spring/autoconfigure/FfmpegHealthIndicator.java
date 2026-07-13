package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import io.github.pandong2015.ffmpeg4j.env.FfmpegCapabilities;
import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;
import io.github.pandong2015.ffmpeg4j.env.FfmpegVersion;

/**
 * ffmpeg 工具链健康指示器（只读，无媒体副作用）。
 *
 * <p>判定：二进制均可发现、且 {@code libass} 与 {@code libfreetype} 均存在 → {@code UP}；任一二进制缺失/不可用，
 * 或任一构建开关缺失 → {@code DOWN}（{@code details} 指明缺失项）。版本低于 4.2 仅在 {@code details} 记告警、
 * <b>不</b>判 {@code DOWN}（沿用 core 语义）。环境经注入的单例 bean 获取，命中后不重复 fork。
 */
public class FfmpegHealthIndicator implements HealthIndicator {

    private final ObjectProvider<FfmpegEnvironment> environmentProvider;

    public FfmpegHealthIndicator(ObjectProvider<FfmpegEnvironment> environmentProvider) {
        this.environmentProvider = environmentProvider;
    }

    @Override
    public Health health() {
        FfmpegEnvironment env;
        try {
            env = environmentProvider.getObject();
        } catch (Exception e) {
            return Health.down(e)
                    .withDetail("ffmpeg", "二进制发现或能力探测失败")
                    .build();
        }

        FfmpegCapabilities caps = env.capabilities();
        boolean libass = caps.hasLibass();
        boolean libfreetype = caps.hasLibfreetype();
        FfmpegVersion version = caps.version();

        Health.Builder builder = (libass && libfreetype) ? Health.up() : Health.down();
        builder.withDetail("ffmpegVersion", version.toString())
                .withDetail("ffmpeg", env.binaries().ffmpegCommand())
                .withDetail("ffprobe", env.binaries().ffprobeCommand())
                .withDetail("libass", libass)
                .withDetail("libfreetype", libfreetype);
        if (!libass) {
            builder.withDetail("missingLibass", "缺 libass：burnSubtitles/burnAss 不可用");
        }
        if (!libfreetype) {
            builder.withDetail("missingLibfreetype", "缺 libfreetype：drawText 不可用");
        }
        if (version.isKnown() && version.isBelowMinimum()) {
            builder.withDetail("versionWarning",
                    "ffmpeg 版本低于建议最低版本 " + FfmpegVersion.MIN_FFMPEG_VERSION + "（仅告警，不判 DOWN）");
        }
        return builder.build();
    }
}
