package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import io.github.pandong2015.ffmpeg4j.engine.RunOptions;
import io.github.pandong2015.ffmpeg4j.env.FfmpegBinaries;
import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;
import io.github.pandong2015.ffmpeg4j.facade.ClipOptions;
import io.github.pandong2015.ffmpeg4j.facade.GifOptions;
import io.github.pandong2015.ffmpeg4j.facade.HlsAbrOptions;
import io.github.pandong2015.ffmpeg4j.facade.HlsOptions;
import io.github.pandong2015.ffmpeg4j.facade.TranscodeOptions;
import io.github.pandong2015.ffmpeg4j.task.TaskHandle;
import io.github.pandong2015.ffmpeg4j.task.TaskReport;
import io.github.pandong2015.ffmpeg4j.task.TaskStatus;

class MeteredFfmpegClientTest {

    private static final Executor REJECTING_EXECUTOR = command -> {
        throw new RejectedExecutionException("测试拒绝执行");
    };

    @Test
    void 同步空指针失败记录failure并回落active() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredFfmpegClient client = client(Runnable::run, registry);

        assertThatThrownBy(() -> client.transcode(
                new File("input.mp4"), new File("output.mp4"), null))
                .isInstanceOf(NullPointerException.class);

        assertFailureMetrics(registry, "transcode");
    }

    @Test
    void 同步非法参数失败记录failure并回落active() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredFfmpegClient client = client(Runnable::run, registry);

        assertThatThrownBy(() -> client.clip(
                new File("input.mp4"), new File("output.mp4"), 2.0, 1.0, ClipOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class);

        assertFailureMetrics(registry, "clip");
    }

    @Test
    void 异步提交被拒绝时记录failure并回落active() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredFfmpegClient client = client(REJECTING_EXECUTOR, registry);

        CompletableFuture<?> future = client.transcodeAsync(
                new File("input.mp4"), new File("output.mp4"), TranscodeOptions.defaults());

        assertRejectedFuture(future);
        assertFailureMetrics(registry, "transcode");
        assertThat(registry.find(MeteredFfmpegClient.REJECTED).counter()).isNull();
    }

    @Test
    void 四类任务入口拒绝时各计量一次且不双减active() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredFfmpegClient client = client(REJECTING_EXECUTOR, registry);
        File in = new File("input.mp4");
        File out = new File("output");

        assertThat(client.transcodeTask(in, out, TranscodeOptions.defaults())
                .completion().join().status())
                .isEqualTo(io.github.pandong2015.ffmpeg4j.task.TaskStatus.FAILED);
        assertThat(client.remuxTask(in, out, io.github.pandong2015.ffmpeg4j.facade.RemuxOptions.defaults())
                .completion().join().status())
                .isEqualTo(io.github.pandong2015.ffmpeg4j.task.TaskStatus.FAILED);
        assertThat(client.hlsSegmentTask(in, out, HlsOptions.defaults())
                .completion().join().status())
                .isEqualTo(io.github.pandong2015.ffmpeg4j.task.TaskStatus.FAILED);
        assertThat(client.hlsAbrTask(in, out, HlsAbrOptions.defaults())
                .completion().join().status())
                .isEqualTo(io.github.pandong2015.ffmpeg4j.task.TaskStatus.FAILED);

        assertTaskRejectedMetrics(registry, "transcode");
        assertTaskRejectedMetrics(registry, "remux");
        assertTaskRejectedMetrics(registry, "hlsSegment");
        assertTaskRejectedMetrics(registry, "hlsAbr");
        assertThat(registry.get(MeteredFfmpegClient.ACTIVE).gauge().value()).isZero();
    }

    @Test
    void gif与hls同步叶子方法均记录指标() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredFfmpegClient client = client(Runnable::run, registry);
        File in = new File("input.mp4");
        File out = new File("output");

        assertThatThrownBy(() -> client.gif(in, out, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> client.hlsSegment(in, out, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> client.hlsAbr(in, out, null))
                .isInstanceOf(NullPointerException.class);

        assertFailureMetrics(registry, "gif");
        assertFailureMetrics(registry, "hlsSegment");
        assertFailureMetrics(registry, "hlsAbr");
    }

    @Test
    void gif与hls异步叶子方法提交失败时均记录指标() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredFfmpegClient client = client(REJECTING_EXECUTOR, registry);
        File in = new File("input.mp4");
        File out = new File("output");

        CompletableFuture<?> gif = client.gifAsync(in, out, GifOptions.defaults());
        CompletableFuture<?> hlsSegment = client.hlsSegmentAsync(in, out, HlsOptions.defaults());
        CompletableFuture<?> hlsAbr = client.hlsAbrAsync(in, out, HlsAbrOptions.defaults());

        assertRejectedFuture(gif);
        assertRejectedFuture(hlsSegment);
        assertRejectedFuture(hlsAbr);

        assertFailureMetrics(registry, "gif");
        assertFailureMetrics(registry, "hlsSegment");
        assertFailureMetrics(registry, "hlsAbr");
        assertThat(registry.find(MeteredFfmpegClient.REJECTED).counter()).isNull();
    }

    @Test
    void 异步计量返回原future且取消后完成失败指标() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredFfmpegClient client = client(Runnable::run, registry);
        CompletableFuture<String> source = new CompletableFuture<>();

        CompletableFuture<String> returned = client.timedAsync("cancelTest", () -> source);

        assertThat(returned).isSameAs(source);
        assertThat(returned.cancel(true)).isTrue();
        assertFailureMetrics(registry, "cancelTest");
    }

    @Test
    void 异步观察回调注册失败时清理指标() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredFfmpegClient client = client(Runnable::run, registry);
        CompletableFuture<String> source = new CompletableFuture<>() {
            @Override
            public CompletableFuture<String> whenComplete(
                    BiConsumer<? super String, ? super Throwable> action) {
                throw new IllegalStateException("测试注册失败");
            }
        };

        assertThatThrownBy(() -> client.timedAsync("registrationTest", () -> source))
                .isInstanceOf(IllegalStateException.class);

        assertFailureMetrics(registry, "registrationTest");
    }

    @Test
    void 任务提交拒绝记录failed与rejected且不使用taskId标签() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredFfmpegClient client = client(REJECTING_EXECUTOR, registry);

        TaskReport<?> report = client.transcodeTask(
                new File("input.mp4"), new File("output.mp4"),
                TranscodeOptions.defaults()).completion().join();

        assertThat(report.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(report.error()).isInstanceOf(RejectedExecutionException.class);
        assertThat(registry.get(MeteredFfmpegClient.LIFECYCLE)
                .tags("operation", "transcode", "status", "failed")
                .counter().count()).isOne();
        assertThat(registry.get(MeteredFfmpegClient.REJECTED)
                .tag("operation", "transcode").counter().count()).isOne();
        assertThat(registry.getMeters()).allMatch(meter ->
                meter.getId().getTags().stream().noneMatch(tag -> tag.getKey().equals("taskId")));
        assertThat(registry.get(MeteredFfmpegClient.ACTIVE).gauge().value()).isZero();
    }

    @Test
    void 排队任务取消记录cancelling与cancelled且active不泄漏() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        MeteredFfmpegClient client = client(queued::set, registry);

        TaskHandle<?> handle = client.transcodeTask(
                new File("input.mp4"), new File("output.mp4"),
                TranscodeOptions.defaults());
        assertThat(handle.cancel()).isTrue();
        queued.get().run();

        assertThat(handle.completion().join().status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(registry.get(MeteredFfmpegClient.LIFECYCLE)
                .tags("operation", "transcode", "status", "cancelling")
                .counter().count()).isOne();
        assertThat(registry.get(MeteredFfmpegClient.LIFECYCLE)
                .tags("operation", "transcode", "status", "cancelled")
                .counter().count()).isOne();
        assertThat(registry.get(MeteredFfmpegClient.ACTIVE).gauge().value()).isZero();
    }

    private static MeteredFfmpegClient client(Executor executor, SimpleMeterRegistry registry) {
        FfmpegBinaries binaries = new FfmpegBinaries(Path.of("ffmpeg"), Path.of("ffprobe"));
        FfmpegEnvironment environment = new FfmpegEnvironment(binaries, null);
        return new MeteredFfmpegClient(environment, RunOptions.defaults(), executor, registry);
    }

    private static void assertFailureMetrics(SimpleMeterRegistry registry, String operation) {
        assertThat(registry.get(MeteredFfmpegClient.ACTIVE).gauge().value()).isZero();
        assertThat(registry.get(MeteredFfmpegClient.TIMER)
                .tags("operation", operation, "result", "failure")
                .timer().count()).isOne();
        assertThat(registry.get(MeteredFfmpegClient.ERRORS)
                .tags("operation", operation, "reason", "error")
                .counter().count()).isOne();
        assertThat(registry.find(MeteredFfmpegClient.TIMER)
                .tags("operation", operation, "result", "success")
                .timer()).isNull();
    }

    private static void assertTaskRejectedMetrics(SimpleMeterRegistry registry, String operation) {
        assertThat(registry.get(MeteredFfmpegClient.TIMER)
                .tags("operation", operation, "result", "failure")
                .timer().count()).isOne();
        assertThat(registry.get(MeteredFfmpegClient.ERRORS)
                .tags("operation", operation, "reason", "error")
                .counter().count()).isOne();
        assertThat(registry.get(MeteredFfmpegClient.REJECTED)
                .tag("operation", operation)
                .counter().count()).isOne();
    }

    private static void assertRejectedFuture(CompletableFuture<?> future) {
        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::join)
                .hasCauseInstanceOf(RejectedExecutionException.class)
                .hasRootCauseMessage("测试拒绝执行");
    }
}
