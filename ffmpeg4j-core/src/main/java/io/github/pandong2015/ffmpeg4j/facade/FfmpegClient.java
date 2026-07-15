package io.github.pandong2015.ffmpeg4j.facade;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.github.pandong2015.ffmpeg4j.compiler.CompiledCommand;
import io.github.pandong2015.ffmpeg4j.engine.FfmpegExecutor;
import io.github.pandong2015.ffmpeg4j.engine.FfmpegRun;
import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;
import io.github.pandong2015.ffmpeg4j.engine.RunResult;
import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;
import io.github.pandong2015.ffmpeg4j.probe.MediaProbe;
import io.github.pandong2015.ffmpeg4j.probe.ProbeResult;

/**
 * 可实例化的 L4 门面：承载与静态 {@link Ffmpeg} 相同的 8 个一行式（transcode/remux/clip/extractAudio/
 * thumbnail/concat/burnSubtitles/probe），但以<em>注入</em>的 {@link FfmpegEnvironment} 执行、以构造时的
 * 默认 {@link RunOptions} 为基线——因此可作为普通 bean 被 Spring 容器注入并按 {@code application.yml} 配置。
 *
 * <p>与静态门面的关系：静态 {@link Ffmpeg} 各方法委托给一个以 {@link FfmpegEnvironment#shared()} 与
 * {@link RunOptions#defaults()} 构造的「默认实例」，故本类的引入对老用户是<em>纯增量</em>——命令构建
 * （{@code FacadeSupport.buildXxx} 纯函数）与执行（{@link FfmpegExecutor}）两侧实现完全共享。
 *
 * <p><b>环境隔离</b>：本实例的所有门面均使用<em>本实例</em>持有的 {@code env}——包括 {@code probe} 走该
 * {@code env} 配置的 {@code ffprobe}，{@code burnSubtitles} 的 libass 前置校验也走本实例的 {@code env}。
 *
 * <p><b>RunOptions 合并</b>：每个门面的调用点 {@code XxxOptions}（若给定其 {@code timeout}/{@code onProgress}）
 * 与构造时的 {@code defaultRunOptions} 按字段合并——调用点显式设定的字段覆盖默认，其余（宽限期、
 * {@code callbackExecutor} 等）沿用默认。
 *
 * <p><b>异步</b>：每个阻塞门面都有对应的 {@code xxxAsync} 变体返回 {@link CompletableFuture}，在构造时给定的
 * {@link Executor}（默认 {@link ForkJoinPool#commonPool()}）上执行、不阻塞调用线程；失败以原始
 * {@link io.github.pandong2015.ffmpeg4j.FfmpegException} {@code completeExceptionally}；对返回句柄调用
 * {@link CompletableFuture#cancel(boolean) cancel} 会复用 core 的优雅取消阶梯（写 {@code q} → SIGTERM → SIGKILL）。
 *
 * <p><b>可扩展</b>：本类非 {@code final}，字段全 {@code final} 故子类无法破坏其不可变状态——留此接缝供
 * 横切埋点（如 Spring starter 的 Micrometer 计时/计数装饰子类）覆盖门面方法。
 */
public class FfmpegClient {

    private final FfmpegEnvironment env;
    private final RunOptions defaultRunOptions;
    private final Executor asyncExecutor;

    /**
     * @param env               执行与探测所用的环境（含已解析的二进制与构建能力）
     * @param defaultRunOptions 门面执行的默认运行选项基线（超时/宽限期/callbackExecutor 等）
     */
    public FfmpegClient(FfmpegEnvironment env, RunOptions defaultRunOptions) {
        this(env, defaultRunOptions, null);
    }

    /**
     * @param env               执行与探测所用的环境
     * @param defaultRunOptions 默认运行选项基线
     * @param asyncExecutor     {@code xxxAsync} 异步门面的执行器；{@code null} 则用 {@link ForkJoinPool#commonPool()}
     */
    public FfmpegClient(FfmpegEnvironment env, RunOptions defaultRunOptions, Executor asyncExecutor) {
        this.env = Objects.requireNonNull(env, "env");
        this.defaultRunOptions = Objects.requireNonNull(defaultRunOptions, "defaultRunOptions");
        this.asyncExecutor = asyncExecutor != null ? asyncExecutor : ForkJoinPool.commonPool();
    }

    /** 本实例持有的环境。 */
    public FfmpegEnvironment environment() {
        return env;
    }

    /** 本实例的默认运行选项基线。 */
    public RunOptions defaultRunOptions() {
        return defaultRunOptions;
    }

    // ===== 1. transcode（强制转码）=====

    public RunResult transcode(File in, File out, String videoCodec, String audioCodec) {
        return transcode(in, out,
                TranscodeOptions.defaults().videoCodec(videoCodec).audioCodec(audioCodec));
    }

    public RunResult transcode(File in, File out, TranscodeOptions options) {
        CompiledCommand cmd = FacadeSupport.buildTranscode(in, out, options);
        return FacadeSupport.execute(cmd, env, eff(options.timeout(), options.onProgress()));
    }

