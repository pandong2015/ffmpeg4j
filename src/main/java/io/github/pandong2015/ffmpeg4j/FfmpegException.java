package io.github.pandong2015.ffmpeg4j;

import java.util.List;

/**
 * ffmpeg/ffprobe 进程以非零码退出（或无法启动）时抛出的结构化异常。
 *
 * <p>携带退出码、执行的命令、stderr 尾部（引擎以环形缓冲保留约最后 50 行），以及一段由已知
 * 错误模式尽力解析得到的可读原因（{@link #reason()} 可能为 {@code null}）。
 *
 * <p>注意：库自身的内部管道故障（如 {@code -progress} TCP 通道 {@code Connection refused}）
 * 不通过本异常外泄，而是归为内部错误类别。
 */
public class FfmpegException extends RuntimeException {

    private final int exitCode;
    private final List<String> command;
    private final String stderrTail;
    private final String reason;

    public FfmpegException(int exitCode, List<String> command, String stderrTail, String reason) {
        super(buildMessage(exitCode, reason, stderrTail));
        this.exitCode = exitCode;
        this.command = command == null ? List.of() : List.copyOf(command);
        this.stderrTail = stderrTail == null ? "" : stderrTail;
        this.reason = reason;
    }

    /** 用于二进制缺失/无法启动等尚无退出码的场景。 */
    public FfmpegException(String message, Throwable cause) {
        super(message, cause);
        this.exitCode = -1;
        this.command = List.of();
        this.stderrTail = "";
        this.reason = message;
    }

    private static String buildMessage(int exitCode, String reason, String stderrTail) {
        StringBuilder sb = new StringBuilder("ffmpeg exited with code ").append(exitCode);
        if (reason != null && !reason.isBlank()) {
            sb.append(": ").append(reason);
        }
        if (stderrTail != null && !stderrTail.isBlank()) {
            sb.append("\n--- stderr tail ---\n").append(stderrTail);
        }
        return sb.toString();
    }

    public int exitCode() {
        return exitCode;
    }

    public List<String> command() {
        return command;
    }

    public String stderrTail() {
        return stderrTail;
    }

    /** 由已知错误模式解析出的可读原因；无法解析时为 {@code null}。 */
    public String reason() {
        return reason;
    }
}
