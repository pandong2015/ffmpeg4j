# 变更说明（CHANGELOG）

本文件记录 ffmpeg4j 各版本的显著变更。遵循「新增 / 变更 / 修复」分类，日期采用 ISO 8601。

## [未发布]（1.0.1-SNAPSHOT）

_下一版本的变更记录于此。_

## [1.0.0] - 2026-07-14

首个版本。纯 Java、封装预装 `ffmpeg`/`ffprobe` 二进制（路线 A，无 JNI/JavaCPP）的通用视频处理库：不可变「流即值」编排 + 图编译器 + 稳健执行引擎。

### 新增

**L0 环境层（`env`）**
- `ffmpeg`/`ffprobe` 二进制发现：系统属性（`-Dffmpeg4j.ffmpeg.path` / `-Dffmpeg4j.ffprobe.path`）→ 环境变量（`FFMPEG4J_FFMPEG` / `FFMPEG4J_FFPROBE`）→ `PATH`。
- 版本探测：最低支持版本 **4.2**（`FfmpegVersion.MIN_FFMPEG_VERSION`），低于则仅告警不硬失败（真实特性 floor ~2.3）；二进制缺失才硬错。
- 构建开关能力探测（libass / libfreetype）：读 `-version` configuration 行 + `-filters` 列表取并集；缺失时对应门面/滤镜下发命令前提前抛可诊断异常。

**L1 执行引擎（`engine`）**
- `FfmpegExecutor.run()` / `runAsync()` 双 API；`FfmpegRun.await()/cancel()/cancel(FORCE)/isDone()`。
- IO 拓扑自适应进度通道（`-progress pipe:1` 或 `tcp://`）；每路输出专职 pump 线程排空防死锁。
- 进度采集与回调（`Progress`、`RunOptions.onProgress`），`callbackExecutor` 逃生舱把派发移出 pump 线程。
- 取消阶梯：优雅写 `q` → SIGTERM → SIGKILL，stdin 被输入占用时降级 SIGTERM；超时复用取消阶梯。
- 结构化 `FfmpegException`（exitCode / command / stderrTail / reason）与已知错误模式库；内部管道故障不外泄为媒体错误。

**L2 图编译器（`compiler`）**
- `GraphCompiler.compile(Output | List<Output>)` → `CompiledCommand{argv, filterComplex}`。
- 引用计数扇出侦测 + 自动插入 `split`/`asplit` 并重连；共享子链去重（按引用标识，不合并结构相等的独立链）。
- 拓扑排序 + pad 命名；编译期校验（悬空 pad、字幕流扇出报错）；filtergraph 转义（含空格/中文/盘符冒号路径）。

**L3 编排模型（`model`）**
- 不可变「流即值」：`Input.of(File|Path|String)` + `video()/audio()/subtitle()/videoOptional()/audioOptional()/withInputArgs(...)`；`Output.to(...)` + `withArgs()/withStreams()/subtitleCodec()`。
- **16 个类型化 curated 滤镜**：视频 `scale`/`crop`/`pad`/`overlay`/`trim`/`fps`/`format`/`fade`/`drawText`，音频 `volume`/`amix`/`atrim`/`atempo`/`afade`，双型 `concat`（含 `concatAudio`/`concatVideo`），字幕烧录 `burnSubtitles`/`burnAss`。`trim`/`atrim` 自动补 `setpts`/`asetpts`；`atempo` 超范围自动拆链。
- 归一化 `Normalization`（`normalizeVideo`/`normalizeAudio`/`normalizeSegments`/`silence`/`blank`，视频链必含 `setsar`）+ `VideoNormTarget`/`AudioNormTarget`。
- 逃生舱：`Filters.rawFilterVideo`/`rawFilterAudio`、`Input.withInputArgs`、`Output.withArgs`（内容不参与类型校验）。

**media-probe（`probe`）**
- `MediaProbe.probe(File)` → `ProbeResult`（`format()`/`streams()`/`videoStreams()`/`audioStreams()`/`subtitleStreams()`/`durationSeconds()`）。JSON 走自研微型解析器，core 不引入 Jackson。

