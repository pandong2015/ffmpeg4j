# ffmpeg4j

纯 Java 的视频处理库：**封装预装的 `ffmpeg`/`ffprobe` 二进制**（路线 A，无 JNI/JavaCPP、不背原生绑定），用不可变的「流即值」模型自由组合底层能力，脏活（滤镜图编译、进程编排、进度/错误/取消）全部封装在库内。

> 目标：像 libav 一样自由组合底层能力，但不承担原生绑定的复杂度与崩溃风险——进程崩溃不波及 JVM，CLI 参数跨版本基本稳定。

## 特性一览

- **不可变「流即值」编排**：`Input` / `Stream` / 滤镜 / `Output`，音频·视频·字幕三态对称；滤镜是纯函数 `Stream -> Stream`，同一 `Stream` 可被引用任意次（值语义）。
- **图编译器**：把用户的流引用图编译成 `ffmpeg` 命令行——引用计数侦测扇出、**自动插入 `split`/`asplit`**、拓扑排序并分配 pad 名、去重、编译期类型/连接校验（音·视·字幕接错滤镜即报错）。
- **稳健的执行引擎**：按 IO 拓扑自适应进度通道（`-progress pipe:1` 或 `tcp://`），每路输出必排空防死锁，取消默认优雅（写 `q` → SIGTERM → SIGKILL），失败时保留 stderr 尾部并组装带原因的 `FfmpegException`；`run()` / `runAsync()` 双 API。
- **结构化 probe**：基于 `ffprobe -print_format json` 读取容器/流元数据。
- **16 个类型化 curated 滤镜 + 万能逃生舱**：任意组合底层能力从第一天成立。
- **8 个一行式门面**：覆盖最常见整段任务。

### 分层架构（L0–L4，每层都有「掉到下一层」的逃生舱）

| 层 | 包 | 职责 |
|---|---|---|
| **L4 门面** | `facade` | `Ffmpeg.transcode/remux/clip/…/probe` 一行式静态便捷方法 |
| **L3 模型** | `model` | 不可变「流即值」编排：`Input`/`Output`/`Filters`/`Normalization` |
| **L2 编译器** | `compiler` | `GraphCompiler.compile(Output)` → `CompiledCommand{argv, filterComplex}` |
| **L1 引擎** | `engine` | `FfmpegExecutor.run/runAsync`、进度、取消、超时、错误组装 |
| **L0 环境** | `env` | 二进制发现、版本探测、构建开关（libass/libfreetype）能力探测 |
| probe | `probe` | `MediaProbe.probe(File)` → `ProbeResult` |

高层无法表达时允许直接掉到低层：`Filters.rawFilterVideo` → L2 原始滤镜、`Output.withArgs` → 原始 argv。**无逃生舱的封装最终都会被绕过。**

---

## 安装与前置

### Maven 坐标

