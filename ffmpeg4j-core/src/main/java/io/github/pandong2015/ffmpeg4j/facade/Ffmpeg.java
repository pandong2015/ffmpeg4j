package io.github.pandong2015.ffmpeg4j.facade;

import java.io.File;
import java.util.List;

import io.github.pandong2015.ffmpeg4j.engine.RunOptions;
import io.github.pandong2015.ffmpeg4j.engine.RunResult;
import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;
import io.github.pandong2015.ffmpeg4j.probe.MediaProbe;
import io.github.pandong2015.ffmpeg4j.probe.ProbeResult;

/**
 * L4 门面入口（静态便捷层）：把最常见的整段任务收敛为一行式静态方法，内部委托 L3 模型 + media-probe + L1 引擎。
 *
 * <p>各方法委托给一个「默认实例」{@link FfmpegClient}——以 {@link FfmpegEnvironment#shared()} 与
 * {@link RunOptions#defaults()} <em>惰性</em>构造（首次调用才触发环境探测，与历史语义一致），因此未显式配置
 * 任何环境时行为与历史版本完全相同。需要按配置注入环境（如 Spring Boot 场景），改用可实例化的
 * {@link FfmpegClient}。
 *
 * <p>每个门面提供两档重载：<b>便捷位置重载</b>（{@link File} 参数、阻塞执行、返回 {@link RunResult}）与
 * <b>进阶重载</b>（接收对应的不可变 {@code XxxOptions}）。唯一例外 {@link #probe(File)} 直接委托
 * {@link MediaProbe}（仅需 ffprobe、不强制触发 ffmpeg 环境探测），保持「只装了 ffprobe 也能 probe」的历史语义。
 */
public final class Ffmpeg {

    /** 惰性构造的默认实例（进程级），承接全部静态门面的实际执行。 */
    private static volatile FfmpegClient defaultClient;

    private Ffmpeg() {
    }

    /** 惰性构造默认实例：首次调用触发一次 {@link FfmpegEnvironment#shared()} 探测（与历史「首次调用才探测」一致）。 */
    private static FfmpegClient defaultClient() {
        FfmpegClient c = defaultClient;
        if (c == null) {
            synchronized (Ffmpeg.class) {
                c = defaultClient;
                if (c == null) {
                    c = new FfmpegClient(FfmpegEnvironment.shared(), RunOptions.defaults());
                    defaultClient = c;
                }
            }
        }
        return c;
    }

    // ===== 1. transcode（强制转码）=====

    public static RunResult transcode(File in, File out, String videoCodec, String audioCodec) {
        return defaultClient().transcode(in, out, videoCodec, audioCodec);
    }

    public static RunResult transcode(File in, File out, TranscodeOptions options) {
        return defaultClient().transcode(in, out, options);
    }

    // ===== 2. remux（换容器）=====

    public static RunResult remux(File in, File out) {
        return defaultClient().remux(in, out);
    }

    public static RunResult remux(File in, File out, RemuxOptions options) {
        return defaultClient().remux(in, out, options);
    }

    // ===== 3. clip（截段）=====

    public static RunResult clip(File in, File out, double startSec, double endSec) {
        return defaultClient().clip(in, out, startSec, endSec);
    }

    public static RunResult clip(File in, File out, double startSec, double endSec, ClipOptions options) {
        return defaultClient().clip(in, out, startSec, endSec, options);
    }

    // ===== 4. extractAudio =====

    public static RunResult extractAudio(File in, File out) {
        return defaultClient().extractAudio(in, out);
    }

    public static RunResult extractAudio(File in, File out, ExtractAudioOptions options) {
        return defaultClient().extractAudio(in, out, options);
    }

    // ===== 5. thumbnail（抓帧）=====

    public static RunResult thumbnail(File in, File out, double atSec) {
        return defaultClient().thumbnail(in, out, atSec);
    }

    public static RunResult thumbnail(File in, File out, double atSec, ThumbnailOptions options) {
        return defaultClient().thumbnail(in, out, atSec, options);
    }

    // ===== 6. concat（拼接）=====

    public static RunResult concat(List<File> ins, File out) {
        return defaultClient().concat(ins, out);
    }

    public static RunResult concat(List<File> ins, File out, ConcatOptions options) {
        return defaultClient().concat(ins, out, options);
    }

    // ===== 7. burnSubtitles =====

    public static RunResult burnSubtitles(File video, File subtitle, File out) {
        return defaultClient().burnSubtitles(video, subtitle, out);
    }

    public static RunResult burnSubtitles(File video, File subtitle, File out, BurnSubtitlesOptions options) {
        return defaultClient().burnSubtitles(video, subtitle, out, options);
    }

    // ===== 8. probe =====

    /**
     * 探测媒体文件的容器与流信息（无 Options 重载）。
     *
     * <p>直接委托 {@link MediaProbe#probe(File)}——仅需 {@code ffprobe}，不触发 {@link FfmpegEnvironment#shared()}
     * 的完整探测（后者要求 ffmpeg 也在），保持「只装了 ffprobe 也能 probe」的历史语义。
     */
    public static ProbeResult probe(File in) {
        return MediaProbe.probe(in);
    }
}
