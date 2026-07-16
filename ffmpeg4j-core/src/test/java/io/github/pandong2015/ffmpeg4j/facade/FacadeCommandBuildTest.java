package io.github.pandong2015.ffmpeg4j.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.pandong2015.ffmpeg4j.FfmpegException;
import io.github.pandong2015.ffmpeg4j.compiler.CompiledCommand;
import io.github.pandong2015.ffmpeg4j.model.Filters;
import io.github.pandong2015.ffmpeg4j.model.Input;
import io.github.pandong2015.ffmpeg4j.model.MediaType;
import io.github.pandong2015.ffmpeg4j.probe.FormatInfo;
import io.github.pandong2015.ffmpeg4j.probe.ProbeResult;
import io.github.pandong2015.ffmpeg4j.probe.StreamInfo;

/**
 * 纯 argv 断言（不依赖真实 ffmpeg）：验证各门面「构建」阶段落实了 §7.3 的正确性约束。
 * 依赖注入的 {@link ProbeResult} 而非真实探测，因而可离线执行。
 */
class FacadeCommandBuildTest {

    // ===== 1. transcode =====

    @Test
    void 转码写入指定编解码器与质量参数() {
        CompiledCommand cmd = FacadeSupport.buildTranscode(
                new File("in.mkv"), new File("out.mp4"),
                TranscodeOptions.defaults().videoCodec("libx265").audioCodec("aac").crf(23).preset("fast"));
        List<String> argv = cmd.argv();
        assertAdjacent(argv, "-c:v", "libx265");
        assertAdjacent(argv, "-c:a", "aac");
        assertAdjacent(argv, "-crf", "23");
        assertAdjacent(argv, "-preset", "fast");
    }

