package io.github.pandong2015.ffmpeg4j.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.pandong2015.ffmpeg4j.compiler.CompiledCommand;

/**
 * ABR {@code buildHlsAbr} 的纯 argv 断言（脱进程）+ master 解析纯函数断言。落实 D2/D3/D7/D8/D10 的
 * split=N、逐档 scale+setsar、{@code -map} 顺序与 {@code var_stream_map} 下标对应、恒 force_key_frames、
 * VOD 双标签、无 base_url、AES、agroup vs 每档音频两形态。
 */
class FacadeAbrCommandBuildTest {

    private static final byte[] KEY16 = new byte[16];

    private static HlsAbrOptions abr(HlsVariant... vs) {
        return HlsAbrOptions.defaults().variants(List.of(vs));
    }

    // ===== split + scale + setsar =====

    @Test
    void 同一视频流被N档消费触发split扇出且逐档scale与setsar() {
        CompiledCommand cmd = FacadeSupport.buildHlsAbr(new File("in.mp4"), new File("out"),
                abr(HlsVariant.of(1080, "5000k"), HlsVariant.of(720, "3000k"), HlsVariant.of(480, "1500k")), null);
        String fc = cmd.filterComplex();
        assertNotNull(fc, "ABR 走 filter_complex");
        assertTrue(fc.contains("split=3"), "同一 v 被 3 档消费应 split=3: " + fc);
        assertEquals(3, count(fc, "setsar=1"), "每档一个 setsar=1: " + fc);
        assertTrue(fc.contains("h=1080") && fc.contains("h=720") && fc.contains("h=480"), "逐档 scale 到目标高: " + fc);
        assertTrue(fc.contains("w=-2"), "未给 width 时按比缩放 scale=-2: " + fc);
    }

    // ===== -map 顺序 + var_stream_map 下标（D10 契约）=====

    @Test
    void agroup模式map顺序为N视频档标签加末尾单音频且下标对齐() {
        CompiledCommand cmd = FacadeSupport.buildHlsAbr(new File("in.mp4"), new File("out"),
                abr(HlsVariant.of(1080, "5000k"), HlsVariant.of(720, "3000k"), HlsVariant.of(480, "1500k")), null);
        List<String> argv = cmd.argv();
        List<String> maps = mapTargets(argv);
        assertEquals(4, maps.size(), "3 视频 + 1 共享音频: " + argv);
        assertTrue(maps.get(0).startsWith("[") && maps.get(1).startsWith("[") && maps.get(2).startsWith("["),
                "前 3 个 map 为 filtergraph 标签: " + maps);
        assertEquals("0:a:0", maps.get(3), "末个 map 为共享音频输入引用: " + maps);

        assertAdjacent(argv, "-var_stream_map",
                "v:0,agroup:aud v:1,agroup:aud v:2,agroup:aud a:0,agroup:aud,name:audio,default:yes");
        // -b:v:N 下标与 var_stream_map v:N 一一对应
        assertAdjacent(argv, "-c:v:0", "libx264");
        assertAdjacent(argv, "-b:v:0", "5000k");
        assertAdjacent(argv, "-maxrate:v:0", "5350k");
        assertAdjacent(argv, "-bufsize:v:0", "7500k");
        assertAdjacent(argv, "-b:v:1", "3000k");
        assertAdjacent(argv, "-b:v:2", "1500k");
        // agroup 单路音频：无下标 -b:a
        assertAdjacent(argv, "-c:a:0", "aac");
        assertAdjacent(argv, "-b:a", "128k");
        assertFalse(argv.contains("-b:a:0"), "agroup 音频不带下标: " + argv);
    }

    @Test
    void 每档独立音频模式map交错且var_stream_map为vi_ai() {
        CompiledCommand cmd = FacadeSupport.buildHlsAbr(new File("in.mp4"), new File("out"),
                abr(HlsVariant.of(720, "3000k"), HlsVariant.of(480, "1500k")).sharedAudio(false), null);
        List<String> argv = cmd.argv();
        List<String> maps = mapTargets(argv);
        assertEquals(4, maps.size(), "2 档 × (视频+音频) 交错: " + argv);
        assertTrue(maps.get(0).startsWith("["), "v0 标签: " + maps);
        assertEquals("0:a:0", maps.get(1), "a0: " + maps);
        assertTrue(maps.get(2).startsWith("["), "v1 标签: " + maps);
        assertEquals("0:a:0", maps.get(3), "a1: " + maps);
        assertAdjacent(argv, "-var_stream_map", "v:0,a:0 v:1,a:1");
        assertAdjacent(argv, "-c:a:0", "aac");
        assertAdjacent(argv, "-b:a:0", "128k");
        assertAdjacent(argv, "-c:a:1", "aac");
        assertAdjacent(argv, "-b:a:1", "128k");
    }

    @Test
    void 显式name下发到var_stream_map与省略name走数字索引() {
        CompiledCommand withName = FacadeSupport.buildHlsAbr(new File("in.mp4"), new File("out"),
                abr(HlsVariant.of(1080, "5000k").name("hd"), HlsVariant.of(720, "3000k")), null);
        assertAdjacent(withName.argv(), "-var_stream_map",
                "v:0,agroup:aud,name:hd v:1,agroup:aud a:0,agroup:aud,name:audio,default:yes");
    }

    // ===== master_pl_name / VOD 双标签 / 恒 force_key_frames / 无 base_url =====

