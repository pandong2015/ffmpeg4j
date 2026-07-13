package io.github.pandong2015.ffmpeg4j.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * {@link ErrorPatterns} 纯逻辑测试：为 5.9 每个模式各一条断言，并覆盖「最具体优先于 generic-failure」
 * 与「tcp Connection refused 归内部、不外泄」，以及新老 errno 措辞兼容。
 *
 * <p>stderr 片段均取自 ffmpeg 真实输出措辞。
 */
class ErrorPatternsTest {

    private static String category(String stderr) {
        Optional<ErrorPattern> p = ErrorPatterns.classify(stderr);
        assertTrue(p.isPresent(), "应命中某模式: " + stderr);
        assertNotNull(p.get().reason(), "每模式须给可读原因");
        assertFalse(p.get().reason().isBlank());
        return p.get().category();
    }

    // —— 5.9 逐模式 —— //

    @Test
    void file_not_found() {
        assertEquals("file-not-found",
                category("in.mp4: No such file or directory"));
    }

    @Test
    void file_not_found_新旧errno措辞兼容() {
        // 老措辞：file: <errno>
        assertEquals("file-not-found",
                category("input.mp4: No such file or directory"));
        // ffmpeg 5.x 新措辞：Error opening input: <errno>
        assertEquals("file-not-found",
                category("[in#0] Error opening input: No such file or directory\nError opening input file input.mp4."));
    }

    @Test
    void invalid_data() {
        assertEquals("invalid-data",
                category("[matroska,webm @ 0x55] Invalid data found when processing input\nbroken.mkv: Invalid data found when processing input"));
    }

    @Test
    void encoder_unavailable() {
        assertEquals("encoder-unavailable",
                category("Unknown encoder 'libx265'"));
        assertEquals("encoder-unavailable",
                category("[vost#0:0] Automatic encoder selection failed Default encoder for format mp4 is probably disabled."));
    }

    @Test
    void encoder_open_failed() {
        assertEquals("encoder-open-failed",
                category("[libx264 @ 0x55] Error while opening encoder for output stream #0:0 - maybe incorrect parameters such as bit_rate, rate, width or height"));
    }

    @Test
    void odd_dimensions() {
        assertEquals("odd-dimensions",
                category("[libx264 @ 0x55] width not divisible by 2 (321x240)"));
        assertEquals("odd-dimensions",
                category("[libx264 @ 0x55] height not divisible by 2 (320x241)"));
    }

    @Test
    void codec_container_incompatible() {
        assertEquals("codec-container-incompatible",
                category("[mp4 @ 0x55] Could not find tag for codec pcm_s16le in stream #1, codec not currently supported in container"));
    }

    @Test
    void no_matching_stream() {
        assertEquals("no-matching-stream",
                category("Stream map '0:5' matches no streams."));
        assertEquals("no-matching-stream",
                category("[out#0] Output file does not contain any stream"));
    }

    @Test
    void output_format_unknown() {
        assertEquals("output-format-unknown",
                category("[NULL @ 0x55] Unable to find a suitable output format for 'out.xyz'"));
        // 2023 改词
        assertEquals("output-format-unknown",
                category("Requested output format 'xyz' is not known."));
    }

    @Test
    void permission_denied() {
        assertEquals("permission-denied",
                category("/root/out.mp4: Permission denied"));
    }

    @Test
    void decoder_unavailable() {
        assertEquals("decoder-unavailable",
                category("Unknown decoder 'libaom-av1'"));
    }

    @Test
    void disk_full() {
        assertEquals("disk-full",
                category("av_interleaved_write_frame(): No space left on device"));
    }

    @Test
    void unknown_filter() {
        assertEquals("unknown-filter",
                category("[AVFilterGraph @ 0x55] No such filter: 'xyz'\nError initializing complex filters."));
    }

    @Test
    void filtergraph_unconnected_pad() {
        assertEquals("filtergraph-unconnected-pad",
                category("Cannot find a matching stream for unlabeled input pad 1 on filter Parsed_overlay_2"));
    }

