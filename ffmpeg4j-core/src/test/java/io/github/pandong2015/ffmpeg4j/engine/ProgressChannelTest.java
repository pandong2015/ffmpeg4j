package io.github.pandong2015.ffmpeg4j.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import io.github.pandong2015.ffmpeg4j.task.TaskWarningCollector;
import io.github.pandong2015.ffmpeg4j.task.WarningCode;

/**
 * {@link ProgressChannel} 的纯逻辑测试：聚焦 {@link ProgressChannel.NoProgressChannel} 降级通道的
 * 全空操作行为，以及 {@link ProgressChannel#forTopology} 在 stdout 不传媒体时选 pipe 通道的分支。
 * 不启动进程、不绑定真实 socket。
 */
class ProgressChannelTest {

    @Test
    void 无进度通道单例非空且可复用() {
        ProgressChannel.NoProgressChannel a = ProgressChannel.NoProgressChannel.INSTANCE;
        ProgressChannel.NoProgressChannel b = ProgressChannel.NoProgressChannel.INSTANCE;
        assertNotNull(a, "降级单例不应为 null");
        // 私有构造 + 静态字段：多次取用应为同一实例，避免重复分配。
        assertSame(a, b, "INSTANCE 应为复用的同一单例");
    }

    @Test
    void 无进度通道是ProgressChannel与Closeable() {
        ProgressChannel.NoProgressChannel instance = ProgressChannel.NoProgressChannel.INSTANCE;
        // 契约：作为 ProgressChannel 的降级实现，也须满足 Closeable（接口继承 Closeable）。
        assertInstanceOf(ProgressChannel.class, instance, "应实现 ProgressChannel");
        assertInstanceOf(Closeable.class, instance, "应实现 Closeable");
    }

    @Test
    void 无进度通道不注入progress参数() {
        // 返回 null 表示本次不注入 -progress，正是 tcp 绑定失败后的「无进度」语义。
        assertNull(ProgressChannel.NoProgressChannel.INSTANCE.progressArg(),
                "无进度通道不应注入 -progress 参数");
    }

    @Test
    void 无进度通道start为空操作不抛异常() {
        ProgressChannel channel = ProgressChannel.NoProgressChannel.INSTANCE;
        // 空对象：即便传入 null 的 Process/Consumer 也不触碰它们，故不应抛异常。
        assertDoesNotThrow(() -> channel.start(null, null),
                "无进度通道 start 应为空操作");
        assertDoesNotThrow(() -> channel.start(null, line -> {}),
                "带非 null 消费者的 start 也应为空操作");
    }

    @Test
    void 无进度通道close幂等不抛异常() {
        ProgressChannel channel = ProgressChannel.NoProgressChannel.INSTANCE;
        // close 契约要求幂等：连续多次调用均不应抛异常。
        assertDoesNotThrow(() -> {
            channel.close();
            channel.close();
        }, "无进度通道 close 应幂等且为空操作");
    }

    @Test
    void 无进度通道awaitReaders为空操作不抛异常() {
        ProgressChannel channel = ProgressChannel.NoProgressChannel.INSTANCE;
        // 无读线程可汇合：任意等待时长（含 0 与负值）都应立即返回、不抛异常、不阻塞。
        assertDoesNotThrow(() -> channel.awaitReaders(0L), "awaitReaders(0) 应立即返回");
        assertDoesNotThrow(() -> channel.awaitReaders(1000L), "awaitReaders(正值) 应为空操作");
        assertDoesNotThrow(() -> channel.awaitReaders(-1L), "awaitReaders(负值) 应为空操作");
    }

    @Test
    void 无进度通道未中断当前线程() {
        // 空操作方法不得干扰调用线程的中断状态。
        ProgressChannel channel = ProgressChannel.NoProgressChannel.INSTANCE;
        channel.awaitReaders(0L);
        channel.start(null, null);
        channel.close();
        assertTrue(!Thread.currentThread().isInterrupted(),
                "空操作不应设置调用线程的中断标志");
    }

    @Test
    void 非媒体stdout拓扑选pipe进度通道() {
        // stdout 不传媒体（写盘）→ 走 -progress pipe:1，不触碰 tcp 绑定。
        ProgressChannel channel = ProgressChannel.forTopology(new IoTopology(false, false));
        assertInstanceOf(PipeProgressChannel.class, channel, "stdout 空闲应选 pipe 通道");
        assertEquals("pipe:1", channel.progressArg(), "pipe 通道注入参数应为 pipe:1");
    }

    @Test
    void 喂输入但stdout空闲仍选pipe进度通道() {
        // 仅 stdin 被占用、stdout 仍写盘：进度通道只看 stdout，故仍为 pipe。
        ProgressChannel channel = ProgressChannel.forTopology(new IoTopology(true, false));
        assertInstanceOf(PipeProgressChannel.class, channel, "只要 stdout 不传媒体就选 pipe 通道");
        assertEquals("pipe:1", channel.progressArg(), "pipe 通道注入参数应为 pipe:1");
    }

    @Test
    void tcp绑定失败降级时产生结构化警告() {
        try (TaskWarningCollector warnings = TaskWarningCollector.open()) {
            ProgressChannel channel = ProgressChannel.forTopology(
                    new IoTopology(false, true), () -> {
                        throw new IOException("bind denied");
                    });

            assertSame(ProgressChannel.NoProgressChannel.INSTANCE, channel);
            assertEquals(1, warnings.snapshot().size());
            assertEquals(WarningCode.PROGRESS_UNAVAILABLE, warnings.snapshot().get(0).code());
        }
    }
}
