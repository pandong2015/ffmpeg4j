package io.github.pandong2015.ffmpeg4j.model;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一个 ffmpeg 输入（{@code -i <path>}）及其输入侧选项。不可变值。
 *
 * <p>通过 {@link #video()}/{@link #audio()}/{@link #subtitle()} 取得按类型选取的流句柄，
 * 用户无需书写 {@code 0:v} 之类的流说明符。输入侧原始参数（位置感知逃生舱，如 {@code -ss} 快切）
 * 经 {@link #withInputArgs(String...)} 追加，编译器会把它们放在本输入的 {@code -i} 之前。
 */
public final class Input {

    private final Path path;
    private final List<String> inputArgs;

    private Input(Path path, List<String> inputArgs) {
        this.path = Objects.requireNonNull(path, "path");
        this.inputArgs = List.copyOf(inputArgs);
    }

    public static Input of(Path path) {
        return new Input(path, List.of());
    }

    public static Input of(File file) {
        return of(file.toPath());
    }

    public static Input of(String path) {
        return of(Path.of(path));
    }

    public Path path() {
        return path;
    }

    /** 本输入的输入侧原始参数（置于 {@code -i} 之前）。 */
    public List<String> inputArgs() {
        return inputArgs;
    }

    /**
     * 位置感知逃生舱：返回一个追加了输入侧原始参数的新 {@code Input}（不可变复制）。
     * 输入侧选项（{@code -ss}/{@code -f}/{@code -framerate}/{@code -hwaccel} 等）须走此处而非末尾追加。
     */
    public Input withInputArgs(String... args) {
        List<String> merged = new ArrayList<>(inputArgs);
        merged.addAll(List.of(args));
        return new Input(path, merged);
    }

    public VideoStream video() {
        return video(0);
    }

    public VideoStream video(int typedIndex) {
        return new VideoStream(new Origin.InputOrigin(this, MediaType.VIDEO, typedIndex));
    }

    public AudioStream audio() {
        return audio(0);
    }

    public AudioStream audio(int typedIndex) {
        return new AudioStream(new Origin.InputOrigin(this, MediaType.AUDIO, typedIndex));
    }

    /**
     * 首路<em>可选</em>视频流（渲染为 {@code 0:v:0?}）：当输入实际不含视频流时，编译器产出的 {@code -map}
     * 带尾随 {@code ?}，令 ffmpeg 静默跳过该映射而非以「matches no streams」中止。供门面在<em>不确定</em>
     * 输入是否含视频流（如转码/截段可能面对纯音频输入）时使用。
     */
    public VideoStream videoOptional() {
        return new VideoStream(new Origin.InputOrigin(this, MediaType.VIDEO, 0, true));
    }

    /** 首路<em>可选</em>音频流（渲染为 {@code 0:a:0?}），语义同 {@link #videoOptional()}（如面对无音轨的静音视频）。 */
    public AudioStream audioOptional() {
        return new AudioStream(new Origin.InputOrigin(this, MediaType.AUDIO, 0, true));
    }

    public SubtitleStream subtitle() {
        return subtitle(0);
    }

    public SubtitleStream subtitle(int typedIndex) {
        return new SubtitleStream(new Origin.InputOrigin(this, MediaType.SUBTITLE, typedIndex));
    }

    @Override
    public String toString() {
        return "Input[" + path + (inputArgs.isEmpty() ? "" : ", args=" + inputArgs) + "]";
    }
}
