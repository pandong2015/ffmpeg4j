package io.github.pandong2015.ffmpeg4j.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 不可变滤镜节点（内部图模型）：一个 ffmpeg 滤镜名 + 有序参数 + 输入流 + 各路输出的媒体类型。
 *
 * <p>用户通过 {@code Filters} 工厂间接创建，不直接构造。参数以有序 {@link Arg} 列表表示：
 * {@code key} 为空表示位置参数（如 {@code setpts=PTS-STARTPTS}），否则渲染为 {@code key=value}。
 *
 * <p>属库内部 API，不保证跨版本稳定。
 */
public final class FilterNode {

    /**
     * 单个滤镜参数；{@code key} 为 null/空表示位置参数。{@code escape} 标记该值为用户自由文本
     * 或文件路径（如 drawText 文本、subtitles 文件名），编译器渲染时须对其做 filtergraph 转义。
     */
    public record Arg(String key, String value, boolean escape) {
        public static Arg of(String key, String value) {
            return new Arg(key, value, false);
        }

        /** 值为用户自由文本/路径，编译器须转义。 */
        public static Arg escaped(String key, String value) {
            return new Arg(key, value, true);
        }

        public static Arg positional(String value) {
            return new Arg(null, value, false);
        }

        public boolean isPositional() {
            return key == null || key.isEmpty();
        }
    }

    private final String filter;
    private final List<Arg> args;
    private final List<Stream> inputs;
    private final List<MediaType> outputTypes;

    public FilterNode(String filter, List<Arg> args, List<Stream> inputs, List<MediaType> outputTypes) {
        this.filter = filter;
        this.args = List.copyOf(args);
        this.inputs = List.copyOf(inputs);
        this.outputTypes = List.copyOf(outputTypes);
    }

    public String filter() {
        return filter;
    }

    public List<Arg> args() {
        return args;
    }

    public List<Stream> inputs() {
        return inputs;
    }

    public List<MediaType> outputTypes() {
        return outputTypes;
    }

    public int outputArity() {
        return outputTypes.size();
    }

    /**
     * 渲染滤镜体（不含 pad 名），如 {@code scale=w=1280:h=720} 或 {@code setpts=PTS-STARTPTS} 或无参的 {@code hflip}。
     * 值已假定由调用方转义（见 command-compiler 的转义需求）。
     */
    public String renderBody() {
        if (args.isEmpty()) {
            return filter;
        }
        List<String> parts = new ArrayList<>(args.size());
        for (Arg a : args) {
            parts.add(a.isPositional() ? a.value() : a.key() + "=" + a.value());
        }
        return filter + "=" + String.join(":", parts);
    }

    @Override
    public String toString() {
        return "FilterNode[" + renderBody() + ", in=" + inputs.size() + ", out=" + outputTypes + "]";
    }
}
