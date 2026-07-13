package io.github.pandong2015.ffmpeg4j.probe.json;

/**
 * 微型 JSON 解析器在遇到非法输入时抛出的运行时异常。
 *
 * <p>仅覆盖 ffprobe 输出这一受控子集；不追求成为通用 JSON 库，因此错误信息以定位偏移量为主。
 */
public final class JsonParseException extends RuntimeException {

    private final int offset;

    JsonParseException(String message, int offset) {
        super(message + " (offset " + offset + ")");
        this.offset = offset;
    }

    /** 出错处在输入串中的字符偏移量。 */
    public int offset() {
        return offset;
    }
}
