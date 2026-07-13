package io.github.pandong2015.ffmpeg4j.env;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link Executables#resolve(String)} 的纯逻辑测试：空白入参兜底、路径分支的存在/可执行判定、
 * PATH 分支找不到时的兜底，以及命中后返回绝对规范化路径。
 *
 * <p>不启动任何进程；「可执行位」语义依赖 POSIX，Windows 环境用 {@code assumeTrue} 跳过而非硬失败。
 */
class ExecutablesTest {

    /** 与被测类一致的 OS 判定，用于守卫依赖可执行位语义的用例。 */
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    @Test
    void null输入直接返回空() {
        // 公共入口对空入参必须安全兜底，不得抛异常。
        assertEquals(Optional.empty(), Executables.resolve(null), "null 入参应安全返回空 Optional");
    }

    @Test
    void 空字符串按空白处理返回空() {
        assertEquals(Optional.empty(), Executables.resolve(""), "空串命中 isBlank 短路，应返回空");
    }

    @Test
    void 纯空白输入返回空() {
        // "   ".isBlank() 为真，走 isBlank 分支而非后续解析。
        assertEquals(Optional.empty(), Executables.resolve("   "), "纯空白入参应返回空");
    }

    @Test
    void 指向不存在文件的绝对路径解析为空(@TempDir Path dir) {
        // 绝对路径触发 looksLikePath 分支，但目标文件不存在，firstExecutable 返回空。
        Path missing = dir.resolve("does-not-exist-xyz");
        assertEquals(Optional.empty(), Executables.resolve(missing.toString()),
                "指向不存在文件的绝对路径应解析为空");
    }

    @Test
    void 含分隔符但不存在的相对路径解析为空() {
        // 含 "/" 使 looksLikePath 为真，走路径分支；文件不存在故为空。
        assertEquals(Optional.empty(), Executables.resolve("no-such-dir/no-such-file-xyz"),
                "含路径分隔符的不存在相对路径应解析为空");
    }

    @Test
    void PATH中找不到的裸命令名回退为空() {
        // 裸命令名走 PATH 搜索分支；该命令必然不存在于任何 PATH 目录，遍历后返回空。
        assertEquals(Optional.empty(),
                Executables.resolve("definitely-not-a-real-command-xyz-123456"),
                "PATH 上不存在该命令名，遍历后应回退为空");
    }

    @Test
    void 命中已存在的可执行文件并返回绝对规范路径(@TempDir Path dir) throws Exception {
        assumeTrue(!WINDOWS, "可执行位语义依赖 POSIX，Windows 环境跳过");
        Path exe = Files.createFile(dir.resolve("my-tool"));
        assertTrue(exe.toFile().setExecutable(true), "测试前置条件：需能为临时文件设置可执行位");

        // 故意在路径中插入冗余的 "." 段，以验证解析结果被 normalize 去除。
        String withDot = dir + File.separator + "." + File.separator + "my-tool";
        Optional<Path> resolved = Executables.resolve(withDot);

        assertTrue(resolved.isPresent(), "已存在且可执行的普通文件应被解析命中");
        assertTrue(resolved.get().isAbsolute(), "解析结果必须是绝对路径");
        assertEquals(exe.toAbsolutePath().normalize(), resolved.get(),
                "解析结果必须是规范化(去除 . 段)的绝对路径");
    }

    @Test
    void 存在但不可执行的文件解析为空(@TempDir Path dir) throws Exception {
        assumeTrue(!WINDOWS, "可执行位语义依赖 POSIX，Windows 环境跳过");
        Path plain = Files.createFile(dir.resolve("not-exec"));
        plain.toFile().setExecutable(false);
        // 以 root 运行时任意文件都可执行，此时跳过以免误判。
        assumeTrue(!Files.isExecutable(plain), "当前环境该文件仍可执行(疑似 root)，跳过");

        assertEquals(Optional.empty(), Executables.resolve(plain.toString()),
                "存在但不具可执行位的普通文件不应被当作可执行解析");
    }

    @Test
    void 前后空白会被trim后再解析(@TempDir Path dir) throws Exception {
        assumeTrue(!WINDOWS, "可执行位语义依赖 POSIX，Windows 环境跳过");
        Path exe = Files.createFile(dir.resolve("tool2"));
        assertTrue(exe.toFile().setExecutable(true), "测试前置条件：需能为临时文件设置可执行位");

        // 入参前后带空白，被 trim 后应等价于原路径，仍能命中。
        Optional<Path> resolved = Executables.resolve("  " + exe + "  ");

        assertTrue(resolved.isPresent(), "trim 去除前后空白后应命中真实可执行文件");
        assertEquals(exe.toAbsolutePath().normalize(), resolved.get(),
                "trim 不应影响最终解析出的规范路径");
    }
}
