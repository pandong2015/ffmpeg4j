package io.github.pandong2015.ffmpeg4j.probe.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 针对 {@link Json} / {@link JsonValue} / {@link JsonParseException} 的补充覆盖测试，
 * 专攻 {@code JsonTest} 未触及的分支：顶层字面量、数字符号/指数/溢出、更多转义、
 * 空/嵌套容器、各类非法输入以及访问器类型不符时的行为。
 */
class JsonCoverageTest {

    // ---------- 顶层字面量 ----------

    @Test
    void 顶层布尔真假各自解析() {
        assertTrue(Json.parse("true").asBoolean());
        assertFalse(Json.parse("false").asBoolean());
    }

    @Test
    void 顶层null解析为isNull() {
        JsonValue v = Json.parse("null");
        assertTrue(v.isNull());
        assertNull(v.asString());
    }

    @Test
    void 顶层字符串解析去掉引号() {
        assertEquals("hello", Json.parse("\"hello\"").asString());
    }

    // ---------- 数字的各种形态 ----------

    @Test
    void 负整数解析为负long() {
        assertEquals(-42L, Json.parse("-42").asLong());
    }

    @Test
    void 小数解析为double() {
        assertEquals(3.14, Json.parse("3.14").asDouble(), 1e-9);
    }

    @Test
    void 带正号指数的科学计数法解析() {
        // 命中指数内部 '+' 号以及 'e' 分支
        assertEquals(6.02e23, Json.parse("6.02e+23").asDouble(), 1e17);
    }

    @Test
    void 超出long范围的整数回退为double() {
        // 20 位整数超过 Long.MAX，Long.parseLong 失败后回退 Double.parseDouble
        JsonValue v = Json.parse("99999999999999999999");
        assertFalse(v.isNull());
        assertEquals(1.0e20, v.asDouble(), 1e5);
    }

    // ---------- 字符串转义 ----------

    @Test
    void 退格换页回车转义正确还原() {
        assertEquals("a\bb\ff\rr", Json.parse("\"a\\bb\\ff\\rr\"").asString());
    }

    @Test
    void 小写十六进制unicode转义还原() {
        assertEquals("café", Json.parse("\"caf\\u00e9\"").asString());
    }

    // ---------- 空/嵌套容器 ----------

    @Test
    void 嵌套的空对象与空数组() {
        JsonValue v = Json.parse("{\"a\":{},\"b\":[]}");
        assertTrue(v.opt("a").isObject());
        assertTrue(v.opt("a").asObject().isEmpty());
        assertTrue(v.opt("b").isArray());
        assertTrue(v.opt("b").asArray().isEmpty());
    }

    @Test
    void 混合类型数组逐元素解析() {
        List<JsonValue> a = Json.parse("[1,\"two\",true,null,3.5]").asArray();
        assertEquals(5, a.size());
        assertEquals(1L, a.get(0).asLong());
        assertEquals("two", a.get(1).asString());
        assertTrue(a.get(2).asBoolean());
        assertTrue(a.get(3).isNull());
        assertEquals(3.5, a.get(4).asDouble(), 1e-9);
    }

    // ---------- 非法输入：均抛 JsonParseException ----------

    @Test
    void null输入抛异常且偏移量为0() {
        JsonParseException ex =
                assertThrows(JsonParseException.class, () -> Json.parse(null));
        assertEquals(0, ex.offset());
        assertEquals("输入为 null (offset 0)", ex.getMessage());
    }

    @Test
    void 空字符串抛输入意外结束() {
        assertThrows(JsonParseException.class, () -> Json.parse(""));
    }

    @Test
    void 未闭合字符串抛异常() {
        assertThrows(JsonParseException.class, () -> Json.parse("\"abc"));
    }

    @Test
    void 转义未完成抛异常() {
        assertThrows(JsonParseException.class, () -> Json.parse("\"ab\\"));
    }

    @Test
    void 非法转义字符抛异常() {
        assertThrows(JsonParseException.class, () -> Json.parse("\"\\x\""));
    }

    @Test
    void unicode转义不完整抛异常() {
        assertThrows(JsonParseException.class, () -> Json.parse("\"\\u12\""));
    }

    @Test
    void 对象键非字符串抛异常() {
        assertThrows(JsonParseException.class, () -> Json.parse("{1:2}"));
    }

    @Test
    void 对象缺少冒号抛异常() {
        assertThrows(JsonParseException.class, () -> Json.parse("{\"a\" 1}"));
    }

    @Test
    void 对象键值后缺少逗号或右括号抛异常() {
        assertThrows(JsonParseException.class, () -> Json.parse("{\"a\":1 2}"));
    }

