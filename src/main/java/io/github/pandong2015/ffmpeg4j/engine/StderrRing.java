package io.github.pandong2015.ffmpeg4j.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 有界环形缓冲，仅保留最近若干行 stderr（默认约 50 行），用于非零退出时组装
 * {@link io.github.pandong2015.ffmpeg4j.FfmpegException} 的 {@code stderrTail}。
 *
 * <p>纯逻辑、线程安全：stderr pump 线程写入、await 线程读取，方法均加锁。
 */
final class StderrRing {

    /** 默认保留的最大行数。 */
    static final int DEFAULT_MAX_LINES = 50;

    private final int maxLines;
    private final Deque<String> lines;

    StderrRing() {
        this(DEFAULT_MAX_LINES);
    }

    StderrRing(int maxLines) {
        if (maxLines <= 0) {
            throw new IllegalArgumentException("maxLines 必须为正");
        }
        this.maxLines = maxLines;
        this.lines = new ArrayDeque<>(maxLines);
    }

    /** 追加一行；超过容量时丢弃最旧的一行。 */
    synchronized void add(String line) {
        if (line == null) {
            return;
        }
        if (lines.size() >= maxLines) {
            lines.pollFirst();
        }
        lines.addLast(line);
    }

    /** 当前保留的行（旧→新）的快照。 */
    synchronized List<String> lines() {
        return new ArrayList<>(lines);
    }

    /** 以换行拼接的尾部文本（旧→新）。 */
    synchronized String tail() {
        return String.join("\n", lines);
    }

    /** 当前保留的行数。 */
    synchronized int size() {
        return lines.size();
    }
}
