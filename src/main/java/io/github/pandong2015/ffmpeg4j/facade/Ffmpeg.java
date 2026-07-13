package io.github.pandong2015.ffmpeg4j.facade;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.github.pandong2015.ffmpeg4j.compiler.CompiledCommand;
import io.github.pandong2015.ffmpeg4j.engine.RunResult;
import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;
import io.github.pandong2015.ffmpeg4j.probe.MediaProbe;
import io.github.pandong2015.ffmpeg4j.probe.ProbeResult;

/**
 * L4 门面入口：把最常见的整段任务收敛为一行式静态便捷方法，内部委托 L3 模型 + media-probe + L1 引擎。
 *
 * <p>每个门面提供两档重载：
 * <ul>
 *   <li><b>便捷位置重载</b>：{@link File} 参数、阻塞执行、返回 {@link RunResult}；</li>
 *   <li><b>进阶重载</b>：接收对应的不可变 {@code XxxOptions}（含 {@code onProgress}/{@code timeout} 及各自
 *       的特定项），映射为 {@link io.github.pandong2015.ffmpeg4j.engine.RunOptions}。</li>
 * </ul>
 * 唯一例外 {@link #probe(File)} 直接返回 {@link ProbeResult} 且无 Options 重载。
 *
 * <p>命令的「构建」与「执行」在 {@link FacadeSupport} 内分离：{@code buildXxx} 为纯函数、可脱离 ffmpeg
 * 断言 argv；本类负责必要的前置探测（remux/concat/extractAudio）、能力校验（burnSubtitles 需 libass）与执行。
 */
public final class Ffmpeg {

    private Ffmpeg() {
    }

    // ===== 1. transcode（强制转码）=====

    public static RunResult transcode(File in, File out, String videoCodec, String audioCodec) {
        return transcode(in, out,
                TranscodeOptions.defaults().videoCodec(videoCodec).audioCodec(audioCodec));
    }

    public static RunResult transcode(File in, File out, TranscodeOptions options) {
        CompiledCommand cmd = FacadeSupport.buildTranscode(in, out, options);
        return FacadeSupport.execute(cmd, options.toRunOptions());
    }

    // ===== 2. remux（换容器）=====

    public static RunResult remux(File in, File out) {
        return remux(in, out, RemuxOptions.defaults());
    }

    public static RunResult remux(File in, File out, RemuxOptions options) {
        ProbeResult probe = MediaProbe.probe(in);
        CompiledCommand cmd = FacadeSupport.buildRemux(in, out, probe, options);
        return FacadeSupport.execute(cmd, options.toRunOptions());
    }

    // ===== 3. clip（截段）=====

    public static RunResult clip(File in, File out, double startSec, double endSec) {
        return clip(in, out, startSec, endSec, ClipOptions.defaults());
    }

    public static RunResult clip(File in, File out, double startSec, double endSec, ClipOptions options) {
        CompiledCommand cmd = FacadeSupport.buildClip(in, out, startSec, endSec, options);
        return FacadeSupport.execute(cmd, options.toRunOptions());
    }

    // ===== 4. extractAudio =====

    public static RunResult extractAudio(File in, File out) {
        return extractAudio(in, out, ExtractAudioOptions.defaults());
    }

    public static RunResult extractAudio(File in, File out, ExtractAudioOptions options) {
        // 仅当目标为 m4a/aac 时才需要源编解码器信息判断可否 -c:a copy；此处统一探测，逻辑更简单且稳健。
        ProbeResult probe = MediaProbe.probe(in);
        CompiledCommand cmd = FacadeSupport.buildExtractAudio(in, out, probe, options);
        return FacadeSupport.execute(cmd, options.toRunOptions());
    }

    // ===== 5. thumbnail（抓帧）=====

    public static RunResult thumbnail(File in, File out, double atSec) {
        return thumbnail(in, out, atSec, ThumbnailOptions.defaults());
    }

    public static RunResult thumbnail(File in, File out, double atSec, ThumbnailOptions options) {
        CompiledCommand cmd = FacadeSupport.buildThumbnail(in, out, atSec, options);
        return FacadeSupport.execute(cmd, options.toRunOptions());
    }

    // ===== 6. concat（拼接）=====

    public static RunResult concat(List<File> ins, File out) {
        return concat(ins, out, ConcatOptions.defaults());
    }

    public static RunResult concat(List<File> ins, File out, ConcatOptions options) {
        List<ProbeResult> probes = new ArrayList<>(ins.size());
        for (File f : ins) {
            probes.add(MediaProbe.probe(f));
        }
        CompiledCommand cmd = FacadeSupport.buildConcat(ins, out, probes, options);
        return FacadeSupport.execute(cmd, options.toRunOptions());
    }

    // ===== 7. burnSubtitles =====

    public static RunResult burnSubtitles(File video, File subtitle, File out) {
        return burnSubtitles(video, subtitle, out, BurnSubtitlesOptions.defaults());
    }

    public static RunResult burnSubtitles(File video, File subtitle, File out, BurnSubtitlesOptions options) {
        // libass 前置校验：缺失即提前抛出可诊断异常，而非放任 ffmpeg 运行期报 "No such filter"。
        FfmpegEnvironment.shared().requireLibass();
        CompiledCommand cmd = FacadeSupport.buildBurnSubtitles(video, subtitle, out, options);
        return FacadeSupport.execute(cmd, options.toRunOptions());
    }

    // ===== 8. probe =====

    /** 探测媒体文件的容器与流信息（无 Options 重载）。 */
    public static ProbeResult probe(File in) {
        return MediaProbe.probe(in);
    }
}
