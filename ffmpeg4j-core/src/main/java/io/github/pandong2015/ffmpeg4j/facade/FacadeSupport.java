package io.github.pandong2015.ffmpeg4j.facade;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

import io.github.pandong2015.ffmpeg4j.FfmpegException;
import io.github.pandong2015.ffmpeg4j.compiler.CompiledCommand;
import io.github.pandong2015.ffmpeg4j.compiler.GraphCompiler;
import io.github.pandong2015.ffmpeg4j.engine.FfmpegExecutor;
import io.github.pandong2015.ffmpeg4j.engine.Progress;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;
import io.github.pandong2015.ffmpeg4j.engine.RunResult;
import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;
import io.github.pandong2015.ffmpeg4j.model.AudioNormTarget;
import io.github.pandong2015.ffmpeg4j.model.AudioStream;
import io.github.pandong2015.ffmpeg4j.model.Filters;
import io.github.pandong2015.ffmpeg4j.model.Input;
import io.github.pandong2015.ffmpeg4j.model.Normalization;
import io.github.pandong2015.ffmpeg4j.model.Output;
import io.github.pandong2015.ffmpeg4j.model.Stream;
import io.github.pandong2015.ffmpeg4j.model.VideoNormTarget;
import io.github.pandong2015.ffmpeg4j.model.VideoStream;
import io.github.pandong2015.ffmpeg4j.probe.ProbeResult;
import io.github.pandong2015.ffmpeg4j.probe.StreamInfo;

/**
 * L4 门面的内部支撑：把「构建 {@link CompiledCommand}」与「执行」分离。
 *
 * <p>所有 {@code buildXxx(...)} 都是<em>纯函数</em>——只依赖入参（含可注入的 {@link ProbeResult}），
 * 不触发任何子进程，故无需真实 ffmpeg 即可对产出的 argv 做单元断言。执行由 {@link #execute} 承担，
 * 委托 L1 {@link FfmpegExecutor}（经 {@link FfmpegEnvironment#shared()} 解析二进制）。
 */
final class FacadeSupport {

    private static final Logger LOG = Logger.getLogger(FacadeSupport.class.getName());

    private FacadeSupport() {
    }

    // ===== 执行侧 =====

    /** 把 {@code onProgress}/{@code timeout} 映射为 {@link RunOptions}（其余取默认）。 */
    static RunOptions runOptions(Duration timeout, Consumer<Progress> onProgress) {
        RunOptions ro = RunOptions.defaults();
        if (timeout != null) {
            ro = ro.timeout(timeout);
        }
        if (onProgress != null) {
            ro = ro.onProgress(onProgress);
        }
        return ro;
    }

    /** 以进程级缓存环境阻塞执行编译产物（静态门面默认路径）。 */
    static RunResult execute(CompiledCommand cmd, RunOptions ro) {
        return execute(cmd, FfmpegEnvironment.shared(), ro);
    }

    /** 以显式注入的环境阻塞执行编译产物（供实例门面 {@link FfmpegClient} 使用配置的二进制）。 */
    static RunResult execute(CompiledCommand cmd, FfmpegEnvironment env, RunOptions ro) {
        return new FfmpegExecutor(env).run(cmd, ro);
    }

    /**
     * 以 {@code base} 为基础，用<em>非空</em>的 {@code timeout}/{@code onProgress} 逐字段覆盖后返回合并的
     * {@link RunOptions}。用于把调用点 {@code XxxOptions} 叠加到实例门面的默认 {@code RunOptions} 之上——
     * 调用点显式设定的字段覆盖默认，其余（宽限期/callbackExecutor 等）沿用 {@code base}。
     */
    static RunOptions runOptions(RunOptions base, Duration timeout, Consumer<Progress> onProgress) {
        RunOptions ro = base;
        if (timeout != null) {
            ro = ro.timeout(timeout);
        }
        if (onProgress != null) {
            ro = ro.onProgress(onProgress);
        }
        return ro;
    }

    // ===== 1. transcode（强制转码）=====

