package io.github.pandong2015.ffmpeg4j.docs;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.pandong2015.ffmpeg4j.compiler.CompiledCommand;
import io.github.pandong2015.ffmpeg4j.compiler.GraphCompiler;
import io.github.pandong2015.ffmpeg4j.engine.CancelMode;
import io.github.pandong2015.ffmpeg4j.engine.FfmpegExecutor;
import io.github.pandong2015.ffmpeg4j.engine.FfmpegRun;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;
import io.github.pandong2015.ffmpeg4j.engine.RunResult;
import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;
import io.github.pandong2015.ffmpeg4j.model.AudioStream;
import io.github.pandong2015.ffmpeg4j.model.Filters;
import io.github.pandong2015.ffmpeg4j.model.Input;
import io.github.pandong2015.ffmpeg4j.model.Output;
import io.github.pandong2015.ffmpeg4j.model.VideoStream;

/**
 * README 低层「流即值」示例的防漂移守卫：README 里的每个低层组合示例都在此有一份等价代码，
 * 一旦公共 API 签名变动导致这些示例编译不过，本测试会在 CI 立刻变红——「能编译的错误示例比
 * 没有更糟」，故用编译期锁定。构图（{@link GraphCompiler#compile}）不触发任何子进程，可离线运行；
 * 仅进度回调 + 取消一节需真实 ffmpeg，缺失时 {@code assumeTrue} 跳过。
 */
class ReadmeExamplesSmokeTest {

    @Test
    void overlay叠加logo全链构图() {
        Input video = Input.of(new File("in.mp4"));
        Input logo = Input.of(new File("logo.png"));
        VideoStream base = video.video();
        VideoStream mark = logo.video();
        VideoStream composed = Filters.overlay(base, mark, "W-w-10", "H-h-10");
        Output output = Output.to(new File("out.mp4"), composed, video.audio())
                .withArgs("-c:v", "libx264", "-c:a", "copy");

        CompiledCommand cmd = new GraphCompiler().compile(output);
        assertNotNull(cmd.filterComplex());
        assertTrue(cmd.filterComplex().contains("overlay"), cmd.filterComplex());
    }

    @Test
    void drawText打字幕构图() {
        Input in = Input.of(new File("in.mp4"));
        // fontFile 传 null：由 ffmpeg 用默认字体（真机需 --enable-libfreetype，构图阶段不校验）。
        VideoStream v = Filters.drawText(in.video(), "ffmpeg4j", null, 24, "white", "(w-tw)/2", "h-th-20");
        Output output = Output.to(new File("out.mp4"), v, in.audio())
                .withArgs("-c:v", "libx264", "-c:a", "copy");

        CompiledCommand cmd = new GraphCompiler().compile(output);
        assertTrue(cmd.filterComplex().contains("drawtext"), cmd.filterComplex());
    }

    @Test
    void burnSubtitles烧录构图() {
        Input in = Input.of(new File("in.mp4"));
        VideoStream v = Filters.burnSubtitles(in.video(), Path.of("sub.srt"));
        Output output = Output.to(new File("out.mp4"), v, in.audioOptional())
                .withArgs("-c:v", "libx264", "-c:a", "copy");

        CompiledCommand cmd = new GraphCompiler().compile(output);
        assertTrue(cmd.filterComplex().contains("subtitles"), cmd.filterComplex());
    }

    @Test
    void 扇出同一流消费两次自动split() {
        Input in = Input.of(new File("in.mp4"));
        VideoStream main = in.video();
        VideoStream pip = Filters.scale(main, 320, 180);                  // 第一次消费 main
        VideoStream out = Filters.overlay(main, pip, "W-w-10", "H-h-10"); // 再次消费 main → 触发 split
        Output output = Output.to(new File("pip.mp4"), out);

        CompiledCommand cmd = new GraphCompiler().compile(output);
        assertTrue(cmd.filterComplex().contains("split"),
                "同一流被消费两次应自动插入 split: " + cmd.filterComplex());
    }

    @Test
    void 多输出单次调用() {
        Input in = Input.of(new File("in.mp4"));
        VideoStream v = in.video();
        VideoStream thumb = Filters.scale(v, 320, -1);                    // 缩略图分支
        Output full = Output.to(new File("full.mp4"), v, in.audio())
                .withArgs("-c:v", "libx264", "-c:a", "copy");
        Output small = Output.to(new File("thumb.mp4"), thumb).withArgs("-c:v", "libx264");

        CompiledCommand cmd = new GraphCompiler().compile(List.of(full, small));
        assertTrue(cmd.argv().contains("full.mp4"));
        assertTrue(cmd.argv().contains("thumb.mp4"));
    }

    @Test
    void 逃生舱rawFilter与位置感知原始参数() {
        Input in = Input.of(new File("in.mp4")).withInputArgs("-ss", "10");     // 输入侧原始参数
        VideoStream v = Filters.rawFilterVideo(in.video(), "hqdn3d");           // 未建模视频滤镜
        AudioStream a = Filters.rawFilterAudio(in.audio(), "loudnorm");         // 未建模音频滤镜
        Output output = Output.to(new File("out.mp4"), v, a)
                .withArgs("-movflags", "+faststart");                           // 输出侧原始参数

        CompiledCommand cmd = new GraphCompiler().compile(output);
        assertTrue(cmd.filterComplex().contains("hqdn3d"), cmd.filterComplex());
        assertTrue(cmd.filterComplex().contains("loudnorm"), cmd.filterComplex());
        assertTrue(cmd.argv().contains("-movflags"));
        // 输入侧 -ss 位于 -i 之前
        assertTrue(cmd.argv().indexOf("-ss") < cmd.argv().indexOf("-i"));
    }

    @Test
    void 进度回调与取消(@TempDir Path tmp) throws Exception {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg 不可用，跳过进度/取消示例");

        // 用 lavfi 造一个较长、慢编码的任务，便于在中途取消。
        Input in = Input.of("testsrc=duration=30:size=640x480:rate=25").withInputArgs("-f", "lavfi");
        Output out = Output.to(tmp.resolve("async.mp4").toString(), in.video())
                .withArgs("-c:v", "libx264", "-preset", "veryslow");
        CompiledCommand cmd = new GraphCompiler().compile(out);

        FfmpegExecutor exec = new FfmpegExecutor(FfmpegEnvironment.shared());
        AtomicInteger ticks = new AtomicInteger();
        RunOptions opts = RunOptions.defaults()
                .onProgress(p -> ticks.incrementAndGet())          // 进度回调
                .callbackExecutor(Executors.newSingleThreadExecutor()); // 回调移出 pump 线程

        FfmpegRun run = exec.runAsync(cmd, opts);
        Thread.sleep(400);
        run.cancel();                    // 等价于 cancel(CancelMode.GRACEFUL)
        RunResult r = run.await();       // 取消后仍返回 RunResult（不抛异常）
        assertNotNull(r, "优雅取消后应返回结果");

        // 另演示强制取消（FORCE 跳过优雅收尾）——第二个短任务立即取消。
        FfmpegRun run2 = exec.runAsync(cmd, RunOptions.defaults());
        run2.cancel(CancelMode.FORCE);
        assertNotNull(run2.await());
    }

    private static boolean commandExists(String command) {
        try {
            Process p = new ProcessBuilder(command, "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
