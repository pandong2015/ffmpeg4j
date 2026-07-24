package io.github.pandong2015.ffmpeg4j.facade;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import io.github.pandong2015.ffmpeg4j.task.FfmpegWarning;
import io.github.pandong2015.ffmpeg4j.task.TaskWarningCollector;
import io.github.pandong2015.ffmpeg4j.task.WarningCode;

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
        // 冲突 fail-fast（引用不存在的流 / 空输出）——优先于任何 argv 组装。
        if (o.disableVideo() && o.disableAudio()) {
            throw new FfmpegException("disableVideo 与 disableAudio 同时为真会产出空输出：至少保留一路", null);
        }
        if (o.disableVideo() && o.videoFilter() != null) {
            throw new FfmpegException("disableVideo 与 videoFilter 冲突：视频已禁用、无流可供滤镜链消费", null);
        }
        List<String> args = new ArrayList<>();

        // ===== 视频段：disableVideo → -vn（跳过全部视频码控）；否则 -c:v + 码控 =====
        if (o.disableVideo()) {
            args.add("-vn");
        } else {
            if (o.videoCodec() == null) {
                throw new FfmpegException(
                        "videoCodec 为 null：请设编码器，或调 disableVideo() 产 -vn（本门面不支持省略 -c:v 由 ffmpeg 依容器自选）", null);
            }
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
            if (o.fps() != null) {
                args.add("-r");
                args.add(num(o.fps()));
            }
            // h264 惯用 VBV：-maxrate/-bufsize；libx265 的 VBV 走 x265Params/extraOutputArgs（不自动翻译）。
            if (o.maxrate() != null) {
                args.add("-maxrate");
                args.add(o.maxrate());
            }
            // bufsize：显式优先；vbv 标记「待派生」且未显式设 bufsize 时依<em>最终</em> maxrate 派生 ×2（build 期求值，
            // 故 vbv("2M").maxrate("3M") 得 6M）。裸 bufsize（无 maxrate）保持既有行为逐字产出、不 hard-fail（byte-compat）。
            String bufsize = o.bufsize();
            if (bufsize == null && o.vbvDeriveBufsize() && o.maxrate() != null) {
                bufsize = doubleRate(o.maxrate());
            }
            if (bufsize != null) {
                args.add("-bufsize");
                args.add(bufsize);
            }
            // x265Params 紧接视频码控段尾（-bufsize 之后、-c:a 之前）；仅对 libx265 有意义，库不校验 codec。
            if (o.x265Params() != null) {
                args.add("-x265-params");
                args.add(o.x265Params());
            }
        }

        // ===== 音频段：disableAudio → -an（跳过 -c:a/-b:a/-ar）；否则 -c:a + 码控 =====
        if (o.disableAudio()) {
            args.add("-an");
        } else {
            if (o.audioCodec() == null) {
                throw new FfmpegException("audioCodec 为 null：请设编码器，或调 disableAudio() 产 -an", null);
            }
            args.add("-c:a");
            args.add(o.audioCodec());
            if (o.audioBitrate() != null) {
                args.add("-b:a");
                args.add(o.audioBitrate());
            }
            if (o.audioSampleRate() != null) {
                // -ar 紧接 -b:a（音频段尾）。
                args.add("-ar");
                args.add(o.audioSampleRate().toString());
            }
        }

        // ===== GOP / 强制关键帧（视频相关，disableVideo 时整体跳过）=====
        if (!o.disableVideo() && o.gop() != null) {
            // GOP 段：关键帧间隔帧数派生 -keyint_min/-g/-sc_threshold 0（下游按 fps*秒 传入帧数）。
            String g = o.gop().toString();
            args.add("-keyint_min");
            args.add(g);
            args.add("-g");
            args.add(g);
            args.add("-sc_threshold");
            args.add("0");
        }
        if (!o.disableVideo() && o.forceKeyframesEverySeconds() != null) {
            // 按秒强制关键帧必然重编码，与 -c:v copy 冲突：build 期 fail-fast（不隐式改 codec）。
            if ("copy".equals(o.videoCodec())) {
                throw new FfmpegException(
                        "关键帧强制（forceKeyframesEverySeconds）需重编码，与 -c:v copy 冲突：请设 videoCodec（非 copy）", null);
            }
            args.addAll(forceKeyFramesArgs(o.forceKeyframesEverySeconds()));
        }

        // ===== strict：全部类型化码控之后、extraOutputArgs 之前（编码器通用旗标，与禁用标志无关）=====
        if (o.strict() != null) {
            args.add("-strict");
            args.add(o.strict());
        }

        // 逃生舱：置于类型化码控之后（同键 ffmpeg 取后者）；内容不参与类型校验。
        args.addAll(o.extraOutputArgs());

        // ===== 输出映射：按禁用/滤镜决定映射哪些流 =====
        String[] argv = args.toArray(new String[0]);
        // disableVideo+videoFilter 已在开头 fail-fast，故此处 filter 仅在未禁用视频时求值。
        VideoStream filtered = (o.videoFilter() != null) ? o.videoFilter().apply(input.video()) : null;
        Output output;
        if (o.disableVideo()) {
            // 纯音频：只映射音频。
            output = Output.to(out, input.audioOptional()).withArgs(argv);
        } else if (o.disableAudio()) {
            // 纯视频：只映射视频（滤镜链输出或可选映射）。
            output = (filtered != null)
                    ? Output.to(out, filtered).withArgs(argv)
                    : Output.to(out, input.videoOptional()).withArgs(argv);
        } else if (filtered != null) {
            // 挂滤镜链：视频以必选映射 input.video() 为起点、经 filter_complex；音频仍可选映射（缺音轨静默跳过）。
            output = Output.to(out, filtered, input.audioOptional()).withArgs(argv);
        } else {
            // 无滤镜、不禁用：视频/音频双可选映射（0:v:0?/0:a:0?），argv 逐字节与既有一致。
            output = Output.to(out, input.videoOptional(), input.audioOptional()).withArgs(argv);
        }
        return new GraphCompiler().compile(output);
    }

    // rate 串（如 "2M"/"2000k"/"3000000"/"2.5M"）的数值前缀 + 单位后缀。
    private static final Pattern RATE_PATTERN = Pattern.compile("^([0-9]*\\.?[0-9]+)\\s*([a-zA-Z]*)$");

    /**
     * 把码率串的<em>数值前缀翻倍</em>、原样保留单位后缀（{@code "2M"→"4M"}、{@code "2000k"→"4000k"}、
     * {@code "3000000"→"6000000"}、{@code "2.5M"→"5M"}）；数值经 {@link #num} locale 无关去尾零渲染。供
     * {@link TranscodeOptions#vbv(String)} 在 build 期派生 {@code bufsize=maxrate×2}。不可解析（空串/纯字母/
     * 含非法字符）抛 {@link IllegalArgumentException}（fail-fast、不产垃圾 argv）。单位语义与翻倍无关，翻数值前缀恒正确。
     */
    static String doubleRate(String rate) {
        Objects.requireNonNull(rate, "rate 不能为 null");
        Matcher m = RATE_PATTERN.matcher(rate.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("无法解析码率串（须为「数值[单位]」，如 2M/2000k/3000000）：" + rate);
        }
        return num(Double.parseDouble(m.group(1)) * 2) + m.group(2);
    }

    // ===== HLS 单码率 VOD 切片（纯函数产 argv；写盘/解析在 FfmpegClient）=====

    /**
     * 构建单码率 VOD HLS 的 argv（纯函数、脱进程可断言）。段与 playlist 分离布局：playlist 落 outDir 根、段落
     * {@code outDir/segmentDir/}；段 URI 前缀经<b>默认注入 {@code -hls_base_url <segmentDir>/}</b> 保证（ffmpeg 单播放
     * 列表对段 URI 取 basename、不隐式相对化），{@code segmentUriPrefix} 覆盖之。{@code keyInfoFile} 路径由门面先定
     * 并传入（本函数不建文件）；AES 启用时接线 {@code -hls_key_info_file}。视频/音频用可选映射（首视频+首音频）。
     */
    static CompiledCommand buildHls(File in, File outDir, HlsOptions o, Path keyInfoFile) {
        Objects.requireNonNull(outDir, "outDir 不能为 null");
        Input input = Input.of(in);
        List<String> args = new ArrayList<>();
        args.add("-c:v");
        args.add(o.videoCodec());
        args.add("-c:a");
        args.add(o.audioCodec());
        if (o.alignKeyframes()) {
            // 段对齐需重编码，与 -c:v copy 冲突：build 期 fail-fast（不隐式改 codec）。
            if ("copy".equals(o.videoCodec())) {
                throw new FfmpegException(
                        "alignKeyframes 需重编码，与 -c:v copy 冲突：请设 videoCodec（非 copy）", null);
            }
            args.addAll(forceKeyFramesArgs(o.hlsTime()));
        }
        args.add("-f");
        args.add("hls");
        args.add("-hls_time");
        args.add(num(o.hlsTime()));
        // VOD 语义双标签固定注入（缺 playlist_type 不写 ENDLIST、缺 list_size 0 只留 5 段）。
        args.add("-hls_playlist_type");
        args.add("vod");
        args.add("-hls_list_size");
        args.add("0");
        args.add("-hls_segment_type");
        args.add("mpegts");
        if (o.startNumber() != 0) {
            args.add("-start_number");
            args.add(Integer.toString(o.startNumber()));
        }
        args.add("-hls_segment_filename");
        args.add(new File(new File(outDir, o.segmentDir()), o.segmentTemplate()).getPath());
        args.add("-hls_base_url");
        args.add(o.segmentUriPrefix() != null ? o.segmentUriPrefix() : o.segmentDir() + "/");
        if (o.key() != null) {
            Objects.requireNonNull(keyInfoFile, "启用 AES 时 keyInfoFile 路径不能为 null");
            args.add("-hls_key_info_file");
            args.add(keyInfoFile.toString());
        }
        // 逃生舱：置于类型化 -hls_* 之后（同键 ffmpeg 取后者）；内容不参与类型校验。
        args.addAll(o.extraOutputArgs());

        Output output = Output.to(new File(outDir, o.playlistName()), input.videoOptional(), input.audioOptional())
                .withArgs(args.toArray(new String[0]));
        return new GraphCompiler().compile(output);
    }

    /**
     * 从 m3u8 正文解析各分段的 <em>basename</em>（{@code #} 注释与空行外的 URI 行，按出现顺序）。纯函数、可单测。
     * 取 basename 使其对默认 {@code -hls_base_url <segmentDir>/}（如 {@code ts/index0.ts}）与 CDN 绝对前缀
     * （如 {@code https://cdn/index0.ts}）均得段文件名，门面再解析到 {@code outDir/segmentDir/} 下的实际路径。
     */
    static List<String> parseSegmentBasenames(String m3u8Content) {
        List<String> result = new ArrayList<>();
        for (String line : parseSegmentUris(m3u8Content)) {
            int slash = Math.max(line.lastIndexOf('/'), line.lastIndexOf('\\'));
            result.add(slash >= 0 ? line.substring(slash + 1) : line);
        }
        return result;
    }

    /** m3u8 中<em>原始</em>段 URI 行（{@code #} 注释与空行外，按出现顺序）——保留 base_url 前缀（如 {@code ts/index0.ts}）。 */
    static List<String> parseSegmentUris(String m3u8Content) {
        List<String> result = new ArrayList<>();
        for (String raw : m3u8Content.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            result.add(line);
        }
        return result;
    }

    // ===== HLS ABR 多码率梯 VOD（纯函数产 argv；probe 裁剪/写盘/解析在 FfmpegClient）=====

    /**
     * 构建 ABR 多码率梯 VOD 的 argv（纯函数、脱进程可断言）。走<b>路线 A</b>：同一 {@code input.video()} 被 N 档
     * {@code scale+setsar} 消费 → 编译器自动 {@code split=N} 扇出。{@code o.variants()} 须为门面<em>已按源高度裁剪</em>
     * 的显式梯（本函数不 probe）。
     *
     * <p>产 argv：逐档 {@code -c:v:N}/{@code -b:v:N}/{@code -maxrate:v:N}/{@code -bufsize:v:N}（可选 crf/preset）、
     * 音频（agroup 单路 {@code -c:a:0}/{@code -b:a}；非 agroup 逐档 {@code -c:a:N}/{@code -b:a:N}）、{@code -var_stream_map}、
     * {@code -master_pl_name}、{@code -f hls} + VOD 双标签 + {@code -hls_segment_filename outDir/%v/<模板>}、<b>恒</b>
     * {@code -force_key_frames}（跨档对齐）、AES {@code -hls_key_info_file}。<b>默认不注入 {@code -hls_base_url}</b>。
     * 输出 playlist 路径用<b>字面 {@code %v}</b>（ffmpeg 按 {@code name:}/数字索引替换）。
     */
    static CompiledCommand buildHlsAbr(File in, File outDir, HlsAbrOptions o, Path keyInfoFile) {
        Objects.requireNonNull(outDir, "outDir 不能为 null");
        List<HlsVariant> variants = o.variants();
        int n = variants.size();
        if (n == 0) {
            throw new IllegalArgumentException("ABR 码率梯不能为空");
        }
        // build 期 fail-fast：段模板须含序号占位；解析后的变体目录名须唯一（否则各档写同目录互相覆盖）。
        if (!SEGMENT_INDEX_ABR.matcher(o.segmentTemplate()).find()) {
            throw new IllegalArgumentException("segmentTemplate 须含序号占位符 %d/%0Nd，实际 " + o.segmentTemplate());
        }
        resolveVariantDirNames(variants); // fail-fast：变体目录名须唯一（门面另用同一真源做 mkdir/解析）

        Input input = Input.of(in);
        VideoStream v = input.video();     // ABR 路线 A 必有视频轨（无视频轨由门面 probe 提前 fail-fast）
        AudioStream audio = input.audio(); // agroup 与每档模式均需音轨

        List<Stream> mapped = new ArrayList<>();
        List<String> args = new ArrayList<>();
        List<String> vsm = new ArrayList<>();
        boolean shared = o.sharedAudio();
        String groupId = "aud";

        if (shared) {
            // agroup：N 个视频档在前、单路音频在末。mapped 次序 = var_stream_map 输出下标次序（D10 契约）。
            for (int i = 0; i < n; i++) {
                mapped.add(scaledSetsar(v, variants.get(i)));
            }
            mapped.add(audio);
            for (int i = 0; i < n; i++) {
                addVideoArgs(args, i, variants.get(i));
            }
            args.add("-c:a:0");
            args.add(variants.get(0).audioCodec());
            args.add("-b:a");
            args.add(o.audioBitrate());
            for (int i = 0; i < n; i++) {
                StringBuilder e = new StringBuilder("v:").append(i).append(",agroup:").append(groupId);
                if (variants.get(i).name() != null) {
                    e.append(",name:").append(variants.get(i).name());
                }
                vsm.add(e.toString());
            }
            vsm.add("a:0,agroup:" + groupId + ",name:audio,default:yes");
        } else {
            // 每档独立音频：mapped 交错 v0,a0,v1,a1…；音频同一值被 N 次消费 → 渲染 N 次 -map 0:a:0（不插 asplit）。
            for (int i = 0; i < n; i++) {
                mapped.add(scaledSetsar(v, variants.get(i)));
                mapped.add(audio);
            }
            for (int i = 0; i < n; i++) {
                addVideoArgs(args, i, variants.get(i));
                args.add("-c:a:" + i);
                args.add(variants.get(i).audioCodec());
                args.add("-b:a:" + i);
                args.add(variants.get(i).audioBitrate());
            }
            for (int i = 0; i < n; i++) {
                StringBuilder e = new StringBuilder("v:").append(i).append(",a:").append(i);
                if (variants.get(i).name() != null) {
                    e.append(",name:").append(variants.get(i).name());
                }
                vsm.add(e.toString());
            }
        }

        args.add("-f");
        args.add("hls");
        args.add("-hls_time");
        args.add(num(o.hlsTime()));
        // VOD 语义双标签固定注入。
        args.add("-hls_playlist_type");
        args.add("vod");
        args.add("-hls_list_size");
        args.add("0");
        args.add("-hls_segment_type");
        args.add("mpegts");
        if (o.startNumber() != 0) {
            args.add("-start_number");
            args.add(Integer.toString(o.startNumber()));
        }
        // 段文件名用字面 %v 子目录（ffmpeg 按 name:/数字索引替换）；每档段与 playlist 共位。
        args.add("-hls_segment_filename");
        args.add(new File(outDir, "%v/" + o.segmentTemplate()).getPath());
        args.add("-master_pl_name");
        args.add(o.masterPlaylistName());
        args.add("-var_stream_map");
        args.add(String.join(" ", vsm));
        // 恒注入跨档关键帧对齐（无缝切码率的正确性前提，一条覆盖全档；复用单一真源 forceKeyFramesArgs）。
        args.addAll(forceKeyFramesArgs(o.hlsTime()));
        if (o.key() != null) {
            Objects.requireNonNull(keyInfoFile, "启用 AES 时 keyInfoFile 路径不能为 null");
            args.add("-hls_key_info_file");
            args.add(keyInfoFile.toString());
        }
        // 逃生舱：置于类型化 -hls_* 之后（同键 ffmpeg 取后者）；内容不参与类型校验。
        args.addAll(o.extraOutputArgs());

        // 输出 playlist 路径字面 %v；每档落 outDir/<解析目录>/index.m3u8。
        Output output = Output.to(new File(outDir, "%v/index.m3u8"), mapped.toArray(new Stream[0]))
                .withArgs(args.toArray(new String[0]));
        return new GraphCompiler().compile(output);
    }

    /**
     * 解析每档的<b>变体目录名</b>（{@link HlsVariant#name()} 给值即用之，否则数字索引 {@code 0/1/2}）——单一真源，
     * 同时驱动 {@code var_stream_map name:}、{@code Files.createDirectories}、master/各档 m3u8 解析路径、
     * {@link HlsVariantResult#name()} 四处同名。目录名须唯一（否则各档写同目录互相覆盖 → build 期 fail-fast）。
     */
    static List<String> resolveVariantDirNames(List<HlsVariant> variants) {
        List<String> names = new ArrayList<>(variants.size());
        for (int i = 0; i < variants.size(); i++) {
            String name = variants.get(i).name();
            names.add(name != null ? name : Integer.toString(i));
        }
        long distinct = names.stream().distinct().count();
        if (distinct != names.size()) {
            throw new IllegalArgumentException("变体目录名冲突（显式 name 与数字索引碰撞）：" + names);
        }
        return List.copyOf(names);
    }

    /** 一档变体的视频编码参数（按输出视频下标 {@code N}）：{@code -c:v:N}/{@code -b:v:N}/{@code -maxrate:v:N}/{@code -bufsize:v:N}（可选 crf/preset）。 */
    private static void addVideoArgs(List<String> args, int i, HlsVariant var) {
        args.add("-c:v:" + i);
        args.add(var.videoCodec());
        args.add("-b:v:" + i);
        args.add(var.videoBitrate());
        args.add("-maxrate:v:" + i);
        args.add(var.effectiveMaxrate());
        args.add("-bufsize:v:" + i);
        args.add(var.effectiveBufsize());
        if (var.crf() != null) {
            args.add("-crf:v:" + i);
            args.add(var.crf().toString());
        }
        if (var.preset() != null) {
            args.add("-preset:v:" + i);
            args.add(var.preset());
        }
    }

    /** 一档的视频链：{@code scale=(width|-2):height} → {@code setsar=1}（MUST 含 setsar，项目归一化约定）。 */
    private static VideoStream scaledSetsar(VideoStream v, HlsVariant var) {
        int w = var.width() != null ? var.width() : -2;
        VideoStream scaled = Filters.scale(v, w, var.height());
        // setsar 非公共 curated 滤镜（归一化内部滤镜），经原始滤镜逃生舱接线 setsar=1。
        return Filters.rawFilterVideo(scaled, "setsar=1");
    }

    // ===== HLS ABR master 解析（纯函数，供 FfmpegClient 装配结果）=====

    /** master 的一条 {@code #EXT-X-STREAM-INF} + 其后紧邻的 URI 行。 */
    record MasterVariant(long bandwidth, int width, int height, String uri) {
    }

    /** master 的一条 {@code #EXT-X-MEDIA:TYPE=AUDIO}（agroup 音频 rendition）。 */
    record MasterAudio(String groupId, String uri) {
    }

    private static final Pattern BANDWIDTH_ATTR = Pattern.compile("[^-]BANDWIDTH=(\\d+)");
    private static final Pattern RESOLUTION_ATTR = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final Pattern GROUP_ID_ATTR = Pattern.compile("GROUP-ID=\"([^\"]*)\"");
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]*)\"");
    private static final Pattern SEGMENT_INDEX_ABR = Pattern.compile("%[0-9]*d");

    /**
     * 解析 master 的各 {@code #EXT-X-STREAM-INF}（{@code BANDWIDTH}/{@code RESOLUTION} + 其后 URI 行），按出现顺序。
     * <b>引号感知/定向正则</b>：{@code BANDWIDTH=(\d+)}/{@code RESOLUTION=(\d+)x(\d+)}——避开 {@code CODECS} 值内含逗号
     * 对朴素 {@code split(',')} 的误分割。{@code RESOLUTION} 缺失时 width/height 记 0。
     */
    static List<MasterVariant> parseMasterVariants(String masterContent) {
        List<MasterVariant> result = new ArrayList<>();
        String[] lines = masterContent.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (!line.startsWith("#EXT-X-STREAM-INF")) {
                continue;
            }
            long bandwidth = 0;
            var bm = BANDWIDTH_ATTR.matcher(" " + line); // 前置空格使 [^-] 能匹配 AVERAGE-BANDWIDTH 之外的 BANDWIDTH
            if (bm.find()) {
                bandwidth = Long.parseLong(bm.group(1));
            }
            int width = 0;
            int height = 0;
            var rm = RESOLUTION_ATTR.matcher(line);
            if (rm.find()) {
                width = Integer.parseInt(rm.group(1));
                height = Integer.parseInt(rm.group(2));
            }
            // 其后首个非注释非空行 = 变体 URI。
            String uri = null;
            for (int j = i + 1; j < lines.length; j++) {
                String u = lines[j].strip();
                if (u.isEmpty() || u.startsWith("#")) {
                    continue;
                }
                uri = u;
                break;
            }
            if (uri != null) {
                result.add(new MasterVariant(bandwidth, width, height, uri));
            }
        }
        return result;
    }

    /** 解析 master 的 {@code #EXT-X-MEDIA:TYPE=AUDIO} 行（{@code GROUP-ID}/{@code URI}），无则空 List。 */
    static List<MasterAudio> parseMasterAudioMedia(String masterContent) {
        List<MasterAudio> result = new ArrayList<>();
        for (String raw : masterContent.split("\n")) {
            String line = raw.strip();
            if (!line.startsWith("#EXT-X-MEDIA") || !line.contains("TYPE=AUDIO")) {
                continue;
            }
            var gm = GROUP_ID_ATTR.matcher(line);
            var um = URI_ATTR.matcher(line);
            String groupId = gm.find() ? gm.group(1) : null;
            String uri = um.find() ? um.group(1) : null;
            if (uri != null) {
                result.add(new MasterAudio(groupId, uri));
            }
        }
        return result;
    }

    // ===== 2. remux（换容器，按流分派 -c copy / 字幕特判）=====

    static CompiledCommand buildRemux(File in, File out, ProbeResult probe, RemuxOptions o) {
        ContainerFamily family = ContainerFamily.of(out.getName());
        Input input = Input.of(in);
        List<Stream> mapped = new ArrayList<>();
        List<String> args = new ArrayList<>();

        List<StreamInfo> videos = probe.videoStreams();
        if (videos.isEmpty()) {
            TaskWarningCollector.add(new FfmpegWarning(
                    WarningCode.OPTIONAL_STREAM_MISSING,
                    "源文件不含可选视频流，remux 将继续处理其他流",
                    java.util.Map.of("streamType", "video")));
        }
        for (int i = 0; i < videos.size(); i++) {
            mapped.add(input.video(i));
        }
        if (!videos.isEmpty()) {
            args.add("-c:v");
            args.add("copy");
        }

        List<StreamInfo> audios = probe.audioStreams();
        if (audios.isEmpty()) {
            TaskWarningCollector.add(new FfmpegWarning(
                    WarningCode.OPTIONAL_STREAM_MISSING,
                    "源文件不含可选音频流，remux 将继续处理其他流",
                    java.util.Map.of("streamType", "audio")));
        }
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
                    TaskWarningCollector.add(new FfmpegWarning(
                            WarningCode.SUBTITLE_DROPPED,
                            "目标容器无法容纳图形字幕，已丢弃该字幕流",
                            java.util.Map.of(
                                    "streamIndex", Integer.toString(s.index()),
                                    "codec", String.valueOf(s.codecName()),
                                    "container", family.name())));
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
        // 请求重采样/改声道时禁用 copy：copy 会静默忽略 -ar/-ac（实测输出仍原采样率且不报错），
        // 回退到该扩展名的自然编码器（如 m4a/aac→aac）真正重编码，使 -ar/-ac 生效。
        if (o.resampling() && "copy".equals(codec)) {
            codec = audioCodecForExtension(ext, null);
        }
        List<String> args = new ArrayList<>();
        args.add("-vn");
        args.add("-c:a");
        args.add(codec);
        if (o.sampleRate() != null) {
            args.add("-ar");
            args.add(o.sampleRate().toString());
        }
        if (o.channels() != null) {
            args.add("-ac");
            args.add(o.channels().toString());
        }
        Input input = Input.of(in);
        // 用 0:a:0 选首条音轨，避开封面图（封面为 mjpeg 视频流）；-vn 再兜底排除视频。
        Output output = Output.to(out, input.audio()).withArgs(args.toArray(new String[0]));
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
        // INPUT_FAST（默认）：-ss 置于 -i 之前（关键帧快 seek）。
        // OUTPUT_ACCURATE：-ss 置于输出侧（-i 之后），解码到目标时刻再取帧，时间点精确。
        boolean accurate = o.seekMode() == SeekMode.OUTPUT_ACCURATE;
        Input input = accurate ? Input.of(in) : Input.of(in).withInputArgs("-ss", num(atSec));
        VideoStream v = input.video();
        if (o.width() != null || o.height() != null) {
            int w = o.width() != null ? o.width() : -1;
            int h = o.height() != null ? o.height() : -1;
            v = Filters.scale(v, w, h);
        }
        List<String> args = new ArrayList<>();
        if (accurate) {
            args.add("-ss");
            args.add(num(atSec));
        }
        args.add("-frames:v");
        args.add("1");
        if (o.quality() != null) {
            args.add("-q:v");
            args.add(o.quality().toString());
        }
        Output output = Output.to(out, v).withArgs(args.toArray(new String[0]));
        return new GraphCompiler().compile(output);
    }

    // ===== 5b. gif（两遍调色板：fps→scale→palettegen/paletteuse，编译器自动 split 菱形）=====

    static CompiledCommand buildGif(File in, File out, GifOptions o) {
        // -ss/-t 均置输入侧（实测输入/输出侧 -t 选帧有别，须对齐 type3 的输入侧）。
        List<String> inArgs = new ArrayList<>();
        inArgs.add("-ss");
        inArgs.add(num(o.start()));
        if (o.duration() != null) {
            inArgs.add("-t");
            inArgs.add(num(o.duration()));
        }
        Input input = Input.of(in).withInputArgs(inArgs.toArray(new String[0]));
        VideoStream v = Filters.fps(input.video(), o.fps());
        if (o.width() != null) {
            int w = o.width();
            int h = o.height() != null ? o.height() : -1;
            // 缺省无 flags（与 type3 逐字节等价）；显式 scaleFlags 走原始滤镜表达带 flags 的 scale。
            v = o.scaleFlags() != null
                    ? Filters.rawFilterVideo(v, "scale=" + w + ":" + h + ":flags=" + o.scaleFlags())
                    : Filters.scale(v, w, h);
        }
        // 同一流 v 同时被 palettegen 与 paletteuse 消费 → 编译器自动插入 split=2 重连（菱形）。
        VideoStream palette = Filters.paletteGen(v);
        VideoStream gif = Filters.paletteUse(v, palette);
        return new GraphCompiler().compile(Output.to(out, gif));
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

    /**
     * 按秒强制关键帧的输出 argv：{@code -force_key_frames expr:gte(t,n_forced*T)}（{@code T} 经 {@link #num}
     * locale 无关、去尾零渲染）。纯函数、单一真源，供 transcode 与 HLS（段边界对齐）复用。调用方保证 {@code seconds>0}。
     */
    static List<String> forceKeyFramesArgs(double seconds) {
        return List.of("-force_key_frames", "expr:gte(t,n_forced*" + num(seconds) + ")");
    }
}
