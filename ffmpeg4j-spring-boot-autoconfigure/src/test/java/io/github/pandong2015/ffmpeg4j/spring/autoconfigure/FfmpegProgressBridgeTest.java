package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import io.github.pandong2015.ffmpeg4j.engine.Progress;

/**
 * {@link FfmpegProgressBridge} 的通道分发测试（纯逻辑，用 {@link ApplicationContextRunner} 提供真实
 * {@code ApplicationEventPublisher} 与 listener beans，无需 ffmpeg）。覆盖 application-event / listener / both
 * 三通道选择与 listener 异常隔离。
 */
class FfmpegProgressBridgeTest {

    @Test
    void application_event通道_广播事件且不调listener() {
        new ApplicationContextRunner().withUserConfiguration(EventChannelConfig.class).run(context -> {
            context.getBean(FfmpegProgressBridge.class).dispatch(new Progress(Map.of("frame", "10")));
            assertThat(context.getBean(EventRecorder.class).events).hasSize(1);
            assertThat(context.getBean(EventRecorder.class).events.get(0).progress().raw())
                    .containsEntry("frame", "10");
            assertThat(context.getBean(CountingListener.class).count.get()).isZero();
        });
    }

    @Test
    void listener通道_直投listener且不广播事件() {
        new ApplicationContextRunner().withUserConfiguration(ListenerChannelConfig.class).run(context -> {
            context.getBean(FfmpegProgressBridge.class).dispatch(Progress.empty());
            assertThat(context.getBean(CountingListener.class).count.get()).isEqualTo(1);
            assertThat(context.getBean(EventRecorder.class).events).isEmpty();
        });
    }

    @Test
    void both通道_两条都递送且listener异常被隔离() {
        new ApplicationContextRunner().withUserConfiguration(BothChannelConfig.class).run(context -> {
            FfmpegProgressBridge bridge = context.getBean(FfmpegProgressBridge.class);
            // 含一个会抛异常的 listener——dispatch 必须不上抛，且正常 listener 与事件广播都不受影响。
            assertThatCode(() -> bridge.dispatch(Progress.empty())).doesNotThrowAnyException();
            assertThat(context.getBean(EventRecorder.class).events).hasSize(1);
            assertThat(context.getBean(CountingListener.class).count.get()).isEqualTo(1);
        });
    }

    // ===== 测试用配置与 bean =====

    @Configuration
    static class EventChannelConfig {
        @Bean
        FfmpegProgressBridge bridge(ApplicationEventPublisher publisher, ObjectProvider<FfmpegProgressListener> listeners) {
            return new FfmpegProgressBridge(publisher, listeners, ProgressChannel.APPLICATION_EVENT);
        }

        @Bean
        EventRecorder eventRecorder() {
            return new EventRecorder();
        }

        @Bean
        CountingListener countingListener() {
            return new CountingListener();
        }
    }

    @Configuration
    static class ListenerChannelConfig {
        @Bean
        FfmpegProgressBridge bridge(ApplicationEventPublisher publisher, ObjectProvider<FfmpegProgressListener> listeners) {
            return new FfmpegProgressBridge(publisher, listeners, ProgressChannel.LISTENER);
        }

        @Bean
        EventRecorder eventRecorder() {
            return new EventRecorder();
        }

        @Bean
        CountingListener countingListener() {
            return new CountingListener();
        }
    }

    @Configuration
    static class BothChannelConfig {
        @Bean
        FfmpegProgressBridge bridge(ApplicationEventPublisher publisher, ObjectProvider<FfmpegProgressListener> listeners) {
            return new FfmpegProgressBridge(publisher, listeners, ProgressChannel.BOTH);
        }

        @Bean
        EventRecorder eventRecorder() {
            return new EventRecorder();
        }

        @Bean
        CountingListener countingListener() {
            return new CountingListener();
        }

        @Bean
        FfmpegProgressListener throwingListener() {
            return event -> {
                throw new RuntimeException("boom");
            };
        }
    }

    /** 经 {@code @EventListener} 记录广播到的进度事件。 */
    static class EventRecorder {
        final List<FfmpegProgressEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(FfmpegProgressEvent event) {
            events.add(event);
        }
    }

    /** 直投通道的计数 listener。 */
    static class CountingListener implements FfmpegProgressListener {
        final AtomicInteger count = new AtomicInteger();

        @Override
        public void onProgress(FfmpegProgressEvent event) {
            count.incrementAndGet();
        }
    }
}
