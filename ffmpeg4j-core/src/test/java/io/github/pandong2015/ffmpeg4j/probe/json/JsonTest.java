package io.github.pandong2015.ffmpeg4j.probe.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesFlatObject() {
        JsonValue v = Json.parse("{\"a\":1,\"b\":\"x\",\"c\":true,\"d\":null}");
        assertTrue(v.isObject());
        Map<String, JsonValue> m = v.asObject();
        assertEquals(1L, m.get("a").asLong());
        assertEquals("x", m.get("b").asString());
        assertTrue(m.get("c").asBoolean());
        assertTrue(m.get("d").isNull());
    }

    @Test
    void parsesNestedObjectsAndArrays() {
        String json = "{\"streams\":[{\"index\":0,\"tags\":{\"lang\":\"eng\"}},"
                + "{\"index\":1,\"nested\":[[1,2],[3,4]]}]}";
        JsonValue root = Json.parse(json);
        List<JsonValue> streams = root.opt("streams").asArray();
        assertEquals(2, streams.size());
        assertEquals(0, streams.get(0).opt("index").asInt());
        assertEquals("eng", streams.get(0).opt("tags").opt("lang").asString());

        List<JsonValue> nested = streams.get(1).opt("nested").asArray();
        assertEquals(4L, nested.get(1).opt(1).asLong());
    }

    @Test
    void parsesNumbersIntegerAndDouble() {
        JsonValue v = Json.parse("{\"i\":128000,\"f\":1.5,\"neg\":-3,\"exp\":1.0e3}");
        assertEquals(128000L, v.opt("i").asLong());
        assertEquals(1.5, v.opt("f").asDouble(), 1e-9);
        assertEquals(-3L, v.opt("neg").asLong());
        assertEquals(1000.0, v.opt("exp").asDouble(), 1e-9);
    }

    @Test
    void parsesStringEscapes() {
        JsonValue v = Json.parse("{\"s\":\"line1\\nline2\\ttab \\\"q\\\" \\\\ / \\u0041\"}");
        assertEquals("line1\nline2\ttab \"q\" \\ / A", v.opt("s").asString());
    }

    @Test
    void coercesQuotedNumbersLikeFfprobe() {
        // ffprobe 常把数字写成带引号字符串
        JsonValue v = Json.parse("{\"duration\":\"1.000000\",\"sample_rate\":\"44100\"}");
        assertEquals(1.0, v.opt("duration").asDouble(), 1e-9);
        assertEquals(44100L, v.opt("sample_rate").asLong());
        assertEquals(44100, v.opt("sample_rate").asInt());
    }

    @Test
    void optReturnsNullSentinelForMissingKeys() {
        JsonValue v = Json.parse("{\"a\":1}");
        assertTrue(v.opt("missing").isNull());
        assertFalse(v.has("missing"));
        assertEquals(-1L, v.opt("missing").asLong(-1L));
        assertNull(v.opt("missing").asString(null));
    }

    @Test
    void handlesEmptyContainersAndWhitespace() {
        assertTrue(Json.parse("  {  }  ").asObject().isEmpty());
        assertTrue(Json.parse("[\n]\t").asArray().isEmpty());
    }

    @Test
    void rejectsTrailingContentAndMalformed() {
        assertThrows(JsonParseException.class, () -> Json.parse("{\"a\":1} garbage"));
        assertThrows(JsonParseException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(JsonParseException.class, () -> Json.parse("[1,2"));
    }
}
