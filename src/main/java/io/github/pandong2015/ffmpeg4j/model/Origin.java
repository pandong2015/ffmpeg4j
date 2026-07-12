package io.github.pandong2015.ffmpeg4j.model;

/**
 * 一个 {@link Stream} 的来源（内部 API）：来自输入文件的某条流，或某个滤镜节点的某路输出。
 *
 * <p>属库内部图模型，用户不应直接依赖。
 */
public sealed interface Origin permits Origin.InputOrigin, Origin.FilterOrigin {

    /** 来自输入文件：{@code input} 的、按媒体类型的第 {@code typedIndex} 路流（如 {@code 0:v:0}）。 */
    record InputOrigin(Input input, MediaType mediaType, int typedIndex) implements Origin {}

    /** 来自滤镜：{@code node} 的第 {@code outputIndex} 路输出。 */
    record FilterOrigin(FilterNode node, int outputIndex) implements Origin {}
}
