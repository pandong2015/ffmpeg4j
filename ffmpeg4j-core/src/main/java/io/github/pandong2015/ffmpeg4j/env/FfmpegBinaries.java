package io.github.pandong2015.ffmpeg4j.env;

import io.github.pandong2015.ffmpeg4j.FfmpegException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 已解析的 ffmpeg / ffprobe 可执行文件路径。
 *
 * <p>解析顺序（每个二进制独立生效）：
 * <ol>
 *   <li>系统属性覆盖：{@code -Dffmpeg4j.ffmpeg.path=...} / {@code -Dffmpeg4j.ffprobe.path=...}</li>
 *   <li>环境变量覆盖：{@code FFMPEG4J_FFMPEG} / {@code FFMPEG4J_FFPROBE}</li>
 *   <li>在 {@code PATH} 上搜索裸命令名 {@code ffmpeg} / {@code ffprobe}</li>
 * </ol>
 *
 * <p>找不到或不可执行时抛出<em>可诊断的</em> {@link FfmpegException}，明确指出「未找到该二进制」，
 * 而非模糊的进程启动失败。
 */
public record FfmpegBinaries(Path ffmpeg, Path ffprobe) {

    /** ffmpeg 路径覆盖系统属性名。 */
    public static final String PROP_FFMPEG = "ffmpeg4j.ffmpeg.path";
    /** ffprobe 路径覆盖系统属性名。 */
    public static final String PROP_FFPROBE = "ffmpeg4j.ffprobe.path";
    /** ffmpeg 路径覆盖环境变量名。 */
    public static final String ENV_FFMPEG = "FFMPEG4J_FFMPEG";
    /** ffprobe 路径覆盖环境变量名。 */
    public static final String ENV_FFPROBE = "FFMPEG4J_FFPROBE";

    public FfmpegBinaries {
        Objects.requireNonNull(ffmpeg, "ffmpeg path");
        Objects.requireNonNull(ffprobe, "ffprobe path");
    }

    /**
     * 按系统属性 → 环境变量 → PATH 的顺序定位 ffmpeg 与 ffprobe。
     *
     * @throws FfmpegException 任一二进制找不到或不可执行时抛出，消息明确指向缺失的二进制。
     */
    public static FfmpegBinaries locate() {
        Path ffmpeg = resolveRequired("ffmpeg", PROP_FFMPEG, ENV_FFMPEG);
        Path ffprobe = resolveRequired("ffprobe", PROP_FFPROBE, ENV_FFPROBE);
        return new FfmpegBinaries(ffmpeg, ffprobe);
    }

    /**
     * 使用显式路径构造，并校验两者均存在且可执行。
     *
     * @throws FfmpegException 任一路径不是可执行的常规文件时抛出。
     */
    public static FfmpegBinaries of(Path ffmpeg, Path ffprobe) {
        return new FfmpegBinaries(
                validateExplicit("ffmpeg", ffmpeg),
                validateExplicit("ffprobe", ffprobe));
    }

    private static Path validateExplicit(String name, Path path) {
        Objects.requireNonNull(path, name + " path");
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
            throw new FfmpegException(
                    "未找到可用的 " + name + " 可执行文件：指定路径不存在或不可执行 -> " + path
                            + "。请提供有效的 " + name + " 二进制路径。",
                    null);
        }
        return path.toAbsolutePath().normalize();
    }

    private static Path resolveRequired(String name, String propKey, String envKey) {
        String override = firstNonBlank(System.getProperty(propKey), System.getenv(envKey));
        if (override != null) {
            Optional<Path> resolved = Executables.resolve(override);
            if (resolved.isEmpty()) {
                throw new FfmpegException(
                        "未找到 " + name + " 可执行文件：显式指定的路径无效或不可执行 -> " + override
                                + "（来自 -D" + propKey + " 或环境变量 " + envKey + "）。",
                        null);
            }
            return resolved.get();
        }
        Optional<Path> onPath = Executables.resolve(name);
        if (onPath.isEmpty()) {
            throw new FfmpegException(
                    "未找到 " + name + " 可执行文件：既不在 PATH 上，也未通过 -D" + propKey
                            + " 或环境变量 " + envKey + " 指定。请安装 ffmpeg/ffprobe（>= "
                            + FfmpegVersion.MIN_FFMPEG_VERSION + " 建议）并确保其在 PATH 中。",
                    null);
        }
        return onPath.get();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    /** ffmpeg 二进制路径的字符串形式，便于拼接命令行。 */
    public String ffmpegCommand() {
        return ffmpeg.toString();
    }

    /** ffprobe 二进制路径的字符串形式，便于拼接命令行。 */
    public String ffprobeCommand() {
        return ffprobe.toString();
    }

    List<String> ffmpegArgv(String... args) {
        List<String> argv = new java.util.ArrayList<>(args.length + 1);
        argv.add(ffmpegCommand());
        java.util.Collections.addAll(argv, args);
        return argv;
    }
}
