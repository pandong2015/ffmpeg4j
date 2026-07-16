package io.github.pandong2015.ffmpeg4j.facade;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * HLS 门面的磁盘副作用（供 {@link FfmpegClient} 使用，与纯函数 {@code buildHls} 分层隔离）：
 * 以 {@code 0600} 原子创建写密钥文件/临时 key_info_file、解析 m3u8 得段清单、清理。
 *
 * <p><b>安全</b>：AES 明文密钥文件与临时 key_info_file 均以 owner-only（{@code rw-------}）<em>原子创建</em>
 * （{@code PosixFilePermissions.asFileAttribute}，非先建后 chmod，避免 TOCTOU）。非 POSIX（Windows）无 0600
 * 等价——请求 AES 时一次性告警「密钥落盘无 OS 级权限保护」，不静默降级。
 */
final class HlsIo {

    private static final Logger LOG = Logger.getLogger(HlsIo.class.getName());
    private static final Set<PosixFilePermission> OWNER_RW = PosixFilePermissions.fromString("rw-------");
    private static final AtomicBoolean NON_POSIX_WARNED = new AtomicBoolean(false);

    private HlsIo() {
    }

    private static boolean isPosix(Path p) {
        return p.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static void warnNonPosixOnce() {
        if (NON_POSIX_WARNED.compareAndSet(false, true)) {
            LOG.warning("当前文件系统非 POSIX（如 Windows），AES 密钥文件与 key_info_file 无法以 0600 保护——"
                    + "密钥落盘无 OS 级权限保护，同机其他用户可能可读；请自行确保存储位置访问受控。");
        }
    }

    /** 写 16 字节原始密钥到 {@code keyFile}，以 0600 原子创建（POSIX）或优雅降级（非 POSIX）。 */
    static void writeKeyFile(Path keyFile, byte[] key) throws IOException {
        Files.deleteIfExists(keyFile); // 确保 CREATE_NEW 原子创建成立（复用 outDir 重跑时旧密钥可能存在）
        if (isPosix(keyFile)) {
            FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(OWNER_RW);
            try (SeekableByteChannel ch = Files.newByteChannel(keyFile,
                    EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attr)) {
                ch.write(ByteBuffer.wrap(key));
            }
        } else {
            warnNonPosixOnce();
            Files.write(keyFile, key, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
    }

    /** 以 0600 原子创建一个<em>唯一命名</em>的临时 key_info_file 并写入三行文本；返回其路径。 */
    static Path writeKeyInfoFile(String text) throws IOException {
        Path tmp;
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            tmp = Files.createTempFile("ffmpeg4j-hls-", ".keyinfo",
                    PosixFilePermissions.asFileAttribute(OWNER_RW));
        } else {
            warnNonPosixOnce();
            tmp = Files.createTempFile("ffmpeg4j-hls-", ".keyinfo");
        }
        Files.writeString(tmp, text);
        return tmp;
    }

    /**
     * 解析写出的 m3u8，得到<b>按出现顺序</b>的分段实际路径（{@code segmentDir/<basename>}）。段清单源自 m3u8
     * （非 glob）——天然有序、免疫 {@code -y} 遗留的孤儿段与词典序错序。
     */
    static List<Path> parseSegments(Path playlist, Path segmentDir) throws IOException {
        String content = Files.readString(playlist);
        List<String> names = FacadeSupport.parseSegmentBasenames(content);
        List<Path> segs = new ArrayList<>(names.size());
        for (String n : names) {
            segs.add(segmentDir.resolve(n));
        }
        return segs;
    }

    /** 运行前清空 {@code segmentDir} 下的常规文件（{@code cleanSegmentDir}），避免复用非空 outDir 残留旧段。 */
    static void cleanSegments(Path segmentDir) throws IOException {
        if (!Files.isDirectory(segmentDir)) {
            return;
        }
        try (var stream = Files.list(segmentDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (Files.isRegularFile(p)) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    /** best-effort 删除（{@code null} 或不存在时静默）。 */
    static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            LOG.fine("清理文件失败（忽略）：" + p + " - " + e.getMessage());
        }
    }
}
