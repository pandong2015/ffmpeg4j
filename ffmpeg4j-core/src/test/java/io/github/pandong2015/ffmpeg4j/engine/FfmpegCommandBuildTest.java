package io.github.pandong2015.ffmpeg4j.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 不依赖 ffmpeg 的纯逻辑单测：锁定命令改写、拓扑→进度通道选择、以及非零退出的终局归类分支契约。
 * 这些核心逻辑此前仅由 {@code assumeTrue(commandExists("ffmpeg"))} 门控的集成测试覆盖，无 ffmpeg
 * 环境下完全未验证；此处以纯函数种子补齐。
 */
class FfmpegCommandBuildTest {

    private static long count(List<String> xs, String v) {
        return xs.stream().filter(v::equals).count();
    }

    // —— 命令改写 buildEffectiveCommand —— //

    @Test
    void 占位argv0被替换为真实二进制且不重复y() {
        // 编译器已给出 -y；改写不得再次注入 -y。
        List<String> raw = List.of("ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc", "/out.mp4");
        List<String> eff = FfmpegRunImpl.buildEffectiveCommand("/opt/ff/ffmpeg", raw, "pipe:1");

        assertEquals("/opt/ff/ffmpeg", eff.get(0), "argv[0] 占位应被替换为解析后的二进制绝对路径");
        assertFalse(eff.contains("ffmpeg"), "占位字面量 ffmpeg 不应残留在参数中");
        assertEquals(1, count(eff, "-y"), "-y 恰出现一次，改写不得重复注入");
        assertTrue(eff.contains("/out.mp4"), "原输出目标应原样保留");
    }

    @Test
    void pipe进度参数恰注入一次() {
        List<String> eff = FfmpegRunImpl.buildEffectiveCommand(
                "/bin/ffmpeg", List.of("ffmpeg", "-i", "/in.mp4", "/out.mp4"), "pipe:1");
        assertEquals(1, count(eff, "-progress"), "-progress 恰注入一次");
        int idx = eff.indexOf("-progress");
        assertEquals("pipe:1", eff.get(idx + 1), "pipe 拓扑应注入 pipe:1");
    }

    @Test
    void tcp进度参数恰注入一次() {
        List<String> eff = FfmpegRunImpl.buildEffectiveCommand(
                "/bin/ffmpeg", List.of("ffmpeg", "-i", "/in.mp4", "pipe:1"), "tcp://127.0.0.1:5555");
        assertEquals(1, count(eff, "-progress"), "-progress 恰注入一次");
        int idx = eff.indexOf("-progress");
        assertEquals("tcp://127.0.0.1:5555", eff.get(idx + 1), "tcp 拓扑应注入回环 tcp 地址");
    }

    @Test
    void 无进度通道不注入progress() {
        // 降级为无进度（tcp 绑定失败）时 progressArg 为 null，改写不得注入 -progress。
        List<String> eff = FfmpegRunImpl.buildEffectiveCommand(
                "/bin/ffmpeg", List.of("ffmpeg", "-i", "/in.mp4", "/out.mp4"), null);
        assertEquals(0, count(eff, "-progress"), "progressArg 为 null 时不注入 -progress");
        assertEquals("/bin/ffmpeg", eff.get(0));
    }

    // —— 拓扑 → 进度通道选择 —— //

    @Test
    void 拓扑到通道的选择映射() {
        // stdout 空闲（写盘）→ pipe 通道。
        ProgressChannel pipe = ProgressChannel.forTopology(new IoTopology(false, false));
        assertInstanceOf(PipeProgressChannel.class, pipe, "stdout 空闲应选 pipe 通道");
        assertEquals("pipe:1", pipe.progressArg());
        pipe.close();

        // stdout 传媒体 → tcp 通道。注入工厂使测试不依赖环境是否允许真实 bind。
        ProgressChannel marker = ProgressChannel.NoProgressChannel.INSTANCE;
        ProgressChannel tcp = ProgressChannel.forTopology(new IoTopology(false, true), () -> marker);
        assertEquals(marker, tcp, "stdout 传媒体应调用注入的 tcp 通道工厂");

        // 喂输入但 stdout 空闲 → 仍走 pipe（进度与 stdin 无关）。
        ProgressChannel fedPipe = ProgressChannel.forTopology(new IoTopology(true, false));
        assertInstanceOf(PipeProgressChannel.class, fedPipe, "stdout 空闲即走 pipe，不受 stdin 影响");
        fedPipe.close();
    }

    // —— 终局归类 classifyTermination（findings：超时 vs 用户取消、内部管道不外泄）—— //

    @Test
    void 归类_成功与真实媒体失败() {
        assertEquals(FfmpegRunImpl.Termination.SUCCESS,
                FfmpegRunImpl.classifyTermination(0, false, false, ""));
        assertEquals(FfmpegRunImpl.Termination.MEDIA_FAILURE,
                FfmpegRunImpl.classifyTermination(1, false, false, "Unknown encoder 'libx265'"),
                "非零退出且非内部/超时/取消 → 媒体失败（外泄为 FfmpegException）");
    }

