package io.github.pandong2015.ffmpeg4j.engine;

import java.util.List;

/**
 * 一次 ffmpeg 执行的结果快照。
 *
 * <p>正常成功（退出码 0）或被调用方主动取消（{@code cancel()}）时由引擎返回；因错误非零退出或
 * 超时时引擎改为抛出 {@link io.github.pandong2015.ffmpeg4j.FfmpegException} 而不返回本记录。
 *
 * @param exitCode     进程退出码（0 为成功；取消收尾可能为非 0，如 255）。
 * @param lastProgress 最后一次进度快照（无任何进度块时为 {@link Progress#empty()}，非 {@code null}）。
 * @param command      实际执行的命令（已把占位二进制替换为解析路径、并注入 {@code -progress}）。
 */
public record RunResult(int exitCode, Progress lastProgress, List<String> command) {

    public RunResult {
        command = command == null ? List.of() : List.copyOf(command);
        if (lastProgress == null) {
            lastProgress = Progress.empty();
        }
    }
}
