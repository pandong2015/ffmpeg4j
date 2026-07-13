# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> 会话与说明一律使用**简体中文**；代码、标识符、命令中的英文技术术语保留。

## 项目概览

`ffmpeg4j-core`：纯 Java 库，封装目标机**预装的 `ffmpeg`/`ffprobe` 二进制**（路线 A，**无 JNI/JavaCPP、不链接 libav\***）。
把不可变「流即值」引用图**编译成 ffmpeg 命令行**，再稳健地执行子进程。用户永不手写 `[0:v]`/pad 名。
Java 17，Maven 单模块，**core 无重型运行时依赖**（JSON 走自研微型解析器，禁止引入 Jackson/Guava 等）。

权威规格与进度：`openspec/changes/add-ffmpeg-cli-library/`（`design.md` 决策 D1–D20、`tasks.md` 勾选进度、`specs/` 各能力 MUST）。**动手前先读 tasks.md 确认当前进度；完成一项即勾选对应 checkbox。**

## 常用命令

```bash
mvn -q test                              # 全量编译 + 测试
mvn -q test -Dtest=GraphCompilerTest     # 单个测试类
mvn -q test -Dtest=FfmpegVersionTest#belowMinimumWarnsButDoesNotHardFail  # 单个方法
mvn -q -o compile                        # 仅编译（离线）
mvn -q package                           # 打 jar
```

集成测试依赖 PATH 上的 `ffmpeg`/`ffprobe`；缺失时用 `assumeTrue(...)` **跳过而非失败**，故 CI 与无 ffmpeg 环境均可全绿。

## 架构分层（L0–L4，每层都有「掉到下一层」的逃生舱）

| 层 | 包 | 职责 | 状态 |
|---|---|---|---|
| L0 环境 | `env` | 发现二进制、探测版本与构建开关（libass/libfreetype） | ✅ |
| L1 执行 | `engine` | 子进程执行、流排空防死锁、`-progress` 回调、优雅取消、结构化错误 | 规划中 |
| L2 编译器 | `compiler` | 引用图 → 扇出自动 `split` → 拓扑排序/pad 命名 → 校验 → `-filter_complex`+`-map` argv | ✅ |
| L3 模型 | `model` | 不可变 `Stream` 值、纯函数滤镜、`Input`/`Output`、16 个 curated 滤镜 | ✅ |
| L4 门面 | (规划) | transcode/remux/clip/extractAudio/thumbnail/concat/burnSubtitles/probe | 规划中 |
| probe | `probe`(+`json`) | `ffprobe -print_format json` → 结构化 `ProbeResult` | ✅ |

**数据流**：`Input.of(path).video()` 取类型化流 → `Filters.xxx(stream, ...)` 纯函数变换（返回**新** `Stream`）→ `Output.to(path, streams...)` → `GraphCompiler.compile(output)` → `CompiledCommand(argv, filterComplex)` → （L1）`FfmpegExecutor.run(cmd)`。

## 核心设计约束（改代码前必须理解）

- **不可变「流即值」**：`Stream` 是值，滤镜是纯函数 `Stream → Stream`，从不原地修改。同一 `Stream` 被消费多次时，编译器**按引用标识**自动插入 `split`/`asplit` 重连——值可被引用任意次。结构相等但独立构造的链**不合并**（去重按引用身份，见 `GraphCompiler`）。
- **「编译期校验」= L2 图编译阶段（ffmpeg 启动前）**，非 javac。推荐用 `VideoStream`/`AudioStream`/`SubtitleStream` 密封子类型把错配上提到 javac；`MediaType` 枚举退居 `rawFilter` 产物的运行时兜底。
- **三态对称**：音/视/字幕一等公民。硬字幕（`subtitles=`/`ass=`）诚实建模为**文件参数**（`burnSubtitles(File)`），不塞进 pad 抽象。
- **归一化职责分层**：音频 `aresample`/`aformat` 由编译器内部推导插入；视频 `scale`/`setsar`/`fps`/`format` 的**目标参数**由门面/调用方给定（唯其有 probe 数据），编译器接线，**MUST 含 `setsar`**。`split`/`setpts`/`asetpts` 等一律编译器内部，**不**作 curated 滤镜暴露。
- **逃生舱**：`Filters.rawFilterVideo/Audio`（未建模滤镜）、`Input.withInputArgs`/`Output.withArgs`（位置感知原始 argv）。逃生舱内容**不参与**类型校验，正确性自负。
- **最低 ffmpeg = 4.2**：仅「支持/测试下限」。运行时探测：版本 < 4.2 **仅告警不硬失败**（真实特性 floor ~2.3）；二进制缺失才硬错。真正硬门槛是**构建开关**（缺 libass 调 burnSubtitles 须**编译/启动期**可诊断报错，而非放任 ffmpeg 运行时报 `No such filter`）。
- **进度与取消（L1）**：进度走 `-progress`（机器可读 `key=value`），**不**解析 stderr 那行。取消默认优雅（写 `q` 让 finalize），阶梯 `q`→SIGTERM→SIGKILL；pipe 输入占用 stdin 时降级 SIGTERM。ffmpeg 写出的**每一路都须专职 pump 线程持续排空**（防管道满死锁）。内部管道故障（tcp 进度 `Connection refused`）归内部错误，**不**外泄为媒体类 `FfmpegException`。

## Java 编码规范

- **不可变优先**：值对象用 `record` 或 `final` 字段 + wither（返回新副本）；集合入构造器即 `List.copyOf`。绝不暴露可变内部状态。
- **命名**：类 `PascalCase`、方法/字段 `camelCase`、常量 `UPPER_SNAKE_CASE`；滤镜工厂用 Java 驼峰（`drawText` → ffmpeg `drawtext`）。
- **文件小而聚焦**：单一职责，200–400 行为宜、上限 ~800；宁多个小文件。
- **错误处理**：面向用户失败一律抛 `FfmpegException`（携 `exitCode`/`command`/`stderrTail`/`reason`）；启动/IO 失败用 `(message, cause)` 构造。**绝不静默吞异常**；被中断时 `Thread.currentThread().interrupt()` 后再抛。
- **边界校验**：公共入口对入参 `Objects.requireNonNull`；外部数据（进程输出、文件、JSON）先校验再用。
- **注释/Javadoc/断言消息用中文**，解释「为什么」而非复述代码。公共 API 必须有 Javadoc。
- **并发**：pump/回调线程命名清晰、可 join/关闭；资源用 try-with-resources；不滥用裸 `Thread`。默认不在热路径做重活（进度回调默认在 pump 线程，重活走 `callbackExecutor`）。
- 依赖 `switch`/`instanceof` 模式匹配处理 `sealed` 类型（`Origin`、`Stream` 子类型），新增变体时补齐分支。

## 测试约定

- JUnit 5；**纯逻辑**（版本/JSON/转义/错误模式/编译产物 argv）必须脱离进程单测——断言字符串/argv 精确值。
- **集成测试**用 `assumeTrue(commandExists("ffmpeg"), ...)` 守卫；需要素材时以 `-f lavfi -i testsrc=...` 现场生成到 `@TempDir`。
- 编译器测试断言**产出的 argv**（直链/扇出/菱形图/多输出去重/归一化含 setsar/转义/非法图报错）。