    static CompiledCommand buildTranscode(File in, File out, TranscodeOptions o) {
        Input input = Input.of(in);
        List<String> args = new ArrayList<>();
        args.add("-c:v");
        args.add(o.videoCodec());
        if (o.crf() != null) {
            args.add("-crf");
            args.add(o.crf().toString());
        }
        if (o.preset() != null) {
            args.add("-preset");
            args.add(o.preset());
        }
        if (o.videoBitrate() != null) {
            args.add("-b:v");
            args.add(o.videoBitrate());
        }
        args.add("-c:a");
        args.add(o.audioCodec());
        if (o.audioBitrate() != null) {
            args.add("-b:a");
            args.add(o.audioBitrate());
        }
        // 用可选映射（0:v:0?/0:a:0?）：纯音频输入无视轨、静音视频无音轨时静默跳过而非中止；
        // 未命中的流类型对应的 -c:v/-c:a 在无匹配输出流时被 ffmpeg 忽略，故仍可无条件附上。
        Output output = Output.to(out, input.videoOptional(), input.audioOptional())
                .withArgs(args.toArray(new String[0]));
        return new GraphCompiler().compile(output);
    }

    // ===== 2. remux（换容器，按流分派 -c copy / 字幕特判）=====

    static CompiledCommand buildRemux(File in, File out, ProbeResult probe, RemuxOptions o) {
        ContainerFamily family = ContainerFamily.of(out.getName());
        Input input = Input.of(in);
        List<Stream> mapped = new ArrayList<>();
        List<String> args = new ArrayList<>();

        List<StreamInfo> videos = probe.videoStreams();
        for (int i = 0; i < videos.size(); i++) {
            mapped.add(input.video(i));
        }
        if (!videos.isEmpty()) {
            args.add("-c:v");
            args.add("copy");
        }

        List<StreamInfo> audios = probe.audioStreams();
        for (int i = 0; i < audios.size(); i++) {
            mapped.add(input.audio(i));
        }
        if (!audios.isEmpty()) {
            args.add("-c:a");
            args.add("copy");
        }

        List<StreamInfo> subs = probe.subtitleStreams();
        boolean anySubMapped = false;
        for (int i = 0; i < subs.size(); i++) {
            StreamInfo s = subs.get(i);
            if (family == ContainerFamily.MP4_MOV) {
                if (ContainerFamily.isTextSubtitle(s.codecName())) {
                    mapped.add(input.subtitle(i));
                    anySubMapped = true;
                } else {
                    // 图形字幕（PGS/DVD/DVB）无法进 mp4：不映射并记录说明。
                    LOG.info("remux 目标为 mp4/mov，丢弃无法容纳的图形字幕流 #" + s.index()
                            + "（编解码器 " + s.codecName() + "）。");
                }
            } else {
                mapped.add(input.subtitle(i));
                anySubMapped = true;
            }
        }
        if (anySubMapped) {
            args.add("-c:s");
            args.add(family == ContainerFamily.MP4_MOV ? "mov_text" : "copy");
        }

        Output output = Output.to(out, mapped.toArray(new Stream[0])).withArgs(args.toArray(new String[0]));
        return new GraphCompiler().compile(output);
    }

    // ===== 3. clip（截段）=====

    static CompiledCommand buildClip(File in, File out, double startSec, double endSec, ClipOptions o) {
        double durationSec = endSec - startSec;
        if (durationSec <= 0) {
            throw new IllegalArgumentException("clip 区间无效：end(" + endSec + ") 必须大于 start(" + startSec + ")");
        }
        // 用可选映射（0:v:0?/0:a:0?）：源缺任一流（无声视频、纯音频文件）时静默跳过而非中止。
        if (o.reencode()) {
            // 精切：输出侧 -ss/-t（相对时间线，无歧义）+ 重编码，得到帧级精确区间。
            Input input = Input.of(in);
            Output output = Output.to(out, input.videoOptional(), input.audioOptional()).withArgs(
                    "-ss", num(startSec), "-t", num(durationSec),
                    "-c:v", o.videoCodec(), "-c:a", o.audioCodec());
            return new GraphCompiler().compile(output);
        }
        // 快切：输入侧 -ss 关键帧快 seek + -c copy，输出侧 -t 限时长（用 -t 而非输入 -to 避免区间歧义）。
        Input input = Input.of(in).withInputArgs("-ss", num(startSec));
        Output output = Output.to(out, input.videoOptional(), input.audioOptional())
                .withArgs("-t", num(durationSec), "-c", "copy");
        return new GraphCompiler().compile(output);
    }

    // ===== 4. extractAudio =====

