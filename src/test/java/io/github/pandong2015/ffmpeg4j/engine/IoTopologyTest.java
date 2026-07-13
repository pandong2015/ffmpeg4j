package io.github.pandong2015.ffmpeg4j.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** {@link IoTopology} 纯逻辑推导测试：依 argv 判定 stdin/stdout 是否被媒体管道占用。 */
class IoTopologyTest {

    @Test
    void 写盘任务stdin与stdout均空闲() {
        // v1.0 门面主路径：输入输出都是磁盘文件。
        IoTopology t = IoTopology.derive(List.of(
                "ffmpeg", "-y", "-i", "/in.mp4", "-c:v", "mpeg4", "/out.mp4"));
        assertFalse(t.stdinFed(), "输入为文件，stdin 空闲");
        assertFalse(t.stdoutMedia(), "输出为文件，stdout 空闲");
    }

    @Test
    void lavfi输入也算stdin空闲() {
        IoTopology t = IoTopology.derive(List.of(
                "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=1", "/out.mp4"));
        assertFalse(t.stdinFed());
        assertFalse(t.stdoutMedia());
    }

    @Test
    void stdout传媒体_pipe1() {
        IoTopology t = IoTopology.derive(List.of(
                "ffmpeg", "-i", "/in.mp4", "-f", "mpegts", "pipe:1"));
        assertFalse(t.stdinFed());
        assertTrue(t.stdoutMedia(), "输出 pipe:1 → stdout 传媒体");
    }

    @Test
    void stdout传媒体_横杠() {
        IoTopology t = IoTopology.derive(List.of(
                "ffmpeg", "-i", "/in.mp4", "-f", "mpegts", "-"));
        assertTrue(t.stdoutMedia(), "输出 - → stdout 传媒体");
    }

    @Test
    void stdin喂输入_pipe0() {
        IoTopology t = IoTopology.derive(List.of(
                "ffmpeg", "-i", "pipe:0", "-c", "copy", "/out.mp4"));
        assertTrue(t.stdinFed(), "输入 pipe:0 → stdin 被占用");
        assertFalse(t.stdoutMedia());
    }

    @Test
    void stdin喂输入_横杠() {
        IoTopology t = IoTopology.derive(List.of(
                "ffmpeg", "-i", "-", "-c", "copy", "/out.mp4"));
        assertTrue(t.stdinFed(), "输入 - → stdin 被占用");
    }

    @Test
    void 双管道_喂输入且传媒体() {
        IoTopology t = IoTopology.derive(List.of(
                "ffmpeg", "-i", "pipe:0", "-f", "mpegts", "pipe:1"));
        assertTrue(t.stdinFed());
        assertTrue(t.stdoutMedia());
    }

    @Test
    void 末尾横杠是输入取值时不误判为stdout媒体() {
        // 畸形命令 "... -i -"（无输出）：末尾 - 是输入取值，不应判为 stdout 媒体。
        IoTopology t = IoTopology.derive(List.of("ffmpeg", "-i", "-"));
        assertTrue(t.stdinFed(), "- 作输入取值 → stdin 占用");
        assertFalse(t.stdoutMedia(), "末尾 - 是 -i 取值而非输出目标");
    }

    @Test
    void 文件名恰为pipe字样不误伤() {
        // 输出是普通文件路径，即便含 pipe 字样也非管道目标。
        IoTopology t = IoTopology.derive(List.of("ffmpeg", "-i", "/in.mp4", "/tmp/pipe-out.mp4"));
        assertFalse(t.stdoutMedia(), "普通文件路径非 stdout 媒体");
    }
}