    @Test
    void 数组元素后缺少逗号或右括号抛异常() {
        assertThrows(JsonParseException.class, () -> Json.parse("[1 2]"));
    }

    @Test
    void 非法布尔字面量抛异常() {
        assertThrows(JsonParseException.class, () -> Json.parse("trux"));
    }

    @Test
    void 非法null字面量抛异常() {
        assertThrows(JsonParseException.class, () -> Json.parse("nul"));
    }

    @Test
    void 只有负号的数字抛异常() {
        assertThrows(JsonParseException.class, () -> Json.parse("-"));
    }

    @Test
    void 数字格式错误抛异常() {
        assertThrows(JsonParseException.class, () -> Json.parse("1.2.3"));
    }

    @Test
    void 未闭合对象在结尾抛输入意外结束() {
        assertThrows(JsonParseException.class, () -> Json.parse("{"));
    }

    @Test
    void 顶层多余内容抛异常() {
        assertThrows(JsonParseException.class, () -> Json.parse("1 2"));
    }

    // ---------- JsonValue 访问器：类型不符 ----------

    @Test
    void 非对象调用asObject抛IllegalState() {
        assertThrows(IllegalStateException.class, () -> Json.parse("1").asObject());
    }

    @Test
    void 非数组调用asArray抛IllegalState() {
        assertThrows(IllegalStateException.class, () -> Json.parse("{}").asArray());
    }

    @Test
    void 布尔调用asDouble与asLong抛IllegalState() {
        assertThrows(IllegalStateException.class, () -> Json.parse("true").asDouble());
        assertThrows(IllegalStateException.class, () -> Json.parse("true").asLong());
    }

    @Test
    void 数字调用asBoolean抛IllegalState() {
        assertThrows(IllegalStateException.class, () -> Json.parse("1").asBoolean());
    }

    // ---------- JsonValue 访问器：宽松强转与默认值 ----------

    @Test
    void asString对数字与布尔返回文本形式() {
        assertEquals("42", Json.parse("42").asString());
        assertEquals("true", Json.parse("true").asString());
    }

    @Test
    void asString默认值在JSON_null时回退() {
        assertEquals("fallback", Json.parse("null").asString("fallback"));
    }

    @Test
    void 各带默认值访问器在无法解析时回退() {
        JsonValue s = Json.parse("\"abc\"");
        assertEquals(-1.0, s.asDouble(-1.0), 1e-9);
        assertEquals(7L, s.asLong(7L));
        assertEquals(9, s.asInt(9));
        // 布尔默认值：数字既非 Boolean 也非 String，回退默认值
        assertTrue(Json.parse("1").asBoolean(true));
    }

    @Test
    void 带引号带空格的数字字符串被trim后解析() {
        JsonValue v = Json.parse("{\"x\":\" 2.5 \"}");
        assertEquals(2.5, v.opt("x").asDouble(), 1e-9);
    }

    @Test
    void 字符串小数经asLong走double回退取整() {
        JsonValue v = Json.parse("{\"x\":\"3.9\"}");
        assertEquals(3L, v.opt("x").asLong());
    }

    @Test
    void 字符串true经asBoolean解析为真() {
        JsonValue v = Json.parse("{\"x\":\"true\"}");
        assertTrue(v.opt("x").asBoolean());
    }

    @Test
    void asInt直接对整数取值() {
        assertEquals(42, Json.parse("42").asInt());
    }

    // ---------- JsonValue 导航访问器 ----------

    @Test
    void has在非对象上返回false() {
        assertFalse(Json.parse("1").has("a"));
    }

    @Test
    void opt键在非对象上返回NULL哨兵() {
        assertTrue(Json.parse("[1]").opt("a").isNull());
    }

    @Test
    void opt索引在非数组上返回NULL哨兵() {
        assertTrue(Json.parse("{}").opt(0).isNull());
    }

    @Test
    void opt索引越界与负数返回NULL哨兵() {
        JsonValue arr = Json.parse("[1]");
        assertTrue(arr.opt(5).isNull());
        assertTrue(arr.opt(-1).isNull());
    }

    @Test
    void isObject与isArray的真假判定() {
        assertTrue(Json.parse("{}").isObject());
        assertFalse(Json.parse("{}").isArray());
        assertTrue(Json.parse("[]").isArray());
        assertFalse(Json.parse("[]").isObject());
    }

    // ---------- toString ----------

    @Test
    void toString包裹原始值文本() {
        assertEquals("JsonValue{1}", Json.parse("1").toString());
        assertEquals("JsonValue{null}", Json.parse("null").toString());
    }
}
