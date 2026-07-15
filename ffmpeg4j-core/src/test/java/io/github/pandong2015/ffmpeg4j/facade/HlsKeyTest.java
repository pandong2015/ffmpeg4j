package io.github.pandong2015.ffmpeg4j.facade;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** HlsKey 值对象：脱进程校验、不可变/脱敏、key_info_file 文本。 */
class HlsKeyTest {

    private static byte[] key16() {
        byte[] k = new byte[16];
        for (int i = 0; i < 16; i++) {
            k[i] = (byte) i;
        }
        return k;
    }

    @Test
    void 密钥非16字节即抛() {
        assertThrows(IllegalArgumentException.class, () -> HlsKey.of(new byte[15], "https://k/s.key"));
        assertThrows(IllegalArgumentException.class, () -> HlsKey.of(new byte[17], "https://k/s.key"));
    }

    @Test
    void URI非空且无控制字符或引号() {
        assertThrows(IllegalArgumentException.class, () -> HlsKey.of(key16(), ""));
        assertThrows(IllegalArgumentException.class, () -> HlsKey.of(key16(), "   "));
        assertThrows(IllegalArgumentException.class, () -> HlsKey.of(key16(), "https://k/s\n.key"));
        assertThrows(IllegalArgumentException.class, () -> HlsKey.of(key16(), "https://k/s\r.key"));
        assertThrows(IllegalArgumentException.class, () -> HlsKey.of(key16(), "https://k/\"s.key"));
    }

    @Test
    void IV非16字节即抛() {
        assertThrows(IllegalArgumentException.class, () -> HlsKey.of(key16(), "https://k/s.key", new byte[15]));
        assertThrows(IllegalArgumentException.class, () -> HlsKey.of(key16(), "https://k/s.key", new byte[17]));
    }

    @Test
    void random用SecureRandom且16字节可读回() {
        HlsKey k = HlsKey.random("https://k/s.key");
        assertEquals(16, k.keyBytes().length);
        assertEquals("https://k/s.key", k.keyUri());
        assertFalse(k.hasIv());
        // 两次生成不同（极大概率）——SecureRandom 而非可预测 Random。
        assertFalse(Arrays.equals(k.keyBytes(), HlsKey.random("https://k/s.key").keyBytes()));
    }

    @Test
    void byte数组构造与读取均clone() {
        byte[] in = key16();
        HlsKey k = HlsKey.of(in, "https://k/s.key");
        in[0] = (byte) 0xFF; // 改入参不应影响内部
        assertEquals(0, k.keyBytes()[0]);
        byte[] out = k.keyBytes();
        out[0] = (byte) 0xFF; // 改读出的副本不应影响内部
        assertEquals(0, k.keyBytes()[0]);
    }

    @Test
    void toString不含密钥() {
        HlsKey k = HlsKey.random("https://k/s.key");
        assertEquals("HlsKey[redacted,16B]", k.toString());
    }

    @Test
    void keyInfoFile无IV为两行() {
        HlsKey k = HlsKey.of(key16(), "https://k/s.key");
        assertEquals("https://k/s.key\n/abs/key/enc.key\n", k.keyInfoFileText("/abs/key/enc.key"));
    }

    @Test
    void keyInfoFile有IV为三行且32位小写hex() {
        byte[] iv = new byte[16]; // 全零 IV → 32 个 '0'
        HlsKey k = HlsKey.of(key16(), "https://k/s.key", iv);
        assertTrue(k.hasIv());
        assertEquals("https://k/s.key\n/abs/key/enc.key\n" + "0".repeat(32) + "\n",
                k.keyInfoFileText("/abs/key/enc.key"));
    }

    @Test
    void IVhex渲染逐字节小写() {
        byte[] iv = new byte[16];
        iv[0] = (byte) 0xA7;
        iv[15] = (byte) 0x0F;
        HlsKey k = HlsKey.of(key16(), "https://k/s.key", iv);
        String text = k.keyInfoFileText("/p");
        String ivLine = text.split("\n")[2];
        assertEquals(32, ivLine.length());
        assertTrue(ivLine.startsWith("a7"), "首字节小写 hex: " + ivLine);
        assertTrue(ivLine.endsWith("0f"), "末字节小写 hex: " + ivLine);
    }
}
