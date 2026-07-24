package io.github.pandong2015.ffmpeg4j.task;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次顶层媒体任务的稳定标识。
 *
 * @param value 非空白标识值
 */
public record TaskId(String value) {

    /** 校验并创建调用方指定的任务标识。 */
    public TaskId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空白");
        }
    }

    /** 创建进程内唯一的随机任务标识。 */
    public static TaskId random() {
        return new TaskId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
