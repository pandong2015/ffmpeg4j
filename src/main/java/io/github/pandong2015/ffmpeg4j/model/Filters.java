package io.github.pandong2015.ffmpeg4j.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.pandong2015.ffmpeg4j.model.FilterNode.Arg;

/**
 * v1.0 的 16 个类型化 curated 滤镜（含字幕烧录族）静态工厂。
 *
 * <p>每个滤镜是纯函数 {@code Stream -> Stream}：接收类型化流、返回新流，不修改入参。签名按
 * {@link VideoStream}/{@link AudioStream} 收窄，在 javac 编译期拦截类型错配。选项名已逐一比对
 * 官方 ffmpeg-filters 文档核实。未建模的长尾滤镜走 {@link #rawFilterVideo}/{@link #rawFilterAudio}。
 *
 * <p>归一化滤镜 {@code split}/{@code asplit}/{@code setpts}/{@code asetpts}/{@code aresample}/
 * {@code aformat} <em>不</em>作为公共工厂暴露——它们仅由编译器内部产生（{@code setpts}/{@code asetpts}
 * 由 {@link #trim}/{@link #atrim} 自动追加）。
 */
public final class Filters {

    private Filters() {
    }

    // ===== 视频 9 =====

    public static VideoStream scale(VideoStream in, int width, int height) {
        return video("scale", List.of(Arg.of("w", Integer.toString(width)), Arg.of("h", Integer.toString(height))), in);
    }

    /** 居中裁剪。 */
    public static VideoStream crop(VideoStream in, int width, int height) {
        return video("crop", List.of(Arg.of("w", Integer.toString(width)), Arg.of("h", Integer.toString(height))), in);
    }

    public static VideoStream crop(VideoStream in, int width, int height, int x, int y) {
        return video("crop", List.of(
                Arg.of("w", Integer.toString(width)), Arg.of("h", Integer.toString(height)),
                Arg.of("x", Integer.toString(x)), Arg.of("y", Integer.toString(y))), in);
    }

    /** 填充/加边（letterbox）。{@code x}/{@code y} 接受表达式（如 {@code (ow-iw)/2}）。 */
    public static VideoStream pad(VideoStream in, int width, int height, String x, String y, String color) {
        return video("pad", List.of(
                Arg.of("w", Integer.toString(width)), Arg.of("h", Integer.toString(height)),
                Arg.of("x", x), Arg.of("y", y), Arg.of("color", color)), in);
    }

    /** 叠加。输入顺序有语义：{@code base} 为主输入、{@code over} 为叠加层。{@code x}/{@code y} 接受表达式。 */
    public static VideoStream overlay(VideoStream base, VideoStream over, String x, String y) {
        return video("overlay", List.of(Arg.of("x", x), Arg.of("y", y)), base, over);
    }

    public static VideoStream overlay(VideoStream base, VideoStream over, int x, int y) {
        return overlay(base, over, Integer.toString(x), Integer.toString(y));
    }

    /** 截取 [start,end] 秒；自动追加 {@code setpts=PTS-STARTPTS} 重基时间线。 */
    public static VideoStream trim(VideoStream in, double startSec, double endSec) {
        VideoStream trimmed = video("trim",
                List.of(Arg.of("start", num(startSec)), Arg.of("end", num(endSec))), in);
        return video("setpts", List.of(Arg.positional("PTS-STARTPTS")), trimmed);
    }

    public static VideoStream fps(VideoStream in, double fps) {
        return video("fps", List.of(Arg.of("fps", num(fps))), in);
    }

    /** 强制像素格式转换，如 {@code format(v, "yuv420p")}。 */
    public static VideoStream format(VideoStream in, String... pixelFormats) {
        return video("format", List.of(Arg.of("pix_fmts", String.join("|", pixelFormats))), in);
    }

    /** 淡入/淡出。{@code type} 为 {@code "in"}/{@code "out"}；{@code startSec} 为绝对时间线位置。 */
    public static VideoStream fade(VideoStream in, String type, double startSec, double durationSec) {
        return video("fade", List.of(
                Arg.of("t", type), Arg.of("start_time", num(startSec)), Arg.of("duration", num(durationSec))), in);
    }