```xml
<dependency>
    <groupId>io.github.pandong2015</groupId>
    <artifactId>ffmpeg4j-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

要求 **Java 17+**。`core` 无重型运行时依赖（JSON 走自研微型解析器，不引入 Jackson），保持纯 JDK、可在无头/裁剪 JRE 运行。

### 前置：预装 ffmpeg / ffprobe

目标机器须预装 `ffmpeg` 与 `ffprobe` 二进制。

- **最低支持版本 = 4.2**（承诺测试/支持的下限，覆盖 RHEL/Rocky/Alma 8、Ubuntu 20.04+、Debian 11+ 等仍在维护的基线）。真实特性 floor 约为 2.3——本库依赖的 `-progress`、`filter_complex`+`split`、`subtitles=`/`ass=`、`ffprobe -print_format json` 等在 ~2.3 即全部具备。
- **低于 4.2 仅告警不硬失败**：启动探测到版本偏低时只记录一条 WARNING 并继续。**只有二进制缺失才是硬错误**（抛出可诊断的 `FfmpegException`，点名缺失的二进制）。
- **构建开关探测**：某些特性依赖 ffmpeg 编译期开关，库在启动探测能力（读 `-version` 的 configuration 行 + `-filters` 列表并取并集），缺失时对应门面/滤镜**下发命令前**提前抛出可诊断异常，而非放任 ffmpeg 运行期报含糊的「No such filter」：
  - `--enable-libass` → 字幕烧录（`Ffmpeg.burnSubtitles` / `Filters.burnSubtitles` / `burnAss`）
  - `--enable-libfreetype` → 绘制文字（`Filters.drawText`）

### 二进制发现顺序

每个二进制独立按以下顺序解析：

1. 系统属性：`-Dffmpeg4j.ffmpeg.path=/path/to/ffmpeg`（ffprobe 对应 `-Dffmpeg4j.ffprobe.path`）
2. 环境变量：`FFMPEG4J_FFMPEG=/path/to/ffmpeg`（ffprobe 对应 `FFMPEG4J_FFPROBE`）
3. 在 `PATH` 上搜索裸命令名 `ffmpeg` / `ffprobe`

```bash
# 例：显式指定二进制路径
java -Dffmpeg4j.ffmpeg.path=/opt/ffmpeg/bin/ffmpeg \
     -Dffmpeg4j.ffprobe.path=/opt/ffmpeg/bin/ffprobe -jar your-app.jar
```

---

## 快速上手：8 个一行式门面

所有门面都是 `io.github.pandong2015.ffmpeg4j.facade.Ffmpeg` 的静态方法，阻塞执行并返回 `RunResult`（`probe` 例外，直接返回 `ProbeResult`）。每个门面都有「便捷位置重载」与接收对应 `XxxOptions` 的「进阶重载」。

```java
import io.github.pandong2015.ffmpeg4j.facade.Ffmpeg;
import io.github.pandong2015.ffmpeg4j.engine.RunResult;
import io.github.pandong2015.ffmpeg4j.probe.ProbeResult;
import java.io.File;
import java.util.List;

// 1. transcode —— 强制转码（视频 libx264、音频 aac）
Ffmpeg.transcode(new File("in.mkv"), new File("out.mp4"), "libx264", "aac");

// 2. remux —— 换容器（纯流复制 -c copy，按流分派；mkv→mp4 时文本字幕自动转 mov_text）
Ffmpeg.remux(new File("in.mkv"), new File("out.mp4"));

// 3. clip —— 截取 [1.0s, 3.0s]（默认快切：-ss 关键帧 seek + -c copy）
Ffmpeg.clip(new File("in.mp4"), new File("out.mp4"), 1.0, 3.0);

// 4. extractAudio —— 抽音频（编解码器由扩展名推导：.mp3→libmp3lame）
Ffmpeg.extractAudio(new File("in.mp4"), new File("out.mp3"));

// 5. thumbnail —— 在第 5.0s 抓一帧
Ffmpeg.thumbnail(new File("in.mp4"), new File("thumb.png"), 5.0);

// 6. concat —— 拼接多段（前置归一化，含 setsar；异构缺流自动注入静音/纯色）
Ffmpeg.concat(List.of(new File("a.mp4"), new File("b.mp4")), new File("out.mp4"));

// 7. burnSubtitles —— 硬字幕烧录（需 --enable-libass）
Ffmpeg.burnSubtitles(new File("in.mp4"), new File("sub.srt"), new File("out.mp4"));

// 8. probe —— 读取结构化元数据（唯一直接返回 ProbeResult 的门面）
ProbeResult info = Ffmpeg.probe(new File("in.mp4"));
```

### 进阶重载（`XxxOptions`，不可变 wither 风格）

```java
import io.github.pandong2015.ffmpeg4j.facade.TranscodeOptions;
import io.github.pandong2015.ffmpeg4j.facade.ClipOptions;

// 转码：改用 libx265、CRF 23、preset slow
Ffmpeg.transcode(new File("in.mkv"), new File("out.mp4"),
        TranscodeOptions.defaults().videoCodec("libx265").crf(23).preset("slow"));

