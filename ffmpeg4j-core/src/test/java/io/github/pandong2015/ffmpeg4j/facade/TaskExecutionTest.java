package io.github.pandong2015.ffmpeg4j.facade;

import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.task.TaskEvent;
import io.github.pandong2015.ffmpeg4j.task.TaskHandle;
import io.github.pandong2015.ffmpeg4j.task.TaskId;
import io.github.pandong2015.ffmpeg4j.task.TaskReport;
import io.github.pandong2015.ffmpeg4j.task.TaskStatus;
import io.github.pandong2015.ffmpeg4j.task.FfmpegWarning;
import io.github.pandong2015.ffmpeg4j.task.WarningCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskExecutionTest {

    @Test
    void 成功任务按序发布且监听器异常不改变结果() {
        List<TaskEvent.Type> events = new ArrayList<>();
        TaskExecution<String> execution = new TaskExecution<>(
                new TaskId("success"), "transcode", event -> {
                    events.add(event.type());
                    if (event.type() == TaskEvent.Type.PROGRESS) {
                        throw new IllegalStateException("观察者故障");
                    }
                });

        TaskHandle<String> handle = execution.submit(Runnable::run, context -> {
            context.progress(Progress.empty());
            return "ok";
        });
        TaskReport<String> report = handle.completion().join();

        assertEquals(TaskStatus.COMPLETED, report.status());
        assertEquals("ok", report.result());
        assertEquals(List.of(
                TaskEvent.Type.STARTED,
                TaskEvent.Type.PROGRESS,
                TaskEvent.Type.COMPLETED), events);
    }

    @Test
    void 成功报告保留警告顺序且集合与明细不可变() {
        Map<String, String> mutableDetails = new java.util.HashMap<>();
        mutableDetails.put("step", "first");
        TaskExecution<String> execution = new TaskExecution<>(
                new TaskId("warnings"), "transcode", null);

        TaskReport<String> report = execution.submit(Runnable::run, context -> {
            io.github.pandong2015.ffmpeg4j.task.TaskWarningCollector.add(new FfmpegWarning(
                    WarningCode.VERSION_BELOW_MINIMUM, "版本偏低", mutableDetails));
            mutableDetails.put("step", "changed");
            io.github.pandong2015.ffmpeg4j.task.TaskWarningCollector.add(new FfmpegWarning(
                    WarningCode.PROGRESS_UNAVAILABLE, "进度不可用"));
            return "ok";
        }).completion().join();

        assertEquals(TaskStatus.COMPLETED, report.status());
        assertEquals(List.of(
                        WarningCode.VERSION_BELOW_MINIMUM,
                        WarningCode.PROGRESS_UNAVAILABLE),
                report.warnings().stream().map(FfmpegWarning::code).toList());
        assertEquals("first", report.warnings().get(0).details().get("step"));
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> report.warnings().clear());
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> report.warnings().get(0).details().put("x", "y"));
    }

    @Test
    void 旧五参数报告构造器保持空警告兼容() {
        TaskReport<String> report = new TaskReport<>(
                new TaskId("legacy"), "transcode", TaskStatus.COMPLETED, "ok", null);

        assertEquals(List.of(), report.warnings());
        assertEquals("ok", report.result());
    }

    @Test
    void 失败任务保留原始原因且只有一个终态() {
        RuntimeException failure = new RuntimeException("boom");
        List<TaskEvent> events = new ArrayList<>();
        TaskExecution<String> execution =
                new TaskExecution<>(new TaskId("failure"), "transcode", events::add);

        TaskReport<String> report = execution.submit(Runnable::run, context -> {
            throw failure;
        }).completion().join();

        assertEquals(TaskStatus.FAILED, report.status());
        assertSame(failure, report.error());
        assertEquals(List.of(TaskEvent.Type.STARTED, TaskEvent.Type.FAILED),
                events.stream().map(TaskEvent::type).toList());
    }

    @Test
    void 运行中取消只传播一次并赢得自然完成竞争() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger cancellations = new AtomicInteger();
            List<TaskEvent.Type> events = new ArrayList<>();
            TaskExecution<String> execution = new TaskExecution<>(
                    new TaskId("cancel-race"), "transcode",
                    event -> events.add(event.type()));

            TaskHandle<String> handle = execution.submit(executor, context -> {
                context.cancellationAction(cancellations::incrementAndGet);
                started.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return "自然完成";
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));

            assertTrue(handle.cancel());
            assertFalse(handle.cancel());
            release.countDown();
            TaskReport<String> report = handle.completion().get(5, TimeUnit.SECONDS);

            assertEquals(TaskStatus.CANCELLED, report.status());
            assertEquals(1, cancellations.get());
            assertEquals(List.of(
                    TaskEvent.Type.STARTED,
                    TaskEvent.Type.CANCELLING,
                    TaskEvent.Type.CANCELLED), events);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 排队任务可在开始前取消且不会执行工作() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        List<TaskEvent.Type> events = new ArrayList<>();
        TaskExecution<String> execution = new TaskExecution<>(
                new TaskId("queued"), "transcode",
                event -> events.add(event.type()));

        TaskHandle<String> handle = execution.submit(queued::set, context -> {
            calls.incrementAndGet();
            return "unexpected";
        });
        assertTrue(handle.cancel());
        queued.get().run();

        assertEquals(TaskStatus.CANCELLED, handle.completion().join().status());
        assertEquals(0, calls.get());
        assertEquals(List.of(TaskEvent.Type.CANCELLING, TaskEvent.Type.CANCELLED), events);
    }

    @Test
    void 提交前取消不读取调用线程外层警告scope() {
        try (var outer = io.github.pandong2015.ffmpeg4j.task.TaskWarningCollector.open()) {
            io.github.pandong2015.ffmpeg4j.task.TaskWarningCollector.add(new FfmpegWarning(
                    WarningCode.VERSION_BELOW_MINIMUM, "外层警告"));
            AtomicReference<Runnable> queued = new AtomicReference<>();
            TaskExecution<String> execution =
                    new TaskExecution<>(new TaskId("isolated-warnings"), "transcode", null);

            TaskHandle<String> handle = execution.submit(queued::set, context -> "unexpected");
            assertTrue(handle.cancel());

            assertEquals(List.of(), handle.completion().join().warnings());
            assertEquals(1, outer.snapshot().size());
        }
    }

    @Test
    void 执行器拒绝在调用线程形成失败报告且没有started() {
        RejectedExecutionException rejection = new RejectedExecutionException("容量已满");
        List<TaskEvent> events = new ArrayList<>();
        TaskExecution<String> execution =
                new TaskExecution<>(new TaskId("rejected"), "transcode", events::add);

        TaskHandle<String> handle = execution.submit(command -> {
            throw rejection;
        }, context -> "never");
        TaskReport<String> report = handle.completion().join();

        assertEquals(TaskStatus.FAILED, report.status());
        assertSame(rejection, report.error());
        assertEquals(List.of(TaskEvent.Type.FAILED),
                events.stream().map(TaskEvent::type).toList());
    }
}
