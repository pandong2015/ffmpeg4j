package io.github.pandong2015.ffmpeg4j.probe;

import io.github.pandong2015.ffmpeg4j.FfmpegException;
import io.github.pandong2015.ffmpeg4j.probe.json.Json;
import io.github.pandong2015.ffmpeg4j.probe.json.JsonValue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 媒体探测入口：调用预装的 {@code ffprobe} 读取文件元数据并映射为 {@link ProbeResult}。
 *
 * <p>执行命令：{@code ffprobe -v error -print_format json -show_format -show_streams <file>}。
 * 捕获 stdout 交由 {@link ProbeMapper} 解析。
 *
 * <ul>
 *   <li>文件不存在 / 非媒体：ffprobe 以非零码退出，抛出携带 stderr 尾部与可读原因的
 *       {@link FfmpegException}（结构化构造器）。</li>
 *   <li>二进制缺失 / 无法启动：抛出携带 cause 的 {@link FfmpegException}。</li>
 * </ul>
 */
public final class MediaProbe {

    /** 默认 ffprobe 可执行名（依赖 PATH 解析）。 */
    public static final String DEFAULT_FFPROBE = "ffprobe";

    /** stderr 环形尾部保留的行数上限。 */
    private static final int STDERR_TAIL_LINES = 50;

    private MediaProbe() {
    }

    /** 使用默认 {@code ffprobe} 探测给定路径。 */
    public static ProbeResult probe(Path file) {
        return probe(file, DEFAULT_FFPROBE);
    }

    /** 使用默认 {@code ffprobe} 探测给定文件。 */
    public static ProbeResult probe(File file) {
        Objects.requireNonNull(file, "file");
        return probe(file.toPath(), DEFAULT_FFPROBE);
    }

    /** 使用指定的 ffprobe 二进制路径/名称探测给定路径。 */
    public static ProbeResult probe(Path file, String ffprobeBinary) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(ffprobeBinary, "ffprobeBinary");

        List<String> command = List.of(
                ffprobeBinary,
                "-v", "error",
                "-print_format", "json",
                "-show_format",
                "-show_streams",
                file.toString());

        ProcessExec.Result result;
        try {
            result = ProcessExec.run(command);
        } catch (IOException e) {
            throw new FfmpegException(
                    "无法启动 ffprobe（二进制 '" + ffprobeBinary + "' 未找到或无法执行）: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FfmpegException("ffprobe 执行被中断", e);
        }

        if (result.exitCode() != 0) {
            throw new FfmpegException(
                    result.exitCode(),
                    command,
                    tail(result.stderr(), STDERR_TAIL_LINES),
                    reason(result.stderr()));
        }

        try {
            return fromJson(result.stdout());
        } catch (RuntimeException e) {
            throw new FfmpegException("解析 ffprobe JSON 输出失败: " + e.getMessage(), e);
        }
    }

    /**
     * 直接把一段 ffprobe JSON 文本映射为 {@link ProbeResult}（不触发任何进程）。
     * 便于测试与离线场景复用映射逻辑。
     */
    public static ProbeResult fromJson(String ffprobeJson) {
        JsonValue root = Json.parse(ffprobeJson);
        return ProbeMapper.map(root);
    }

    /** 取 stderr 最后 {@code maxLines} 行拼接为诊断尾部。 */
    private static String tail(String stderr, int maxLines) {
        if (stderr == null || stderr.isBlank()) {
            return "";
        }
        String[] lines = stderr.strip().split("\\R");
        int from = Math.max(0, lines.length - maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /** 从 stderr 中尽力解析可读原因：取最后一条非空行；无则返回 {@code null}。 */
    private static String reason(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return null;
        }
        String[] lines = stderr.strip().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                return line;
            }
        }
        return null;
    }
}
