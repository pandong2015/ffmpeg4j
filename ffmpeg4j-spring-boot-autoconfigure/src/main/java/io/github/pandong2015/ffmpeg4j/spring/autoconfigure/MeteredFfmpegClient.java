package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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
import io.github.pandong2015.ffmpeg4j.facade.ThumbnailOptions;
import io.github.pandong2015.ffmpeg4j.facade.TranscodeOptions;
import io.github.pandong2015.ffmpeg4j.facade.RemuxOptions;
import io.github.pandong2015.ffmpeg4j.probe.ProbeResult;

/**
 * 带 Micrometer 埋点的 {@link FfmpegClient}：覆盖每个门面「叶子」方法（接收 {@code XxxOptions} 者 + probe），
 * 用 {@link Timer} 计量执行耗时（tag：{@code operation}/{@code result}）、失败按 {@code FfmpegException.reason()}
 * 分桶计数（有界基数，映射 core 的 ErrorPattern），并以 {@link Gauge} 暴露当前运行中的门面任务数（≈ 活跃子进程数）。
 *
 * <p>只覆盖叶子方法即可覆盖全部调用：便捷重载在父类内部以虚派发调用叶子方法，故经由本子类时自动被计量一次
 * （不会重复计时）。
 */
public class MeteredFfmpegClient extends FfmpegClient {

    static final String TIMER = "ffmpeg4j.facade.duration";
    static final String ERRORS = "ffmpeg4j.facade.errors";
    static final String ACTIVE = "ffmpeg4j.subprocess.active";

    private final MeterRegistry registry;
    private final AtomicInteger active = new AtomicInteger();

    public MeteredFfmpegClient(FfmpegEnvironment env, RunOptions defaultRunOptions,
                               Executor asyncExecutor, MeterRegistry registry) {
        super(env, defaultRunOptions, asyncExecutor);
        this.registry = registry;
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
    public RunResult concat(List<File> ins, File out, ConcatOptions options) {
        return timed("concat", () -> super.concat(ins, out, options));
    }

    @Override
    public RunResult burnSubtitles(File video, File subtitle, File out, BurnSubtitlesOptions options) {
        return timed("burnSubtitles", () -> super.burnSubtitles(video, subtitle, out, options));
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
    public CompletableFuture<RunResult> concatAsync(List<File> ins, File out, ConcatOptions options) {
        return timedAsync("concat", () -> super.concatAsync(ins, out, options));
    }

    @Override
    public CompletableFuture<RunResult> burnSubtitlesAsync(File video, File subtitle, File out, BurnSubtitlesOptions options) {
        return timedAsync("burnSubtitles", () -> super.burnSubtitlesAsync(video, subtitle, out, options));
    }

    @Override
    public CompletableFuture<ProbeResult> probeAsync(File in) {
        return timedAsync("probe", () -> super.probeAsync(in));
    }

    // ===== 埋点助手 =====

    private <T> T timed(String operation, Supplier<T> call) {
        Timer.Sample sample = Timer.start(registry);
        active.incrementAndGet();
        String result = "success";
        try {
            return call.get();
        } catch (FfmpegException e) {
            result = "failure";
            recordFailure(operation, e);
            throw e;
        } finally {
            active.decrementAndGet();
            sample.stop(timer(operation, result));
        }
    }

    private <T> CompletableFuture<T> timedAsync(String operation, Supplier<CompletableFuture<T>> call) {
        Timer.Sample sample = Timer.start(registry);
        active.incrementAndGet();
        CompletableFuture<T> future = call.get();
        return future.whenComplete((value, err) -> {
            active.decrementAndGet();
            String result = err == null ? "success" : "failure";
            if (err != null) {
                Throwable cause = (err instanceof CompletionException && err.getCause() != null) ? err.getCause() : err;
                if (cause instanceof FfmpegException fe) {
                    recordFailure(operation, fe);
                } else {
                    registry.counter(ERRORS, "operation", operation, "reason", "error").increment();
                }
            }
            sample.stop(timer(operation, result));
        });
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

    private static String reasonTag(FfmpegException e) {
        String reason = e.reason();
        return (reason == null || reason.isBlank()) ? "unknown" : reason;
    }
}