// 截段：精切（reencode=true，输出侧 -ss/-t + 重编码，得到帧级精确区间）
Ffmpeg.clip(new File("in.mp4"), new File("out.mp4"), 1.0, 3.0,
        ClipOptions.defaults().reencode(true));
```

---

## 低层「流即值」组合

门面之下是可自由组合的编排模型。全链为 **`Input` → `Filters` → `Output` → `GraphCompiler` → `FfmpegExecutor`**。

### overlay 叠加 logo

```java
import io.github.pandong2015.ffmpeg4j.model.*;
import io.github.pandong2015.ffmpeg4j.compiler.*;
import io.github.pandong2015.ffmpeg4j.engine.*;
import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;
import java.io.File;

Input video = Input.of(new File("in.mp4"));
Input logo  = Input.of(new File("logo.png"));

VideoStream base = video.video();
VideoStream mark = logo.video();
// x/y 接受表达式：把 logo 放到右下角，边距 10px
VideoStream composed = Filters.overlay(base, mark, "W-w-10", "H-h-10");

Output output = Output.to(new File("out.mp4"), composed, video.audio())
        .withArgs("-c:v", "libx264", "-c:a", "copy");

CompiledCommand cmd = new GraphCompiler().compile(output);
RunResult r = new FfmpegExecutor(FfmpegEnvironment.shared()).run(cmd);
```

### drawText 打字幕（需 `--enable-libfreetype`）

```java
Input in = Input.of(new File("in.mp4"));
// 参数：文本、字体文件(可为 null 用默认字体)、字号、颜色、x、y（均接受表达式）
VideoStream v = Filters.drawText(in.video(), "ffmpeg4j",
        null, 24, "white", "(w-tw)/2", "h-th-20");

Output output = Output.to(new File("out.mp4"), v, in.audio())
        .withArgs("-c:v", "libx264", "-c:a", "copy");
CompiledCommand cmd = new GraphCompiler().compile(output);
```

### burnSubtitles 烧录（需 `--enable-libass`）

```java
import java.nio.file.Path;

Input in = Input.of(new File("in.mp4"));
VideoStream v = Filters.burnSubtitles(in.video(), Path.of("sub.srt"));
// 带 force_style：Filters.burnSubtitles(in.video(), Path.of("sub.srt"), "FontName=Arial,FontSize=24")

Output output = Output.to(new File("out.mp4"), v, in.audioOptional())
        .withArgs("-c:v", "libx264", "-c:a", "copy");
CompiledCommand cmd = new GraphCompiler().compile(output);
```

### 扇出：同一 `Stream` 消费两次，编译器自动 `split`

`Stream` 是值——被引用任意次都合法。同一 `Stream` 被消费多于一次时，编译器**自动插入 `split=N` 并重连**，无需手写 `[0:v]split`。

```java
Input in = Input.of(new File("in.mp4"));
VideoStream main = in.video();
VideoStream pip  = Filters.scale(main, 320, 180);                   // 第 1 次消费 main
VideoStream out  = Filters.overlay(main, pip, "W-w-10", "H-h-10");  // 再次消费 main → 自动 split

Output output = Output.to(new File("pip.mp4"), out);
CompiledCommand cmd = new GraphCompiler().compile(output);
// cmd.filterComplex() 里会出现编译器插入的 split
```

### 多输出：单次 ffmpeg 调用产出多个文件

```java
Input in = Input.of(new File("in.mp4"));
VideoStream v     = in.video();
VideoStream thumb = Filters.scale(v, 320, -1);   // 缩略图分支（-1 让高按比例推导）

Output full  = Output.to(new File("full.mp4"), v, in.audio())
        .withArgs("-c:v", "libx264", "-c:a", "copy");
Output small = Output.to(new File("thumb.mp4"), thumb).withArgs("-c:v", "libx264");