    static CompiledCommand buildExtractAudio(File in, File out, ProbeResult probeOrNull, ExtractAudioOptions o) {
        // 已探测且确认无音轨时，提前抛出可诊断异常，而非放任 ffmpeg 运行期报 "matches no streams"。
        if (probeOrNull != null && probeOrNull.firstAudio().isEmpty()) {
            throw new FfmpegException("源文件不含音频流，无法抽取音频：" + in, null);
        }
        String ext = ContainerFamily.extension(out.getName());
        String sourceAudioCodec = probeOrNull == null
                ? null
                : probeOrNull.firstAudio().map(StreamInfo::codecName).orElse(null);
        String codec = audioCodecForExtension(ext, sourceAudioCodec);
        Input input = Input.of(in);
        // 用 0:a:0 选首条音轨，避开封面图（封面为 mjpeg 视频流）；-vn 再兜底排除视频。
        Output output = Output.to(out, input.audio()).withArgs("-vn", "-c:a", codec);
        return new GraphCompiler().compile(output);
    }

    /** 按输出扩展名（必要时结合源音频编解码器）推导音频编解码器。 */
    static String audioCodecForExtension(String ext, String sourceAudioCodec) {
        return switch (ext) {
            case "wav" -> "pcm_s16le";
            case "mp3" -> "libmp3lame";
            case "flac" -> "flac";
            case "opus" -> "libopus";
            case "ogg" -> "libvorbis";
            case "m4a", "aac" -> "aac".equalsIgnoreCase(sourceAudioCodec) ? "copy" : "aac";
            default -> "aac";
        };
    }

    // ===== 5. thumbnail（抓帧）=====

    static CompiledCommand buildThumbnail(File in, File out, double atSec, ThumbnailOptions o) {
        Input input = Input.of(in).withInputArgs("-ss", num(atSec));
        VideoStream v = input.video();
        if (o.width() != null || o.height() != null) {
            int w = o.width() != null ? o.width() : -1;
            int h = o.height() != null ? o.height() : -1;
            v = Filters.scale(v, w, h);
        }
        List<String> args = new ArrayList<>();
        args.add("-frames:v");
        args.add("1");
        if (o.quality() != null) {
            args.add("-q:v");
            args.add(o.quality().toString());
        }
        Output output = Output.to(out, v).withArgs(args.toArray(new String[0]));
        return new GraphCompiler().compile(output);
    }

    // ===== 6. concat（拼接，前置归一化 + 缺流注入/拒绝）=====

    static CompiledCommand buildConcat(List<File> ins, File out, List<ProbeResult> probes, ConcatOptions o) {
        if (ins.isEmpty()) {
            throw new IllegalArgumentException("concat 至少需要一个输入");
        }
        if (ins.size() != probes.size()) {
            throw new IllegalArgumentException("concat 输入数与 probe 数不一致：" + ins.size() + " vs " + probes.size());
        }

        // 全局同构判定：全无视轨 -> 纯音频拼接；全无音轨 -> 纯视频拼接；仅在真正异构（既有含视频段又有缺视频
        // 段等）时才落到 v+a 路径并对缺流段注入限时占位。避免对纯音频拼接强注入黑帧（既会令音频容器写入失败，
        // 也会把音频无谓地编码成大段黑屏）。
        boolean anyVideo = probes.stream().anyMatch(p -> !p.videoStreams().isEmpty());
        boolean anyAudio = probes.stream().anyMatch(p -> !p.audioStreams().isEmpty());
        if (!anyVideo && !anyAudio) {
            throw new FfmpegException("concat 所有输入既无视频流也无音频流，无法拼接。", null);
        }

        VideoNormTarget vt = deriveVideoTarget(probes.get(0), o);
        AudioNormTarget at = o.audioTarget() != null ? o.audioTarget() : AudioNormTarget.stereo48k();

        if (!anyVideo) {
            // 纯音频拼接：仅归一化并 concat 音频，只映射音频流。
            List<AudioStream> as = new ArrayList<>();
            for (int i = 0; i < ins.size(); i++) {
                Input input = Input.of(ins.get(i));
                ProbeResult p = probes.get(i);
                as.add(resolveSegmentAudio(input, !p.audioStreams().isEmpty(), p.durationSeconds(), at, o, i));
            }
            return new GraphCompiler().compile(Output.to(out, Filters.concatAudio(as)));
        }
        if (!anyAudio) {
            // 纯视频拼接：仅归一化并 concat 视频，只映射视频流。
            List<VideoStream> vs = new ArrayList<>();
            for (int i = 0; i < ins.size(); i++) {
                Input input = Input.of(ins.get(i));
                ProbeResult p = probes.get(i);
                vs.add(resolveSegmentVideo(input, !p.videoStreams().isEmpty(), p.durationSeconds(), vt, o, i));
            }
            return new GraphCompiler().compile(Output.to(out, Filters.concatVideo(vs)));
        }

        List<Filters.Segment> segs = new ArrayList<>();
        for (int i = 0; i < ins.size(); i++) {
            Input input = Input.of(ins.get(i));
            ProbeResult p = probes.get(i);
            boolean hasVideo = !p.videoStreams().isEmpty();
            boolean hasAudio = !p.audioStreams().isEmpty();
            double dur = p.durationSeconds();

            VideoStream v = resolveSegmentVideo(input, hasVideo, dur, vt, o, i);
            AudioStream a = resolveSegmentAudio(input, hasAudio, dur, at, o, i);
            segs.add(new Filters.Segment(v, a));
        }

        Filters.ConcatResult r = Filters.concat(segs);
        Output output = Output.to(out, r.video(), r.audio());
        return new GraphCompiler().compile(output);
    }

