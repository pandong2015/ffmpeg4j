package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import io.micrometer.core.instrument.MeterRegistry;

import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;
import io.github.pandong2015.ffmpeg4j.facade.FfmpegClient;

/**
 * ffmpeg4j 的可观测自动配置：Actuator {@code HealthIndicator}/{@code InfoContributor} 与 Micrometer 指标。
 *
 * <p>三块能力各自按 classpath 条件装配（缺 actuator / micrometer 时对应 bean 静默跳过、不抛 {@code NoClassDefFoundError}、
 * 不影响上下文启动）——分别置于以 {@code @ConditionalOnClass} 守卫的嵌套 {@code @Configuration} 中，故引用可选类型的
 * 方法签名仅在对应依赖存在时才被加载。
 *
 * <p>指标经带埋点的 {@link MeteredFfmpegClient} 实现，需<em>替换</em>基础 {@code ffmpegClient} bean，因此本自动配置
 * 声明为 {@code before} 基础自动配置——其 metered 门面 bean 先注册，令基础 {@code @ConditionalOnMissingBean} 退让。
 */
@AutoConfiguration(before = Ffmpeg4jAutoConfiguration.class)
public class FfmpegObservabilityAutoConfiguration {

    /** 指标：MeterRegistry 在场时装配带埋点门面替换基础门面；无 registry 实例则回退普通门面。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @Lazy
        FfmpegClient ffmpegClient(FfmpegEnvironment ffmpegEnvironment,
                                  Ffmpeg4jProperties properties,
                                  ConfigurableListableBeanFactory beanFactory,
                                  FfmpegProgressBridge progressBridge,
                                  ObjectProvider<MeterRegistry> registries) {
            Ffmpeg4jAutoConfiguration.ClientWiring wiring =
                    Ffmpeg4jAutoConfiguration.resolveWiring(properties, beanFactory, progressBridge);
            MeterRegistry registry = registries.getIfAvailable();
            if (registry == null) {
                return new FfmpegClient(
                        ffmpegEnvironment, wiring.runOptions(), wiring.asyncExecutor(),
                        progressBridge.asTaskConsumer());
            }
            return new MeteredFfmpegClient(
                    ffmpegEnvironment, wiring.runOptions(), wiring.asyncExecutor(),
                    registry, progressBridge.asTaskConsumer());
        }
    }

    /** 健康指示器：Actuator 在场时装配。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    static class HealthConfiguration {

        @Bean
        @ConditionalOnMissingBean
        FfmpegHealthIndicator ffmpegHealthIndicator(ObjectProvider<FfmpegEnvironment> ffmpegEnvironment) {
            return new FfmpegHealthIndicator(ffmpegEnvironment);
        }
    }

    /** 信息贡献者：Actuator 在场时装配。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(InfoContributor.class)
    static class InfoConfiguration {

        @Bean
        @ConditionalOnMissingBean
        FfmpegInfoContributor ffmpegInfoContributor(ObjectProvider<FfmpegEnvironment> ffmpegEnvironment) {
            return new FfmpegInfoContributor(ffmpegEnvironment);
        }
    }
}
