package io.github.pandong2015.ffmpeg4j.engine;

import java.util.List;

/**
 * 依据 argv 推导的 IO 拓扑：本次任务 stdin/stdout 是否被媒体管道占用。据此联动决定进度通道与
 * 取消能力（见 design D6）：
 *
 * <table border="1">
 *   <caption>拓扑 → 联动</caption>
 *   <tr><th>stdin</th><th>stdout</th><th>进度通道</th><th>取消</th></tr>
 *   <tr><td>空闲</td><td>空闲(写盘)</td><td>-progress pipe:1</td><td>优雅(写 q)</td></tr>
 *   <tr><td>空闲</td><td>传帧</td><td>-progress tcp://127.0.0.1:port</td><td>优雅</td></tr>
 *   <tr><td>喂输入</td><td>空闲</td><td>-progress pipe:1</td><td>降级 SIGTERM</td></tr>
 *   <tr><td>喂输入</td><td>传帧</td><td>-progress tcp://…</td><td>降级 SIGTERM</td></tr>
 * </table>
 *
 * @param stdinFed    stdin 被输入媒体占用（argv 含 {@code -i pipe:}/{@code pipe:0}/{@code -}）。
 * @param stdoutMedia stdout 被输出媒体占用（输出目标为 {@code pipe:}/{@code pipe:1}/{@code -}）。
 */
record IoTopology(boolean stdinFed, boolean stdoutMedia) {

    /**
     * 从 argv 推导拓扑。argv 约定 argv[0] 为二进制（占位或真实路径），其后为 ffmpeg 参数。
     *
     * <p>规则：
     * <ul>
     *   <li>紧跟 {@code -i} 的取值若为 {@code pipe:}/{@code pipe:0}/{@code -} → stdin 被占用。</li>
     *   <li>ffmpeg 的输出目标恒为 argv 末尾参数；末尾为 {@code pipe:}/{@code pipe:1}/{@code -}
     *       且并非某个 {@code -i} 的取值时 → stdout 被占用（传媒体）。</li>
     * </ul>
     */
    static IoTopology derive(List<String> argv) {
        boolean stdinFed = false;
        if (argv != null) {
            for (int i = 0; i < argv.size(); i++) {
                if ("-i".equals(argv.get(i)) && i + 1 < argv.size() && isStdinTarget(argv.get(i + 1))) {
                    stdinFed = true;
                }
            }
        }
        boolean stdoutMedia = deriveStdoutMedia(argv);
        return new IoTopology(stdinFed, stdoutMedia);
    }

    private static boolean deriveStdoutMedia(List<String> argv) {
        if (argv == null || argv.size() < 2) {
            return false;
        }
        String last = argv.get(argv.size() - 1);
        if (!isStdoutTarget(last)) {
            return false;
        }
        // 末尾若是上一个 -i 的取值，则它是输入而非输出（畸形命令，谨慎不判为 stdout 媒体）。
        String prev = argv.get(argv.size() - 2);
        return !"-i".equals(prev);
    }

    private static boolean isStdinTarget(String v) {
        return v != null && (v.equals("-") || v.equals("pipe:") || v.equals("pipe:0"));
    }

    private static boolean isStdoutTarget(String v) {
        return v != null && (v.equals("-") || v.equals("pipe:") || v.equals("pipe:1"));
    }
}