    /**
     * 绘制文字。{@code text} 与 {@code fontFile} 由编译器做 filtergraph 转义（{@link Arg#escaped}）。
     * 需 ffmpeg 构建含 {@code --enable-libfreetype}。
     */
    public static VideoStream drawText(VideoStream in, String text, Path fontFile, int fontSize,
                                       String fontColor, String x, String y) {
        List<Arg> args = new ArrayList<>();
        if (fontFile != null) {
            args.add(Arg.escaped("fontfile", fontFile.toString()));
        }
        args.add(Arg.escaped("text", text));
        args.add(Arg.of("fontsize", Integer.toString(fontSize)));
        args.add(Arg.of("fontcolor", fontColor));
        args.add(Arg.of("x", x));
        args.add(Arg.of("y", y));
        return video("drawtext", args, in);
    }

    // ===== 音频 5 =====

    /** 音量，线性因子（{@code "0.5"}）或分贝（{@code "6dB"}）。 */
    public static AudioStream volume(AudioStream in, String volume) {
        return audio("volume", List.of(Arg.of("volume", volume)), in);
    }

    /** 多路混音。 */
    public static AudioStream amix(List<AudioStream> inputs) {
        List<Stream> ins = new ArrayList<>(inputs);
        FilterNode node = new FilterNode("amix", List.of(Arg.of("inputs", Integer.toString(inputs.size()))),
                ins, List.of(MediaType.AUDIO));
        return new AudioStream(new Origin.FilterOrigin(node, 0));
    }

    /** 音频截取 [start,end] 秒；自动追加 {@code asetpts=PTS-STARTPTS}。 */
    public static AudioStream atrim(AudioStream in, double startSec, double endSec) {
        AudioStream trimmed = audio("atrim",
                List.of(Arg.of("start", num(startSec)), Arg.of("end", num(endSec))), in);
        return audio("asetpts", List.of(Arg.positional("PTS-STARTPTS")), trimmed);
    }

    /**
     * 变速不变调。单实例范围 [0.5,100]；因子 &gt;2.0（或 &lt;0.5）时为音质自动拆成链，
     * 使范围限制不泄漏给调用方。
     */
    public static AudioStream atempo(AudioStream in, double tempo) {
        AudioStream cur = in;
        for (double f : decomposeAtempo(tempo)) {
            cur = audio("atempo", List.of(Arg.of("tempo", num(f))), cur);
        }
        return cur;
    }

    /** 音频淡入/淡出，与 {@link #fade} 对称。 */
    public static AudioStream afade(AudioStream in, String type, double startSec, double durationSec) {
        return audio("afade", List.of(
                Arg.of("t", type), Arg.of("start_time", num(startSec)), Arg.of("duration", num(durationSec))), in);
    }

    // ===== 双型 concat =====

    /** 每段一路视频 + 一路音频。 */
    public record Segment(VideoStream video, AudioStream audio) {
    }

    /** concat 的双路输出。 */
    public record ConcatResult(VideoStream video, AudioStream audio) {
    }

    /**
     * 拼接多段（每段 v+a）。输入按段交错（seg0.v, seg0.a, seg1.v, seg1.a, ...），输出先 v 后 a。
     * 归一化（分辨率/SAR/fps/像素格式、采样率/声道）由门面/编译器在其前处理，见 command-compiler。
     */
    public static ConcatResult concat(List<Segment> segments) {
        List<Stream> ins = new ArrayList<>();
        for (Segment s : segments) {
            ins.add(s.video());
            ins.add(s.audio());
        }
        FilterNode node = new FilterNode("concat", List.of(
                Arg.of("n", Integer.toString(segments.size())), Arg.of("v", "1"), Arg.of("a", "1")),
                ins, List.of(MediaType.VIDEO, MediaType.AUDIO));
        return new ConcatResult(
                new VideoStream(new Origin.FilterOrigin(node, 0)),
                new AudioStream(new Origin.FilterOrigin(node, 1)));
    }

