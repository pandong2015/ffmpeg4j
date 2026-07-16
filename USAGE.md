# ffmpeg4j 使用说明

一份从「装依赖」到「Spring Boot 集成」的完整上手指南。术语与示例均对齐当前实现（`1.5.0`，已发布至 Maven Central）。

> 速览：普通 Java 项目引 **`ffmpeg4j-core`**，用静态门面 `Ffmpeg.xxx(...)` 一行式完成常见任务；
> Spring Boot 项目引 **`ffmpeg4j-spring-boot-starter`**，注入 `FfmpegClient` bean、配置走 `application.yml`。

---

## 目录

1. [这是什么](#1-这是什么)
2. [前置条件](#2-前置条件)
3. [安装](#3-安装)
4. [30 秒上手](#4-30-秒上手)
5. [十一个门面](#5-十一个门面)
6. [进阶选项 XxxOptions](#6-进阶选项-xxxoptions)
7. [异步与取消](#7-异步与取消)
8. [probe：结构化元数据](#8-probe结构化元数据)
9. [进度回调](#9-进度回调)
10. [低层「流即值」组合](#10-低层流即值组合)
11. [逃生舱](#11-逃生舱)
12. [错误处理](#12-错误处理)
13. [Spring Boot 集成](#13-spring-boot-集成)
14. [二进制发现与配置](#14-二进制发现与配置)
15. [常见任务速查](#15-常见任务速查)
16. [已知约束](#16-已知约束)

---

## 1. 这是什么

`ffmpeg4j` 是一个**纯 Java** 视频处理库，封装目标机上**预装的 `ffmpeg` / `ffprobe` 命令行二进制**
（路线 A：无 JNI、不链接 `libav*`）。它把不可变的「流即值」引用图**编译成 ffmpeg 命令行**，再稳健地执行子进程——
你永远不必手写 `[0:v]` / pad 名。

- **core（`ffmpeg4j-core`）**：零重型运行时依赖，可脱离任何框架使用。
- **starter（`ffmpeg4j-spring-boot-starter`）**：Spring Boot 3.x 自动装配、外部化配置、Actuator/Micrometer 观测。

---

## 2. 前置条件

- **JDK 17+**（库以 Java 17 编译）。
- 目标机 **PATH 上有 `ffmpeg` 与 `ffprobe`**，或通过配置显式指定路径（见 [§14](#14-二进制发现与配置)）。
  - 建议版本 **≥ 4.2**；低于则**仅告警不硬失败**（真实特性下限更低）。仅二进制缺失才硬错。
- 用到烧字幕 / 打字幕时，ffmpeg 需带 **`--enable-libass` / `--enable-libfreetype`** 构建开关
  （缺失时库会在下发命令前**提前抛可诊断异常**，而非放任 ffmpeg 运行期报 `No such filter`）。

验证环境：

```bash
ffmpeg -version
ffprobe -version
```

---

## 3. 安装

已发布到 **Maven Central**（groupId `io.github.pandong2015`），**无需额外仓库配置**。当前版本 **`1.5.0`**。

### 普通 Java 项目

**Maven**
```xml
<dependency>
    <groupId>io.github.pandong2015</groupId>
    <artifactId>ffmpeg4j-core</artifactId>
    <version>1.5.0</version>
</dependency>
```

**Gradle**（Kotlin DSL）
```kotlin
implementation("io.github.pandong2015:ffmpeg4j-core:1.5.0")
```

### Spring Boot 项目

**Maven**
```xml
<dependency>
    <groupId>io.github.pandong2015</groupId>
    <artifactId>ffmpeg4j-spring-boot-starter</artifactId>
    <version>1.5.0</version>
</dependency>
```

**Gradle**（Kotlin DSL）
```kotlin
implementation("io.github.pandong2015:ffmpeg4j-spring-boot-starter:1.5.0")
```

> starter 传递引入 `ffmpeg4j-core`，无需再单独声明。可观测依赖（actuator/micrometer）为可选，见 [§13](#13-spring-boot-集成)。
> Maven Central 页面：<https://central.sonatype.com/artifact/io.github.pandong2015/ffmpeg4j-core>

---

## 4. 30 秒上手

静态门面 `Ffmpeg` 把最常见的整段任务收敛为一行式：

```java
import io.github.pandong2015.ffmpeg4j.facade.Ffmpeg;
import java.io.File;

// 转码：视频 H.264、音频 AAC
Ffmpeg.transcode(new File("in.mov"), new File("out.mp4"), "libx264", "aac");

// 换容器（不重编码）
Ffmpeg.remux(new File("in.mkv"), new File("out.mp4"));

// 截取 10s~25s
Ffmpeg.clip(new File("in.mp4"), new File("clip.mp4"), 10.0, 25.0);

// 抽音频
Ffmpeg.extractAudio(new File("in.mp4"), new File("audio.m4a"));

// 第 5 秒抓一帧
Ffmpeg.thumbnail(new File("in.mp4"), new File("thumb.jpg"), 5.0);

// 生成 GIF（两遍调色板法，默认 fps=15）
Ffmpeg.gif(new File("in.mp4"), new File("out.gif"));

// 探测元数据
double seconds = Ffmpeg.probe(new File("in.mp4")).durationSeconds();
```

> 每个门面返回 `RunResult`（含 `exitCode()`、最后进度、实际命令）；失败抛 `FfmpegException`（见 [§12](#12-错误处理)）。

---

## 5. 十一个门面

（十个动作门面 + `probe`。）

| 门面 | 便捷签名 | 说明 |
|------|----------|------|
| `transcode` | `transcode(in, out, videoCodec, audioCodec)` | 强制转码 |
| `remux` | `remux(in, out)` | 换容器，尽量不重编码；文本字幕转 `mov_text`、图形字幕丢弃 |
| `clip` | `clip(in, out, startSec, endSec)` | 无歧义 `-ss start -t (end-start)` 截取 |
| `extractAudio` | `extractAudio(in, out)` | 按扩展名推导是否重编码，`-map 0:a:0` 取首音轨避开封面图；可选 `-ar`/`-ac` |
| `thumbnail` | `thumbnail(in, out, atSec)` | 指定时间点抓帧；`seekMode` 可选输入侧快 seek / 输出侧精确 |
| `gif` | `gif(in, out)` | 两遍调色板法生成 GIF（编译器自动 `split` 菱形） |
| `concat` | `concat(List<File> ins, out)` | 前置归一化（含 setsar）+ 异构流集合注入静音/纯色或可诊断拒绝 |
| `burnSubtitles` | `burnSubtitles(video, subtitle, out)` | 硬字幕烧录（需 libass） |
| `hlsSegment` | `hlsSegment(in, outDir) → HlsResult` | 单码率 VOD HLS 切片（可选 AES-128）；产 `outDir/index.m3u8` + `ts/*.ts`（+ `key/enc.key`）。默认 `-c copy`；`alignKeyframes` 转码对齐段边界 |
| `hlsAbr` | `hlsAbr(in, outDir) → HlsAbrResult` | ABR 多码率梯 VOD（可选 AES-128）；一入 N 档产 `outDir/master.m3u8` + 每档 `<目录>/index.m3u8`+段。**恒转码 + 恒跨档关键帧对齐**；默认梯按源高度裁剪；agroup 共享单音轨 |
| `probe` | `probe(in) → ProbeResult` | 结构化元数据（无 Options 重载） |

```java
// HLS 单码率 VOD 切片（直拷，快）
HlsResult r = Ffmpeg.hlsSegment(new File("in.mp4"), new File("out"));
// → out/index.m3u8 + out/ts/index0.ts...；r.segments() 为有序段路径

// 带 AES-128（B2：调用方持密钥；key URI 明文进 m3u8，勿内嵌凭证）
Ffmpeg.hlsSegment(new File("in.mp4"), new File("out"),
        HlsOptions.defaults().key(HlsKey.of(keyBytes16, "https://keys.example/s.key")));
// 或 B1 便利：HlsKey.random("https://keys.example/s.key")（SecureRandom 16 字节，字节可读回）

// 均匀段（转码 + 关键帧对齐）；通用按秒关键帧亦可用于 transcode：
Ffmpeg.hlsSegment(new File("in.mp4"), new File("out"),
        HlsOptions.defaults().videoCodec("libx264").hlsTime(6.0).alignKeyframes(true));

// HLS ABR 多码率梯（恒转码）：默认梯按源高度裁剪，产 master.m3u8 + 各档目录
HlsAbrResult abr = Ffmpeg.hlsAbr(new File("in.mp4"), new File("out"));
// → out/master.m3u8 + out/0|1|2/index.m3u8+段（+ agroup 时 out/audio/*）；abr.variants() 为各档结果
// 分派建议：只要一档且直拷 → 用 hlsSegment（copy 快、无 master）；要转码 + master → 用 hlsAbr（即便 N=1）

// 自定义梯 + AES-128（复用单码率 HlsKey；key/ 务必排除在 CDN 托管根之外）
Ffmpeg.hlsAbr(new File("in.mp4"), new File("out"), HlsAbrOptions.defaults()
        .variants(List.of(HlsVariant.of(1080, "5000k"), HlsVariant.of(720, "3000k").width(1280)))
        .hlsTime(6.0)
        .key(HlsKey.of(keyBytes16, "https://keys.example/abr.key")));
// 注：ABR 省略 IV 时 ffmpeg 产 IV=0x00…00 跨段复用（异于单码率的段序号派生 IV）——单密钥模型，VOD 风险低

// 拼接多段
Ffmpeg.concat(List.of(new File("a.mp4"), new File("b.mp4")), new File("joined.mp4"));

// 烧录字幕（需 ffmpeg 带 --enable-libass）
Ffmpeg.burnSubtitles(new File("video.mp4"), new File("subs.srt"), new File("hardsub.mp4"));

// GIF：截取 0~3s、320 宽、10fps
Ffmpeg.gif(new File("in.mp4"), new File("out.gif"),
        GifOptions.defaults().start(0).duration(3).fps(10).width(320));

// 抽音频为 16k 单声道 WAV（ASR 前置；自动禁用 copy 以真正重采样）
Ffmpeg.extractAudio(new File("in.mp4"), new File("asr.wav"),
        ExtractAudioOptions.defaults().sampleRate(16000).channels(1));

// 精确时间点抓帧（输出侧 seek）
Ffmpeg.thumbnail(new File("in.mp4"), new File("frame.png"), 12.5,
        ThumbnailOptions.defaults().seekMode(SeekMode.OUTPUT_ACCURATE));
```

---

## 6. 进阶选项 XxxOptions

每个门面（`probe` 除外）都有「便捷重载 + `XxxOptions` 进阶重载」。`XxxOptions` 是**不可变、wither 风格**：
`xxx(value)` 返回带该改动的新副本，同名 `xxx()` 为只读访问器。

```java
import io.github.pandong2015.ffmpeg4j.facade.TranscodeOptions;
import java.time.Duration;

TranscodeOptions opts = TranscodeOptions.defaults()
        .videoCodec("libx265")
        .audioCodec("aac")
        .crf(23)
        .preset("slow")
        .timeout(Duration.ofMinutes(30))
        .onProgress(p -> System.out.println(p.raw()));

Ffmpeg.transcode(new File("in.mp4"), new File("out.mp4"), opts);
```

**转码接滤镜链 + 码控（type1 风格：缩放补偶 + 右下角水印 + VBV + GOP）**——`videoFilter` 是单输入滤镜链入口，
函数内可自建水印图输入并叠加，编译器自动补第二路 `-i`；类型化码控 `fps`/`maxrate`/`bufsize`/`gop` + `extraOutputArgs` 逃生舱：

```java
import io.github.pandong2015.ffmpeg4j.model.Filters;
import io.github.pandong2015.ffmpeg4j.model.Input;

File logo = new File("/app/watermarks/logo.png");
TranscodeOptions t1 = TranscodeOptions.defaults()
        .videoCodec("libx264").fps(25).maxrate("2M").bufsize("4M").gop(50)   // gop=帧数（下游算 fps*秒）
        .videoFilter(v -> Filters.overlay(
                Filters.padToEven(Filters.scale(v, 1280, -1)),               // 缩放后补偶
                Input.of(logo).withInputArgs("-loop", "1").video(),          // 水印图（循环）
                "W-w-6", "H-h-6", true));                                    // 右下角、shortest 收尾
Ffmpeg.transcode(new File("in.mp4"), new File("out.mp4"), t1);

// libx265 的 VBV：typed x265Params（库不自动翻译 maxrate→x265-params，显式 x265 通道）：
TranscodeOptions h265 = TranscodeOptions.defaults().videoCodec("libx265")
        .x265Params("vbv-maxrate=2000:vbv-bufsize=4000");

// VBV 便利：vbv(maxrate) 自动派生 bufsize=maxrate×2（build 期依最终 maxrate 求值）：
TranscodeOptions vbv = TranscodeOptions.defaults().vbv("2M");           // → -maxrate 2M -bufsize 4M

// 音频重采样 -ar / 实验编码器 -strict：
TranscodeOptions au = TranscodeOptions.defaults()
        .audioCodec("aac").audioSampleRate(44100).strictExperimental();  // → -ar 44100 -strict -2

// 纯视频（-an）；纯音频抽取请首选 Ffmpeg.extractAudio，disableVideo 仅用于从含视频源转码剥离视频：
TranscodeOptions videoOnly = TranscodeOptions.defaults().disableAudio(true).videoCodec("libx264");
```

> 复杂多输入 overlay 表达式（动/浮水印的 `if/mod/sin`，含转义逗号 `\,`）走 `Filters.rawFilterVideo(base, over, raw)`
> 逐字下发（转义自负）。**7 种 watermarkType 的具体表达式属下游业务规则，不在 ffmpeg4j-core**——core 提供通用底座
> （overlay shortest / 2 输入逃生舱 / `-loop` 输入 / pad 表达式），下游据此组合。

各门面对应 `TranscodeOptions` / `RemuxOptions` / `ClipOptions` / `ExtractAudioOptions` / `ThumbnailOptions` /
`GifOptions` / `ConcatOptions` / `BurnSubtitlesOptions`，均含各自的特定项与执行侧的 `onProgress` / `timeout`。
特定项举例：`TranscodeOptions.videoFilter/fps/maxrate/bufsize/gop/vbv/x265Params/audioSampleRate/strict(Experimental)/disableVideo/disableAudio/extraOutputArgs`（滤镜链 + 码控 + 流禁用）、
`ExtractAudioOptions.sampleRate/channels`（`-ar`/`-ac`，设定即禁用 copy）、
`ThumbnailOptions.seekMode`（`INPUT_FAST` 默认 / `OUTPUT_ACCURATE` 精确）、
`GifOptions.start/duration/fps/width/height/scaleFlags`。

---

## 7. 异步与取消

需要不阻塞调用线程时，用可实例化门面 `FfmpegClient` 的 `xxxAsync` 变体（返回 `CompletableFuture`）：

```java
import io.github.pandong2015.ffmpeg4j.facade.FfmpegClient;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;
import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;
import io.github.pandong2015.ffmpeg4j.engine.RunResult;
import java.util.concurrent.CompletableFuture;

FfmpegClient client = new FfmpegClient(FfmpegEnvironment.detect(), RunOptions.defaults());

CompletableFuture<RunResult> future =
        client.transcodeAsync(new File("in.mp4"), new File("out.mp4"), "libx264", "aac");

// 取消：复用 core 优雅取消阶梯（写 q → SIGTERM → SIGKILL）
future.cancel(true);

// 或等待结果
RunResult result = future.join();
```

> 异步任务在构造 `FfmpegClient` 时给定的 `Executor`（默认 `ForkJoinPool.commonPool()`）上执行；
> 失败以原始 `FfmpegException` 完成 future。Spring Boot 下该执行器自动接为 Spring `TaskExecutor`（见 [§13](#13-spring-boot-集成)）。

---

## 8. probe：结构化元数据

```java
import io.github.pandong2015.ffmpeg4j.probe.ProbeResult;
import io.github.pandong2015.ffmpeg4j.probe.StreamInfo;

ProbeResult probe = Ffmpeg.probe(new File("in.mp4"));

double seconds = probe.durationSeconds();

for (StreamInfo v : probe.videoStreams()) {
    System.out.printf("视频 #%d %s %dx%d @%.2ffps%n",
            v.index(), v.codecName(), v.width(), v.height(), v.avgFrameRateFps());
}
for (StreamInfo a : probe.audioStreams()) {
    System.out.printf("音频 #%d %s %dHz %dch%n",
            a.index(), a.codecName(), a.sampleRate(), a.channels());
}
```

`ProbeResult`：`format()` / `streams()` / `streams(MediaType)` / `videoStreams()` / `audioStreams()` /
`subtitleStreams()` / `durationSeconds()`。
`StreamInfo`（record）：`index` / `type` / `codecName` / `codecLongName` / `width` / `height` /
`sampleRate` / `channels`，以及扩展字段 `profile` / `codecTag` / `pixelFormat` / `level` / `hasBFrames` /
`sampleFormat` / `channelLayout` / `timeBase` / `startTimeSeconds` / `durationSeconds` / `bitRate` /
`nbFrames` / `sampleAspectRatio` / `displayAspectRatio` / `attachedPic`（封面图流）/ `language`
（视频/音频专属字段在另一类型上为 `null`）+ `isVideo()/isAudio()/isSubtitle()` + `avgFrameRateFps()/rFrameRateFps()`。
另有**原始保真字段** `codecTagHex`（原始 `codec_tag` 十六进制，与 `codecTag`=`codec_tag_string` 并列）/ `rawStartTime` / `rawDuration`
（`start_time`/`duration` 的原始定点串，byte-exact、**缺失→`null`**，据此区分「真实 0」与「缺失」；既有 `startTimeSeconds`/`durationSeconds` 的 `0.0` 哨兵语义不变）。
`FormatInfo` 另含 `nbPrograms` / `startTimeSeconds`，及原始保真串 `rawStartTime` / `rawDuration`。

失败路径（文件不存在 / 非法媒体）抛携 ffprobe 失败信息的 `FfmpegException`。

---

## 9. 进度回调

进度走 ffmpeg 的 `-progress`（机器可读 `key=value`），**不**解析 stderr。用 `RunOptions.onProgress` 订阅：

```java
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;

RunOptions ro = RunOptions.defaults()
        .onProgress(p -> {
            // p.raw() 是当前进度块的 key=value 快照：frame/fps/out_time/speed 等
            System.out.println(p.raw().get("out_time"));
        });
```

> ⚠️ **回调必须非阻塞**：默认在进度 pump 线程（pipe 模式下是 stdout 的唯一排空者）**同步**触发——里面做阻塞 IO/等锁会
> 停住排空、令进程不退出。需做重活时用 `RunOptions.callbackExecutor(Executor)` 把派发移出 pump 线程
> （Spring Boot 下自动接 `TaskExecutor`）。

取消宽限期也在 `RunOptions` 上：`cancelGracePeriod(Duration)`（写 `q` 后升 SIGTERM）、
`terminateGracePeriod(Duration)`（SIGTERM 后升 SIGKILL）。

---

## 10. 低层「流即值」组合

门面覆盖不了的自由组合，用 L3「流即值」模型：`Input` 取类型化流 → `Filters` 纯函数变换（返回**新** `Stream`）→
`Output` 收集 → `GraphCompiler` 编译成 argv → `FfmpegExecutor` 执行。

```java
import io.github.pandong2015.ffmpeg4j.model.*;
import io.github.pandong2015.ffmpeg4j.compiler.CompiledCommand;
import io.github.pandong2015.ffmpeg4j.compiler.GraphCompiler;
import io.github.pandong2015.ffmpeg4j.engine.FfmpegExecutor;
import io.github.pandong2015.ffmpeg4j.engine.RunOptions;
import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;

// 缩放到 1280x720
VideoStream v = Filters.scale(Input.of("in.mp4").video(), 1280, 720);
Output output = Output.to("out.mp4", v);

// 构图不触发子进程，可离线断言 argv：
CompiledCommand cmd = new GraphCompiler().compile(output);

// 执行：
new FfmpegExecutor(FfmpegEnvironment.shared()).run(cmd, RunOptions.defaults());
```

**扇出自动 `split`**——同一 `Stream` 被消费多次，编译器按引用标识自动插入 `split`/`asplit` 并重连：

```java
VideoStream src = Input.of("in.mp4").video();
Output full  = Output.to("full.mp4",  src);                    // 用一次
Output small = Output.to("small.mp4", Filters.scale(src, 640, 360)); // 又用一次
CompiledCommand cmd = new GraphCompiler().compile(List.of(full, small)); // 自动 split
```

**叠加 logo（overlay）**：

```java
VideoStream base = Input.of("in.mp4").video();
VideoStream logo = Input.of("logo.png").video();
VideoStream out  = Filters.overlay(base, logo, "W-w-10", "10"); // 右上角
```

21 个 curated 类型化滤镜：视频 `scale`/`crop`/`pad`/`padToEven`/`overlay`/`trim`/`fps`/`format`/`fade`/`drawText`，
音频 `volume`/`amix`/`atrim`/`atempo`/`afade`，GIF 调色板 `paletteGen`/`paletteUse`，拼接 `concatVideo`/`concatAudio`，
字幕烧录 `burnSubtitles`/`burnAss`（另有 2 个 `rawFilterVideo`/`rawFilterAudio` 逃生舱，不计入 curated）。
`split`/`setpts`/`aresample` 等归一化滤镜由编译器**内部**处理，不作 curated 暴露。

---

## 11. 逃生舱

未建模的能力用逃生舱兜底（内容**不参与**类型校验，正确性自负）：

```java
// 未建模滤镜：直接给原始 filter 串
VideoStream v = Filters.rawFilterVideo(Input.of("in.mp4").video(), "hqdn3d=4:3:6:4");
AudioStream a = Filters.rawFilterAudio(Input.of("in.mp4").audio(), "loudnorm");

// 位置感知的原始 argv
Input in   = Input.of("in.mp4").withInputArgs("-hwaccel", "cuda");   // 落在该 -i 之前
Output out = Output.to("out.mp4", v).withArgs("-movflags", "+faststart"); // 落在该输出之前
```

---

## 12. 错误处理

面向用户的失败一律抛 `FfmpegException`（`RuntimeException`）：

```java
import io.github.pandong2015.ffmpeg4j.FfmpegException;

try {
    Ffmpeg.transcode(new File("in.mp4"), new File("out.mp4"), "libx264", "aac");
} catch (FfmpegException e) {
    e.exitCode();    // 退出码（二进制无法启动时为 -1）
    e.reason();      // 由已知错误模式解析出的可读原因（可能为 null）
    e.command();     // 实际执行的命令
    e.stderrTail();  // stderr 尾部（约最后 50 行）
}
```

> 库自身的内部管道故障（如进度 TCP 通道 `Connection refused`）**不**外泄为 `FfmpegException`，归内部错误类别。

---

## 13. Spring Boot 集成

引 `ffmpeg4j-spring-boot-starter` 后，`FfmpegClient` 成为可注入 bean。

### 配置（`application.yml`）

```yaml
ffmpeg4j:
  ffmpeg-path: /usr/local/bin/ffmpeg      # 留空则走 PATH 发现；与 ffprobe-path 须同配或同空
  ffprobe-path: /usr/local/bin/ffprobe
  fail-fast: true                         # 启动即校验二进制，缺失则启动失败（默认 true）
  default-timeout: 30m                    # 映射进默认 RunOptions（可选）
  cancel-grace-period: 5s
  terminate-grace-period: 5s
  min-version-check: true                 # 版本 <4.2 仅告警不硬失败
  async:
    use-spring-executor: true             # 进度回调接 Spring TaskExecutor（移出 pump 线程）
    progress-channel: application-event   # application-event | listener | both
```

### 注入使用

```java
@Service
public class VideoService {
    private final FfmpegClient ffmpeg;                 // 自动装配

    public VideoService(FfmpegClient ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    public void transcode(File in, File out) {
        ffmpeg.transcode(in, out, "libx264", "aac");             // 同步
    }

    public CompletableFuture<RunResult> transcodeAsync(File in, File out) {
        return ffmpeg.transcodeAsync(in, out, "libx264", "aac"); // 异步，在 Spring TaskExecutor 上执行
    }
}
```

> 可用 `@Bean FfmpegClient` / `FfmpegEnvironment` 覆盖任意默认（`@ConditionalOnMissingBean`）。
> **边界**：静态 `Ffmpeg.xxx` 永远走全局默认环境，**不受** `ffmpeg4j.*` 配置影响；配置只作用于注入的 `FfmpegClient` bean。

### 进度事件

进度经绑定的 `TaskExecutor` 派发（**绝不占 pump 线程**）。两条通道由 `progress-channel` 切换：

```java
// application-event / both：@EventListener 订阅
@EventListener
public void onProgress(FfmpegProgressEvent event) {
    log.info("进度 {}", event.progress().raw());
}

// listener / both：注入一个 FfmpegProgressListener bean
@Bean
FfmpegProgressListener myProgressListener() {
    return event -> log.info("进度 {}", event.progress().raw());
}
```

### 可观测（可选）

在应用里再引入 `spring-boot-starter-actuator`（及 Micrometer 实现）即自动装配：

- **Health** `/actuator/health`：二进制可用且 `libass`/`libfreetype` 均在 → `UP`；任一缺失 → `DOWN`（`details` 指明缺失项）；版本 <4.2 仅告警仍 `UP`。
- **Info** `/actuator/info`：暴露 ffmpeg 版本与构建开关。
- **Metrics**：`ffmpeg4j.facade.duration`（Timer，tag `operation`/`result`）、`ffmpeg4j.facade.errors`（失败按原因分桶）、`ffmpeg4j.subprocess.active`（运行中门面任务数 Gauge）。

> 缺 actuator/micrometer 时对应组件**静默跳过**、不影响启动。

---

## 14. 二进制发现与配置

不用 Spring 时，`ffmpeg`/`ffprobe` 的解析顺序（每个二进制独立生效）：

1. **系统属性**：`-Dffmpeg4j.ffmpeg.path=...` / `-Dffmpeg4j.ffprobe.path=...`
2. **环境变量**：`FFMPEG4J_FFMPEG` / `FFMPEG4J_FFPROBE`
3. 在 **PATH** 上搜索裸命令名 `ffmpeg` / `ffprobe`

```bash
java -Dffmpeg4j.ffmpeg.path=/opt/ffmpeg/bin/ffmpeg \
     -Dffmpeg4j.ffprobe.path=/opt/ffmpeg/bin/ffprobe -jar app.jar
```

Spring Boot 下改用 `ffmpeg4j.ffmpeg-path` / `ffmpeg4j.ffprobe-path`（见 [§13](#13-spring-boot-集成)）。

---

## 15. 常见任务速查

| 想做 | 一行式 |
|------|--------|
| 转码 H.264/AAC | `Ffmpeg.transcode(in, out, "libx264", "aac")` |
| 换容器 mkv→mp4 | `Ffmpeg.remux(in, out)` |
| 截取片段 | `Ffmpeg.clip(in, out, 10.0, 25.0)` |
| 抽音频 | `Ffmpeg.extractAudio(in, out)` |
| 抓封面帧 | `Ffmpeg.thumbnail(in, out, 3.0)` |
| 拼接 | `Ffmpeg.concat(List.of(a, b), out)` |
| 烧字幕 | `Ffmpeg.burnSubtitles(video, srt, out)`（需 libass） |
| 读时长 | `Ffmpeg.probe(in).durationSeconds()` |
| 缩放 | `Filters.scale(Input.of(p).video(), 1280, 720)` |
| 叠 logo | `Filters.overlay(base, logo, "W-w-10", "10")` |
| 异步 + 取消 | `client.transcodeAsync(...)` → `future.cancel(true)` |
| 未建模滤镜 | `Filters.rawFilterVideo(v, "hqdn3d=...")` |

---

## 16. 已知约束

1. **pipe 输入无法优雅取消**：stdin 被输入媒体占用，写不进 `q`，取消降级为 SIGTERM。各门面均写盘（stdin 空闲，优雅取消可用）。
2. **进度回调默认在 pump 线程同步触发，必须非阻塞**——重活用 `callbackExecutor`。
3. **版本 <4.2 仅告警不硬失败**；仅二进制缺失才硬错。
4. **当前范围**（截至 1.5.0）：HLS VOD 已支持（`hlsSegment` 单码率 / `hlsAbr` ABR 多码率梯，均含可选 AES-128）；暂不含帧进出 JVM、硬件加速一等支持、DASH/LL-HLS、字幕高级样式——这些靠逃生舱兜底。
5. **构建 / 覆盖率**：库在任意 JDK ≥17 上可运行；生成 JaCoCo 覆盖率报告需用 **JDK 17 或 21**（JaCoCo 0.8.12 不支持 JDK ≥23 插桩）。

---

**许可证**：Apache-2.0（本库仅子进程调用 ffmpeg 二进制、不链接 libav*，独立于 ffmpeg 的 GPL/LGPL；目标机 ffmpeg 二进制的合规为分发者/运维者责任）。详见 `README.md` / `LICENSE` / `CHANGELOG.md`。
