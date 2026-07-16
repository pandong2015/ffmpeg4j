package io.github.pandong2015.ffmpeg4j.facade;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * HLS AES-128 加密的密钥值对象（不可变）。承载 ffmpeg {@code -hls_key_info_file} 所需的三部分：
 * <ol>
 *   <li>16 字节原始 AES 密钥（写入磁盘密钥文件、供 ffmpeg 加密每个分段）；</li>
 *   <li>key URI（原样写进 {@code #EXT-X-KEY} 的 {@code URI}，播放器据此取密钥，<em>明文进 m3u8</em>）；</li>
 *   <li>可选的 16 字节 IV（省略时 ffmpeg 以段序号派生 IV，VOD 场景各段 IV 天然不同）。</li>
 * </ol>
 *
 * <p><b>责任模型 B2（默认）</b>：调用方经 {@link #of} 提供密钥字节/URI/可选 IV，机密的分发与生命周期归调用方；
 * <b>B1（便利）</b>：{@link #random(String)} 用 {@link SecureRandom} 生成 16 字节随机密钥（字节可读回持久化）。
 *
 * <p><b>安全</b>：{@code byte[]} 在构造与读取时一律 {@code clone}（防外部改内部）；{@link #toString()} 脱敏，
 * 密钥字节绝不进 argv（argv 只出现 key_info_file 路径）/日志/异常。key URI <em>明文进 m3u8</em>，其可达性与鉴权由
 * 调用方负责——切勿在 URI 内嵌 token/凭证。校验在工厂即时进行（16 字节密钥/IV、URI 非空且无控制字符/引号）。
 */
public final class HlsKey {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final byte[] keyBytes;
    private final String keyUri;
    private final byte[] iv; // 可空

    private HlsKey(byte[] keyBytes, String keyUri, byte[] iv) {
        Objects.requireNonNull(keyBytes, "keyBytes 不能为 null");
        if (keyBytes.length != 16) {
            throw new IllegalArgumentException("AES-128 密钥须为 16 字节，当前 " + keyBytes.length + " 字节");
        }
        validateKeyUri(keyUri);
        if (iv != null && iv.length != 16) {
            throw new IllegalArgumentException("IV 须为 16 字节，当前 " + iv.length + " 字节");
        }
        this.keyBytes = keyBytes.clone();
        this.keyUri = keyUri;
        this.iv = iv == null ? null : iv.clone();
    }

    /** B2：调用方提供 16 字节密钥与 key URI（省略 IV，ffmpeg 用段序号派生 IV——VOD 推荐）。 */
    public static HlsKey of(byte[] keyBytes, String keyUri) {
        return new HlsKey(keyBytes, keyUri, null);
    }

    /**
     * B2：调用方提供 16 字节密钥、key URI 与显式 16 字节 IV。
     *
     * <p><b>警告</b>：显式固定 IV 会施于同一密钥的<em>每一段</em>（AES-128-CBC 跨段 IV 复用，削弱机密性）；
     * VOD 场景应优先 {@link #of(byte[], String)} 省略 IV，由 ffmpeg 用段序号派生（各段 IV 不同）。
     */
    public static HlsKey of(byte[] keyBytes, String keyUri, byte[] iv) {
        Objects.requireNonNull(iv, "iv 不能为 null（省略 IV 请用 of(keyBytes, keyUri)）");
        return new HlsKey(keyBytes, keyUri, iv);
    }

    /** B1 便利：用 {@link SecureRandom}（<em>非</em> {@code java.util.Random}）生成 16 字节随机密钥；省略 IV。 */
    public static HlsKey random(String keyUri) {
        byte[] key = new byte[16];
        SECURE_RANDOM.nextBytes(key);
        return new HlsKey(key, keyUri, null);
    }

    private static void validateKeyUri(String keyUri) {
        if (keyUri == null || keyUri.isBlank()) {
            throw new IllegalArgumentException("key URI 不能为空");
        }
        for (int i = 0; i < keyUri.length(); i++) {
            char c = keyUri.charAt(i);
            if (Character.isISOControl(c) || c == '"') {
                // 控制字符（含 CR/LF）会注入/错位 key_info_file 三行结构；内嵌引号会破坏 #EXT-X-KEY。
                throw new IllegalArgumentException("key URI 不得含控制字符（CR/LF 等）或内嵌引号");
            }
        }
    }

    /** 16 字节原始密钥（克隆，防外部改内部）。 */
    public byte[] keyBytes() {
        return keyBytes.clone();
    }

    public String keyUri() {
        return keyUri;
    }

    /** 显式 IV（16 字节，克隆）；未设为 {@code null}。 */
    public byte[] iv() {
        return iv == null ? null : iv.clone();
    }

    /** 是否设置了显式 IV。 */
    public boolean hasIv() {
        return iv != null;
    }

    /**
     * 生成 ffmpeg {@code -hls_key_info_file} 的<em>精确文本</em>（纯函数，可脱进程单测）。逐行：
     * 第 1 行 {@code keyUri}（写进 {@code #EXT-X-KEY}）；第 2 行密钥文件<b>绝对</b>路径（ffmpeg 本地读 16 字节）；
     * 第 3 行（有 IV 时）32 个小写 hex。各行以 {@code \n} 结尾。
     *
     * @param keyFileAbsolutePath 密钥文件的绝对路径（由门面在写盘时确定并传入）
     */
    public String keyInfoFileText(String keyFileAbsolutePath) {
        Objects.requireNonNull(keyFileAbsolutePath, "keyFileAbsolutePath 不能为 null");
        StringBuilder sb = new StringBuilder();
        sb.append(keyUri).append('\n');
        sb.append(keyFileAbsolutePath).append('\n');
        if (iv != null) {
            sb.append(toHex(iv)).append('\n');
        }
        return sb.toString();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 脱敏：绝不打印密钥字节。 */
    @Override
    public String toString() {
        return "HlsKey[redacted,16B]";
    }
}