    /**
     * 纯音频拼接（{@code concat=n=N:v=0:a=1}）：各段一路音频，输出一路音频。
     * 供门面在所有输入均无视轨时走纯音频路径，避免无谓地注入并编码黑帧视频。
     */
    public static AudioStream concatAudio(List<AudioStream> segments) {
        List<Stream> ins = new ArrayList<>(segments);
        FilterNode node = new FilterNode("concat", List.of(
                Arg.of("n", Integer.toString(segments.size())), Arg.of("v", "0"), Arg.of("a", "1")),
                ins, List.of(MediaType.AUDIO));
        return new AudioStream(new Origin.FilterOrigin(node, 0));
    }

    /**
     * 纯视频拼接（{@code concat=n=N:v=1:a=0}）：各段一路视频，输出一路视频。
     * 供门面在所有输入均无音轨时走纯视频路径，避免注入无谓的静音音轨。
     */
    public static VideoStream concatVideo(List<VideoStream> segments) {
        List<Stream> ins = new ArrayList<>(segments);
        FilterNode node = new FilterNode("concat", List.of(
                Arg.of("n", Integer.toString(segments.size())), Arg.of("v", "1"), Arg.of("a", "0")),
                ins, List.of(MediaType.VIDEO));
        return new VideoStream(new Origin.FilterOrigin(node, 0));
    }

    // ===== 字幕烧录族（第 16 个 curated；字幕源为文件参数而非 pad）=====

    /** 烧录字幕文件（{@code subtitles=} 滤镜，支持 {@code force_style}）。需 {@code --enable-libass}。 */
    public static VideoStream burnSubtitles(VideoStream in, Path subtitleFile) {
        return video("subtitles", List.of(Arg.escaped("filename", subtitleFile.toString())), in);
    }

    public static VideoStream burnSubtitles(VideoStream in, Path subtitleFile, String forceStyle) {
        return video("subtitles", List.of(
                Arg.escaped("filename", subtitleFile.toString()), Arg.of("force_style", forceStyle)), in);
    }

    /** 烧录 ASS/SSA 字幕（{@code ass=} 滤镜；无 {@code force_style} 选项）。需 {@code --enable-libass}。 */
    public static VideoStream burnAss(VideoStream in, Path assFile) {
        return video("ass", List.of(Arg.escaped("filename", assFile.toString())), in);
    }

    // ===== 逃生舱 =====

    /** 插入任意未建模的视频滤镜（内容不参与编译期类型校验，正确性自负）。 */
    public static VideoStream rawFilterVideo(VideoStream in, String rawFilter) {
        return new VideoStream(new Origin.FilterOrigin(
                new FilterNode(rawFilter, List.of(), List.of(in), List.of(MediaType.VIDEO)), 0));
    }

    public static AudioStream rawFilterAudio(AudioStream in, String rawFilter) {
        return new AudioStream(new Origin.FilterOrigin(
                new FilterNode(rawFilter, List.of(), List.of(in), List.of(MediaType.AUDIO)), 0));
    }

    // ===== 内部辅助 =====

    /** 为音质把超出单实例范围/ >2.0 或 <0.5 的 atempo 因子拆成链。 */
    static List<Double> decomposeAtempo(double tempo) {
        List<Double> factors = new ArrayList<>();
        double r = tempo;
        while (r > 2.0) {
            factors.add(2.0);
            r /= 2.0;
        }
        while (r < 0.5) {
            factors.add(0.5);
            r /= 0.5;
        }
        factors.add(r);
        return factors;
    }

    private static VideoStream video(String filter, List<Arg> args, Stream... inputs) {
        return new VideoStream(new Origin.FilterOrigin(
                new FilterNode(filter, args, List.of(inputs), List.of(MediaType.VIDEO)), 0));
    }

    private static AudioStream audio(String filter, List<Arg> args, Stream... inputs) {
        return new AudioStream(new Origin.FilterOrigin(
                new FilterNode(filter, args, List.of(inputs), List.of(MediaType.AUDIO)), 0));
    }

    private static String num(double d) {
        if (Double.isFinite(d) && d == Math.rint(d)) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }
}
