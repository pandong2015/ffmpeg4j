package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;

/**
 * 向 {@code /actuator/info} 贡献 ffmpeg 工具链静态元数据：版本与决定门面可用性的构建开关
 * （{@code libass} → burnSubtitles/burnAss，{@code libfreetype} → drawText）。元数据取自 core 环境探测结果，
 * 不执行任何会失败的媒体操作。
 */
public class FfmpegInfoContributor implements InfoContributor {

    private final ObjectProvider<FfmpegEnvironment> environmentProvider;

    public FfmpegInfoContributor(ObjectProvider<FfmpegEnvironment> environmentProvider) {
        this.environmentProvider = environmentProvider;
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> details = new LinkedHashMap<>();
        try {
            FfmpegEnvironment env = environmentProvider.getObject();
            details.put("available", true);
            details.put("version", env.version().toString());
            details.put("libass", env.capabilities().hasLibass());
            details.put("libfreetype", env.capabilities().hasLibfreetype());
        } catch (Exception e) {
            // 环境不可用（二进制缺失/探测失败）时优雅降级为 available=false，而非让 /actuator/info 报错。
            details.clear();
            details.put("available", false);
            details.put("error", String.valueOf(e.getMessage()));
        }
        builder.withDetail("ffmpeg4j", details);
    }
}