    @Test
    void 转码用可选流映射兼容缺视轨或缺音轨输入() {
        CompiledCommand cmd = FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().videoCodec("libx264").audioCodec("aac"));
        List<String> argv = cmd.argv();
        assertTrue(argv.contains("0:v:0?"), "视频应为可选映射（缺视轨时不致中止）: " + argv);
        assertTrue(argv.contains("0:a:0?"), "音频应为可选映射（缺音轨时不致中止）: " + argv);
    }

    @Test
    void 转码未设videoFilter时双可选无filterComplex() {
        CompiledCommand cmd = FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"), TranscodeOptions.defaults());
        assertTrue(cmd.filterComplex() == null, "无 videoFilter 不应有 filter_complex");
        assertTrue(cmd.argv().contains("0:v:0?"), "视频应为可选映射: " + cmd.argv());
        assertTrue(cmd.argv().contains("0:a:0?"), "音频应为可选映射: " + cmd.argv());
    }

    @Test
    void 转码挂videoFilter后视频经filterComplex而音频仍可选() {
        CompiledCommand cmd = FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().videoFilter(v -> Filters.scale(v, 1280, -1)));
        assertNotNull(cmd.filterComplex(), "挂 videoFilter 应有 filter_complex");
        assertTrue(cmd.filterComplex().contains("scale"), "应含 scale: " + cmd.filterComplex());
        assertTrue(cmd.argv().contains("0:a:0?"), "音频应为可选映射: " + cmd.argv());
        assertFalse(cmd.argv().contains("0:v:0?"), "视频经滤镜后不应是可选原始映射: " + cmd.argv());
    }

    @Test
    void 转码videoFilter内多输入水印自动补第二路输入() {
        CompiledCommand cmd = FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().videoFilter(v ->
                        Filters.overlay(v, Input.of("logo.png").withInputArgs("-loop", "1").video(),
                                "W-w-6", "H-h-6", true)));
        List<String> argv = cmd.argv();
        assertEquals(2, argv.stream().filter("-i"::equals).count(), "应有两路 -i: " + argv);
        assertTrue(cmd.filterComplex().contains("[0:v:0][1:v:0]overlay"),
                "overlay 应接 [0:v:0][1:v:0]: " + cmd.filterComplex());
        assertTrue(cmd.filterComplex().contains("shortest=1"), "循环水印应含 shortest=1: " + cmd.filterComplex());
        assertAdjacent(argv, "-loop", "1");
    }

    @Test
    void 转码类型化码控与GOP派生() {
        CompiledCommand cmd = FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().videoCodec("libx264").fps(25).maxrate("2M").bufsize("4M").gop(50));
        List<String> argv = cmd.argv();
        assertAdjacent(argv, "-r", "25");
        assertAdjacent(argv, "-maxrate", "2M");
        assertAdjacent(argv, "-bufsize", "4M");
        assertAdjacent(argv, "-keyint_min", "50");
        assertAdjacent(argv, "-g", "50");
        assertAdjacent(argv, "-sc_threshold", "0");
    }

    @Test
    void 转码按秒强制关键帧渲染() {
        CompiledCommand cmd = FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().videoCodec("libx264").forceKeyframesEverySeconds(1));
        assertAdjacent(cmd.argv(), "-force_key_frames", "expr:gte(t,n_forced*1)");
    }

    @Test
    void 转码gop与按秒强制关键帧互补共存() {
        CompiledCommand cmd = FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().videoCodec("libx264").gop(50).forceKeyframesEverySeconds(2));
        List<String> argv = cmd.argv();
        assertAdjacent(argv, "-keyint_min", "50");
        assertAdjacent(argv, "-g", "50");
        assertAdjacent(argv, "-force_key_frames", "expr:gte(t,n_forced*2)");
    }

    @Test
    void 按秒强制关键帧去尾零渲染() {
        CompiledCommand cmd = FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().videoCodec("libx264").forceKeyframesEverySeconds(1.5));
        assertAdjacent(cmd.argv(), "-force_key_frames", "expr:gte(t,n_forced*1.5)");
    }

    @Test
    void 按秒强制关键帧非正数即抛() {
        assertThrows(IllegalArgumentException.class,
                () -> TranscodeOptions.defaults().forceKeyframesEverySeconds(0));
        assertThrows(IllegalArgumentException.class,
                () -> TranscodeOptions.defaults().forceKeyframesEverySeconds(-1));
    }

    @Test
    void 按秒强制关键帧与copy冲突build期抛错() {
        assertThrows(FfmpegException.class, () -> FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().videoCodec("copy").forceKeyframesEverySeconds(2)));
    }

    @Test
    void 转码默认不含按秒强制关键帧() {
        CompiledCommand cmd = FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"), TranscodeOptions.defaults());
        assertFalse(cmd.argv().contains("-force_key_frames"), "默认不应含 -force_key_frames: " + cmd.argv());
    }

    // ===== HLS 单码率 VOD 切片 =====

    @Test
    void HLS默认copy路径最小argv() {
        CompiledCommand cmd = FacadeSupport.buildHls(
                new File("in.mp4"), new File("out"), HlsOptions.defaults(), null);
        List<String> argv = cmd.argv();
        assertAdjacent(argv, "-c:v", "copy");
        assertAdjacent(argv, "-c:a", "copy");
        assertAdjacent(argv, "-f", "hls");
        assertAdjacent(argv, "-hls_time", "8");
        assertAdjacent(argv, "-hls_playlist_type", "vod");
        assertAdjacent(argv, "-hls_list_size", "0");
        assertAdjacent(argv, "-hls_segment_type", "mpegts");
        assertAdjacent(argv, "-hls_segment_filename", "out/ts/index%d.ts");
        assertAdjacent(argv, "-hls_base_url", "ts/");
        assertTrue(argv.contains("out/index.m3u8"), "输出为 playlist: " + argv);
        // 首视频+首音频可选映射
        assertTrue(argv.contains("0:v:0?"), "视频首轨可选映射: " + argv);
        assertTrue(argv.contains("0:a:0?"), "音频首轨可选映射: " + argv);
        assertFalse(argv.contains("-force_key_frames"), "默认不对齐: " + argv);
        assertFalse(argv.contains("-hls_key_info_file"), "无 AES: " + argv);
    }

    @Test
    void HLS的AES接线keyInfoFile() {
        HlsOptions o = HlsOptions.defaults().key(HlsKey.of(new byte[16], "https://k/s.key"));
        CompiledCommand cmd = FacadeSupport.buildHls(
                new File("in.mp4"), new File("out"), o, Path.of("/tmp/ki.txt"));
        assertAdjacent(cmd.argv(), "-hls_key_info_file", "/tmp/ki.txt");
    }

    @Test
    void HLS转码对齐关键帧注入forceKeyFrames() {
        HlsOptions o = HlsOptions.defaults().videoCodec("libx264").hlsTime(6.0).alignKeyframes(true);
        CompiledCommand cmd = FacadeSupport.buildHls(new File("in.mp4"), new File("out"), o, null);
        assertAdjacent(cmd.argv(), "-force_key_frames", "expr:gte(t,n_forced*6)");
    }

    @Test
    void HLS对齐关键帧与copy冲突build期抛错() {
        HlsOptions o = HlsOptions.defaults().alignKeyframes(true); // videoCodec 仍 copy
        assertThrows(FfmpegException.class,
                () -> FacadeSupport.buildHls(new File("in.mp4"), new File("out"), o, null));
    }

    @Test
    void HLS的segmentUriPrefix覆盖默认baseUrl() {
        HlsOptions o = HlsOptions.defaults().segmentUriPrefix("https://cdn/");
        CompiledCommand cmd = FacadeSupport.buildHls(new File("in.mp4"), new File("out"), o, null);
        List<String> argv = cmd.argv();
        assertAdjacent(argv, "-hls_base_url", "https://cdn/");
        // 只出现一次 base_url（覆盖非叠加）
        assertEquals(1, argv.stream().filter("-hls_base_url"::equals).count(), "base_url 仅一次: " + argv);
    }

    @Test
    void HLS段清单解析m3u8正文() {
        String m3u8 = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:8.0,\nts/index0.ts\n#EXTINF:8.0,\nts/index1.ts\n#EXT-X-ENDLIST\n";
        List<String> names = FacadeSupport.parseSegmentBasenames(m3u8);
        assertEquals(List.of("index0.ts", "index1.ts"), names);
        // CDN 绝对前缀亦取 basename
        assertEquals(List.of("index0.ts"),
                FacadeSupport.parseSegmentBasenames("#EXTINF:8,\nhttps://cdn/index0.ts\n"));
    }

    @Test
    void 转码h265的VBV经extraOutputArgs逐字追加() {
        CompiledCommand cmd = FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().videoCodec("libx265")
                        .extraOutputArgs("-x265-params", "vbv-maxrate=2000:vbv-bufsize=4000"));
        List<String> argv = cmd.argv();
        assertAdjacent(argv, "-c:v", "libx265");
        assertAdjacent(argv, "-x265-params", "vbv-maxrate=2000:vbv-bufsize=4000");
    }

    @Test
    void 转码默认不含码控段() {
        CompiledCommand cmd = FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"), TranscodeOptions.defaults());
        List<String> argv = cmd.argv();
        assertFalse(argv.contains("-r"), "默认不应含 -r: " + argv);
        assertFalse(argv.contains("-maxrate"), "默认不应含 -maxrate: " + argv);
        assertFalse(argv.contains("-g"), "默认不应含 -g: " + argv);
        assertFalse(argv.contains("-sc_threshold"), "默认不应含 -sc_threshold: " + argv);
    }

    // ===== 2. remux（§7.3 字幕分派）=====

    @Test
    void remux含文本字幕的mkv转mp4产出movText() {
        ProbeResult probe = probe(10.0,
                video(0, 640, 480, "25/1"),
                audio(1, "aac"),
                subtitle(2, "subrip"));
        CompiledCommand cmd = FacadeSupport.buildRemux(
                new File("in.mkv"), new File("out.mp4"), probe, RemuxOptions.defaults());
        List<String> argv = cmd.argv();
        assertAdjacent(argv, "-c:v", "copy");
        assertAdjacent(argv, "-c:a", "copy");
        assertAdjacent(argv, "-c:s", "mov_text");
        // 文本字幕被映射
        assertTrue(argv.contains("0:s:0"), "文本字幕应被映射: " + argv);
    }

    @Test
    void remux图形字幕在mp4目标下被丢弃() {
        ProbeResult probe = probe(10.0,
                video(0, 640, 480, "25/1"),
                subtitle(1, "hdmv_pgs_subtitle"));
        CompiledCommand cmd = FacadeSupport.buildRemux(
                new File("in.mkv"), new File("out.mp4"), probe, RemuxOptions.defaults());
        List<String> argv = cmd.argv();
        // 图形字幕不映射，也不应出现 -c:s
        assertFalse(argv.contains("-c:s"), "图形字幕进 mp4 应被丢弃，不应出现 -c:s: " + argv);
        assertFalse(argv.contains("0:s:0"), "图形字幕不应被映射: " + argv);
    }

    @Test
    void remux目标为mkv时字幕直接copy() {
        ProbeResult probe = probe(10.0,
                video(0, 640, 480, "25/1"),
                subtitle(1, "hdmv_pgs_subtitle"));
        CompiledCommand cmd = FacadeSupport.buildRemux(
                new File("in.mp4"), new File("out.mkv"), probe, RemuxOptions.defaults());
        List<String> argv = cmd.argv();
        assertAdjacent(argv, "-c:s", "copy");
        assertTrue(argv.contains("0:s:0"), "mkv 目标应映射字幕: " + argv);
    }

    // ===== 3. clip（§7.3 用 -ss + -t，不用 -to）=====

    @Test
    void clip快切用输入侧ss与输出侧t而非to() {
        CompiledCommand cmd = FacadeSupport.buildClip(
                new File("in.mp4"), new File("out.mp4"), 1.0, 3.0, ClipOptions.defaults());
        List<String> argv = cmd.argv();
        assertAdjacent(argv, "-ss", "1");
        assertAdjacent(argv, "-t", "2");           // end - start = 2
        assertFalse(argv.contains("-to"), "clip 不应使用有歧义的 -to: " + argv);
        assertAdjacent(argv, "-c", "copy");
        // 输入侧 -ss 应位于 -i 之前（快 seek）
        assertTrue(argv.indexOf("-ss") < argv.indexOf("-i"), "快切 -ss 应在 -i 之前: " + argv);
    }

    @Test
    void clip精切用输出侧ss与t并重编码() {
        CompiledCommand cmd = FacadeSupport.buildClip(
                new File("in.mp4"), new File("out.mp4"), 1.5, 4.0,
                ClipOptions.defaults().reencode(true).videoCodec("libx264").audioCodec("aac"));
        List<String> argv = cmd.argv();
        assertAdjacent(argv, "-ss", "1.5");
        assertAdjacent(argv, "-t", "2.5");
        assertFalse(argv.contains("-to"), "clip 不应使用 -to: " + argv);
        assertAdjacent(argv, "-c:v", "libx264");
        assertAdjacent(argv, "-c:a", "aac");
        // 精切：-ss 在 -i 之后（输出侧）
        assertTrue(argv.indexOf("-ss") > argv.indexOf("-i"), "精切 -ss 应在 -i 之后: " + argv);
    }

    @Test
    void clip用可选流映射兼容缺轨输入() {
        CompiledCommand fast = FacadeSupport.buildClip(
                new File("in.mp4"), new File("out.mp4"), 1.0, 3.0, ClipOptions.defaults());
        assertTrue(fast.argv().contains("0:v:0?"), "快切视频应为可选映射: " + fast.argv());
        assertTrue(fast.argv().contains("0:a:0?"), "快切音频应为可选映射: " + fast.argv());
        CompiledCommand precise = FacadeSupport.buildClip(
                new File("in.mp4"), new File("out.mp4"), 1.0, 3.0, ClipOptions.defaults().reencode(true));
        assertTrue(precise.argv().contains("0:v:0?"), "精切视频应为可选映射: " + precise.argv());
        assertTrue(precise.argv().contains("0:a:0?"), "精切音频应为可选映射: " + precise.argv());
    }

    @Test
    void clip区间非法时抛异常() {
        assertThrows(IllegalArgumentException.class, () -> FacadeSupport.buildClip(
                new File("in.mp4"), new File("out.mp4"), 3.0, 3.0, ClipOptions.defaults()));
    }

    // ===== 4. extractAudio（§7.3 -map 0:a + 按扩展名选 codec）=====

    @Test
    void 抽音频映射首条音轨避开封面图() {
        CompiledCommand cmd = FacadeSupport.buildExtractAudio(
                new File("in.mp4"), new File("out.mp3"), null, ExtractAudioOptions.defaults());
        List<String> argv = cmd.argv();
        assertAdjacent(argv, "-map", "0:a:0");
        assertAdjacent(argv, "-c:a", "libmp3lame");
    }

    @Test
    void 抽音频按扩展名推导编解码器() {
        assertEquals("pcm_s16le", FacadeSupport.audioCodecForExtension("wav", null));
        assertEquals("libmp3lame", FacadeSupport.audioCodecForExtension("mp3", null));
        assertEquals("flac", FacadeSupport.audioCodecForExtension("flac", null));
        assertEquals("libopus", FacadeSupport.audioCodecForExtension("opus", null));
        assertEquals("libvorbis", FacadeSupport.audioCodecForExtension("ogg", null));
        // m4a/aac：源已 aac 可 copy，否则 aac 重编码
        assertEquals("copy", FacadeSupport.audioCodecForExtension("m4a", "aac"));
        assertEquals("aac", FacadeSupport.audioCodecForExtension("m4a", "mp3"));
        assertEquals("aac", FacadeSupport.audioCodecForExtension("aac", null));
    }

    @Test
    void 抽音频到m4a且源为aac时copy() {
        ProbeResult probe = probe(5.0, video(0, 320, 240, "10/1"), audio(1, "aac"));
        CompiledCommand cmd = FacadeSupport.buildExtractAudio(
                new File("in.mp4"), new File("out.m4a"), probe, ExtractAudioOptions.defaults());
        assertAdjacent(cmd.argv(), "-c:a", "copy");
    }

    @Test
    void 抽音频源无音轨时提前抛可诊断异常() {
        ProbeResult noAudio = probe(5.0, video(0, 320, 240, "10/1"));   // 无声视频
        assertThrows(FfmpegException.class, () -> FacadeSupport.buildExtractAudio(
                new File("in.mp4"), new File("out.mp3"), noAudio, ExtractAudioOptions.defaults()));
    }

    // ===== 5. thumbnail =====

    @Test
    void 抓帧输出单帧并可选缩放() {
        CompiledCommand cmd = FacadeSupport.buildThumbnail(
                new File("in.mp4"), new File("out.png"), 2.5,
                ThumbnailOptions.defaults().width(160));
        List<String> argv = cmd.argv();
        assertAdjacent(argv, "-frames:v", "1");
        assertAdjacent(argv, "-ss", "2.5");
        assertNotNull(cmd.filterComplex(), "带缩放应有 filter_complex");
        assertTrue(cmd.filterComplex().contains("scale"), "应含 scale 滤镜: " + cmd.filterComplex());
    }

    @Test
    void 抓帧不缩放时无滤镜() {
        CompiledCommand cmd = FacadeSupport.buildThumbnail(
                new File("in.mp4"), new File("out.jpg"), 1.0, ThumbnailOptions.defaults());
        assertTrue(cmd.filterComplex() == null, "无缩放应无 filter_complex（纯 -map 透传）");
    }

    // ===== 6. concat（§7.3 归一化含 setsar；异构注入/拒绝）=====

    @Test
    void concat归一化链含setsar() {
        ProbeResult a = probe(2.0, video(0, 320, 240, "10/1"), audio(1, "aac"));
        ProbeResult b = probe(2.0, video(0, 640, 480, "25/1"), audio(1, "mp3"));
        CompiledCommand cmd = FacadeSupport.buildConcat(
                List.of(new File("a.mp4"), new File("b.mp4")), new File("out.mp4"),
                List.of(a, b), ConcatOptions.defaults());
        assertNotNull(cmd.filterComplex());
        assertTrue(cmd.filterComplex().contains("setsar"), "归一化必含 setsar: " + cmd.filterComplex());
        assertTrue(cmd.filterComplex().contains("concat"), "应含 concat 滤镜: " + cmd.filterComplex());
    }

    @Test
    void concat缺音段注入限时静音() {
        ProbeResult a = probe(2.0, video(0, 320, 240, "10/1"), audio(1, "aac"));
        ProbeResult b = probe(2.0, video(0, 320, 240, "10/1"));   // 无音轨
        CompiledCommand cmd = FacadeSupport.buildConcat(
                List.of(new File("a.mp4"), new File("b.mp4")), new File("out.mp4"),
                List.of(a, b), ConcatOptions.defaults());
        String fc = cmd.filterComplex();
        assertTrue(fc.contains("anullsrc"), "缺音段应注入 anullsrc 静音源: " + fc);
        assertTrue(fc.contains("atrim"), "注入静音应用 atrim 限时长: " + fc);
    }

    @Test
    void concat缺视段注入限时纯色() {
        ProbeResult a = probe(2.0, video(0, 320, 240, "10/1"), audio(1, "aac"));
        ProbeResult b = probe(2.0, audio(0, "aac"));   // 无视轨
        CompiledCommand cmd = FacadeSupport.buildConcat(
                List.of(new File("a.mp4"), new File("b.mp4")), new File("out.mp4"),
                List.of(a, b), ConcatOptions.defaults().width(320).height(240).fps(10.0));
        String fc = cmd.filterComplex();
        assertTrue(fc.contains("color="), "缺视段应注入 color 纯色源: " + fc);
        assertTrue(fc.contains("trim"), "注入纯色应用 trim 限时长: " + fc);
    }

    @Test
    void concat异构且策略为reject时抛异常() {
        ProbeResult a = probe(2.0, video(0, 320, 240, "10/1"), audio(1, "aac"));
        ProbeResult b = probe(2.0, video(0, 320, 240, "10/1"));   // 无音轨
        assertThrows(FfmpegException.class, () -> FacadeSupport.buildConcat(
                List.of(new File("a.mp4"), new File("b.mp4")), new File("out.mp4"),
                List.of(a, b), ConcatOptions.defaults().onMissingStream(ConcatOptions.OnMissingStream.REJECT)));
    }

    @Test
    void concat全为音频走纯音频拼接不注入黑帧() {
        ProbeResult a = probe(2.0, audio(0, "mp3"));
        ProbeResult b = probe(2.0, audio(0, "aac"));
        CompiledCommand cmd = FacadeSupport.buildConcat(
                List.of(new File("a.mp3"), new File("b.mp3")), new File("out.mp3"),
                List.of(a, b), ConcatOptions.defaults());
        String fc = cmd.filterComplex();
        assertTrue(fc.contains("concat=n=2:v=0:a=1"), "全音频应走纯音频 concat: " + fc);
        assertFalse(fc.contains("color="), "纯音频拼接不应注入纯色视频: " + fc);
        assertFalse(cmd.argv().contains("0:v:0"), "纯音频输出不应映射视频流: " + cmd.argv());
    }

    @Test
    void concat全为视频走纯视频拼接不注入静音() {
        ProbeResult a = probe(2.0, video(0, 320, 240, "10/1"));
        ProbeResult b = probe(2.0, video(0, 320, 240, "10/1"));
        CompiledCommand cmd = FacadeSupport.buildConcat(
                List.of(new File("a.mp4"), new File("b.mp4")), new File("out.mp4"),
                List.of(a, b), ConcatOptions.defaults());
        String fc = cmd.filterComplex();
        assertTrue(fc.contains("concat=n=2:v=1:a=0"), "全视频应走纯视频 concat: " + fc);
        assertFalse(fc.contains("anullsrc"), "纯视频拼接不应注入静音音轨: " + fc);
    }

    @Test
    void concat全部既无视频也无音频时抛异常() {
        ProbeResult a = probe(2.0, subtitle(0, "subrip"));
        ProbeResult b = probe(2.0, subtitle(0, "subrip"));
        assertThrows(FfmpegException.class, () -> FacadeSupport.buildConcat(
                List.of(new File("a.mkv"), new File("b.mkv")), new File("out.mkv"),
                List.of(a, b), ConcatOptions.defaults()));
    }

    // ===== 7. burnSubtitles =====

    @Test
    void 烧字幕建图含subtitles滤镜与视频重编码() {
        CompiledCommand cmd = FacadeSupport.buildBurnSubtitles(
                new File("v.mp4"), new File("s.srt"), new File("out.mp4"),
                BurnSubtitlesOptions.defaults());
        assertNotNull(cmd.filterComplex());
        assertTrue(cmd.filterComplex().contains("subtitles"), "应含 subtitles 滤镜: " + cmd.filterComplex());
        assertAdjacent(cmd.argv(), "-c:v", "libx264");
        assertAdjacent(cmd.argv(), "-c:a", "copy");
    }

    @Test
    void 烧字幕带forceStyle() {
        CompiledCommand cmd = FacadeSupport.buildBurnSubtitles(
                new File("v.mp4"), new File("s.srt"), new File("out.mp4"),
                BurnSubtitlesOptions.defaults().forceStyle("FontName=Arial,FontSize=24"));
        assertTrue(cmd.filterComplex().contains("force_style"), "应含 force_style: " + cmd.filterComplex());
    }

    @Test
    void 烧字幕音频用可选映射兼容无音轨视频() {
        CompiledCommand cmd = FacadeSupport.buildBurnSubtitles(
                new File("v.mp4"), new File("s.srt"), new File("out.mp4"),
                BurnSubtitlesOptions.defaults());
        assertTrue(cmd.argv().contains("0:a:0?"), "烧字幕音频应为可选映射（无音轨视频也能烧）: " + cmd.argv());
    }

    // ===== ContainerFamily 分类 =====

    @Test
    void 容器族按扩展名判定() {
        assertEquals(ContainerFamily.MP4_MOV, ContainerFamily.of("x.mp4"));
        assertEquals(ContainerFamily.MP4_MOV, ContainerFamily.of("/a/b/x.MOV"));
        assertEquals(ContainerFamily.MATROSKA, ContainerFamily.of("x.mkv"));
        assertEquals(ContainerFamily.MATROSKA, ContainerFamily.of("x.webm"));
        assertEquals(ContainerFamily.OTHER, ContainerFamily.of("x.avi"));
        assertTrue(ContainerFamily.isTextSubtitle("subrip"));
        assertTrue(ContainerFamily.isGraphicSubtitle("dvd_subtitle"));
        assertFalse(ContainerFamily.isTextSubtitle("hdmv_pgs_subtitle"));
    }

    // ===== 5b. gif（两遍调色板；编译器自动 split 菱形）=====

    @Test
    void gif默认链含fps与palettegen与paletteuse与split() {
        CompiledCommand cmd = FacadeSupport.buildGif(
                new File("in.mp4"), new File("out.gif"), GifOptions.defaults());
        String fc = cmd.filterComplex();
        assertNotNull(fc, "GIF 应有 filter_complex");
        assertTrue(fc.contains("fps=fps=15"), "默认 fps=15: " + fc);
        assertTrue(fc.contains("palettegen"), "应含 palettegen: " + fc);
        assertTrue(fc.contains("paletteuse"), "应含 paletteuse: " + fc);
        assertTrue(fc.contains("split=2"), "主流被 palettegen/paletteuse 两次消费应自动 split=2: " + fc);
    }

    @Test
    void gif未设width时链中无scale() {
        CompiledCommand cmd = FacadeSupport.buildGif(
                new File("in.mp4"), new File("out.gif"), GifOptions.defaults());
        assertFalse(cmd.filterComplex().contains("scale"), "未设 width 不应有 scale: " + cmd.filterComplex());
    }

    @Test
    void gif设width时含scale() {
        CompiledCommand cmd = FacadeSupport.buildGif(
                new File("in.mp4"), new File("out.gif"), GifOptions.defaults().fps(10).width(320));
        String fc = cmd.filterComplex();
        assertTrue(fc.contains("fps=fps=10"), "fps 应为 10: " + fc);
        assertTrue(fc.contains("scale=w=320:h=-1"), "设 width=320 应有 scale=w=320:h=-1: " + fc);
    }

    @Test
    void gif的ss与t均置输入侧() {
        CompiledCommand cmd = FacadeSupport.buildGif(
                new File("in.mp4"), new File("out.gif"), GifOptions.defaults().start(0.5).duration(1.0));
        List<String> argv = cmd.argv();
        assertAdjacent(argv, "-ss", "0.5");
        assertAdjacent(argv, "-t", "1");
        int iIdx = argv.indexOf("-i");
        assertTrue(argv.indexOf("-ss") < iIdx, "-ss 应在 -i 之前: " + argv);
        assertTrue(argv.indexOf("-t") < iIdx, "-t 应在 -i 之前（输入侧）: " + argv);
    }

    @Test
    void gif的paletteuse主视频输入在调色板之前() {
        // paletteuse 的两路输入顺序应为 [主视频分支][palette]；palette 由 palettegen 产出。
        CompiledCommand cmd = FacadeSupport.buildGif(
                new File("in.mp4"), new File("out.gif"), GifOptions.defaults());
        String fc = cmd.filterComplex();
        // 定位 palettegen 的输出 label，它应作为 paletteuse 的第二路输入。
        java.util.regex.Matcher pg = java.util.regex.Pattern.compile("palettegen\\[(\\w+)\\]").matcher(fc);
        assertTrue(pg.find(), "应能定位 palettegen 输出 label: " + fc);
        String paletteLabel = pg.group(1);
        java.util.regex.Matcher pu = java.util.regex.Pattern.compile("\\[(\\w+)\\]\\[(\\w+)\\]paletteuse").matcher(fc);
        assertTrue(pu.find(), "应能定位 paletteuse 两路输入: " + fc);
        assertEquals(paletteLabel, pu.group(2), "paletteuse 第二路输入应为 palette（palettegen 输出）: " + fc);
        assertNotEquals(paletteLabel, pu.group(1), "paletteuse 第一路输入应为主视频分支而非 palette: " + fc);
    }

    @Test
    void gif的scaleFlags显式设定才追加() {
        CompiledCommand plain = FacadeSupport.buildGif(
                new File("in.mp4"), new File("out.gif"), GifOptions.defaults().width(320));
        assertFalse(plain.filterComplex().contains("flags"), "默认不应有 flags: " + plain.filterComplex());
        CompiledCommand lanczos = FacadeSupport.buildGif(
                new File("in.mp4"), new File("out.gif"), GifOptions.defaults().width(320).scaleFlags("lanczos"));
        assertTrue(lanczos.filterComplex().contains("flags=lanczos"), "显式设 lanczos 应追加: " + lanczos.filterComplex());
    }

    // ===== extractAudio 的 -ar/-ac 与 copy 冲突 =====

    @Test
    void 抽音频采样率与声道映射为ar与ac() {
        CompiledCommand cmd = FacadeSupport.buildExtractAudio(
                new File("in.mp4"), new File("out.wav"), null,
                ExtractAudioOptions.defaults().sampleRate(16000).channels(1));
        assertAdjacent(cmd.argv(), "-ar", "16000");
        assertAdjacent(cmd.argv(), "-ac", "1");
    }

    @Test
    void 抽音频未设采样率时不含ar与ac() {
        CompiledCommand cmd = FacadeSupport.buildExtractAudio(
                new File("in.mp4"), new File("out.wav"), null, ExtractAudioOptions.defaults());
        assertFalse(cmd.argv().contains("-ar"), "默认不应含 -ar: " + cmd.argv());
        assertFalse(cmd.argv().contains("-ac"), "默认不应含 -ac: " + cmd.argv());
    }

    @Test
    void 抽音频重采样时对可copy源强制重编码() {
        // aac 源抽 m4a 本会 copy；设了 sampleRate 后须改用 aac 重编码（copy 会静默忽略 -ar）。
        ProbeResult aac = probe(5.0, audio(0, "aac"));
        CompiledCommand cmd = FacadeSupport.buildExtractAudio(
                new File("in.m4a"), new File("out.m4a"), aac,
                ExtractAudioOptions.defaults().sampleRate(16000));
        assertAdjacent(cmd.argv(), "-c:a", "aac");
        assertFalse(cmd.argv().contains("copy"), "重采样时不应 copy: " + cmd.argv());
        assertAdjacent(cmd.argv(), "-ar", "16000");
    }

    @Test
    void 抽音频未重采样时保持copy优化() {
        ProbeResult aac = probe(5.0, audio(0, "aac"));
        CompiledCommand cmd = FacadeSupport.buildExtractAudio(
                new File("in.m4a"), new File("out.m4a"), aac, ExtractAudioOptions.defaults());
        assertAdjacent(cmd.argv(), "-c:a", "copy");
    }

    // ===== thumbnail seekMode =====

    @Test
    void 抓帧默认输入侧快seek() {
        CompiledCommand cmd = FacadeSupport.buildThumbnail(
                new File("in.mp4"), new File("out.png"), 2.5, ThumbnailOptions.defaults());
        assertTrue(cmd.argv().indexOf("-ss") < cmd.argv().indexOf("-i"),
                "默认 INPUT_FAST：-ss 应在 -i 之前: " + cmd.argv());
    }

    @Test
    void 抓帧OUTPUT_ACCURATE时ss置输出侧() {
        CompiledCommand cmd = FacadeSupport.buildThumbnail(
                new File("in.mp4"), new File("out.png"), 2.5,
                ThumbnailOptions.defaults().seekMode(SeekMode.OUTPUT_ACCURATE));
        List<String> argv = cmd.argv();
        assertTrue(argv.indexOf("-ss") > argv.indexOf("-i"), "OUTPUT_ACCURATE：-ss 应在 -i 之后: " + argv);
        assertAdjacent(argv, "-ss", "2.5");
    }

    @Test
    void 抓帧OUTPUT_ACCURATE带缩放时ss与filterComplex共存() {
        CompiledCommand cmd = FacadeSupport.buildThumbnail(
                new File("in.mp4"), new File("out.png"), 1.0,
                ThumbnailOptions.defaults().width(160).seekMode(SeekMode.OUTPUT_ACCURATE));
        assertNotNull(cmd.filterComplex(), "带缩放应有 filter_complex");
        assertTrue(cmd.argv().indexOf("-ss") > cmd.argv().indexOf("-i"), "-ss 应在输出侧: " + cmd.argv());
    }

    // ===== 1b. transcode 流禁用 / 进阶码控 / VBV 派生（gap 3/4/5）=====

    @Test
    void 转码禁用视频产vn且只映射音频() {
        CompiledCommand cmd = FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.m4a"),
                TranscodeOptions.defaults().disableVideo(true).audioCodec("aac"));
        List<String> argv = cmd.argv();
        assertTrue(argv.contains("-vn"), "应含 -vn: " + argv);
        assertFalse(argv.contains("-c:v"), "不应含 -c:v: " + argv);
        assertAdjacent(argv, "-c:a", "aac");
        assertTrue(argv.contains("0:a:0?"), "应映射音频: " + argv);
        assertFalse(argv.contains("0:v:0?"), "不应映射视频: " + argv);
    }

    @Test
    void 转码禁用视频时静默跳过全部视频码控与x265Params() {
        CompiledCommand cmd = FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.m4a"),
                TranscodeOptions.defaults().disableVideo(true).audioCodec("aac")
                        .videoCodec("libx265").crf(20).preset("slow").videoBitrate("3M")
                        .fps(30).maxrate("2M").bufsize("4M").gop(50).x265Params("log-level=none"));
        List<String> argv = cmd.argv();
        assertTrue(argv.contains("-vn"), "应含 -vn: " + argv);
        for (String skipped : List.of("-c:v", "-crf", "-preset", "-b:v", "-r",
                "-maxrate", "-bufsize", "-g", "-keyint_min", "-sc_threshold", "-x265-params")) {
            assertFalse(argv.contains(skipped), "disableVideo 应静默跳过 " + skipped + ": " + argv);
        }
        assertAdjacent(argv, "-c:a", "aac");
    }

    @Test
    void 转码禁用音频产an且只映射视频() {
        CompiledCommand cmd = FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().disableAudio(true).videoCodec("libx264").audioSampleRate(44100));
        List<String> argv = cmd.argv();
        assertTrue(argv.contains("-an"), "应含 -an: " + argv);
        assertFalse(argv.contains("-c:a"), "不应含 -c:a: " + argv);
        assertFalse(argv.contains("-ar"), "disableAudio 时不应含 -ar: " + argv);
        assertAdjacent(argv, "-c:v", "libx264");
        assertTrue(argv.contains("0:v:0?"), "应映射视频: " + argv);
        assertFalse(argv.contains("0:a:0?"), "不应映射音频: " + argv);
    }

    @Test
    void 转码codec为null未禁用时build期抛错不污染argv() {
        assertThrows(FfmpegException.class, () -> FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"), TranscodeOptions.defaults().videoCodec(null)));
        assertThrows(FfmpegException.class, () -> FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"), TranscodeOptions.defaults().audioCodec(null)));
    }

    @Test
    void 转码同禁音视频或禁视频叠滤镜链build期抛错() {
        assertThrows(FfmpegException.class, () -> FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().disableVideo(true).disableAudio(true)));
        assertThrows(FfmpegException.class, () -> FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().disableVideo(true).videoFilter(v -> v)));
    }

    @Test
    void 转码音频采样率紧接ba() {
        List<String> argv = FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().audioCodec("aac").audioBitrate("128k").audioSampleRate(44100)).argv();
        assertAdjacent(argv, "-b:a", "128k");
        assertAdjacent(argv, "-ar", "44100");
        assertEquals(argv.indexOf("-b:a") + 2, argv.indexOf("-ar"), "-ar 应紧接 -b:a: " + argv);
    }

    @Test
    void 转码strict于码控段尾extraOutputArgs前且strictExperimental等价() {
        CompiledCommand cmd = FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().gop(50).strictExperimental().extraOutputArgs("-movflags", "+faststart"));
        List<String> argv = cmd.argv();
        assertAdjacent(argv, "-strict", "-2");
        assertTrue(argv.indexOf("-strict") > argv.indexOf("-sc_threshold"), "-strict 应在 GOP 段后: " + argv);
        assertTrue(argv.indexOf("-strict") < argv.indexOf("-movflags"), "-strict 应在 extraOutputArgs 前: " + argv);
        List<String> argv2 = FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().gop(50).strict("-2").extraOutputArgs("-movflags", "+faststart")).argv();
        assertEquals(argv, argv2, "strict(-2) 应与 strictExperimental() 逐字节相同");
    }

    @Test
    void 转码x265Params紧接视频码控段尾在ca之前() {
        List<String> argv = FacadeSupport.buildTranscode(
                new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().videoCodec("libx265").maxrate("2M").bufsize("4M")
                        .x265Params("vbv-maxrate=2000:vbv-bufsize=4000")).argv();
        assertAdjacent(argv, "-x265-params", "vbv-maxrate=2000:vbv-bufsize=4000");
        assertTrue(argv.indexOf("-x265-params") > argv.indexOf("-bufsize"), "-x265-params 应在 -bufsize 后: " + argv);
        assertTrue(argv.indexOf("-x265-params") < argv.indexOf("-c:a"), "-x265-params 应在 -c:a 前: " + argv);
    }

    @Test
    void 转码vbv派生bufsize为maxrate两倍且跟随最终maxrate() {
        assertAdjacent(FacadeSupport.buildTranscode(new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().vbv("2M")).argv(), "-bufsize", "4M");
        // 显式二参不派生
        assertAdjacent(FacadeSupport.buildTranscode(new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().vbv("2M", "6M")).argv(), "-bufsize", "6M");
        // build 期派生跟随最终 maxrate
        List<String> argv = FacadeSupport.buildTranscode(new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().vbv("2M").maxrate("3M")).argv();
        assertAdjacent(argv, "-maxrate", "3M");
        assertAdjacent(argv, "-bufsize", "6M");
        // 显式 bufsize 清除派生意图、build 期不被覆盖（防「派生条件逻辑取反」类 bug）
        assertAdjacent(FacadeSupport.buildTranscode(new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().vbv("2M").bufsize("5M")).argv(), "-bufsize", "5M");
    }

    @Test
    void 转码孤立bufsize保持既有行为不抛错也不产maxrate() {
        List<String> argv = FacadeSupport.buildTranscode(new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().bufsize("4M")).argv();
        assertAdjacent(argv, "-bufsize", "4M");
        assertFalse(argv.contains("-maxrate"), "孤立 bufsize 不应产 -maxrate: " + argv);
    }

    @Test
    void 转码裸maxrate不自动派生bufsize且默认不产新增旗标() {
        List<String> bare = FacadeSupport.buildTranscode(new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults().maxrate("2M")).argv();
        assertTrue(bare.contains("-maxrate"), "应含 -maxrate: " + bare);
        assertFalse(bare.contains("-bufsize"), "裸 maxrate 不应派生 bufsize: " + bare);
        List<String> def = FacadeSupport.buildTranscode(new File("in.mp4"), new File("out.mp4"),
                TranscodeOptions.defaults()).argv();
        assertFalse(def.contains("-vn") || def.contains("-an") || def.contains("-ar")
                || def.contains("-strict") || def.contains("-x265-params"), "默认不产新增旗标: " + def);
    }

    @Test
    void doubleRate翻倍保留单位() {
        assertEquals("4M", FacadeSupport.doubleRate("2M"));
        assertEquals("4000k", FacadeSupport.doubleRate("2000k"));
        assertEquals("6000000", FacadeSupport.doubleRate("3000000"));
        assertEquals("5M", FacadeSupport.doubleRate("2.5M"));
        assertThrows(IllegalArgumentException.class, () -> FacadeSupport.doubleRate(""));
        assertThrows(IllegalArgumentException.class, () -> FacadeSupport.doubleRate("abc"));
        assertThrows(IllegalArgumentException.class, () -> FacadeSupport.doubleRate("2M!"));
    }

    // ===== helpers =====

    private static void assertAdjacent(List<String> argv, String a, String b) {
        for (int i = 0; i + 1 < argv.size(); i++) {
            if (argv.get(i).equals(a) && argv.get(i + 1).equals(b)) {
                return;
            }
        }
        fail("未找到相邻的 [" + a + " " + b + "]，实际 argv=" + argv);
    }

    private static ProbeResult probe(double durationSec, StreamInfo... streams) {
        return new ProbeResult(
                new FormatInfo("test", "test", durationSec, -1, -1, streams.length),
                List.of(streams));
    }

    private static StreamInfo video(int index, int w, int h, String frameRate) {
        return new StreamInfo(index, MediaType.VIDEO, "h264", null, w, h, frameRate, frameRate, null, null);
    }

    private static StreamInfo audio(int index, String codec) {
        return new StreamInfo(index, MediaType.AUDIO, codec, null, null, null, null, null, 48000, 2);
    }

    private static StreamInfo subtitle(int index, String codec) {
        return new StreamInfo(index, MediaType.SUBTITLE, codec, null, null, null, null, null, null, null);
    }
}
