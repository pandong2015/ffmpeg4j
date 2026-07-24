package io.github.pandong2015.ffmpeg4j.task;

import java.util.Map;
import java.util.Objects;

/**
 * 一项不可变的非致命任务警告。
 *
 * @param code    稳定代码
 * @param message 中文诊断说明
 * @param details 低层诊断明细的不可变快照
 */
public record FfmpegWarning(WarningCode code, String message, Map<String, String> details) {

    /** 校验字段并防御性复制明细。 */
    public FfmpegWarning {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        details = Map.copyOf(Objects.requireNonNull(details, "details"));
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空白");
        }
    }

    /** 创建不含额外明细的警告。 */
    public FfmpegWarning(WarningCode code, String message) {
        this(code, message, Map.of());
    }
}