    @Test
    void 归类_内部管道故障不外泄为媒体失败() {
        // finding: internal 分类此前是死代码；归类须把回环进度管道 Connection refused 判为 INTERNAL，
        // 由 doAwait 以 RunResult 正常返回而非抛媒体类 FfmpegException。
        assertEquals(FfmpegRunImpl.Termination.INTERNAL,
                FfmpegRunImpl.classifyTermination(1, false, false,
                        "tcp://127.0.0.1:54321: Connection refused",
                        "tcp://127.0.0.1:54321"),
                "回环进度管道 Connection refused 应归 INTERNAL，不外泄为媒体错误");
    }

    @Test
    void 归类_用户localhost媒体失败不冒充本次progress故障() {
        assertEquals(FfmpegRunImpl.Termination.MEDIA_FAILURE,
                FfmpegRunImpl.classifyTermination(1, false, false,
                        "tcp://127.0.0.1:9000: Connection refused",
                        "tcp://127.0.0.1:54321"),
                "只有本次实际注入的 progress 端点才能归为内部故障");
    }

    @Test
    void tcp监听与progress参数使用同一IPv4回环地址() throws IOException {
        RecordingServerSocket socket = new RecordingServerSocket(54321);
        TcpProgressChannel channel = new TcpProgressChannel(socket);
        try {
            InetSocketAddress bound = (InetSocketAddress) socket.boundAddress;
            assertEquals("127.0.0.1", bound.getAddress().getHostAddress(), "监听必须明确绑定 IPv4 回环");
            assertEquals("tcp://127.0.0.1:54321", channel.progressArg(),
                    "发布给 ffmpeg 的地址必须与监听地址及实际端口一致");
        } finally {
            channel.close();
        }
    }

    @Test
    void tcp初始化失败会关闭socket并保留关闭失败() throws IOException {
        IOException bindFailure = new IOException("bind failed");
        IOException closeFailure = new IOException("close failed");
        FailingServerSocket socket = new FailingServerSocket(bindFailure, closeFailure);

        IOException thrown = assertThrows(IOException.class, () -> new TcpProgressChannel(socket));

        assertSame(bindFailure, thrown, "应保留初始化阶段的原始异常");
        assertTrue(socket.closeCalled, "初始化失败必须关闭尚未交由通道管理的 socket");
        assertEquals(1, thrown.getSuppressed().length, "关闭失败应作为 suppressed 保留");
        assertSame(closeFailure, thrown.getSuppressed()[0]);
    }

    @Test
    void 归类_用户先取消不被后到超时覆盖() {
        // finding: 先到的用户取消（timedOut 因看门狗 CAS 落败而保持 false）应返回结果而非超时异常。
        assertEquals(FfmpegRunImpl.Termination.CANCELLED,
                FfmpegRunImpl.classifyTermination(255, false, true, ""),
                "用户取消（timedOut=false, cancelRequested=true）→ 返回结果");
    }

    @Test
    void 归类_真实超时抛超时异常() {
        // 超时确实抢先：timedOut=true（同时看门狗的 cancel 也置了 cancelRequested）→ 仍判 TIMEOUT。
        assertEquals(FfmpegRunImpl.Termination.TIMEOUT,
                FfmpegRunImpl.classifyTermination(255, true, true, ""),
                "超时抢先时（timedOut=true）应判 TIMEOUT，优先于取消返回");
    }

    /** 只记录绑定参数，不触碰操作系统网络能力。 */
    private static final class RecordingServerSocket extends ServerSocket {
        private final int localPort;
        private SocketAddress boundAddress;

        private RecordingServerSocket(int localPort) throws IOException {
            this.localPort = localPort;
        }

        @Override
        public void setReuseAddress(boolean on) throws SocketException {
            // 无需设置真实 socket 选项。
        }

        @Override
        public void bind(SocketAddress endpoint) {
            this.boundAddress = endpoint;
        }

        @Override
        public void setSoTimeout(int timeout) throws SocketException {
            // 无需设置真实 socket 选项。
        }

        @Override
        public int getLocalPort() {
            return localPort;
        }

        @Override
        public void close() {
            // 无真实资源。
        }
    }

    /** 在 bind 与 close 阶段分别失败，用于锁定初始化失败时的资源清理契约。 */
    private static final class FailingServerSocket extends ServerSocket {
        private final IOException bindFailure;
        private final IOException closeFailure;
        private boolean closeCalled;

        private FailingServerSocket(IOException bindFailure, IOException closeFailure) throws IOException {
            this.bindFailure = bindFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public void setReuseAddress(boolean on) throws SocketException {
            // 让初始化继续到 bind。
        }

        @Override
        public void bind(SocketAddress endpoint) throws IOException {
            throw bindFailure;
        }

        @Override
        public void close() throws IOException {
            closeCalled = true;
            throw closeFailure;
        }
    }
}
