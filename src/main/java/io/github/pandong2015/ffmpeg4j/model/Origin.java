package io.github.pandong2015.ffmpeg4j.model;

/**
 * 一个 {@link Stream} 的来源（内部 API）：来自输入文件的某条流，或某个滤镜节点的某路输出。
 *
 * <p>属库内部图模型，用户不应直接依赖。
 */
public sealed interface Origin permits Origin.InputOrigin, Origin.FilterOrigin {

    /**
     * 来自输入文件：{@code input} 的、按媒体类型的第 {@code typedIndex} 路流（如 {@code 0:v:0}）。
     *
     * <p>{@code optional} 为真时编译器把 {@code -map} 渲染为可选映射（尾随 {@code ?}，如 {@code 0:a:0?}）：
     * 当输入实际不含该流时 ffmpeg 静默跳过而非以「matches no streams」中止。
     */
    record InputOrigin(Input input, MediaType mediaType, int typedIndex, boolean optional) implements Origin {
        /** 便捷构造：默认必选（{@code optional=false}），保持既有调用点不变。 */
        public InputOrigin(Input input, MediaType mediaType, int typedIndex) {
            this(input, mediaType, typedIndex, false);
        }
    }

    /** 来自滤镜：{@code node} 的第 {@code outputIndex} 路输出。 */
    record FilterOrigin(FilterNode node, int outputIndex) implements Origin {}
}
