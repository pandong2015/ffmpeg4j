package io.github.pandong2015.ffmpeg4j.compiler;

import java.util.List;

/**
 * 编译产物：完整的 ffmpeg 参数列表（argv）与（可选的）{@code -filter_complex} 字符串。
 *
 * <p>{@code filterComplex} 在无滤镜（纯 remux/transcode 直接 {@code -map}）时为 {@code null}。
 */
public record CompiledCommand(List<String> argv, String filterComplex) {

    public CompiledCommand {
        argv = List.copyOf(argv);
    }

    /** 便于日志/断言的单行渲染（非用于 shell 执行）。 */
    public String render() {
        return String.join(" ", argv);
    }
}
