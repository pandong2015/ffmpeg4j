package io.github.pandong2015.ffmpeg4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.pandong2015.ffmpeg4j.model.FilterNode.Arg;

/**
 * {@link Filters} curated 滤镜工厂的纯值层单元测试（提升 JaCoCo 覆盖）。
 *
 * <p>只断言产物图节点的滤镜名、参数、{@code renderBody}、转义标记与媒体类型等纯值，
 * 不启动 ffmpeg。聚焦既有 {@code StreamModelTest}/{@code GraphCompilerTest} 未覆盖的
 * 滤镜与分支：{@code crop(4 参)}/{@code pad}/{@code overlay(int)}/{@code fade}/带字体的
 * {@code drawText}/{@code amix}/{@code atrim}/{@code concatAudio}/{@code concatVideo}/
 * {@code burnSubtitles(forceStyle)}/{@code burnAss}/{@code atempo} 慢速拆链等。
 */
class FiltersCoverageTest {

    private final Input in = Input.of("in.mp4");

    /** 从流的来源取出其 {@link FilterNode}（断言来源确为滤镜输出）。 */
    private static FilterNode nodeOf(Stream s) {
        Origin o = s.origin();
        Origin.FilterOrigin fo = assertInstanceOf(Origin.FilterOrigin.class, o, "产物来源应为 FilterOrigin");
        return fo.node();
    }

    // ===== 视频滤镜 =====

    @Test
    void crop四参裁剪产出带xy的crop节点() {
        VideoStream out = Filters.crop(in.video(), 640, 360, 10, 20);
        FilterNode n = nodeOf(out);
        assertEquals("crop", n.filter());
        assertEquals("crop=w=640:h=360:x=10:y=20", n.renderBody());
        assertEquals(List.of(MediaType.VIDEO), n.outputTypes());
        assertEquals(MediaType.VIDEO, out.mediaType());
        assertEquals(1, n.inputs().size());
    }

    @Test
    void pad加边节点含color且xy为表达式() {
        VideoStream out = Filters.pad(in.video(), 1920, 1080, "(ow-iw)/2", "(oh-ih)/2", "black");
        FilterNode n = nodeOf(out);
        assertEquals("pad", n.filter());
        assertEquals("pad=w=1920:h=1080:x=(ow-iw)/2:y=(oh-ih)/2:color=black", n.renderBody());
    }

    @Test
    void overlay整型重载委托为字符串并保留双输入() {
        VideoStream base = in.video();
        VideoStream over = Input.of("logo.png").video();
        FilterNode n = nodeOf(Filters.overlay(base, over, 10, 20));
        assertEquals("overlay", n.filter());
        assertEquals("overlay=x=10:y=20", n.renderBody());
        // overlay 的两路输入顺序有语义：base 在前、over 在后
        assertEquals(2, n.inputs().size());
        assertEquals(MediaType.VIDEO, n.inputs().get(0).mediaType());
        assertEquals(MediaType.VIDEO, n.inputs().get(1).mediaType());
    }

    @Test
    void padToEven补齐偶数尺寸仅含wh() {
        VideoStream out = Filters.padToEven(in.video());
        FilterNode n = nodeOf(out);
        assertEquals("pad", n.filter());
        assertEquals("pad=w=ceil(iw/2)*2:h=ceil(ih/2)*2", n.renderBody());
        assertEquals(1, n.inputs().size());
    }

    @Test
    void pad表达式重载wh逐字下发不转义() {
        VideoStream out = Filters.pad(in.video(), "ceil(iw/2)*2", "ceil(ih/2)*2",
                "(ow-iw)/2", "(oh-ih)/2", "black");
        FilterNode n = nodeOf(out);
        assertEquals("pad=w=ceil(iw/2)*2:h=ceil(ih/2)*2:x=(ow-iw)/2:y=(oh-ih)/2:color=black", n.renderBody());
        assertTrue(!n.args().get(0).escape(), "表达式经 Arg.of 不应转义");
    }

