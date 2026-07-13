package io.github.pandong2015.ffmpeg4j.probe.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自研微型 JSON 递归下降解析器：仅覆盖 ffprobe 输出的子集
 * （对象、数组、字符串、数字、布尔、null），不引入任何第三方依赖（无 Jackson）。
 *
 * <p>入口 {@link #parse(String)} 返回不可继续解析残余内容的完整 {@link JsonValue}。
 * 对良构输入稳健；非法输入抛出 {@link JsonParseException}。
 */
public final class Json {

    private final String s;
    private final int len;
    private int pos;

    private Json(String s) {
        this.s = s;
        this.len = s.length();
    }

    /** 解析整段 JSON 文本；末尾若有非空白残留则报错。 */
    public static JsonValue parse(String text) {
        if (text == null) {
            throw new JsonParseException("输入为 null", 0);
        }
        Json p = new Json(text);
        p.skipWs();
        JsonValue v = p.parseValue();
        p.skipWs();
        if (p.pos < p.len) {
            throw p.err("解析完成后仍有多余内容");
        }
        return v;
    }

    private JsonValue parseValue() {
        skipWs();
        char c = peek();
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> new JsonValue(parseString());
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private JsonValue parseObject() {
        expect('{');
        Map<String, JsonValue> m = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            pos++;
            return new JsonValue(m);
        }
        while (true) {
            skipWs();
            if (peek() != '"') {
                throw err("对象键必须为字符串");
            }
            String key = parseString();
            skipWs();
            expect(':');
            m.put(key, parseValue());
            skipWs();
            char c = next();
            if (c == '}') {
                break;
            }
            if (c != ',') {
                throw err("期望 ',' 或 '}'");
            }
        }
        return new JsonValue(m);
    }

    private JsonValue parseArray() {
        expect('[');
        List<JsonValue> a = new ArrayList<>();
        skipWs();
        if (peek() == ']') {
            pos++;
            return new JsonValue(a);
        }
        while (true) {
            a.add(parseValue());
            skipWs();
            char c = next();
            if (c == ']') {
                break;
            }
            if (c != ',') {
                throw err("期望 ',' 或 ']'");
            }
        }
        return new JsonValue(a);
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= len) {
                throw err("字符串未闭合");
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= len) {
                throw err("转义未完成");
            }
            char e = s.charAt(pos++);
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > len) {
                        throw err("\\u 转义不完整");
                    }
                    sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw err("非法转义: \\" + e);
            }
        }
        return sb.toString();
    }

    private JsonValue parseNumber() {
        int start = pos;
        boolean isDouble = false;
        if (peek() == '-' || peek() == '+') {
            pos++;
        }
        while (pos < len) {
            char c = s.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E') {
                isDouble = true;
                pos++;
            } else if (c == '+' || c == '-') {
                pos++;
            } else {
                break;
            }
        }
        String tok = s.substring(start, pos);
        if (tok.isEmpty() || "-".equals(tok) || "+".equals(tok)) {
            throw err("非法数字: '" + tok + "'");
        }
        try {
            if (!isDouble) {
                return new JsonValue(Long.parseLong(tok));
            }
        } catch (NumberFormatException ignore) {
            // 超出 long 范围，回退为 double
        }
        try {
            return new JsonValue(Double.parseDouble(tok));
        } catch (NumberFormatException e) {
            throw err("非法数字: '" + tok + "'");
        }
    }

    private JsonValue parseBoolean() {
        if (s.startsWith("true", pos)) {
            pos += 4;
            return new JsonValue(Boolean.TRUE);
        }
        if (s.startsWith("false", pos)) {
            pos += 5;
            return new JsonValue(Boolean.FALSE);
        }
        throw err("非法字面量");
    }

    private JsonValue parseNull() {
        if (s.startsWith("null", pos)) {
            pos += 4;
            return new JsonValue(null);
        }
        throw err("非法字面量");
    }

    private char peek() {
        if (pos >= len) {
            throw err("输入意外结束");
        }
        return s.charAt(pos);
    }

    private char next() {
        if (pos >= len) {
            throw err("输入意外结束");
        }
        return s.charAt(pos++);
    }

    private void expect(char c) {
        if (pos >= len || s.charAt(pos) != c) {
            throw err("期望 '" + c + "'");
        }
        pos++;
    }

    private void skipWs() {
        while (pos < len) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private JsonParseException err(String message) {
        return new JsonParseException(message, pos);
    }
}
