package io.github.pandong2015.ffmpeg4j.engine;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.function.Consumer;

/**
 * tcp 进度通道：stdout 传媒体时，进度改经 {@code -progress tcp://127.0.0.1:<port>}，本通道在本地
 * 环回地址监听该端口收进度。
 *
 * <p>关键生命周期（spec「IO 拓扑驱动的运行时配置」）：
 * <ul>
 *   <li><b>bind-before-spawn</b>：构造时即绑定 {@link ServerSocket}（端口 0 由系统分配），消除
 *       「进程已连而服务端未 bind」的竞态；{@link #progressArg()} 返回已定端口。</li>
 *   <li>{@code accept} 设 {@link ServerSocket#setSoTimeout(int)}，避免永久阻塞。</li>
 *   <li>进程退出（{@code waitFor} 返回）即 {@link #close()} 关闭 socket，解开 accept/read。</li>
 *   <li>「进程已退出而进度连接从未建立」作<b>正常终止路径</b>处理：不作错误、不泄漏线程。</li>
 * </ul>
 */
final class TcpProgressChannel implements ProgressChannel {

    /** accept 轮询超时；配合 close 标志退出，防止永久阻塞。 */
    private static final int ACCEPT_SO_TIMEOUT_MS = 500;

    private final ServerSocket serverSocket;
    private final int port;
    private volatile boolean closed;
    private volatile Socket accepted;
    private volatile Thread reader;

    TcpProgressChannel() throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        ss.setSoTimeout(ACCEPT_SO_TIMEOUT_MS);
        this.serverSocket = ss;
        this.port = ss.getLocalPort();
    }

    int port() {
        return port;
    }

    @Override
    public String progressArg() {
        return "tcp://127.0.0.1:" + port;
    }

    @Override
    public void start(Process process, Consumer<String> lineConsumer) {
        Thread t = new Thread(() -> acceptLoop(lineConsumer), "ffmpeg-progress-tcp");
        t.setDaemon(true);
        this.reader = t;
        t.start();
    }

    private void acceptLoop(Consumer<String> lineConsumer) {
        while (!closed) {
            try {
                Socket socket = serverSocket.accept();
                this.accepted = socket;
                // 单次连接即为进度流；读到 EOF/关闭即结束。
                new StreamPump(socket.getInputStream(), lineConsumer).run();
                return;
            } catch (SocketTimeoutException timeout) {
                // 尚无连接：回到循环检查 closed。进程若已退出，close() 会置位并跳出——正常路径。
            } catch (SocketException closedDuringAccept) {
                // close() 关闭了 serverSocket：正常终止，不作错误。
                return;
            } catch (IOException io) {
                // accept/read 出错：视作连接不可用，正常收口，不外泄为媒体错误。
                return;
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        Socket s = accepted;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 已关闭无害。
            }
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // 已关闭无害。
        }
    }

    @Override
    public void awaitReaders(long millis) {
        Thread t = reader;
        if (t != null) {
            try {
                t.join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
