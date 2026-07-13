package io.github.pandong2015.ffmpeg4j.probe.json;

import java.util.List;
import java.util.Map;

/**
 * 一个已解析的 JSON 值。内部以下述之一承载原始数据：
 * {@code Map<String,JsonValue>}（对象）、{@code List<JsonValue>}（数组）、
 * {@link String}、{@link Long}/{@link Double}（数字）、{@link Boolean} 或 {@code null}。
 *
 * <p>访问器均为宽松强转：ffprobe 会把大量数字写成带引号的字符串
 * （如 {@code "duration":"1.000000"}、{@code "sample_rate":"44100"}），
 * 因此 {@link #asDouble()} / {@link #asLong()} 对字符串与真数字一视同仁。
 * 带默认值的重载在缺失或无法解析时回退，便于映射层写出健壮代码。
 */
public final class JsonValue {

    /** 表示「缺失/JSON null」的单例，导航访问器越界时返回它而非 {@code null}。 */
    static final JsonValue NULL = new JsonValue(null);

    private final Object raw;

    JsonValue(Object raw) {
        this.raw = raw;
    }

    public boolean isNull() {
        return raw == null;
    }

    public boolean isObject() {
        return raw instanceof Map;
    }

    public boolean isArray() {
        return raw instanceof List;
    }

    @SuppressWarnings("unchecked")
    public Map<String, JsonValue> asObject() {
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, JsonValue>) m;
        }
        throw new IllegalStateException("不是 JSON 对象: " + raw);
    }

    @SuppressWarnings("unchecked")
    public List<JsonValue> asArray() {
        if (raw instanceof List<?> l) {
            return (List<JsonValue>) l;
        }
        throw new IllegalStateException("不是 JSON 数组: " + raw);
    }

    /** 返回字符串；对数字/布尔返回其文本形式；JSON null 返回 {@code null}。 */
    public String asString() {
        if (raw == null) {
            return null;
        }
        return raw instanceof String s ? s : String.valueOf(raw);
    }

    public String asString(String def) {
        return isNull() ? def : asString();
    }

    public double asDouble() {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s) {
            return Double.parseDouble(s.trim());
        }
        throw new IllegalStateException("不是数字: " + raw);
    }

    public double asDouble(double def) {
        try {
            return asDouble();
        } catch (RuntimeException e) {
            return def;
        }
    }

    public long asLong() {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof String s) {
            String t = s.trim();
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException e) {
                return (long) Double.parseDouble(t);
            }
        }
        throw new IllegalStateException("不是数字: " + raw);
    }

    public long asLong(long def) {
        try {
            return asLong();
        } catch (RuntimeException e) {
            return def;
        }
    }

    public int asInt() {
        return (int) asLong();
    }

    public int asInt(int def) {
        return (int) asLong(def);
    }

    public boolean asBoolean() {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        throw new IllegalStateException("不是布尔: " + raw);
    }

    public boolean asBoolean(boolean def) {
        try {
            return asBoolean();
        } catch (RuntimeException e) {
            return def;
        }
    }

    /** 对象是否含有该键（且为对象时才可能为真）。 */
    public boolean has(String key) {
        return isObject() && asObject().containsKey(key);
    }

    /** 取对象成员；非对象或缺失时返回 {@link #NULL} 单例（永不返回 {@code null}）。 */
    public JsonValue opt(String key) {
        if (isObject()) {
            JsonValue v = asObject().get(key);
            return v == null ? NULL : v;
        }
        return NULL;
    }

    /** 取数组元素；越界或非数组时返回 {@link #NULL} 单例。 */
    public JsonValue opt(int index) {
        if (isArray()) {
            List<JsonValue> a = asArray();
            if (index >= 0 && index < a.size()) {
                return a.get(index);
            }
        }
        return NULL;
    }

    @Override
    public String toString() {
        return "JsonValue{" + raw + "}";
    }
}
