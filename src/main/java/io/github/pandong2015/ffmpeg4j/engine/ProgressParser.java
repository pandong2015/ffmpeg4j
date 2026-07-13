package io.github.pandong2015.ffmpeg4j.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code -progress} 输出的纯逻辑解析器：逐行喂入，累积 {@code key=value}，逢
 * {@code progress=continue|end} 即完成一块并产出 {@link Progress} 快照。
 *
 * <p>无进程依赖、非线程安全——按设计只由单一进度 pump 线程顺序喂入。
 */
final class ProgressParser {

    private final Map<String, String> current = new LinkedHashMap<>();

    /**
     * 喂入一行。
     *
     * @return 当该行是 {@code progress=...}（块结束标记）时，返回本块的进度快照；否则返回空。
     */
    Optional<Progress> offer(String line) {
        if (line == null) {
            return Optional.empty();
        }
        int eq = line.indexOf('=');
        if (eq <= 0) {
            // 非 key=value 行（空行/杂项）忽略。
            return Optional.empty();
        }
        String key = line.substring(0, eq).trim();
        String value = line.substring(eq + 1).trim();
        if (key.isEmpty()) {
            return Optional.empty();
        }
        current.put(key, value);
        if (key.equals("progress")) {
            Progress snapshot = new Progress(new LinkedHashMap<>(current));
            current.clear();
            return Optional.of(snapshot);
        }
        return Optional.empty();
    }
}
