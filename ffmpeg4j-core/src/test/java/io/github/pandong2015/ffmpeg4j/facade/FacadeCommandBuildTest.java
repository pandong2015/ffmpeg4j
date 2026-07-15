package io.github.pandong2015.ffmpeg4j.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.pandong2015.ffmpeg4j.FfmpegException;
import io.github.pandong2015.ffmpeg4j.compiler.CompiledCommand;
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
