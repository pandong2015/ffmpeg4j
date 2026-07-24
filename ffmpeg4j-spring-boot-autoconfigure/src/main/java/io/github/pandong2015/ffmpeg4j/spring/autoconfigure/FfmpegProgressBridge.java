package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.task.TaskEvent;
import io.github.pandong2015.ffmpeg4j.task.TaskId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 把 core 进度及任务生命周期桥接到 Spring 应用事件和直投监听器。
 *
 * <p>任务事件按 taskId 串行派发；不同任务可以并发。任一发布器或监听器异常均会被隔离。
 */
public class FfmpegProgressBridge {

    private static final Logger LOG = Logger.getLogger(FfmpegProgressBridge.class.getName());
    private static final int MAX_RETRY_ATTEMPTS = 250;
    private static final int MAX_QUEUED_PROGRESS = 256;
    private static final Executor RETRY_SCHEDULER =
            CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS);

    private final ApplicationEventPublisher publisher;
    private final ObjectProvider<FfmpegProgressListener> progressListeners;
    private final ObjectProvider<FfmpegTaskListener> taskListeners;
    private final ProgressChannel channel;
    private final Executor executor;
    private final ConcurrentMap<TaskId, SerialDispatcher> dispatchers = new ConcurrentHashMap<>();
    private final Map<Progress, TaskId> taskProgress =
            java.util.Collections.synchronizedMap(new IdentityHashMap<>());

    /** 兼容旧构造方式；生命周期任务未配置独立监听器且在调用线程派发。 */
    public FfmpegProgressBridge(
            ApplicationEventPublisher publisher,
            ObjectProvider<FfmpegProgressListener> listeners,
            ProgressChannel channel) {
        this(publisher, listeners, null, channel, Runnable::run);
    }

    /** 创建完整任务生命周期桥。 */
    public FfmpegProgressBridge(
            ApplicationEventPublisher publisher,
            ObjectProvider<FfmpegProgressListener> progressListeners,
            ObjectProvider<FfmpegTaskListener> taskListeners,
            ProgressChannel channel,
            Executor executor) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.progressListeners = Objects.requireNonNull(progressListeners, "progressListeners");
        this.taskListeners = taskListeners;
        this.channel = Objects.requireNonNull(channel, "channel");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * 分发旧门面的一条进度快照。任务 API 已通过统一任务事件发布同一快照时，此处会按对象身份去重。
     */
    public void dispatch(Progress progress) {
        Objects.requireNonNull(progress, "progress");
        if (taskProgress.remove(progress) != null) {
            return;
        }
        dispatchProgressNow(new FfmpegProgressEvent(null, progress));
    }

    /** 按同一 taskId 串行分发 core 生命周期事件。 */
    public void dispatch(TaskEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.type() == TaskEvent.Type.PROGRESS) {
            synchronized (taskProgress) {
                // 旧回调通常会立即按对象身份移除；无旧回调时用上限避免长期任务无限积累。
                if (taskProgress.size() >= 4096) {
                    taskProgress.clear();
                }
                taskProgress.put(event.progress(), event.taskId());
            }
        }
        SerialDispatcher dispatcher =
                dispatchers.computeIfAbsent(event.taskId(), SerialDispatcher::new);
        boolean queued = dispatcher.submit(event.type(), () -> {
                    dispatchTaskNow(event);
                    if (terminal(event.type())) {
                        dispatchers.remove(event.taskId());
                    }
                });
        if (!queued && event.type() == TaskEvent.Type.PROGRESS) {
            taskProgress.remove(event.progress());
        }
    }

    /** 适配为 core 的旧进度回调。 */
    public java.util.function.Consumer<Progress> asConsumer() {
        return this::dispatch;
    }

    /** 适配为 core 的任务生命周期观察者。 */
    public java.util.function.Consumer<TaskEvent> asTaskConsumer() {
        return this::dispatch;
    }

    /** 当前生效的通道。 */
    public ProgressChannel channel() {
        return channel;
    }

    private void dispatchTaskNow(TaskEvent coreEvent) {
        FfmpegTaskEvent event = FfmpegTaskEvent.from(coreEvent);
        if (applicationEventsEnabled()) {
            publishSafely(event, "ffmpeg4j 生命周期事件发布失败");
        }
        if (listenersEnabled() && taskListeners != null) {
            for (FfmpegTaskListener listener : taskListeners) {
                try {
                    listener.onTaskEvent(event);
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "ffmpeg4j 生命周期 listener 抛异常（已隔离）", t);
                }
            }
        }
        if (coreEvent.type() == TaskEvent.Type.PROGRESS) {
            dispatchProgressNow(new FfmpegProgressEvent(
                    coreEvent.taskId(), coreEvent.operation(),
                    coreEvent.timestamp(), coreEvent.progress()));
        }
    }

    private void dispatchProgressNow(FfmpegProgressEvent event) {
        if (applicationEventsEnabled()) {
            publishSafely(event, "ffmpeg4j 进度事件发布失败");
        }
        if (listenersEnabled()) {
            for (FfmpegProgressListener listener : progressListeners) {
                try {
                    listener.onProgress(event);
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "ffmpeg4j 进度 listener 抛异常（已隔离）", t);
                }
            }
        }
    }

    private void publishSafely(Object event, String message) {
        try {
            publisher.publishEvent(event);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, message + "（已隔离，不影响任务）", t);
        }
    }

    private boolean applicationEventsEnabled() {
        return channel == ProgressChannel.APPLICATION_EVENT || channel == ProgressChannel.BOTH;
    }

    private boolean listenersEnabled() {
        return channel == ProgressChannel.LISTENER || channel == ProgressChannel.BOTH;
    }

    private static boolean terminal(TaskEvent.Type type) {
        return type == TaskEvent.Type.COMPLETED
                || type == TaskEvent.Type.FAILED
                || type == TaskEvent.Type.CANCELLED;
    }

    /** 用一条 drain 任务保证同一 taskId 的 FIFO，不为每个事件独占线程。 */
    private final class SerialDispatcher {
        private final TaskId taskId;
        private final Queue<QueuedCommand> queue = new ArrayDeque<>();
        private boolean running;
        private boolean retryScheduled;
        private int retryAttempts;
        private int queuedProgress;

        private SerialDispatcher(TaskId taskId) {
            this.taskId = taskId;
        }

        synchronized boolean submit(TaskEvent.Type type, Runnable command) {
            if (type == TaskEvent.Type.PROGRESS && queuedProgress >= MAX_QUEUED_PROGRESS) {
                return false;
            }
            queue.add(new QueuedCommand(type, command));
            if (type == TaskEvent.Type.PROGRESS) {
                queuedProgress++;
            }
            scheduleDrainLocked();
            return true;
        }

        private void scheduleDrainLocked() {
            if (running || retryScheduled || queue.isEmpty()) {
                return;
            }
            running = true;
            try {
                executor.execute(this::drain);
                retryAttempts = 0;
            } catch (Throwable rejection) {
                running = false;
                scheduleRetryLocked(rejection);
            }
        }

        private void scheduleRetryLocked(Throwable rejection) {
            retryAttempts++;
            if (retryAttempts > MAX_RETRY_ATTEMPTS) {
                int dropped = queue.size();
                queue.clear();
                queuedProgress = 0;
                retryScheduled = false;
                dispatchers.remove(taskId, this);
                removeTaskProgress(taskId);
                LOG.log(Level.WARNING,
                        "ffmpeg4j 生命周期派发持续被拒绝，达到重试上限后清理 {0} 个事件：taskId={1}",
                        new Object[]{dropped, taskId});
                LOG.log(Level.FINE, "最后一次生命周期派发拒绝原因", rejection);
                return;
            }
            retryScheduled = true;
            RETRY_SCHEDULER.execute(this::retry);
        }

        private void retry() {
            synchronized (this) {
                retryScheduled = false;
                scheduleDrainLocked();
            }
        }

        private void drain() {
            while (true) {
                QueuedCommand queued;
                synchronized (this) {
                    queued = queue.poll();
                    if (queued == null) {
                        running = false;
                        retryAttempts = 0;
                        return;
                    }
                    if (queued.type() == TaskEvent.Type.PROGRESS) {
                        queuedProgress--;
                    }
                }
                try {
                    queued.command().run();
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "ffmpeg4j 生命周期派发异常（已隔离）", t);
                }
            }
        }
    }

    private void removeTaskProgress(TaskId taskId) {
        synchronized (taskProgress) {
            taskProgress.entrySet().removeIf(entry -> taskId.equals(entry.getValue()));
        }
    }

    private record QueuedCommand(TaskEvent.Type type, Runnable command) {
    }
}
