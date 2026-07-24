package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import io.github.pandong2015.ffmpeg4j.FfmpegException;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;
import io.github.pandong2015.ffmpeg4j.engine.RunResult;
import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;
import io.github.pandong2015.ffmpeg4j.facade.BurnSubtitlesOptions;
import io.github.pandong2015.ffmpeg4j.facade.ClipOptions;
import io.github.pandong2015.ffmpeg4j.facade.ConcatOptions;
import io.github.pandong2015.ffmpeg4j.facade.ExtractAudioOptions;
import io.github.pandong2015.ffmpeg4j.facade.FfmpegClient;
import io.github.pandong2015.ffmpeg4j.facade.GifOptions;
import io.github.pandong2015.ffmpeg4j.facade.HlsAbrOptions;
import io.github.pandong2015.ffmpeg4j.facade.HlsAbrResult;
import io.github.pandong2015.ffmpeg4j.facade.HlsOptions;
import io.github.pandong2015.ffmpeg4j.facade.HlsResult;
import io.github.pandong2015.ffmpeg4j.facade.ThumbnailOptions;
import io.github.pandong2015.ffmpeg4j.facade.TranscodeOptions;
import io.github.pandong2015.ffmpeg4j.facade.RemuxOptions;
import io.github.pandong2015.ffmpeg4j.probe.ProbeResult;
import io.github.pandong2015.ffmpeg4j.task.TaskEvent;
import io.github.pandong2015.ffmpeg4j.task.TaskId;
import io.github.pandong2015.ffmpeg4j.task.TaskHandle;

/**
 * 带 Micrometer 埋点的 {@link FfmpegClient}：覆盖每个门面「叶子」方法（接收 {@code XxxOptions} 者 + probe），
 * 用 {@link Timer} 计量执行耗时（tag：{@code operation}/{@code result}）、失败按 {@code FfmpegException.reason()}
 * 分桶计数（有界基数，映射 core 的 ErrorPattern），并以 {@link Gauge} 暴露当前运行中的门面任务数（≈ 活跃子进程数）。
 *
 * <p>只覆盖叶子方法即可覆盖全部调用：便捷重载在父类内部以虚派发调用叶子方法，故经由本子类时自动被计量一次
 * （不会重复计时）。
 */
public class MeteredFfmpegClient extends FfmpegClient {

    private static final Logger LOG = Logger.getLogger(MeteredFfmpegClient.class.getName());

    static final String TIMER = "ffmpeg4j.facade.duration";
    static final String ERRORS = "ffmpeg4j.facade.errors";
    static final String ACTIVE = "ffmpeg4j.subprocess.active";
    static final String LIFECYCLE = "ffmpeg4j.task.lifecycle";
    static final String REJECTED = "ffmpeg4j.task.rejected";

    private final MeterRegistry registry;
    private final AtomicInteger active;
    private final TaskReportMetrics taskReportMetrics;

    public MeteredFfmpegClient(FfmpegEnvironment env, RunOptions defaultRunOptions,
                               Executor asyncExecutor, MeterRegistry registry) {
        this(env, defaultRunOptions, asyncExecutor, registry, null);
    }

    public MeteredFfmpegClient(
            FfmpegEnvironment env,
            RunOptions defaultRunOptions,
            Executor asyncExecutor,
            MeterRegistry registry,
            Consumer<TaskEvent> taskEventListener) {
        this(env, defaultRunOptions, asyncExecutor, registry, taskEventListener,
                new AtomicInteger(), java.util.concurrent.ConcurrentHashMap.newKeySet());
    }

    private MeteredFfmpegClient(
            FfmpegEnvironment env,
            RunOptions defaultRunOptions,
            Executor asyncExecutor,
            MeterRegistry registry,
            Consumer<TaskEvent> taskEventListener,
            AtomicInteger active,
            Set<TaskId> activeTasks) {
        super(env, defaultRunOptions, asyncExecutor,
                lifecycleObserver(registry, active, activeTasks, taskEventListener));
        this.registry = registry;
        this.active = active;
        this.taskReportMetrics = new TaskReportMetrics(registry);
        Gauge.builder(ACTIVE, active, AtomicInteger::get)
                .description("当前运行中的 ffmpeg 门面任务数（≈ 活跃子进程数）")
                .register(registry);
    }

    // ===== 同步叶子方法计量 =====

    @Override
    public RunResult transcode(File in, File out, TranscodeOptions options) {
        return timed("transcode", () -> super.transcode(in, out, options));
    }

    @Override
    public RunResult remux(File in, File out, RemuxOptions options) {
        return timed("remux", () -> super.remux(in, out, options));
    }

