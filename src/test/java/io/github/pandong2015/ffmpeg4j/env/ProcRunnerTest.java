package io.github.pandong2015.ffmpeg4j.env;

import io.github.pandong2015.ffmpeg4j.FfmpegException;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link ProcRunner} 的纯逻辑测试：入参校验、Result 记录语义，以及借助 POSIX 上必然存在的
 * 命令（echo/false/sleep）走通「捕获输出 / 非零退出 / 超时」三条真实路径。绝不启动 ffmpeg。
 */
class ProcRunnerTest {

    // ---- 入参校验：纯逻辑，不启动任何进程 ----

    @Test
    void 空命令列表抛IllegalArgumentException() {
        // command.isEmpty() 分支：编译探测在拼装 argv 出错时应快速失败，而非启动一个空进程。
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> ProcRunner.run(List.of()));
        assertEquals("command must not be empty", ex.getMessage(), "空列表应给出确定的错误消息");
    }

    @Test
    void null命令抛IllegalArgumentException() {
        // command == null 分支先于 isEmpty() 判定，避免 NPE。
        assertThrows(IllegalArgumentException.class,
                () -> ProcRunner.run(null, Duration.ofSeconds(1)));
    }

    @Test
    void 不存在的二进制抛FfmpegException() {
        // ProcessBuilder.start() 对不存在的可执行文件抛 IOException，被包装为面向用户的 FfmpegException；
        // 此路径与平台无关，任何操作系统都不存在该路径。
        assertThrows(FfmpegException.class,
                () -> ProcRunner.run(List.of("/definitely/not/a/real/binary_xyzzy_ffmpeg4j")));
    }

    // ---- 真实进程路径：用 POSIX 命令守卫，Windows 下跳过 ----

    @Test
    void echo捕获标准输出且退出码为零() {
        assumeTrue(new File("/bin/echo").exists(), "非 POSIX 环境（无 /bin/echo）跳过");
        ProcRunner.Result result = ProcRunner.run(
                List.of("/bin/echo", "ffmpeg4j"), Duration.ofSeconds(5));
        assertEquals(0, result.exitCode(), "echo 正常结束退出码应为 0");
        assertEquals("ffmpeg4j", result.output().trim(),
                "合并流应捕获到 echo 打印的内容（去掉尾随换行）");
    }

    @Test
    void 单参run使用默认超时也能正常捕获输出() {
        // 覆盖 run(List) 单参重载：它委托给带 DEFAULT_TIMEOUT 的重载，echo 会在超时内秒退。
        assumeTrue(new File("/bin/echo").exists(), "非 POSIX 环境（无 /bin/echo）跳过");
        ProcRunner.Result result = ProcRunner.run(List.of("/bin/echo", "hi"));
        assertEquals(0, result.exitCode());
        assertEquals("hi", result.output().trim());
    }

    @Test
    void false命令返回非零退出码而不抛异常() {
        // 非零退出属于「命令正常结束」，ProcRunner 只如实回传退出码，不因非零就抛异常。
        assumeTrue(new File("/bin/false").exists(), "非 POSIX 环境（无 /bin/false）跳过");
        ProcRunner.Result result = ProcRunner.run(List.of("/bin/false"), Duration.ofSeconds(5));
        assertNotEquals(0, result.exitCode(), "false 应以非零退出码结束");
    }

    @Test
    void sleep超过超时时抛FfmpegException() {
        // waitFor 超时 → destroyForcibly → 抛 FfmpegException；用极短超时确保测试自身很快返回。
        assumeTrue(new File("/bin/sleep").exists(), "非 POSIX 环境（无 /bin/sleep）跳过");
        assertThrows(FfmpegException.class,
                () -> ProcRunner.run(List.of("/bin/sleep", "5"), Duration.ofMillis(100)));
    }

    // ---- Result 记录：纯值对象语义 ----

    @Test
    void Result记录暴露退出码与输出访问器() {
        ProcRunner.Result r = new ProcRunner.Result(3, "some-output");
        assertEquals(3, r.exitCode());
        assertEquals("some-output", r.output());
    }

    @Test
    void Result记录按值实现equals与hashCode() {
        // record 自动生成的相等性：字段全相等则相等，任一字段不同则不等。
        ProcRunner.Result a = new ProcRunner.Result(0, "x");
        ProcRunner.Result b = new ProcRunner.Result(0, "x");
        ProcRunner.Result c = new ProcRunner.Result(1, "x");
        assertEquals(a, b, "同退出码同输出应相等");
        assertEquals(a.hashCode(), b.hashCode(), "相等对象的 hashCode 必须一致");
        assertNotEquals(a, c, "退出码不同则不相等");
    }

    @Test
    void Result记录toString含字段名与值() {
        // record 的 toString 是确定格式，便于日志排错时精确定位退出码与输出。
        ProcRunner.Result r = new ProcRunner.Result(2, "xy");
        assertEquals("Result[exitCode=2, output=xy]", r.toString());
    }

    // ---- 常量 ----

    @Test
    void 默认探测超时为15秒() {
        assertEquals(Duration.ofSeconds(15), ProcRunner.DEFAULT_TIMEOUT,
                "默认探测超时应为 15 秒，供 -version/-filters 这类短命调用使用");
    }
}