// 传入 List<Output>：v 被两个输出消费也会自动 split
CompiledCommand cmd = new GraphCompiler().compile(List.of(full, small));
RunResult r = new FfmpegExecutor(FfmpegEnvironment.shared()).run(cmd);
```

---

## 进度回调 + 取消

`runAsync` 返回可等待/可取消的 `FfmpegRun`。进度回调消费 `-progress` 解析出的 `Progress` 快照。

```java
import io.github.pandong2015.ffmpeg4j.engine.*;
import io.github.pandong2015.ffmpeg4j.env.FfmpegEnvironment;
import java.util.concurrent.Executors;

CompiledCommand cmd = /* ... 见上文构图 ... */;
FfmpegExecutor exec = new FfmpegExecutor(FfmpegEnvironment.shared());

RunOptions opts = RunOptions.defaults()
        .onProgress(p -> System.out.println(
                "已处理 " + p.outTimeMillis() + "ms，速度 " + p.speed() + "x，帧 " + p.frame()))
        // 回调默认在进度 pump 线程同步触发、必须非阻塞；重活用 callbackExecutor 移出：
        .callbackExecutor(Executors.newSingleThreadExecutor());

FfmpegRun run = exec.runAsync(cmd, opts);

// 需要时取消（后台推进，本方法立即返回）：
run.cancel();                      // 优雅取消（写 q → SIGTERM → SIGKILL）
// run.cancel(CancelMode.FORCE);   // 强制取消（跳过优雅收尾，输出可能未 finalize）

RunResult r = run.await();         // 正常结束或被主动取消时返回 RunResult；出错/超时抛 FfmpegException
```

`Progress` 常用访问器：`frame()` / `fps()` / `outTimeMillis()` / `outTimeMicros()` / `totalSize()` / `speed()` / `isEnd()`——字段缺失或为 `N/A` 时返回合理默认，绝不抛异常。

---

## probe：结构化元数据

```java
import io.github.pandong2015.ffmpeg4j.probe.*;
import io.github.pandong2015.ffmpeg4j.facade.Ffmpeg;
import java.io.File;

ProbeResult p = Ffmpeg.probe(new File("in.mp4"));   // 等价于 MediaProbe.probe(file)

System.out.println("容器: " + p.format().formatName());
System.out.println("时长(秒): " + p.durationSeconds());