    @Override
    public RunResult clip(File in, File out, double startSec, double endSec, ClipOptions options) {
        return timed("clip", () -> super.clip(in, out, startSec, endSec, options));
    }

    @Override
    public RunResult extractAudio(File in, File out, ExtractAudioOptions options) {
        return timed("extractAudio", () -> super.extractAudio(in, out, options));
    }

    @Override
    public RunResult thumbnail(File in, File out, double atSec, ThumbnailOptions options) {
        return timed("thumbnail", () -> super.thumbnail(in, out, atSec, options));
    }

    @Override
    public RunResult gif(File in, File out, GifOptions options) {
        return timed("gif", () -> super.gif(in, out, options));
    }

    @Override
    public RunResult concat(List<File> ins, File out, ConcatOptions options) {
        return timed("concat", () -> super.concat(ins, out, options));
    }

    @Override
    public RunResult burnSubtitles(File video, File subtitle, File out, BurnSubtitlesOptions options) {
        return timed("burnSubtitles", () -> super.burnSubtitles(video, subtitle, out, options));
    }

    @Override
    public HlsResult hlsSegment(File in, File outDir, HlsOptions options) {
        return timed("hlsSegment", () -> super.hlsSegment(in, outDir, options));
    }

    @Override
    public HlsAbrResult hlsAbr(File in, File outDir, HlsAbrOptions options) {
        return timed("hlsAbr", () -> super.hlsAbr(in, outDir, options));
    }

    @Override
    public ProbeResult probe(File in) {
        return timed("probe", () -> super.probe(in));
    }

    // ===== 异步叶子方法计量 =====

    @Override
    public CompletableFuture<RunResult> transcodeAsync(File in, File out, TranscodeOptions options) {
        return timedAsync("transcode", () -> super.transcodeAsync(in, out, options));
    }

    @Override
    public CompletableFuture<RunResult> remuxAsync(File in, File out, RemuxOptions options) {
        return timedAsync("remux", () -> super.remuxAsync(in, out, options));
    }

    @Override
    public CompletableFuture<RunResult> clipAsync(File in, File out, double startSec, double endSec, ClipOptions options) {
        return timedAsync("clip", () -> super.clipAsync(in, out, startSec, endSec, options));
    }

    @Override
    public CompletableFuture<RunResult> extractAudioAsync(File in, File out, ExtractAudioOptions options) {
        return timedAsync("extractAudio", () -> super.extractAudioAsync(in, out, options));
    }

    @Override
    public CompletableFuture<RunResult> thumbnailAsync(File in, File out, double atSec, ThumbnailOptions options) {
        return timedAsync("thumbnail", () -> super.thumbnailAsync(in, out, atSec, options));
    }

    @Override
    public CompletableFuture<RunResult> gifAsync(File in, File out, GifOptions options) {
        return timedAsync("gif", () -> super.gifAsync(in, out, options));
    }

    @Override
    public CompletableFuture<RunResult> concatAsync(List<File> ins, File out, ConcatOptions options) {
        return timedAsync("concat", () -> super.concatAsync(ins, out, options));
    }

    @Override
    public CompletableFuture<RunResult> burnSubtitlesAsync(File video, File subtitle, File out, BurnSubtitlesOptions options) {
        return timedAsync("burnSubtitles", () -> super.burnSubtitlesAsync(video, subtitle, out, options));
    }

    @Override
    public CompletableFuture<HlsResult> hlsSegmentAsync(File in, File outDir, HlsOptions options) {
        return timedAsync("hlsSegment", () -> super.hlsSegmentAsync(in, outDir, options));
    }

    @Override
    public CompletableFuture<HlsAbrResult> hlsAbrAsync(File in, File outDir, HlsAbrOptions options) {
        return timedAsync("hlsAbr", () -> super.hlsAbrAsync(in, outDir, options));
    }

    @Override
    public CompletableFuture<ProbeResult> probeAsync(File in) {
        return timedAsync("probe", () -> super.probeAsync(in));
    }

    // ===== 带报告的任务入口：警告指标只从结构化报告读取 =====

    @Override
    public TaskHandle<RunResult> transcodeTask(
            TaskId taskId, File in, File out, TranscodeOptions options) {
        return taskReportMetrics.observe(
                "transcode", () -> super.transcodeTask(taskId, in, out, options));
    }

    @Override
    public TaskHandle<RunResult> remuxTask(
            TaskId taskId, File in, File out, RemuxOptions options) {
        return taskReportMetrics.observe(
                "remux", () -> super.remuxTask(taskId, in, out, options));
    }

