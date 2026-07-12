package io.github.pandong2015.ffmpeg4j.model;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一个 ffmpeg 输出目标（文件路径）及其映射的流与输出侧选项。不可变值。
 *
 * <p>{@code mapped} 是要 {@code -map} 进本输出的流；{@code outputArgs} 是输出侧原始参数
 * （codec、{@code -movflags} 等，置于输出文件之前）。一个任务可含多个 {@code Output}
 * 以在单次 ffmpeg 调用里产出多个输出文件（见 command-compiler 的多输出去重）。
 */
public final class Output {

    private final Path path;
    private final List<Stream> mapped;
    private final List<String> outputArgs;

    private Output(Path path, List<Stream> mapped, List<String> outputArgs) {
        this.path = Objects.requireNonNull(path, "path");
        this.mapped = List.copyOf(mapped);
        this.outputArgs = List.copyOf(outputArgs);
    }

    public static Output to(Path path, Stream... streams) {
        return new Output(path, List.of(streams), List.of());
    }

    public static Output to(File file, Stream... streams) {
        return to(file.toPath(), streams);
    }

    public static Output to(String path, Stream... streams) {
        return to(Path.of(path), streams);
    }

    public Path path() {
        return path;
    }

    public List<Stream> mapped() {
        return mapped;
    }

    /** 本输出的输出侧原始参数（置于输出文件之前）。 */
    public List<String> outputArgs() {
        return outputArgs;
    }

    /** 位置感知逃生舱：追加输出侧原始参数，返回新 {@code Output}（如 {@code -movflags +faststart}）。 */
    public Output withArgs(String... args) {
        List<String> merged = new ArrayList<>(outputArgs);
        merged.addAll(List.of(args));
        return new Output(path, mapped, merged);
    }

    /** 追加要映射进本输出的流，返回新 {@code Output}。 */
    public Output withStreams(Stream... streams) {
        List<Stream> merged = new ArrayList<>(mapped);
        merged.addAll(List.of(streams));
        return new Output(path, merged, outputArgs);
    }

    @Override
    public String toString() {
        return "Output[" + path + ", mapped=" + mapped.size() + (outputArgs.isEmpty() ? "" : ", args=" + outputArgs) + "]";
    }
}