for (StreamInfo s : p.videoStreams()) {
    System.out.println("视频 " + s.codecName() + " " + s.width() + "x" + s.height()
            + " @" + s.avgFrameRateFps() + "fps");
}
for (StreamInfo s : p.audioStreams()) {
    System.out.println("音频 " + s.codecName() + " " + s.sampleRate() + "Hz " + s.channels() + "ch");
}
for (StreamInfo s : p.subtitleStreams()) {
    System.out.println("字幕 " + s.codecName());
}
```

`ProbeResult` 提供 `format()` / `streams()` / `videoStreams()` / `audioStreams()` / `subtitleStreams()` / `durationSeconds()` / `firstVideo()` / `firstAudio()`。文件不存在或非法媒体时抛出携带 stderr 尾部与可读原因的 `FfmpegException`。

---

## 逃生舱

每一层抽象都留了「掉到下一层」的口子，内容**不参与编译期类型/连接校验，正确性自负**（错误交由执行引擎的 `FfmpegException` 尽力翻译）。

- **`Filters.rawFilterVideo(v, "…")` / `Filters.rawFilterAudio(a, "…")`** —— 插入任意未建模的滤镜。
  ```java
  VideoStream v = Filters.rawFilterVideo(in.video(), "hqdn3d");   // 未建模的降噪滤镜
  AudioStream a = Filters.rawFilterAudio(in.audio(), "loudnorm"); // 未建模的响度归一
  ```

- **`Input.withInputArgs(...)`** —— 输入侧原始参数（位置感知，编译器置于该输入的 `-i` 之前），如 `-ss` 快切、`-hwaccel` 硬解。
  ```java
  Input in = Input.of(new File("in.mp4")).withInputArgs("-ss", "10", "-hwaccel", "auto");
  ```

- **`Output.withArgs(...)`** —— 输出侧原始参数（置于输出文件之前），如 `-movflags +faststart`。
  ```java
  Output out = Output.to(new File("out.mp4"), v).withArgs("-movflags", "+faststart");
  ```

- **`RunOptions.callbackExecutor(Executor)`** —— 把进度回调派发移出 pump 线程，使回调中可安全做阻塞/重活。
  ```java
  RunOptions.defaults().callbackExecutor(Executors.newSingleThreadExecutor());
  ```

---

## 已知约束

1. **pipe 输入模式无法优雅取消**：stdin 被输入媒体占用，写不进 `q`，取消自动降级为 SIGTERM，输出可能未 finalize。v1.0 的门面都写盘（stdin 空闲，优雅取消可用），故该约束主要影响未来的 frame 进出场景。
2. **进度回调默认在 pump 线程同步触发，必须非阻塞**：在 pipe 模式下 pump 线程是 stdout 的唯一排空者——回调里做阻塞 IO/等锁会停住排空、令 ffmpeg 写 `-progress` 阻塞、进程不退出（无超时时 `run()` 将永久挂起）。需做重活时务必用 `callbackExecutor`。
3. **版本 < 4.2 仅告警不硬失败**：真实功能下限约 2.3；只有二进制缺失才硬错。
4. **v1.0 范围**：不含帧进出 JVM（预留 `ffmpeg4j-frame` 模块）；不含硬件加速、HLS/DASH、字幕高级样式的一等支持——这些靠 `rawFilterVideo`/`rawFilterAudio` 与 `withInputArgs`/`withArgs` 逃生舱兜底。

---

## 构建与测试

```bash
mvn test          # 运行全部单元 + 集成测试
```

- **运行时**：目标机需 JDK 17+（`maven.compiler.release=17`）。库本身在任意 ≥17 的 JDK（含 21/26）上均可编译、打包、运行。
- 集成/E2E 测试用 `-f lavfi` 现场生成素材，**缺 ffmpeg/ffprobe 或缺相应构建开关（如 libass）时以 `assumeTrue` 跳过而非失败**。

### 覆盖率

已接入 **JaCoCo**（仅构建/测试期插件，report-only、不设失败阈值）。`mvn test` 后打开报告：

```bash
open target/site/jacoco/index.html    # 或用浏览器打开该文件
```

CSV 汇总见 `target/site/jacoco/jacoco.csv`。

> ⚠️ **覆盖率报告需用 JDK 17 或 21 构建**。JaCoCo 0.8.12 无法插桩 JDK ≥ 23 的 class（会报 `Unsupported class file major version 70` 等），此时**测试照常全绿、jar 照常产出，仅覆盖率数据缺失**。若默认 JDK 较新，临时切换即可：
>
> ```bash
> JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn clean test   # macOS；Linux 换成对应 JDK21 路径
> ```
>
> 当前基线（JDK 21）：**行覆盖 ~88%**，`compiler` 层最高（~97%）。

---

## 许可证

本库采用 **[Apache License 2.0](./LICENSE)**（归属声明见 [`NOTICE`](./NOTICE)）。可自由用于闭源与商业项目，含明文专利授权。

> **与 ffmpeg 许可证的关系**：本库是纯 Java、**仅通过子进程调用外部 `ffmpeg`/`ffprobe` 二进制**（路线 A：不链接 `libav*`、不内嵌 ffmpeg 源码、不随库分发 ffmpeg），因此**不是 ffmpeg 的衍生作品**，其 Apache-2.0 许可证独立成立。
>
> 你机器上安装的 `ffmpeg` 二进制则各按其自身许可证——依构建方式可能是 GPL（如 `--enable-gpl --enable-libx264`）或 LGPL。**为实际部署的那个 ffmpeg 二进制满足合规，是分发者/运维者的责任，与本库许可证无关**（引入本库不会让你的项目「自动 GPL」）。