    @Override
    public TaskHandle<HlsResult> hlsSegmentTask(
            TaskId taskId, File in, File outDir, HlsOptions options) {
        return taskReportMetrics.observe(
                "hlsSegment", () -> super.hlsSegmentTask(taskId, in, outDir, options));
    }

    @Override
    public TaskHandle<HlsAbrResult> hlsAbrTask(
            TaskId taskId, File in, File outDir, HlsAbrOptions options) {
        return taskReportMetrics.observe(
                "hlsAbr", () -> super.hlsAbrTask(taskId, in, outDir, options));
    }

    // ===== 埋点助手 =====

    private <T> T timed(String operation, Supplier<T> call) {
        Timer.Sample sample = Timer.start(registry);
        active.incrementAndGet();
        String result = "success";
        try {
            return call.get();
        } catch (RuntimeException | Error e) {
            result = "failure";
            recordFailure(operation, e);
            throw e;
        } finally {
            active.decrementAndGet();
            sample.stop(timer(operation, result));
        }
    }

    <T> CompletableFuture<T> timedAsync(String operation, Supplier<CompletableFuture<T>> call) {
        Timer.Sample sample = Timer.start(registry);
        active.incrementAndGet();
        AtomicBoolean metricsFinished = new AtomicBoolean();
        CompletableFuture<T> future;
        try {
            future = call.get();
        } catch (RuntimeException | Error e) {
            finishAsyncMetrics(operation, sample, e, metricsFinished);
            throw e;
        }
        try {
            // 只旁路观察，必须返回父类原 Future，保留其自定义 cancel 向底层任务传播的契约。
            future.whenComplete((value, err) -> finishAsyncMetrics(operation, sample, err, metricsFinished));
        } catch (RuntimeException | Error e) {
            finishAsyncMetrics(operation, sample, e, metricsFinished);
            throw e;
        }
        return future;
    }

    private Timer timer(String operation, String result) {
        return Timer.builder(TIMER)
                .tag("operation", operation)
                .tag("result", result)
                .register(registry);
    }

    private void recordFailure(String operation, FfmpegException e) {
        registry.counter(ERRORS, "operation", operation, "reason", reasonTag(e)).increment();
    }

    private void recordFailure(String operation, Throwable failure) {
        if (failure instanceof FfmpegException ffmpegFailure) {
            recordFailure(operation, ffmpegFailure);
            return;
        }
        registry.counter(ERRORS, "operation", operation, "reason", "error").increment();
    }

    private void finishAsyncMetrics(String operation, Timer.Sample sample, Throwable failure,
                                    AtomicBoolean metricsFinished) {
        if (!metricsFinished.compareAndSet(false, true)) {
            return;
        }
        active.decrementAndGet();
        String result = failure == null ? "success" : "failure";
        if (failure != null) {
            Throwable cause = (failure instanceof CompletionException && failure.getCause() != null)
                    ? failure.getCause() : failure;
            recordFailure(operation, cause);
        }
        sample.stop(timer(operation, result));
    }

    private static String reasonTag(FfmpegException e) {
        String reason = e.reason();
        return (reason == null || reason.isBlank()) ? "unknown" : reason;
    }

    private static Consumer<TaskEvent> lifecycleObserver(
            MeterRegistry registry,
            AtomicInteger active,
            Set<TaskId> activeTasks,
            Consumer<TaskEvent> downstream) {
        return event -> {
            registry.counter(
                    LIFECYCLE,
                    "operation", event.operation(),
                    "status", event.type().name().toLowerCase(java.util.Locale.ROOT))
                    .increment();
            if (event.type() == TaskEvent.Type.STARTED && activeTasks.add(event.taskId())) {
                active.incrementAndGet();
            } else if (terminal(event.type()) && activeTasks.remove(event.taskId())) {
                active.decrementAndGet();
            }
            if (event.type() == TaskEvent.Type.FAILED
                    && event.error() instanceof RejectedExecutionException) {
                registry.counter(REJECTED, "operation", event.operation()).increment();
                LOG.log(Level.WARNING,
                        "ffmpeg4j 任务被执行器拒绝：operation={0}, taskId={1}, reason={2}",
                        new Object[] {
                                event.operation(), event.taskId().value(), event.error().getMessage()
                        });
            }
            if (downstream != null) {
                downstream.accept(event);
            }
        };
    }

    private static boolean terminal(TaskEvent.Type type) {
        return type == TaskEvent.Type.COMPLETED
                || type == TaskEvent.Type.FAILED
                || type == TaskEvent.Type.CANCELLED;
    }
}
