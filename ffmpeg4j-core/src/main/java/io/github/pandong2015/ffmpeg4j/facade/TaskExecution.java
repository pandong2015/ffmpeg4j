package io.github.pandong2015.ffmpeg4j.facade;

import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.task.TaskEvent;
import io.github.pandong2015.ffmpeg4j.task.TaskHandle;
import io.github.pandong2015.ffmpeg4j.task.TaskId;
import io.github.pandong2015.ffmpeg4j.task.TaskReport;
import io.github.pandong2015.ffmpeg4j.task.TaskStatus;
import io.github.pandong2015.ffmpeg4j.task.TaskWarningCollector;
import io.github.pandong2015.ffmpeg4j.task.FfmpegWarning;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 门面内部的单任务状态机；所有状态迁移和终态收口均通过 CAS 完成。
 */
final class TaskExecution<T> {

    @FunctionalInterface
    interface Work<T> {
        T execute(Context context) throws Throwable;
    }

    static final class Context {
        private final TaskExecution<?> owner;

        private Context(TaskExecution<?> owner) {
            this.owner = owner;
        }

        void cancellationAction(Runnable action) {
            Objects.requireNonNull(action, "action");
            owner.cancelAction.set(action);
            if (owner.state.get() == TaskStatus.CANCELLING) {
                owner.invokeCancelAction();
            }
        }

        void progress(Progress progress) {
            Objects.requireNonNull(progress, "progress");
            synchronized (owner.eventLock) {
                if (owner.state.get() == TaskStatus.RUNNING) {
                    owner.publish(TaskEvent.Type.PROGRESS, progress, null);
                }
            }
        }

        boolean cancellationRequested() {
            return owner.state.get() == TaskStatus.CANCELLING;
        }
    }

    private final TaskId taskId;
    private final String operation;
    private final Consumer<TaskEvent> listener;
    private final AtomicReference<TaskStatus> state = new AtomicReference<>(TaskStatus.SUBMITTED);
    private final AtomicReference<Runnable> cancelAction = new AtomicReference<>();
    private final AtomicBoolean cancelInvoked = new AtomicBoolean();
    private final CompletableFuture<TaskReport<T>> completion = new CompletableFuture<>();
    private final AtomicReference<List<FfmpegWarning>> warningSnapshot =
            new AtomicReference<>(List.of());
    private final Context context = new Context(this);
    private final Object eventLock = new Object();

    TaskExecution(TaskId taskId, String operation, Consumer<TaskEvent> listener) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.listener = listener == null ? event -> { } : listener;
    }

    TaskHandle<T> submit(Executor executor, Work<T> work) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(work, "work");
        TaskHandle<T> handle = new TaskHandle<>(taskId, operation, completion, state::get, this::cancel);
        try {
            executor.execute(() -> run(work));
        } catch (Throwable rejection) {
            failBeforeStart(rejection);
        }
        return handle;
    }

    private void run(Work<T> work) {
        synchronized (eventLock) {
            if (!state.compareAndSet(TaskStatus.SUBMITTED, TaskStatus.RUNNING)) {
                if (state.get() == TaskStatus.CANCELLING) {
                    finishCancelled();
                }
                return;
            }
            publish(TaskEvent.Type.STARTED, null, null);
        }
        TaskWarningCollector warnings = TaskWarningCollector.open();
        try {
            T result = Objects.requireNonNull(work.execute(context), "任务结果");
            warningSnapshot.set(warnings.snapshot());
            boolean completed = false;
            synchronized (eventLock) {
                if (state.compareAndSet(TaskStatus.RUNNING, TaskStatus.COMPLETED)) {
                    publish(TaskEvent.Type.COMPLETED, null, null);
                    completed = true;
                } else if (state.get() == TaskStatus.CANCELLING) {
                    finishCancelled();
                }
            }
            if (completed) {
                completion.complete(new TaskReport<>(
                        taskId, operation, TaskStatus.COMPLETED, result, null, warningSnapshot.get()));
            }
        } catch (Throwable error) {
            warningSnapshot.set(warnings.snapshot());
            boolean failed = false;
            synchronized (eventLock) {
                if (state.get() == TaskStatus.CANCELLING) {
                    finishCancelled();
                } else if (state.compareAndSet(TaskStatus.RUNNING, TaskStatus.FAILED)) {
                    publish(TaskEvent.Type.FAILED, null, error);
                    failed = true;
                }
            }
            if (failed) {
                completion.complete(new TaskReport<>(
                        taskId, operation, TaskStatus.FAILED, null, error, warningSnapshot.get()));
            }
        } finally {
            warnings.close();
        }
    }

    private boolean cancel() {
        TaskStatus previous;
        synchronized (eventLock) {
            previous = state.get();
            if (previous.terminal() || previous == TaskStatus.CANCELLING) {
                return false;
            }
            state.set(TaskStatus.CANCELLING);
            publish(TaskEvent.Type.CANCELLING, null, null);
            if (previous == TaskStatus.SUBMITTED) {
                finishCancelled();
            }
        }
        invokeCancelAction();
        return true;
    }

    private void invokeCancelAction() {
        Runnable action = cancelAction.get();
        if (action != null && cancelInvoked.compareAndSet(false, true)) {
            try {
                action.run();
            } catch (Throwable ignored) {
                // 取消动作失败不能夺走任务真实终态；底层 await/退出仍负责最终收口。
            }
        }
    }

    private void finishCancelled() {
        boolean cancelled = false;
        synchronized (eventLock) {
            if (state.compareAndSet(TaskStatus.CANCELLING, TaskStatus.CANCELLED)) {
                publish(TaskEvent.Type.CANCELLED, null, null);
                cancelled = true;
            }
        }
        if (cancelled) {
            completion.complete(new TaskReport<>(
                    taskId, operation, TaskStatus.CANCELLED, null, null,
                    warningSnapshot.get()));
        }
    }

    private void failBeforeStart(Throwable error) {
        boolean failed = false;
        synchronized (eventLock) {
            if (state.compareAndSet(TaskStatus.SUBMITTED, TaskStatus.FAILED)) {
                publish(TaskEvent.Type.FAILED, null, error);
                failed = true;
            }
        }
        if (failed) {
            completion.complete(new TaskReport<>(
                    taskId, operation, TaskStatus.FAILED, null, error, java.util.List.of()));
        }
    }

    private void publish(TaskEvent.Type type, Progress progress, Throwable error) {
        try {
            listener.accept(new TaskEvent(taskId, operation, type, Instant.now(), progress, error));
        } catch (Throwable ignored) {
            // 生命周期观察者不得改变媒体任务结果。
        }
    }
}
