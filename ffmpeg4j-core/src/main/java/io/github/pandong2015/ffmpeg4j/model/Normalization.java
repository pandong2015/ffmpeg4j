package io.github.pandong2015.ffmpeg4j.model;

import java.util.ArrayList;
import java.util.List;

import io.github.pandong2015.ffmpeg4j.model.FilterNode.Arg;

/**
 * 汇聚归一化能力（model 层纯函数）：把异构的视频/音频流归一到统一的分辨率/SAR/帧率/像素格式与
 * 采样率/采样格式/声道布局，供门面在 {@link Filters#concat} 等汇聚操作<em>前置</em>调用。
 *
 * <p>编译器（L2）保持通用、<em>不</em>对 concat 特判归一化；本类承载归一化知识，输出的仍是普通
 * 「流即值」，交由编译器照常编译。{@code setsar}/{@code aresample}/{@code aformat}/{@code anullsrc}/
 * {@code color} 均为归一化<em>内部</em>滤镜，<em>不</em>作为 {@link Filters} 的公共 curated 方法暴露
 * （沿用既有约定），在本类内用 {@link FilterNode} 直接构造；{@code scale}/{@code fps}/{@code format}
 * 本就是 curated 滤镜，直接复用 {@link Filters} 工厂。
 */
public final class Normalization {

    private Normalization() {
    }

    /**
     * 视频归一化链：{@code scale=w:h -> setsar=1 -> fps=fps -> format=pixFmt}。
     *
     * <p><em>必含</em> {@code setsar=1}：{@code scale}/{@code pad} 都不保证输出统一的样本宽高比（SAR），
     * 而 concat 要求各段 SAR 一致，故显式归一。
     */
    public static VideoStream normalizeVideo(VideoStream in, VideoNormTarget t) {
        VideoStream scaled = Filters.scale(in, t.width(), t.height());
        VideoStream squared = video("setsar", List.of(Arg.positional("1")), scaled);
        VideoStream framed = Filters.fps(squared, t.fps());
        return Filters.format(framed, t.pixelFormat());
    }

    /**
     * 音频归一化链：{@code aresample=<rate> -> aformat=sample_fmts=<fmt>:channel_layouts=<cl>}。
     */
    public static AudioStream normalizeAudio(AudioStream in, AudioNormTarget t) {
        AudioStream resampled = audio("aresample",
                List.of(Arg.positional(Integer.toString(t.sampleRate()))), in);
        return audio("aformat", List.of(
                Arg.of("sample_fmts", t.sampleFormat()),
                Arg.of("channel_layouts", t.channelLayout())), resampled);
    }

    /**
     * 对每段的视频与音频分别归一化（供 concat 前置），返回归一化后的新 {@link Filters.Segment} 列表。
     * 不修改入参，返回不可变副本。
     */
    public static List<Filters.Segment> normalizeSegments(List<Filters.Segment> segs,
                                                          VideoNormTarget vt, AudioNormTarget at) {
        List<Filters.Segment> out = new ArrayList<>(segs.size());
        for (Filters.Segment s : segs) {
            out.add(new Filters.Segment(normalizeVideo(s.video(), vt), normalizeAudio(s.audio(), at)));
        }
        return List.copyOf(out);
    }

    /**
     * 缺轨占位「静音源」：零输入 {@code anullsrc=r=<rate>:cl=<layout>} source 滤镜。
     * 供门面在流集合异构（某些段缺音轨）时注入，使 concat 各段轨数一致。
     */
    public static AudioStream silence(AudioNormTarget t) {
        FilterNode node = new FilterNode("anullsrc", List.of(
                Arg.of("r", Integer.toString(t.sampleRate())),
                Arg.of("cl", t.channelLayout())),
                List.of(), List.of(MediaType.AUDIO));
        return new AudioStream(new Origin.FilterOrigin(node, 0));
    }

    /**
     * 缺轨占位「纯色源」：零输入 {@code color=c=<color>:s=<w>x<h>:r=<fps>} source 滤镜。
     * 供门面在流集合异构（某些段缺视频）时注入，使 concat 各段轨数一致。
     */
    public static VideoStream blank(VideoNormTarget t, String color) {
        FilterNode node = new FilterNode("color", List.of(
                Arg.of("c", color),
                Arg.of("s", t.width() + "x" + t.height()),
                Arg.of("r", num(t.fps()))),
                List.of(), List.of(MediaType.VIDEO));
        return new VideoStream(new Origin.FilterOrigin(node, 0));
    }

    // ===== 内部辅助（构造归一化专用的内部滤镜节点，不经 Filters 公共工厂）=====

    private static VideoStream video(String filter, List<Arg> args, Stream in) {
        return new VideoStream(new Origin.FilterOrigin(
                new FilterNode(filter, args, List.of(in), List.of(MediaType.VIDEO)), 0));
    }

    private static AudioStream audio(String filter, List<Arg> args, Stream in) {
        return new AudioStream(new Origin.FilterOrigin(
                new FilterNode(filter, args, List.of(in), List.of(MediaType.AUDIO)), 0));
    }

    /** 整数值渲染为整数（{@code 30} 而非 {@code 30.0}），与 {@link Filters} 内的数值格式化一致。 */
    private static String num(double d) {
        if (Double.isFinite(d) && d == Math.rint(d)) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }
}
