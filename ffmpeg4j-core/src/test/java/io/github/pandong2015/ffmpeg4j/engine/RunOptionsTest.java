package io.github.pandong2015.ffmpeg4j.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/** {@link RunOptions} 不可变 wither 风格测试。 */
class RunOptionsTest {

    @Test
    void 默认值() {
        RunOptions o = RunOptions.defaults();
        assertNull(o.timeout(), "默认无超时");
        assertNull(o.onProgress(), "默认无回调");
        assertNull(o.callbackExecutor(), "默认在 pump 线程触发");
        assertEquals(Duration.ofSeconds(5), o.cancelGracePeriod());
        assertEquals(Duration.ofSeconds(5), o.terminateGracePeriod());
    }

    @Test
    void wither返回新副本不改原对象() {
        RunOptions base = RunOptions.defaults();
        RunOptions withTimeout = base.timeout(Duration.ofSeconds(30));
        assertNotSame(base, withTimeout, "wither 必须返回新副本");
        assertNull(base.timeout(), "原对象不被改动");
        assertEquals(Duration.ofSeconds(30), withTimeout.timeout());
    }

    @Test
    void 各wither互不干扰() {
        Consumer<Progress> cb = p -> {
        };
        Executor exec = Runnable::run;
        RunOptions o = RunOptions.defaults()
                .timeout(Duration.ofSeconds(10))
                .onProgress(cb)
                .callbackExecutor(exec)
                .cancelGracePeriod(Duration.ofMillis(200))
                .terminateGracePeriod(Duration.ofMillis(300));
        assertEquals(Duration.ofSeconds(10), o.timeout());
        assertSame(cb, o.onProgress());
        assertSame(exec, o.callbackExecutor());
        assertEquals(Duration.ofMillis(200), o.cancelGracePeriod());
        assertEquals(Duration.ofMillis(300), o.terminateGracePeriod());
    }

    @Test
    void timeout可清除为null() {
        RunOptions o = RunOptions.defaults().timeout(Duration.ofSeconds(5)).timeout(null);
        assertNull(o.timeout(), "传 null 清除超时");
    }

    @Test
    void 非法参数被拒() {
        assertThrows(IllegalArgumentException.class,
                () -> RunOptions.defaults().timeout(Duration.ZERO), "超时不能为 0");
        assertThrows(IllegalArgumentException.class,
                () -> RunOptions.defaults().timeout(Duration.ofSeconds(-1)), "超时不能为负");
        assertThrows(IllegalArgumentException.class,
                () -> RunOptions.defaults().cancelGracePeriod(Duration.ofSeconds(-1)), "宽限期不能为负");
        assertThrows(NullPointerException.class,
                () -> RunOptions.defaults().cancelGracePeriod(null));
        assertThrows(NullPointerException.class,
                () -> RunOptions.defaults().terminateGracePeriod(null));
    }

    @Test
    void 零宽限期允许() {
        // 0 宽限期语义合法（立即升级），不应抛异常。
        RunOptions o = RunOptions.defaults().cancelGracePeriod(Duration.ZERO);
        assertEquals(Duration.ZERO, o.cancelGracePeriod());
    }
}