    @Test
    void filter_init() {
        assertEquals("filter-init",
                category("[subtitles @ 0x55] Error applying options to the filter.\nError initializing filter 'subtitles' with args 'missing.srt'"));
    }

    @Test
    void generic_failure兜底() {
        assertEquals("generic-failure",
                category("Some unclassified problem happened\nConversion failed!"));
    }

    // —— 优先级与内部管道 —— //

    @Test
    void 最具体优先于generic_failure() {
        // 同时含具体 No such filter 与末尾通用 Conversion failed!，应判为 unknown-filter。
        String stderr = "[AVFilterGraph @ 0x55] No such filter: 'foobar'\n"
                + "Error initializing complex filters.\n"
                + "Conversion failed!";
        assertEquals("unknown-filter", category(stderr),
                "最具体的 unknown-filter 应优先于 generic-failure 兜底");
    }

    @Test
    void 具体编码器优先于通用errno() {
        // 同含 Unknown encoder 与 No such file or directory，编码器更具体、应在前。
        String stderr = "Unknown encoder 'libx265'\nsub.srt: No such file or directory";
        assertEquals("encoder-unavailable", category(stderr),
                "具体编码器模式应优先于通用 errno 兜底");
    }

    @Test
    void tcp_connection_refused归内部且不外泄() {
        String stderr = "tcp://127.0.0.1:54321: Connection refused";
        Optional<ErrorPattern> p = ErrorPatterns.classify(stderr);
        assertTrue(p.isPresent(), "应命中");
        assertEquals("progress-plumbing", p.get().category());
        assertTrue(p.get().internal(), "progress-plumbing 须标记为内部管道故障");
        assertTrue(ErrorPatterns.isInternal(stderr), "isInternal 应为 true——不外泄为媒体错误");
    }

    @Test
    void 媒体错误不被判为内部() {
        assertFalse(ErrorPatterns.isInternal("Unknown encoder 'x'"), "媒体错误非内部");
    }

    @Test
    void 网络输入ConnectionRefused判为媒体错误而非内部管道() {
        // 用户以远端网络源作输入且不可达：Connection refused 属媒体/网络错误，
        // 绝不能因 progress-plumbing 过度匹配而误判为库内部进度管道故障。
        String rtmp = "[tcp @ 0x55] Connection to tcp://live.example.com:1935 failed: Connection refused\n"
                + "rtmp://live.example.com/app: Connection refused";
        assertFalse(ErrorPatterns.isInternal(rtmp), "远端网络 Connection refused 非库内部管道故障");
        assertEquals("network-input-unavailable", category(rtmp),
                "远端网络不可达应判为媒体类 network-input-unavailable");

        // 裸 IP（非回环）同样应判为媒体错误。
        String bareIp = "tcp://93.184.216.34:8080: Connection refused";
        assertFalse(ErrorPatterns.isInternal(bareIp), "非回环地址的 Connection refused 非内部");
        assertEquals("network-input-unavailable", category(bareIp));
    }

    @Test
    void 回环进度端点ConnectionRefused仍判为内部管道() {
        // 收窄后：仅回环地址（127.0.0.1/localhost/[::1]）的 Connection refused 才算库内部进度管道故障。
        assertTrue(ErrorPatterns.isInternal("tcp://127.0.0.1:54321: Connection refused"));
        assertTrue(ErrorPatterns.isInternal("Failed to open tcp://localhost:8000: Connection refused"));
        assertTrue(ErrorPatterns.isInternal("tcp://[::1]:9000: Connection refused"));
    }

    @Test
    void 无命中返回空() {
        assertTrue(ErrorPatterns.classify("").isEmpty(), "空串无命中");
        assertTrue(ErrorPatterns.classify(null).isEmpty(), "null 无命中");
        assertTrue(ErrorPatterns.classify("一切正常，无错误关键字").isEmpty(), "无关键字无命中");
        assertTrue(ErrorPatterns.reasonFor("无关键字").isEmpty());
    }
}