    public CompletableFuture<RunResult> transcodeAsync(File in, File out, String videoCodec, String audioCodec) {
        return transcodeAsync(in, out,
                TranscodeOptions.defaults().videoCodec(videoCodec).audioCodec(audioCodec));
    }

    public CompletableFuture<RunResult> transcodeAsync(File in, File out, TranscodeOptions options) {
        return executeAsync(() -> FacadeSupport.buildTranscode(in, out, options),
                eff(options.timeout(), options.onProgress()));
    }

    // ===== 2. remux（换容器）=====

    public RunResult remux(File in, File out) {
        return remux(in, out, RemuxOptions.defaults());
    }

    public RunResult remux(File in, File out, RemuxOptions options) {
        ProbeResult probe = probeWith(in);
        CompiledCommand cmd = FacadeSupport.buildRemux(in, out, probe, options);
        return FacadeSupport.execute(cmd, env, eff(options.timeout(), options.onProgress()));
    }

    public CompletableFuture<RunResult> remuxAsync(File in, File out) {
        return remuxAsync(in, out, RemuxOptions.defaults());
    }

    public CompletableFuture<RunResult> remuxAsync(File in, File out, RemuxOptions options) {
        return executeAsync(() -> FacadeSupport.buildRemux(in, out, probeWith(in), options),
                eff(options.timeout(), options.onProgress()));
    }

    // ===== 3. clip（截段）=====

    public RunResult clip(File in, File out, double startSec, double endSec) {
        return clip(in, out, startSec, endSec, ClipOptions.defaults());
    }

    public RunResult clip(File in, File out, double startSec, double endSec, ClipOptions options) {
        CompiledCommand cmd = FacadeSupport.buildClip(in, out, startSec, endSec, options);
        return FacadeSupport.execute(cmd, env, eff(options.timeout(), options.onProgress()));
    }

    public CompletableFuture<RunResult> clipAsync(File in, File out, double startSec, double endSec) {
        return clipAsync(in, out, startSec, endSec, ClipOptions.defaults());
    }

    public CompletableFuture<RunResult> clipAsync(File in, File out, double startSec, double endSec, ClipOptions options) {
        return executeAsync(() -> FacadeSupport.buildClip(in, out, startSec, endSec, options),
                eff(options.timeout(), options.onProgress()));
    }

    // ===== 4. extractAudio =====

    public RunResult extractAudio(File in, File out) {
        return extractAudio(in, out, ExtractAudioOptions.defaults());
    }

    public RunResult extractAudio(File in, File out, ExtractAudioOptions options) {
        ProbeResult probe = probeWith(in);
        CompiledCommand cmd = FacadeSupport.buildExtractAudio(in, out, probe, options);
        return FacadeSupport.execute(cmd, env, eff(options.timeout(), options.onProgress()));
    }

    public CompletableFuture<RunResult> extractAudioAsync(File in, File out) {
        return extractAudioAsync(in, out, ExtractAudioOptions.defaults());
    }

    public CompletableFuture<RunResult> extractAudioAsync(File in, File out, ExtractAudioOptions options) {
        return executeAsync(() -> FacadeSupport.buildExtractAudio(in, out, probeWith(in), options),
                eff(options.timeout(), options.onProgress()));
    }

    // ===== 5. thumbnail（抓帧）=====

    public RunResult thumbnail(File in, File out, double atSec) {
        return thumbnail(in, out, atSec, ThumbnailOptions.defaults());
    }

    public RunResult thumbnail(File in, File out, double atSec, ThumbnailOptions options) {
        CompiledCommand cmd = FacadeSupport.buildThumbnail(in, out, atSec, options);
        return FacadeSupport.execute(cmd, env, eff(options.timeout(), options.onProgress()));
    }

    public CompletableFuture<RunResult> thumbnailAsync(File in, File out, double atSec) {
        return thumbnailAsync(in, out, atSec, ThumbnailOptions.defaults());
    }

    public CompletableFuture<RunResult> thumbnailAsync(File in, File out, double atSec, ThumbnailOptions options) {
        return executeAsync(() -> FacadeSupport.buildThumbnail(in, out, atSec, options),
                eff(options.timeout(), options.onProgress()));
    }

    // ===== 5b. gif（两遍调色板生成 GIF）=====

    public RunResult gif(File in, File out) {
        return gif(in, out, GifOptions.defaults());
    }

    public RunResult gif(File in, File out, GifOptions options) {
        CompiledCommand cmd = FacadeSupport.buildGif(in, out, options);
        return FacadeSupport.execute(cmd, env, eff(options.timeout(), options.onProgress()));
    }

    public CompletableFuture<RunResult> gifAsync(File in, File out) {
        return gifAsync(in, out, GifOptions.defaults());
    }

    public CompletableFuture<RunResult> gifAsync(File in, File out, GifOptions options) {
        return executeAsync(() -> FacadeSupport.buildGif(in, out, options),
                eff(options.timeout(), options.onProgress()));
    }

