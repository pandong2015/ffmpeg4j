package io.github.pandong2015.ffmpeg4j.engine;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 已知 ffmpeg 错误模式库，按「最具体优先」求值：具体模式在前，通用 errno（file-not-found /
 * permission-denied / invalid-data）挪到末尾兜底，{@code generic-failure}（{@code Conversion
 * failed!}）作最终兜底。匹配整段 stderr 尾部，兼容新老 errno 措辞（{@code file: <errno>} 与
 * 5.x 的 {@code Error opening input: <errno>}）。
 *
 * <p>库内部管道故障必须结合本次实际注入的 {@code -progress} 端点判定，不能仅凭回环地址推断，
 * 否则用户自己的 localhost 媒体输入会被误判。
 */
final class ErrorPatterns {

    /**
     * 有序模式表——顺序即优先级，务必「最具体在前、通用 errno 在后、generic-failure 最末」。
     */
    private static final List<ErrorPattern> PATTERNS = List.of(
            // —— 滤镜相关（本库为 filter_complex 编译器，高价值、置前）—— //
            ErrorPattern.of(
                    "unknown-filter",
                    "No such filter",
                    "滤镜名未知或未被编译进当前 ffmpeg 构建（No such filter）"),
            ErrorPattern.of(
                    "filtergraph-unconnected-pad",
                    "Cannot find a matching stream for unlabeled input pad",
                    "filtergraph 存在未连接的输入 pad（自动 split/pad 命名或接线失败）"),
            ErrorPattern.of(
                    "filter-init",
                    "Error initializing (a simple|complex )?filter|Error while initializing (a simple|complex )?filter|"
                            + "Error applying options to the filter|Error reinitializing filters",
                    "滤镜初始化失败（选项非法或输入不可读，如烧字幕文件/字体不可读）"),

            // —— 编解码器 —— //
            ErrorPattern.of(
                    "encoder-unavailable",
                    "Unknown encoder|Automatic encoder selection failed|Encoder \\(codec [^)]*\\) not found",
                    "请求的编码器不可用或未编译进当前 ffmpeg 构建"),
            ErrorPattern.of(
                    "decoder-unavailable",
                    "Unknown decoder|Automatic decoder selection failed|Decoder \\(codec [^)]*\\) not found",
                    "请求的解码器不可用或未编译进当前 ffmpeg 构建"),
            ErrorPattern.of(
                    "encoder-open-failed",
                    "Error while opening encoder",
                    "打开编码器失败（参数与输入不兼容，如像素格式/分辨率/码率设置无效）"),
            ErrorPattern.of(
                    "odd-dimensions",
                    "(width|height) not divisible by 2",
                    "尺寸不能被 2 整除（yuv420p 的 H.264/HEVC 要求宽高为偶数）"),

            // —— HLS/AES 加密（本变更卖点，置于通用 errno 之前，避免 reason=null）—— //
            ErrorPattern.of(
                    "hls-invalid-key-size",
                    "Invalid key size",
                    "AES 密钥长度非法（HLS AES-128 密钥须为 16 字节原始字节）"),
            ErrorPattern.of(
                    "hls-encryption-unavailable",
                    "Encryption not supported|not built with (openssl|gnutls)|"
                            + "(openssl|gnutls)[^\\n]*(not found|not available|unavailable)",
                    "当前 ffmpeg 构建不支持加密（HLS AES 需 --enable-openssl 或 --enable-gnutls）"),
            ErrorPattern.of(
                    "codec-container-incompatible",
                    "codec not currently supported in container|Could not find tag for codec",
                    "该编解码器与目标容器不兼容（无法封装进该容器）"),

            // —— 流映射 / 输出格式 —— //
            ErrorPattern.of(
                    "no-matching-stream",
                    "matches no streams|does not contain any stream",
                    "指定的 -map 流选择未匹配到任何流"),
            ErrorPattern.of(
                    "output-format-unknown",
                    "Unable to find a suitable output format|Requested output format[^\\n]*is not known",
                    "无法确定输出封装格式（未知的输出格式或无法识别的扩展名）"),

            // —— 资源类 —— //
            ErrorPattern.of(
                    "disk-full",
                    "No space left on device",
                    "磁盘空间不足（No space left on device）"),
            ErrorPattern.of(
                    "invalid-data",
                    "Invalid data found",
                    "输入不是有效媒体或已损坏（Invalid data found when processing input）"),

            // —— 通用 errno 兜底（新老措辞兼容：file: <errno> 与 Error opening input: <errno>）—— //
            ErrorPattern.of(
                    "file-not-found",
                    "No such file or directory",
                    "文件或目录不存在（输入文件、输出目录、字幕或字体路径缺失）"),
            ErrorPattern.of(
                    "permission-denied",
                    "Permission denied",
                    "权限不足，无法读取或写入指定路径"),

            // —— 网络输入不可达（媒体/网络错误，非库内部管道故障）：用户远端源宕机/不通 —— //
            ErrorPattern.of(
                    "network-input-unavailable",
                    "Connection refused|Connection timed out|Network is unreachable|No route to host",
                    "网络输入源不可达（连接被拒绝/超时/网络不可达），请检查输入 URL 与网络连通性"),

            // —— 最终兜底 —— //
            ErrorPattern.of(
                    "generic-failure",
                    "Conversion failed",
                    "转换失败（ffmpeg 报告 Conversion failed，详见 stderr 尾部）"));

    private ErrorPatterns() {
    }

    /** 返回按优先级命中的第一条模式；无命中返回空。 */
    static Optional<ErrorPattern> classify(String stderrTail) {
        return classify(stderrTail, null);
    }

    /** 结合本次实际注入的 progress 参数分类；只有精确端点连接失败才属于内部故障。 */
    static Optional<ErrorPattern> classify(String stderrTail, String progressArg) {
        if (stderrTail == null || stderrTail.isBlank()) {
            return Optional.empty();
        }
        ErrorPattern progressFailure = progressFailure(progressArg);
        if (progressFailure != null && progressFailure.matches(stderrTail)) {
            return Optional.of(progressFailure);
        }
        for (ErrorPattern p : PATTERNS) {
            if (p.matches(stderrTail)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /** 命中模式的可读原因；无命中返回空。 */
    static Optional<String> reasonFor(String stderrTail) {
        return classify(stderrTail).map(ErrorPattern::reason);
    }

    /** 结合本次实际注入的 progress 参数返回命中原因。 */
    static Optional<String> reasonFor(String stderrTail, String progressArg) {
        return classify(stderrTail, progressArg).map(ErrorPattern::reason);
    }

    /** 命中模式是否属于库内部管道故障（不应外泄为媒体错误）。 */
    static boolean isInternal(String stderrTail) {
        return isInternal(stderrTail, null);
    }

    /** 仅当 stderr 命中本次实际注入的 tcp progress 端点时返回 true。 */
    static boolean isInternal(String stderrTail, String progressArg) {
        return classify(stderrTail, progressArg).map(ErrorPattern::internal).orElse(false);
    }

    private static ErrorPattern progressFailure(String progressArg) {
        if (progressArg == null || !progressArg.startsWith("tcp://")) {
            return null;
        }
        return ErrorPattern.internal(
                "progress-plumbing",
                Pattern.quote(progressArg) + "(?=[:\\s]|$)[^\\n]*Connection refused",
                "库内部进度管道连接失败（-progress tcp 回环通道 Connection refused），属库自身管道问题、非媒体错误");
    }
}
