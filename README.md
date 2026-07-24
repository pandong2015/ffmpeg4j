# ffmpeg4j

[![Maven Central](https://img.shields.io/maven-central/v/io.github.pandong2015/ffmpeg4j-core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.pandong2015/ffmpeg4j-core)
![license](https://img.shields.io/badge/license-Apache--2.0-blue.svg)
![java](https://img.shields.io/badge/Java-17%2B-orange.svg)
![spring boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen.svg)

纯 Java 的视频处理库：**封装预装的 `ffmpeg`/`ffprobe` 二进制**（路线 A，无 JNI/JavaCPP、不链接 `libav*`）。用不可变的「流即值」模型自由组合底层能力，脏活（滤镜图编译、进程编排、进度/错误/取消）全部封装在库内——你永远不必手写 `[0:v]`/pad 名。

> 像 libav 一样自由组合底层能力，但不承担原生绑定的复杂度与崩溃风险：进程崩溃不波及 JVM，CLI 参数跨版本基本稳定。

---

## ✨ 特性亮点

- **不可变「流即值」编排** —— `Input`/`Stream`/滤镜/`Output`，音视频字幕三态对称；滤镜是纯函数 `Stream → Stream`，同一 `Stream` 可被引用任意次（值语义）。
- **图编译器** —— 把流引用图编译成 `ffmpeg` 命令行：引用计数侦测扇出、**自动插入 `split`/`asplit`**、拓扑排序分配 pad 名、去重、编译期类型/连接校验。
- **稳健执行引擎** —— IO 拓扑自适应进度通道（`-progress pipe:1`/`tcp://`）、每路输出必排空防死锁、优雅取消（`q`→SIGTERM→SIGKILL）、结构化 `FfmpegException`；`run()`/`runAsync()` 双 API。
- **11 个一行式门面**（含 HLS 单码率切片 `hlsSegment` 与 ABR 多码率梯 `hlsAbr`，均可选 AES-128）+ **19 个类型化 curated 滤镜** + **万能逃生舱**（未建模滤镜/原始 argv 自负正确性）。
- **结构化 probe**（`ffprobe -print_format json` → `ProbeResult`）。
- **Spring Boot 3.x Starter** —— 注入 `FfmpegClient`、`application.yml` 配置、Actuator 健康/信息、Micrometer 指标。
- **core 零重型依赖**（JSON 自研微解析器，无 Jackson/Spring），可在无头/裁剪 JRE 运行。

## 🧱 分层架构（L0–L4，每层都有「掉到下一层」的逃生舱）

| 层 | 包 | 职责 |
|---|---|---|
| **L4 门面** | `facade` | `Ffmpeg.transcode/…/probe` 一行式 · 可实例化 `FfmpegClient`（含 `xxxAsync`） |
| **L3 模型** | `model` | 不可变「流即值」：`Input`/`Output`/`Filters`/`Normalization` |
| **L2 编译器** | `compiler` | `GraphCompiler.compile(Output)` → `CompiledCommand{argv, filterComplex}` |
| **L1 引擎** | `engine` | `FfmpegExecutor.run/runAsync`、进度、取消、超时、错误组装 |
| **L0 环境** | `env` | 二进制发现、版本探测、构建开关（libass/libfreetype）能力探测 |
| probe | `probe` | `MediaProbe.probe(File)` → `ProbeResult` |

---

## 📦 安装

| 模块 | 坐标 | 用途 |
|------|------|------|
| 核心库 | `io.github.pandong2015:ffmpeg4j-core` | 普通 Java 项目 |
| Spring Boot Starter | `io.github.pandong2015:ffmpeg4j-spring-boot-starter` | Spring Boot 3.x（传递引入 core） |

已发布至 **[Maven Central](https://central.sonatype.com/artifact/io.github.pandong2015/ffmpeg4j-core)**，无需额外仓库配置。当前版本 **`1.5.0`**。

```xml
<dependency>
    <groupId>io.github.pandong2015</groupId>
    <artifactId>ffmpeg4j-core</artifactId>            <!-- 或 ffmpeg4j-spring-boot-starter -->
    <version>1.5.0</version>
</dependency>
```
```kotlin
// Gradle (Kotlin DSL)
implementation("io.github.pandong2015:ffmpeg4j-core:1.5.0")
```

**前置**：Java 17+；目标机预装 `ffmpeg`/`ffprobe`（建议 ≥ 4.2，低于仅告警不硬失败；缺失才硬错）。烧字幕/打字幕需 ffmpeg 带 `--enable-libass`/`--enable-libfreetype`（缺失时下发命令前提前诊断报错）。二进制发现顺序：系统属性 → 环境变量 → `PATH`（详见 [USAGE.md](./USAGE.md#14-二进制发现与配置)）。

---

## 🚀 快速上手

### 普通 Java —— 11 个一行式门面

```java
import io.github.pandong2015.ffmpeg4j.facade.Ffmpeg;
import java.io.File;

Ffmpeg.transcode(new File("in.mkv"), new File("out.mp4"), "libx264", "aac"); // 转码
Ffmpeg.remux(new File("in.mkv"), new File("out.mp4"));                       // 换容器
Ffmpeg.clip(new File("in.mp4"), new File("out.mp4"), 1.0, 3.0);             // 截段
Ffmpeg.thumbnail(new File("in.mp4"), new File("thumb.png"), 5.0);          // 抓帧
Ffmpeg.gif(new File("in.mp4"), new File("out.gif"));                        // 生成 GIF
Ffmpeg.hlsSegment(new File("in.mp4"), new File("out"));                     // HLS 单码率 VOD 切片
Ffmpeg.hlsAbr(new File("in.mp4"), new File("out"));                        // HLS ABR 多码率梯（恒转码）
double sec = Ffmpeg.probe(new File("in.mp4")).durationSeconds();            // 探测
```

> 十一门面：`transcode` / `remux` / `clip` / `extractAudio` / `thumbnail` / `gif` / `concat` / `burnSubtitles` / `hlsSegment` / `hlsAbr` / `probe`，各含 `XxxOptions` 进阶重载。

### 自由组合 ——「流即值」+ 自动 `split`

`Stream` 是值，被引用任意次都合法。同一 `Stream` 消费多于一次时，编译器**自动插入 `split` 并重连**：

```java
import io.github.pandong2015.ffmpeg4j.model.*;
import io.github.pandong2015.ffmpeg4j.compiler.*;

Input in = Input.of(new File("in.mp4"));
VideoStream main = in.video();
VideoStream pip  = Filters.scale(main, 320, 180);                   // 第 1 次消费 main
VideoStream out  = Filters.overlay(main, pip, "W-w-10", "H-h-10");  // 再次消费 main → 自动 split

CompiledCommand cmd = new GraphCompiler().compile(Output.to(new File("pip.mp4"), out));
```

### Spring Boot —— 注入 `FfmpegClient`

```yaml
# application.yml
ffmpeg4j:
  ffmpeg-path: /usr/local/bin/ffmpeg   # 留空则走 PATH 发现
  fail-fast: true                      # 启动即校验二进制
  async:
    core-pool-size: 2                  # 默认专用有界执行器
    max-pool-size: 4
    queue-capacity: 64
    thread-name-prefix: ffmpeg4j-
    await-termination: true
    await-termination-period: 30s
    rejection-policy: abort            # 饱和时快速失败，不无限排队
    progress-channel: application-event
```

```java
@Service
public class VideoService {
    private final FfmpegClient ffmpeg;                 // 自动装配

    public VideoService(FfmpegClient ffmpeg) { this.ffmpeg = ffmpeg; }

    public CompletableFuture<RunResult> transcode(File in, File out) {
        return ffmpeg.transcodeAsync(in, out, "libx264", "aac");   // 异步，在 Spring TaskExecutor 上执行
    }
}
```

默认池同时承载异步门面与进度回调。应用提供唯一或 `@Primary` 的 `TaskExecutor` 时优先使用用户执行器；
设置 `ffmpeg4j.async.use-spring-executor=false` 可保留 core 默认语义。默认 `ABORT` 策略在工作线程和队列均满时快速拒绝，
避免无界排队拖垮进程。

需要稳定任务标识、完整终态和结构化警告时，使用任务 API：

```java
TaskHandle<RunResult> task =
        ffmpeg.transcodeTask(in, out, "libx264", "aac");

log.info("taskId={}", task.taskId());
task.completion().thenAccept(report -> {
    log.info("taskId={} status={}", report.taskId(), report.status());
    report.warnings().forEach(warning -> {
        if (warning.code() == WarningCode.PROGRESS_UNAVAILABLE) {
            log.warn("任务继续执行，但进度不可用：{}", warning.details());
        }
    });
});
```

`TaskHandle` 可查询状态并通过 `cancel()` 传播取消；`TaskReport` 以 `COMPLETED`、`FAILED` 或 `CANCELLED`
收口，包含结果、错误及有序不可变 warnings。原有同步方法和 `xxxAsync` 签名、返回类型与用法保持兼容。
Spring 下还可订阅携同一 `taskId` 的 `FfmpegTaskEvent` 生命周期与 `FfmpegProgressEvent` 进度事件；
引入 `spring-boot-starter-actuator` 即自动装配 Health/Info/Micrometer 指标。

---

## 📖 完整文档

| 文档 | 内容 |
|------|------|
| **[USAGE.md](./USAGE.md)** | **完整使用指南**：十一门面（含 HLS 切片/ABR） · `XxxOptions` · 异步/取消 · 进度回调 · probe · 低层组合（overlay/drawText/burnSubtitles/扇出/多输出）· 逃生舱 · 错误处理 · Spring Boot 全套 · 常见任务速查 |
| [CHANGELOG.md](./CHANGELOG.md) | 版本变更 |
| [LICENSE](./LICENSE) / [NOTICE](./NOTICE) | Apache-2.0 全文 + 归属声明 |

**逃生舱**（高层无法表达时掉到低层，内容不参与类型校验、正确性自负）：`Filters.rawFilterVideo/rawFilterAudio`（未建模滤镜）、`Input.withInputArgs`（输入侧原始 argv，如 `-hwaccel`）、`Output.withArgs`（输出侧原始 argv，如 `-movflags +faststart`）、`RunOptions.callbackExecutor`（把进度回调移出 pump 线程）。

---

## 🛠 构建与测试

```bash
mvn test          # 三模块反应堆：全部单元 + 集成测试
```

集成/E2E 测试用 `-f lavfi` 现场生成素材，**缺 ffmpeg/ffprobe 或缺构建开关时以 `assumeTrue` 跳过而非失败**。已接入 JaCoCo（report-only）。

> ⚠️ **覆盖率报告需用 JDK 17 或 21 构建**（JaCoCo 0.8.12 不支持 JDK ≥ 23 插桩；此时测试照常全绿、仅无覆盖率数据）。当前基线（JDK 21）行覆盖 ~90%。

## ⚠️ 已知约束

- **pipe 输入模式无法优雅取消**（stdin 被占，降级 SIGTERM）；各门面均写盘，影响小。
- **进度回调默认在 pump 线程同步触发，必须非阻塞**——重活用 `callbackExecutor`（Spring 下自动接 `TaskExecutor`）。
- 版本 < 4.2 仅告警不硬失败；仅二进制缺失才硬错。
- HLS VOD 已支持（`hlsSegment` 单码率切片 + `hlsAbr` ABR 多码率梯，均可选 AES-128）；不含帧进出 JVM、硬件加速一等支持、DASH/fMP4/live——靠逃生舱兜底。

---

## 📄 许可证

**[Apache License 2.0](./LICENSE)**（归属见 [NOTICE](./NOTICE)）。可自由用于闭源与商业项目，含明文专利授权。

> **与 ffmpeg 许可证的关系**：本库仅通过子进程调用外部 `ffmpeg`/`ffprobe`（不链接 `libav*`、不内嵌/不随库分发 ffmpeg），**不是 ffmpeg 的衍生作品**，Apache-2.0 独立成立。你机器上的 ffmpeg 二进制各按其自身许可证（依构建可能 GPL/LGPL）——为部署的那个二进制合规是分发者/运维者的责任，**引入本库不会让你的项目「自动 GPL」**。