    @Test
    void masterPlName与VOD双标签固定注入() {
        List<String> argv = FacadeSupport.buildHlsAbr(new File("in.mp4"), new File("out"),
                abr(HlsVariant.of(720, "3000k")), null).argv();
        assertAdjacent(argv, "-master_pl_name", "master.m3u8");
        assertAdjacent(argv, "-hls_playlist_type", "vod");
        assertAdjacent(argv, "-hls_list_size", "0");
        assertAdjacent(argv, "-hls_segment_type", "mpegts");
    }

    @Test
    void 恒注入一条跨档force_key_frames() {
        List<String> argv = FacadeSupport.buildHlsAbr(new File("in.mp4"), new File("out"),
                abr(HlsVariant.of(720, "3000k"), HlsVariant.of(480, "1500k")).hlsTime(6.0), null).argv();
        assertAdjacent(argv, "-force_key_frames", "expr:gte(t,n_forced*6)");
        assertEquals(1, count(argv, "-force_key_frames"), "对齐表达式仅一条覆盖全档: " + argv);
    }

    @Test
    void ABR默认不注入baseUrl() {
        List<String> argv = FacadeSupport.buildHlsAbr(new File("in.mp4"), new File("out"),
                abr(HlsVariant.of(720, "3000k")), null).argv();
        assertFalse(argv.contains("-hls_base_url"), "ABR 共位布局默认不注入 base_url: " + argv);
    }

    @Test
    void 段与playlist路径用字面百分v() {
        List<String> argv = FacadeSupport.buildHlsAbr(new File("in.mp4"), new File("out"),
                abr(HlsVariant.of(720, "3000k")), null).argv();
        assertAdjacent(argv, "-hls_segment_filename", "out/%v/seg_%d.ts");
        assertTrue(argv.contains("out/%v/index.m3u8"), "输出 playlist 路径字面 %v: " + argv);
    }

    // ===== AES =====

    @Test
    void AES注入keyInfoFile路径() {
        HlsAbrOptions o = abr(HlsVariant.of(720, "3000k")).key(HlsKey.of(KEY16, "https://k/s.key"));
        List<String> argv = FacadeSupport.buildHlsAbr(new File("in.mp4"), new File("out"), o,
                Path.of("/tmp/x.keyinfo")).argv();
        assertAdjacent(argv, "-hls_key_info_file", "/tmp/x.keyinfo");
    }

    // ===== fail-fast =====

    @Test
    void 变体目录名冲突报错() {
        HlsAbrOptions o = abr(HlsVariant.of(720, "3000k").name("x"), HlsVariant.of(480, "1500k").name("x"));
        assertThrows(IllegalArgumentException.class,
                () -> FacadeSupport.buildHlsAbr(new File("in.mp4"), new File("out"), o, null));
    }

    // ===== master 解析（纯函数）=====

    @Test
    void 解析master变体带引号感知的CODECS逗号() {
        String master = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=5350000,AVERAGE-BANDWIDTH=5000000,"
                + "RESOLUTION=1920x1080,CODECS=\"avc1.640028,mp4a.40.2\"\n"
                + "0/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=3210000,RESOLUTION=1280x720,CODECS=\"avc1.4d401f,mp4a.40.2\"\n"
                + "1/index.m3u8\n";
        List<FacadeSupport.MasterVariant> mvs = FacadeSupport.parseMasterVariants(master);
        assertEquals(2, mvs.size());
        assertEquals(5350000L, mvs.get(0).bandwidth(), "BANDWIDTH 不被 AVERAGE-BANDWIDTH 干扰");
        assertEquals(1920, mvs.get(0).width());
        assertEquals(1080, mvs.get(0).height());
        assertEquals("0/index.m3u8", mvs.get(0).uri());
        assertEquals(1280, mvs.get(1).width());
        assertEquals("1/index.m3u8", mvs.get(1).uri());
    }

    @Test
    void 解析master音频媒体行() {
        String master = "#EXTM3U\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"group_aud\",NAME=\"audio\",DEFAULT=YES,URI=\"audio/index.m3u8\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=3210000,RESOLUTION=1280x720,AUDIO=\"group_aud\"\n"
                + "0/index.m3u8\n";
        List<FacadeSupport.MasterAudio> audios = FacadeSupport.parseMasterAudioMedia(master);
        assertEquals(1, audios.size());
        assertEquals("group_aud", audios.get(0).groupId());
        assertEquals("audio/index.m3u8", audios.get(0).uri());
        // 非 agroup（无 MEDIA 行）时为空
        assertTrue(FacadeSupport.parseMasterAudioMedia("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv0.m3u8\n").isEmpty());
    }

    // ===== helpers =====

    /** 依 argv 顺序抽取每个 {@code -map} 的目标（下一元素），保留出现次序。 */
    private static List<String> mapTargets(List<String> argv) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i + 1 < argv.size(); i++) {
            if (argv.get(i).equals("-map")) {
                out.add(argv.get(i + 1));
            }
        }
        return out;
    }

    private static int count(List<String> argv, String token) {
        int c = 0;
        for (String s : argv) {
            if (s.equals(token)) {
                c++;
            }
        }
        return c;
    }

    private static int count(String haystack, String needle) {
        int c = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            c++;
            idx += needle.length();
        }
        return c;
    }

    private static void assertAdjacent(List<String> argv, String a, String b) {
        for (int i = 0; i + 1 < argv.size(); i++) {
            if (argv.get(i).equals(a) && argv.get(i + 1).equals(b)) {
                return;
            }
        }
        fail("未找到相邻的 [" + a + " " + b + "]，实际 argv=" + argv);
    }
}
