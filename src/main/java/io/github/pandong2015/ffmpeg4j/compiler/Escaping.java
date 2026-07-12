package io.github.pandong2015.ffmpeg4j.compiler;

/**
 * filtergraph 转义工具（L2 编译器职责）。
 *
 * <p>{@code -filter_complex} 作为单个 argv 元素传给 ffmpeg（不经 shell），故无需 shell 引号；
 * 此处只处理 filtergraph <em>内部</em>的分隔/特殊字符——用于 drawText 的自由文本、subtitles/ass
 * 的文件名等由用户提供、可能含特殊字符的值：
 * <ul>
 *   <li>{@code \\}（反斜杠，须先处理以免二次转义）</li>
 *   <li>{@code :}（选项分隔符；Windows 盘符冒号如 {@code C:} 尤须转义）</li>
 *   <li>{@code '}（引号）</li>
 *   <li>{@code %}（drawText 文本展开，如 strftime / {@code %{...}}）</li>
 * </ul>
 */
public final class Escaping {

    private Escaping() {
    }

    /** 对进入 filter_complex 的用户自由文本/文件名做单遍 filtergraph 转义。 */
    public static String filterArgValue(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case ':' -> sb.append("\\:");
                case '\'' -> sb.append("\\'");
                case '%' -> sb.append("\\%");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
