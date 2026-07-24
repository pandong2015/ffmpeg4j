package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.task.TaskEvent;
import io.github.pandong2015.ffmpeg4j.task.TaskId;

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

    @Test
    void 生命周期事件同taskId有序且进度携任务上下文() {
        new ApplicationContextRunner().withUserConfiguration(LifecycleConfig.class).run(context -> {
            FfmpegProgressBridge bridge = context.getBean(FfmpegProgressBridge.class);
            TaskId taskId = new TaskId("task-ordered");
            Instant now = Instant.now();
            Progress progress = new Progress(Map.of("frame", "7"));

            bridge.dispatch(new TaskEvent(
                    taskId, "transcode", TaskEvent.Type.STARTED, now, null, null));
            bridge.dispatch(new TaskEvent(
                    taskId, "transcode", TaskEvent.Type.PROGRESS, now, progress, null));
            // 模拟默认 RunOptions 的旧进度回调；真实链路中它紧跟 core PROGRESS、早于终态。
            bridge.dispatch(progress);
            bridge.dispatch(new TaskEvent(
                    taskId, "transcode", TaskEvent.Type.COMPLETED, now, null, null));

            EventRecorder recorder = context.getBean(EventRecorder.class);
            assertThat(recorder.taskEvents).extracting(FfmpegTaskEvent::status)
                    .containsExactly(
                            TaskEvent.Type.STARTED,
                            TaskEvent.Type.PROGRESS,
                            TaskEvent.Type.COMPLETED);
            assertThat(recorder.taskEvents).allMatch(event ->
                    event.taskId().equals(taskId) && event.operation().equals("transcode"));
            assertThat(recorder.events).singleElement().satisfies(event -> {
                assertThat(event.taskId()).isEqualTo(taskId);
                assertThat(event.operation()).isEqualTo("transcode");
            });
            assertThat(context.getBean(CountingTaskListener.class).count.get()).isEqualTo(3);
        });
    }

    @Test
    void cancelling与cancelled有序且监听异常不外泄() {
        new ApplicationContextRunner().withUserConfiguration(LifecycleConfig.class).run(context -> {
            FfmpegProgressBridge bridge = context.getBean(FfmpegProgressBridge.class);
            TaskId taskId = new TaskId("task-cancel");
            Instant now = Instant.now();

            assertThatCode(() -> {
                bridge.dispatch(new TaskEvent(
                        taskId, "transcode", TaskEvent.Type.STARTED, now, null, null));
                bridge.dispatch(new TaskEvent(
                        taskId, "transcode", TaskEvent.Type.CANCELLING, now, null, null));
                bridge.dispatch(new TaskEvent(
                        taskId, "transcode", TaskEvent.Type.CANCELLED, now, null, null));
            }).doesNotThrowAnyException();

            assertThat(context.getBean(EventRecorder.class).taskEvents)
                    .extracting(FfmpegTaskEvent::status)
                    .containsExactly(
                            TaskEvent.Type.STARTED,
                            TaskEvent.Type.CANCELLING,
                            TaskEvent.Type.CANCELLED);
        });
    }

    @Test
    void 执行器饱和时不在提交线程执行慢listener() {
        new ApplicationContextRunner().withUserConfiguration(RejectedLifecycleConfig.class).run(context -> {
            FfmpegProgressBridge bridge = context.getBean(FfmpegProgressBridge.class);
            ThreadPoolTaskExecutor executor = context.getBean(ThreadPoolTaskExecutor.class);
            CountDownLatch occupied = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.execute(() -> {
                occupied.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(occupied.await(2, TimeUnit.SECONDS)).isTrue();
            TaskEvent started = new TaskEvent(
                    new TaskId("task-rejected"), "transcode",
                    TaskEvent.Type.STARTED, Instant.now(), null, null);

            try {
                org.junit.jupiter.api.Assertions.assertTimeout(
                        Duration.ofMillis(200), () -> {
                            bridge.dispatch(started);
                            bridge.dispatch(new TaskEvent(
                                    started.taskId(), started.operation(),
                                    TaskEvent.Type.COMPLETED, Instant.now(), null, null));
                        });
                RetryingTaskListener listener = context.getBean(RetryingTaskListener.class);
                assertThat(listener.count.get()).isZero();
            } finally {
                release.countDown();
            }
            RetryingTaskListener listener = context.getBean(RetryingTaskListener.class);
            assertThat(listener.terminal.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(listener.statuses).containsExactly(
                    TaskEvent.Type.STARTED, TaskEvent.Type.COMPLETED);
            assertThat(listener.threadNames)
                    .allMatch(name -> name.startsWith("bridge-test-"));
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

    @Configuration
    static class LifecycleConfig {
        @Bean
        FfmpegProgressBridge bridge(
                ApplicationEventPublisher publisher,
                ObjectProvider<FfmpegProgressListener> progressListeners,
                ObjectProvider<FfmpegTaskListener> taskListeners) {
            return new FfmpegProgressBridge(
                    publisher, progressListeners, taskListeners,
                    ProgressChannel.BOTH, Runnable::run);
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
        CountingTaskListener countingTaskListener() {
            return new CountingTaskListener();
        }

        @Bean
        FfmpegTaskListener throwingTaskListener() {
            return event -> {
                if (event.status() == TaskEvent.Type.CANCELLING) {
                    throw new RuntimeException("生命周期监听器故障");
                }
            };
        }
    }

    @Configuration
    static class RejectedLifecycleConfig {
        @Bean
        FfmpegProgressBridge bridge(
                ApplicationEventPublisher publisher,
                ObjectProvider<FfmpegProgressListener> progressListeners,
                ObjectProvider<FfmpegTaskListener> taskListeners,
                ThreadPoolTaskExecutor executor) {
            return new FfmpegProgressBridge(
                    publisher, progressListeners, taskListeners,
                    ProgressChannel.LISTENER, executor);
        }

        @Bean
        ThreadPoolTaskExecutor executor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(0);
            executor.setThreadNamePrefix("bridge-test-");
            return executor;
        }

        @Bean
        RetryingTaskListener retryingTaskListener() {
            return new RetryingTaskListener();
        }
    }

    /** 经 {@code @EventListener} 记录广播到的进度事件。 */
    static class EventRecorder {
        final List<FfmpegProgressEvent> events = new CopyOnWriteArrayList<>();
        final List<FfmpegTaskEvent> taskEvents = new CopyOnWriteArrayList<>();

        @EventListener
        void on(FfmpegProgressEvent event) {
            events.add(event);
        }

        @EventListener
        void on(FfmpegTaskEvent event) {
            taskEvents.add(event);
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

    static class CountingTaskListener implements FfmpegTaskListener {
        final AtomicInteger count = new AtomicInteger();

        @Override
        public void onTaskEvent(FfmpegTaskEvent event) {
            count.incrementAndGet();
        }
    }

    static class RetryingTaskListener implements FfmpegTaskListener {
        final AtomicInteger count = new AtomicInteger();
        final CountDownLatch terminal = new CountDownLatch(1);
        final List<TaskEvent.Type> statuses = new CopyOnWriteArrayList<>();
        final List<String> threadNames = new CopyOnWriteArrayList<>();

        @Override
        public void onTaskEvent(FfmpegTaskEvent event) {
            count.incrementAndGet();
            statuses.add(event.status());
            threadNames.add(Thread.currentThread().getName());
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (event.status() == TaskEvent.Type.COMPLETED) {
                terminal.countDown();
            }
        }
    }
}
