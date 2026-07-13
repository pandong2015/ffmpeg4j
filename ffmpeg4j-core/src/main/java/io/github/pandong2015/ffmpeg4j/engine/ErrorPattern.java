package io.github.pandong2015.ffmpeg4j.engine;

import java.util.regex.Pattern;

/**
 * 一条已知错误模式：类别标识、匹配整段 stderr 尾部的正则、一句人话原因，以及是否属于
 * 「库内部管道故障」（{@code internal=true} 表示不应作为媒体类异常抛给调用方）。
 *
 * @param category 类别 slug（如 {@code unknown-filter}）。
 * @param pattern  在 stderr 尾部上 {@code find()} 匹配的正则。
 * @param reason   面向用户的可读原因（简体中文一句话）。
 * @param internal 是否为库内部管道故障（内部错误类别，MUST NOT 外泄为媒体错误）。
 */
record ErrorPattern(String category, Pattern pattern, String reason, boolean internal) {

    /** 构造一个媒体类（非内部）模式。 */
    static ErrorPattern of(String category, String regex, String reason) {
        return new ErrorPattern(category, compile(regex), reason, false);
    }

    /** 构造一个库内部管道类模式（不外泄为媒体错误）。 */
    static ErrorPattern internal(String category, String regex, String reason) {
        return new ErrorPattern(category, compile(regex), reason, true);
    }

    private static Pattern compile(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    /** 该模式是否命中给定 stderr 尾部（整段 {@code find}）。 */
    boolean matches(String stderrTail) {
        return stderrTail != null && pattern.matcher(stderrTail).find();
    }
}