**L4 门面（`facade`）—— 8 个一行式**
- `transcode` / `remux` / `clip` / `extractAudio` / `thumbnail` / `concat` / `burnSubtitles` / `probe`。
- 每个门面「便捷位置重载 + `XxxOptions` 进阶重载」（`probe` 豁免 Options）。
- 落实正确性约束：remux 文本字幕转 `mov_text`、图形字幕丢弃；clip 用无歧义 `-ss start -t (end-start)` 并区分快切/精切；concat 前置归一化（含 setsar）+ 流集合异构注入静音/纯色或可诊断拒绝；extractAudio 按扩展名推导编解码器并用 `-map 0:a` 避开封面图。

**实例门面 + Spring Boot Starter（`facade.FfmpegClient` / `ffmpeg4j-spring-boot-*`）**
- core 新增可实例化门面 `FfmpegClient(FfmpegEnvironment, RunOptions[, Executor])`：8 门面为实例方法 + 对应 `xxxAsync` 返回 `CompletableFuture`（cancel 复用优雅取消阶梯）；静态 `Ffmpeg` 委托默认实例，向后兼容。
- **Spring Boot 3.x 支持**（新模块 `ffmpeg4j-spring-boot-autoconfigure` + `ffmpeg4j-spring-boot-starter`）：`@ConfigurationProperties(ffmpeg4j)` 外部化配置、`FfmpegEnvironment`/`FfmpegExecutor`/`FfmpegClient` 自动装配（`@ConditionalOnMissingBean` 可覆盖）、启动 fail-fast 校验。
- 进度桥接：`callbackExecutor` 接 Spring `TaskExecutor`（回调移出 pump 线程），进度经 `FfmpegProgressEvent`（`ApplicationEventPublisher`）或注入的 `FfmpegProgressListener` 递送，通道由 `async.progress-channel` 切换。
- 可观测（Actuator/Micrometer，classpath 条件装配）：Health（含 libass/libfreetype 判定）、Info、指标（门面计时 + 失败分桶 + 运行中任务 Gauge）。

### 工程

- 多模块化：聚合父 POM `ffmpeg4j-parent` + 三子模块（`ffmpeg4j-core` 坐标不变、`ffmpeg4j-spring-boot-autoconfigure`、`ffmpeg4j-spring-boot-starter`）；Spring Boot 3.5.3 BOM 统一版本；core 保持零重型依赖（依赖树无 Spring/Jackson/Guava）。
- Maven 坐标 `io.github.pandong2015:ffmpeg4j-core:1.0.0`（已发布至 Maven Central），Java 17，JUnit 5.10.2。
- 许可证 **Apache-2.0**（`LICENSE` 全文 + `NOTICE` 归属声明）；本库仅子进程调用 ffmpeg 二进制、不链接 libav*，独立于 ffmpeg 的 GPL/LGPL。
- JaCoCo 覆盖率报告（report-only，不设失败阈值）；`maven-source-plugin` / `maven-javadoc-plugin` 生成 sources/javadoc jar。
- 测试：单元 + 离线 argv 断言 + 真机 E2E（缺 ffmpeg/构建开关时 `assumeTrue` 跳过而非失败）。

### 已知约束

- pipe 输入模式无法优雅取消（stdin 被占，降级 SIGTERM）；v1.0 门面均写盘，影响小。
- 进度回调默认在 pump 线程同步触发，必须非阻塞（重活用 `callbackExecutor`）。
- 版本 < 4.2 仅告警不硬失败。
- 不含帧进出 JVM（预留 `ffmpeg4j-frame`）、硬件加速、HLS/DASH、字幕高级样式的一等支持（靠逃生舱兜底）。

### 发布

- **1.0.0** 已 GPG 签名并部署至 **Maven Central**（2026-07-14）；`pom.xml` 的 `<scm>`/`<url>` 已为真实仓库地址。

[1.0.0]: https://github.com/pandong2015/ffmpeg4j/releases/tag/v1.0.0