    @Test
    void overlay的shortest重载追加shortest1() {
        VideoStream base = in.video();
        VideoStream over = Input.of("logo.png").video();
        FilterNode n = nodeOf(Filters.overlay(base, over, "W-w-6", "H-h-6", true));
        assertEquals("overlay=x=W-w-6:y=H-h-6:shortest=1", n.renderBody());
        assertEquals(2, n.inputs().size());
    }

    @Test
    void overlay的shortest为false时不追加() {
        FilterNode n = nodeOf(Filters.overlay(in.video(), Input.of("logo.png").video(), "0", "0", false));
        assertEquals("overlay=x=0:y=0", n.renderBody());
    }

    @Test
    void rawFilterVideo双输入接两路且body逐字() {
        VideoStream base = in.video();
        VideoStream wm = Input.of("wm.png").video();
        String raw = "overlay=shortest=1:x=if(eq(mod(n\\,200)\\,0)\\,20\\,x)";
        FilterNode n = nodeOf(Filters.rawFilterVideo(base, wm, raw));
        assertEquals(raw, n.renderBody(), "2 输入逃生舱 body 应逐字");
        assertEquals(2, n.inputs().size());
        assertEquals(List.of(MediaType.VIDEO), n.outputTypes());
    }

    @Test
    void rawFilterVideo单输入回归不变() {
        FilterNode n = nodeOf(Filters.rawFilterVideo(in.video(), "pad=ceil(iw/2)*2:ceil(ih/2)*2"));
        assertEquals("pad=ceil(iw/2)*2:ceil(ih/2)*2", n.renderBody());
        assertEquals(1, n.inputs().size());
    }

    @Test
    void fade视频淡出节点且小数start_time按小数渲染() {
        // 5.5 走 num 的非整数分支渲染为 "5.5"；duration 2 走整数分支渲染为 "2"
        FilterNode n = nodeOf(Filters.fade(in.video(), "out", 5.5, 2));
        assertEquals("fade", n.filter());
        assertEquals("fade=t=out:start_time=5.5:duration=2", n.renderBody());
    }

    @Test
    void drawText传入fontFile时首参为转义fontfile() {
        VideoStream out = Filters.drawText(in.video(), "hi", Path.of("/fonts/DejaVu.ttf"),
                24, "white", "10", "20");
        FilterNode n = nodeOf(out);
        assertEquals("drawtext", n.filter());
        // 有 fontFile 时首参为 fontfile，且被标记为需转义
        Arg first = n.args().get(0);
        assertEquals("fontfile", first.key());
        assertEquals("/fonts/DejaVu.ttf", first.value());
        assertTrue(first.escape(), "fontfile 应标记为需转义");
        // text 亦需转义
        Arg textArg = n.args().get(1);
        assertEquals("text", textArg.key());
        assertTrue(textArg.escape(), "text 应标记为需转义");
        assertEquals("drawtext=fontfile=/fonts/DejaVu.ttf:text=hi:fontsize=24:fontcolor=white:x=10:y=20",
                n.renderBody());
    }

    // ===== 音频滤镜 =====

    @Test
    void amix多路混音节点inputs计数与音频输出() {
        AudioStream a1 = in.audio();
        AudioStream a2 = Input.of("b.mp3").audio();
        AudioStream a3 = Input.of("c.mp3").audio();
        AudioStream out = Filters.amix(List.of(a1, a2, a3));
        FilterNode n = nodeOf(out);
        assertEquals("amix", n.filter());
        assertEquals("amix=inputs=3", n.renderBody());
        assertEquals(3, n.inputs().size());
        assertEquals(List.of(MediaType.AUDIO), n.outputTypes());
        assertEquals(MediaType.AUDIO, out.mediaType());
    }