    // ===== 6. concat（拼接）=====

    public RunResult concat(List<File> ins, File out) {
        return concat(ins, out, ConcatOptions.defaults());
    }

    public RunResult concat(List<File> ins, File out, ConcatOptions options) {
        CompiledCommand cmd = FacadeSupport.buildConcat(ins, out, probeAll(ins), options);
        return FacadeSupport.execute(cmd, env, eff(options.timeout(), options.onProgress()));
    }

    public CompletableFuture<RunResult> concatAsync(List<File> ins, File out) {
        return concatAsync(ins, out, ConcatOptions.defaults());
    }

    public CompletableFuture<RunResult> concatAsync(List<File> ins, File out, ConcatOptions options) {
        return executeAsync(() -> FacadeSupport.buildConcat(ins, out, probeAll(ins), options),
                eff(options.timeout(), options.onProgress()));
    }

    // ===== 7. burnSubtitles =====

    public RunResult burnSubtitles(File video, File subtitle, File out) {
        return burnSubtitles(video, subtitle, out, BurnSubtitlesOptions.defaults());
    }

    public RunResult burnSubtitles(File video, File subtitle, File out, BurnSubtitlesOptions options) {
        // libass 前置校验走本实例的 env：缺失即提前抛出可诊断异常，而非放任 ffmpeg 运行期报 "No such filter"。
        env.requireLibass();
        CompiledCommand cmd = FacadeSupport.buildBurnSubtitles(video, subtitle, out, options);
        return FacadeSupport.execute(cmd, env, eff(options.timeout(), options.onProgress()));
    }

    public CompletableFuture<RunResult> burnSubtitlesAsync(File video, File subtitle, File out) {
        return burnSubtitlesAsync(video, subtitle, out, BurnSubtitlesOptions.defaults());
    }

    public CompletableFuture<RunResult> burnSubtitlesAsync(File video, File subtitle, File out, BurnSubtitlesOptions options) {
        return executeAsync(() -> {
            env.requireLibass();
            return FacadeSupport.buildBurnSubtitles(video, subtitle, out, options);
        }, eff(options.timeout(), options.onProgress()));
    }

    // ===== 8. probe =====

    /** 探测媒体文件的容器与流信息，走本实例 {@code env} 配置的 {@code ffprobe}（无 Options 重载）。 */
    public ProbeResult probe(File in) {
        return probeWith(in);
    }

    /** {@link #probe(File)} 的异步变体：在 {@code asyncExecutor} 上执行 ffprobe，返回 {@link CompletableFuture}。 */
    public CompletableFuture<ProbeResult> probeAsync(File in) {
        return CompletableFuture.supplyAsync(() -> probeWith(in), asyncExecutor);
    }

    // ===== 内部：环境接线与异步桥接 =====

    /** 用本实例 env 的 ffprobe 探测。 */
    private ProbeResult probeWith(File f) {
        Objects.requireNonNull(f, "file");
        return MediaProbe.probe(f.toPath(), env.binaries().ffprobeCommand());
    }

    /** 逐个 probe 一组输入（供 concat 使用）。 */
    private List<ProbeResult> probeAll(List<File> ins) {
        List<ProbeResult> probes = new ArrayList<>(ins.size());
        for (File f : ins) {
            probes.add(probeWith(f));
        }
        return probes;
    }

    /** 把调用点 timeout/onProgress 合并到默认 RunOptions 之上。 */
    private RunOptions eff(Duration timeout, Consumer<Progress> onProgress) {
        return FacadeSupport.runOptions(defaultRunOptions, timeout, onProgress);
    }

    /**
     * 在 {@code asyncExecutor} 上执行「（可含 probe 的）命令构建 + runAsync + await」，返回可取消的 future。
     * 命令构建（可能阻塞的 probe）在 executor 线程完成，不占调用线程；对 future 调用 {@code cancel} 会把优雅
     * 取消阶梯转达给底层 {@link FfmpegRun}（若已启动）。
     */
    private CompletableFuture<RunResult> executeAsync(Supplier<CompiledCommand> cmdSupplier, RunOptions ro) {
        AtomicReference<FfmpegRun> runRef = new AtomicReference<>();
        CompletableFuture<RunResult> future = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                FfmpegRun run = runRef.get();
                if (run != null) {
                    run.cancel(); // 优雅阶梯：写 q → SIGTERM → SIGKILL（pipe 输入下降级）
                }
                return super.cancel(mayInterruptIfRunning);
            }
        };
        asyncExecutor.execute(() -> {
            if (future.isCancelled()) {
                return;
            }
            try {
                CompiledCommand cmd = cmdSupplier.get();
                FfmpegRun run = new FfmpegExecutor(env).runAsync(cmd, ro);
                runRef.set(run);
                if (future.isCancelled()) {
                    run.cancel();
                    return;
                }
                future.complete(run.await());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }
}