    private static VideoStream resolveSegmentVideo(Input input, boolean hasVideo, double dur,
                                                   VideoNormTarget vt, ConcatOptions o, int idx) {
        if (hasVideo) {
            return Normalization.normalizeVideo(input.video(), vt);
        }
        if (o.onMissingStream() == ConcatOptions.OnMissingStream.REJECT) {
            throw new FfmpegException("concat 第 " + idx + " 段缺少视频流，且策略为 REJECT。"
                    + "前置条件：各段流集合须一致，或改用 INJECT_SILENCE_OR_BLANK 注入占位。", null);
        }
        if (dur <= 0) {
            throw new FfmpegException("concat 第 " + idx + " 段缺视频且无法从 probe 得到有效时长，"
                    + "无法限定注入的纯色源长度（会导致 concat 卡死）。", null);
        }
        // 注入限时纯色源，再归一化到统一目标（含 setsar/format）。
        return Normalization.normalizeVideo(Filters.trim(Normalization.blank(vt, "black"), 0, dur), vt);
    }

    private static AudioStream resolveSegmentAudio(Input input, boolean hasAudio, double dur,
                                                   AudioNormTarget at, ConcatOptions o, int idx) {
        if (hasAudio) {
            return Normalization.normalizeAudio(input.audio(), at);
        }
        if (o.onMissingStream() == ConcatOptions.OnMissingStream.REJECT) {
            throw new FfmpegException("concat 第 " + idx + " 段缺少音频流，且策略为 REJECT。"
                    + "前置条件：各段流集合须一致，或改用 INJECT_SILENCE_OR_BLANK 注入占位。", null);
        }
        if (dur <= 0) {
            throw new FfmpegException("concat 第 " + idx + " 段缺音频且无法从 probe 得到有效时长，"
                    + "无法限定注入的静音源长度（会导致 concat 卡死）。", null);
        }
        // 注入限时静音源，再归一化到统一目标（aresample/aformat）。
        return Normalization.normalizeAudio(Filters.atrim(Normalization.silence(at), 0, dur), at);
    }

    private static VideoNormTarget deriveVideoTarget(ProbeResult first, ConcatOptions o) {
        StreamInfo v0 = first.firstVideo().orElse(null);
        int w = o.width() != null ? o.width() : (v0 != null && v0.width() != null ? v0.width() : 1280);
        int h = o.height() != null ? o.height() : (v0 != null && v0.height() != null ? v0.height() : 720);
        double fps = o.fps() != null
                ? o.fps()
                : (v0 != null && v0.avgFrameRateFps() > 0 ? v0.avgFrameRateFps() : 30.0);
        String pix = o.pixelFormat() != null ? o.pixelFormat() : "yuv420p";
        return new VideoNormTarget(w, h, fps, pix);
    }

    // ===== 7. burnSubtitles（烧录字幕；libass 前置校验在 Ffmpeg 公共入口做）=====

    static CompiledCommand buildBurnSubtitles(File video, File subtitle, File out, BurnSubtitlesOptions o) {
        Input input = Input.of(video);
        VideoStream v = o.forceStyle() != null
                ? Filters.burnSubtitles(input.video(), subtitle.toPath(), o.forceStyle())
                : Filters.burnSubtitles(input.video(), subtitle.toPath());
        // 音频用可选映射（0:a:0?）：无音轨视频也能仅烧录视频而不被「matches no streams」中止。
        Output output = Output.to(out, v, input.audioOptional())
                .withArgs("-c:v", o.videoCodec(), "-c:a", o.audioCodec());
        return new GraphCompiler().compile(output);
    }

    // ===== 数值格式化：整数值渲染为整数（10 而非 10.0），与 model 层一致 =====

    static String num(double d) {
        if (Double.isFinite(d) && d == Math.rint(d)) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }
}