    @Test
    void atrim自动追加asetpts且内层为atrim() {
        AudioStream out = Filters.atrim(in.audio(), 1, 4);
        // 外层是自动追加的 asetpts=PTS-STARTPTS
        FilterNode outer = nodeOf(out);
        assertEquals("asetpts", outer.filter());
        assertEquals("asetpts=PTS-STARTPTS", outer.renderBody());
        assertEquals(MediaType.AUDIO, out.mediaType());
        // 内层为 atrim=start=1:end=4
        FilterNode inner = nodeOf(outer.inputs().get(0));
        assertEquals("atrim", inner.filter());
        assertEquals("atrim=start=1:end=4", inner.renderBody());
    }

    @Test
    void atempo慢速小于0点5拆链() {
        // 0.25 -> 拆成 atempo=0.5 -> atempo=0.5 链，覆盖 decomposeAtempo 的 r<0.5 分支
        AudioStream out = Filters.atempo(in.audio(), 0.25);
        FilterNode outer = nodeOf(out);
        assertEquals("atempo", outer.filter());
        assertEquals("atempo=tempo=0.5", outer.renderBody());
        FilterNode inner = nodeOf(outer.inputs().get(0));
        assertEquals("atempo=tempo=0.5", inner.renderBody());
    }

    // ===== 字幕烧录族 =====

    @Test
    void burnSubtitles带forceStyle的三参重载() {
        VideoStream out = Filters.burnSubtitles(in.video(), Path.of("subs.srt"), "FontSize=24");
        FilterNode n = nodeOf(out);
        assertEquals("subtitles", n.filter());
        assertEquals("subtitles=filename=subs.srt:force_style=FontSize=24", n.renderBody());
        // filename 需转义、force_style 不转义
        assertTrue(n.args().get(0).escape(), "filename 应标记为需转义");
        assertEquals("force_style", n.args().get(1).key());
        assertTrue(!n.args().get(1).escape(), "force_style 不应转义");
    }

    @Test
    void burnAss使用ass滤镜且文件名转义() {
        VideoStream out = Filters.burnAss(in.video(), Path.of("subs.ass"));
        FilterNode n = nodeOf(out);
        assertEquals("ass", n.filter());
        assertEquals("ass=filename=subs.ass", n.renderBody());
        assertTrue(n.args().get(0).escape(), "filename 应标记为需转义");
    }

    // ===== 纯视频/纯音频 concat =====

    @Test
    void concatAudio纯音频拼接节点() {
        AudioStream out = Filters.concatAudio(List.of(in.audio(), Input.of("b.mp3").audio()));
        FilterNode n = nodeOf(out);
        assertEquals("concat", n.filter());
        assertEquals("concat=n=2:v=0:a=1", n.renderBody());
        assertEquals(List.of(MediaType.AUDIO), n.outputTypes());
        assertEquals(MediaType.AUDIO, out.mediaType());
    }

    @Test
    void concatVideo纯视频拼接节点() {
        VideoStream out = Filters.concatVideo(List.of(in.video(), Input.of("b.mp4").video()));
        FilterNode n = nodeOf(out);
        assertEquals("concat", n.filter());
        assertEquals("concat=n=2:v=1:a=0", n.renderBody());
        assertEquals(List.of(MediaType.VIDEO), n.outputTypes());
        assertEquals(MediaType.VIDEO, out.mediaType());
    }

    // ===== 内部辅助 decomposeAtempo（包内可见）=====

    @Test
    void decomposeAtempo各分支精确返回() {
        // 范围内：原样单元素
        assertEquals(List.of(1.5), Filters.decomposeAtempo(1.5));
        // 大于 2.0：反复折半，末位为余数（4.0 -> 2,2；8.0 -> 2,2,2）
        assertEquals(List.of(2.0, 2.0), Filters.decomposeAtempo(4.0));
        assertEquals(List.of(2.0, 2.0, 2.0), Filters.decomposeAtempo(8.0));
        // 小于 0.5：反复减半上翻，末位为余数（0.25 -> 0.5,0.5）
        assertEquals(List.of(0.5, 0.5), Filters.decomposeAtempo(0.25));
    }
}
