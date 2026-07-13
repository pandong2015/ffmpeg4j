package io.github.pandong2015.ffmpeg4j.facade;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import io.github.pandong2015.ffmpeg4j.compiler.CompiledCommand;
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
 * （{@code FacadeSupport.buildXxx} 纯函数）与执行（{@link io.github.pandong2015.ffmpeg4j.engine.FfmpegExecutor}）
 * 两侧实现完全共享。
 *
 * <p><b>环境隔离</b>：本实例的所有门面均使用<em>本实例</em>持有的 {@code env}——包括 {@code probe} 走该
 * {@code env} 配置的 {@code ffprobe}（{@code env.binaries().ffprobeCommand()}），而非 PATH 默认；
 * {@code burnSubtitles} 的 libass 前置校验也走本实例的 {@code env}。
 *
 * <p><b>RunOptions 合并</b>：每个门面的调用点 {@code XxxOptions}（若给定其 {@code timeout}/{@code onProgress}）
 * 与构造时的 {@code defaultRunOptions} 按字段合并——调用点显式设定的字段覆盖默认，其余（取消/终止宽限期、
 * {@code callbackExecutor} 等）沿用默认。
 */
public final class FfmpegClient {

    private final FfmpegEnvironment env;
    private final RunOptions defaultRunOptions;

    /**
     * @param env               执行与探测所用的环境（含已解析的二进制与构建能力）
     * @param defaultRunOptions 门面执行的默认运行选项基线（超时/宽限期/callbackExecutor 等）
     */
    public FfmpegClient(FfmpegEnvironment env, RunOptions defaultRunOptions) {
        this.env = Objects.requireNonNull(env, "env");
        this.defaultRunOptions = Objects.requireNonNull(defaultRunOptions, "defaultRunOptions");
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

    // ===== 2. remux（换容器）=====

    public RunResult remux(File in, File out) {
        return remux(in, out, RemuxOptions.defaults());
    }

    public RunResult remux(File in, File out, RemuxOptions options) {
        ProbeResult probe = probeWith(in);
        CompiledCommand cmd = FacadeSupport.buildRemux(in, out, probe, options);
        return FacadeSupport.execute(cmd, env, eff(options.timeout(), options.onProgress()));
    }

    // ===== 3. clip（截段）=====

    public RunResult clip(File in, File out, double startSec, double endSec) {
        return clip(in, out, startSec, endSec, ClipOptions.defaults());
    }

    public RunResult clip(File in, File out, double startSec, double endSec, ClipOptions options) {
        CompiledCommand cmd = FacadeSupport.buildClip(in, out, startSec, endSec, options);
        return FacadeSupport.execute(cmd, env, eff(options.timeout(), options.onProgress()));
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

    // ===== 5. thumbnail（抓帧）=====

    public RunResult thumbnail(File in, File out, double atSec) {
        return thumbnail(in, out, atSec, ThumbnailOptions.defaults());
    }

    public RunResult thumbnail(File in, File out, double atSec, ThumbnailOptions options) {
        CompiledCommand cmd = FacadeSupport.buildThumbnail(in, out, atSec, options);
        return FacadeSupport.execute(cmd, env, eff(options.timeout(), options.onProgress()));
    }

    // ===== 6. concat（拼接）=====

    public RunResult concat(List<File> ins, File out) {
        return concat(ins, out, ConcatOptions.defaults());
    }

    public RunResult concat(List<File> ins, File out, ConcatOptions options) {
        List<ProbeResult> probes = new ArrayList<>(ins.size());
        for (File f : ins) {
            probes.add(probeWith(f));
        }
        CompiledCommand cmd = FacadeSupport.buildConcat(ins, out, probes, options);
        return FacadeSupport.execute(cmd, env, eff(options.timeout(), options.onProgress()));
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

    // ===== 8. probe =====

    /** 探测媒体文件的容器与流信息，走本实例 {@code env} 配置的 {@code ffprobe}（无 Options 重载）。 */
    public ProbeResult probe(File in) {
        return probeWith(in);
    }

    // ===== 内部：环境接线 =====

    /** 用本实例 env 的 ffprobe 探测。 */
    private ProbeResult probeWith(File f) {
        Objects.requireNonNull(f, "file");
        return MediaProbe.probe(f.toPath(), env.binaries().ffprobeCommand());
    }

    /** 把调用点 timeout/onProgress 合并到默认 RunOptions 之上。 */
    private RunOptions eff(Duration timeout, Consumer<Progress> onProgress) {
        return FacadeSupport.runOptions(defaultRunOptions, timeout, onProgress);
    }
}
