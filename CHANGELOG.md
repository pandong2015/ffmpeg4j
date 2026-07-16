# 变更说明（CHANGELOG）

本文件记录 ffmpeg4j 各版本的显著变更。遵循「新增 / 变更 / 修复」分类，日期采用 ISO 8601。

## [1.4.0] - 2026-07-16

新增 **HLS ABR 多码率梯 VOD 门面**（可选 AES-128）。纯 additive，既有 argv 逐字节不变，`hlsSegment` 契约不动；core 仍零重型依赖。ABR 全链路在 ffmpeg 8.0.1 实测验证；4.2 为支持下限但未单独验证。

### 新增

**L4 HLS ABR 门面（第 10 个动作门面）**
- `Ffmpeg.hlsAbr(File in, File outDir[, HlsAbrOptions])` 与 `FfmpegClient` 对称实例 + `hlsAbrAsync`（返回 `CompletableFuture<HlsAbrResult>`）。一入 N 档产 `outDir/master.m3u8` + 每档 `<目录>/index.m3u8` + 各档段（+ 启用 AES 时 `outDir/key/enc.key`）——单条 ffmpeg 命令（`-var_stream_map` + `-master_pl_name`），非 N 次单码率循环。**不重载 `hlsSegment`**（返回类型不同、语义相反：copy vs 恒转码，ABR 即便 N=1 也产 master）。
- **恒转码走路线 A**：同一 `input.video()` 被 N 档 `scale`+`setsar` 消费，触发编译器原生 `split=N` 扇出（L2/L3 零改动）。
- **恒跨档关键帧对齐**：一条 `-force_key_frames expr:gte(t,n_forced*hlsTime)` 覆盖全档（无缝切码率的正确性前提，恒开、不暴露开关）；`hlsTime` 默认 6.0。
- **默认不注入 `-hls_base_url`**（与单码率相反）：每档 playlist 与段共位 → 段 URI=basename 天然自洽；`%v` 在 `-hls_base_url`/`name:` 内 8.0.1 实测均不展开。

**码率梯建模**
- `HlsVariant.of(int height, String videoBitrate)` + wither：`width`（默认 `scale=-2:h` 保比偶宽）/`maxrate`/`bufsize`（默认由 `videoBitrate` 派生 ≈1.07×/1.5×）/`audioBitrate`/`videoCodec`(libx264)/`audioCodec`(aac)/`crf`/`preset`/`name`（默认数字索引目录）。非法 `name`（含 `%v` 或 `var_stream_map` 元字符）即时报错。
- `HlsLadder.defaults()`：**1080p@5M / 720p@3M / 480p@1.5M / 360p@800k**；`cropToSourceHeight` 按源高度剔除放大档（极小源兜底单档取偶、不放大）。默认梯 probe 源高度裁剪在门面完成，`buildHlsAbr` 保持纯函数；无视频轨/probe 失败可诊断 fail-fast。

**结果与选项**
- `HlsAbrResult`（record）：`master` + `List<HlsVariantResult>` + 可空 `HlsAudioRendition`（agroup）+ 可空 `keyFile` + `RunResult`。master 解析**引号感知/定向正则**（`BANDWIDTH`/`RESOLUTION` 避开 `CODECS` 值内逗号）。
- `HlsAbrOptions`（不可变 wither）：`variants`（内部 null 哨兵=默认梯+裁剪）/`hlsTime`(6.0)/`key`/`audioBitrate`(128k, agroup)/`masterPlaylistName`/`segmentTemplate`/`startNumber`/`sharedAudio`(默认 true=agroup 共享单音轨)/`extraOutputArgs` + `onProgress`/`timeout`。

**音频 agroup 共享单音轨（默认）**
- 音频只编一次，`var_stream_map` 每视频档挂 `agroup`、末尾追加 `a:0,...,name:audio,default:yes`；省 N× 存储、各档边界一致；master 自动 `#EXT-X-MEDIA`，`HlsAbrResult` 单列 `audioRendition`。`sharedAudio(false)` 退回每档独立音频（带下标 `-c:a:N`/`-b:a:N`）。

**AES-128（复用单码率基座，单密钥覆盖全档）**
- `HlsAbrOptions.key(HlsKey)` 直接复用 `HlsKey`（B2/B1）；单个 `outDir/key/enc.key` + 临时 key_info_file 加密所有档、`0600` 原子创建、失败清理孤儿明文。**已知安全项**：省略 IV 时 ffmpeg 在 `var_stream_map` 下产 `IV=0x00…00` 跨段/跨 rendition 复用（异于单码率的段序号派生 IV）——采单密钥模型，VOD 场景风险低。**密钥托管**：`key/` MUST 排除在 CDN/静态托管根之外。

> **边界**：fMP4 ABR / live·event / 密钥轮换 / per-variant 独立密钥 / SAMPLE-AES / 字幕 rendition / CDN 扁平段前缀均出界，走 `extraOutputArgs` 或后续变更。

## [1.3.0] - 2026-07-15

新增 **HLS 单码率 VOD 切片门面**（可选 AES-128）与**通用按秒强制关键帧**能力。纯 additive，既有 argv 逐字节不变，core 仍零重型依赖（AES 随机仅用 JDK `SecureRandom`）。HLS/AES 行为在 ffmpeg 8.0.1 实测验证；4.2 为支持下限但未单独验证。

### 新增

**L4 HLS 切片门面（第 9 个动作门面）**
- `Ffmpeg.hlsSegment(File in, File outDir[, HlsOptions])` 与 `FfmpegClient` 对称实例 + `hlsSegmentAsync`（返回 `CompletableFuture<HlsResult>`）。产物三分离布局：`outDir/index.m3u8` + `outDir/ts/*.ts` + 启用 AES 时 `outDir/key/enc.key`。默认 `-c copy`；VOD 双标签（`-hls_playlist_type vod`/`-hls_list_size 0`）内部固定注入。
- 段 URI 前缀经**默认注入 `-hls_base_url <segmentDir>/`** 保证（ffmpeg 单播放列表对段 URI 取 basename、不隐式相对化——8.0.1 实测）；`HlsOptions.segmentUriPrefix` 覆盖之。
- `HlsResult`（record）：`segments` 由**解析写出的 m3u8** 得到（有序、免疫 `-y` 孤儿段），不 glob 目录。
- `HlsOptions`（不可变 wither）：`hlsTime`（默认 8.0）/`playlistName`/`segmentDir`/`segmentTemplate`/`keyDir`/`keyFileName`/`startNumber`/`videoCodec`/`audioCodec`（默认 copy）/`key`/`alignKeyframes`/`segmentUriPrefix`/`cleanSegmentDir`/`extraOutputArgs` + `onProgress`/`timeout`。

**AES-128（责任模型 B2 默认 / B1 便利）**
- `HlsKey.of(byte[16], keyUri[, iv])`（B2：调用方持机密）、`HlsKey.random(keyUri)`（B1：JDK `SecureRandom`）。单个 `enc.key` + 临时 key_info_file 覆盖全档，二者以 `0600` 原子创建；临时文件 `finally` 删、失败/取消清理孤儿明文密钥。key URI **明文进 m3u8**（勿内嵌凭证）。`ErrorPatterns` 增补 `Invalid key size`/加密不可用的可读诊断。

**通用按秒强制关键帧**
- `TranscodeOptions.forceKeyframesEverySeconds(double)` → `-force_key_frames expr:gte(t,n_forced*T)`，与帧基 `gop(int)` **互补共存**；单一渲染器 `FacadeSupport.forceKeyFramesArgs`。`force_key_frames` 必然重编码，与 `-c:v copy` 冲突时 build 期 fail-fast。HLS 侧以 `HlsOptions.alignKeyframes(boolean)`（T=hlsTime）复用。

> **边界**：多码率梯（ABR）/fMP4/live/密钥轮换均出界，走 `extraOutputArgs`；ABR 为已确认的独立后续变更（`add-hls-abr-ladder`）。

## [1.2.0] - 2026-07-15

本次发布合并两批纯 additive 能力：面向下游 `ocs-media-task` 的 **P0 能力补齐** 与 type1 转码的 **滤镜链/码控能力（P1）**。默认行为逐字节不变，core 仍零重型依赖。

### P1 —— 转码滤镜链与码控

type1 转码所需的**滤镜链与码控能力**（纯 additive、无 videoFilter 的转码 argv 逐字节不变、无编译器改动）。

#### 新增

**L3 curated 滤镜**
- `Filters.padToEven(VideoStream)`：补齐到最近偶数尺寸（`pad=w=ceil(iw/2)*2:h=ceil(ih/2)*2`），H.264/H.265 编码前补偶。
- `Filters.pad(VideoStream, String w, String h, …)`：pad 的表达式重载（w/h/x/y 接受 ffmpeg 表达式，逐字下发）。
- `Filters.overlay(base, over, x, y, boolean shortest)`：overlay 的 `shortest` 重载，供 `-loop 1` 循环水印收尾。
- `Filters.rawFilterVideo(base, over, raw)`：2 输入原始视频滤镜逃生舱（与单输入版对称，解锁复杂多输入 overlay）。新增 curated `padToEven` 使数量 18→19（pad 表达式/overlay shortest 为既有滤镜的重载，2 输入 rawFilterVideo 为逃生舱、不计入）。

**L4 转码进阶**
- `TranscodeOptions.videoFilter(Function<VideoStream,VideoStream>)`：单输入视频滤镜链入口。给定时视频以必选映射为起点经 `filter_complex`、音频仍可选；未给定时行为逐字节不变。函数内可自建额外输入（水印图）叠加，编译器自动补第二路 `-i`。
- `TranscodeOptions` 类型化码控：`fps`（`-r`）、`maxrate`/`bufsize`（h264 VBV）、`gop`（关键帧间隔帧数，派生 `-keyint_min`/`-g`/`-sc_threshold 0`），与 `extraOutputArgs(String...)` 逃生舱（置于类型化码控之后；libx265 的 VBV 走此处，库不自动翻译）。

> **边界**：7 种 watermarkType 的具体 overlay 表达式是下游业务规则，不进 ffmpeg4j-core；core 提供通用底座（overlay shortest / 2 输入逃生舱 / `-loop` 输入 / pad 表达式），下游据此组合。

### P0 —— media-task 能力补齐

面向下游 `ocs-media-task` 的 **P0 能力补齐**（纯 additive、默认行为逐字节不变、core 仍零重型依赖）。

#### 新增

**L4 门面 —— GIF 生成（第 9 个门面）**
- `Ffmpeg.gif(in, out[, GifOptions])` / `FfmpegClient.gif` + `gifAsync`：两遍调色板法生成 GIF（`fps`→可选 `scale`→`palettegen`/`paletteuse`），主流被两次消费由编译器**自动 `split` 重连**（菱形），产出与手写 type3 命令逐字节等价。
- `GifOptions`（不可变 wither）：`start`（默认 0）/`fps`（默认 15）/`duration`/`width`（未设不加 scale）/`height`/`scaleFlags`（缺省无 flags，可选 `lanczos`）。`-ss`/`-t` 均置输入侧。

**L3 curated 滤镜 —— 调色板族**
- `Filters.paletteGen(VideoStream)`（1 入 1 出）与 `Filters.paletteUse(VideoStream video, VideoStream palette)`（2 入 1 出，输入序 `[video][palette]`，与 `overlay` 同构）。curated 滤镜自 16 增至 18。

**extractAudio 采样率/声道**
- `ExtractAudioOptions.sampleRate(int)`→`-ar`、`channels(int)`→`-ac`（正整数校验，ASR 常需 16k 单声道）。设定后**禁用 `-c:a copy`**（copy 会静默忽略 `-ar`/`-ac`），对可 copy 源强制重编码。

**thumbnail 精确 seek**
- `ThumbnailOptions.seekMode(SeekMode)`（`INPUT_FAST` 默认 / `OUTPUT_ACCURATE`）：精确模式把 `-ss` 置于输出侧，时间点精确；默认保持输入侧快 seek，既有 argv 不变。

**probe 字段扩展**
- `StreamInfo` 扩：`profile`/`codecTag`/`hasBFrames`/`pixelFormat`/`level`/`timeBase`/`startTimeSeconds`/`durationSeconds`/`bitRate`/`nbFrames`/`sampleFormat`/`channelLayout`/`sampleAspectRatio`/`displayAspectRatio`/`attachedPic`（嵌套 `disposition.attached_pic`）/`language`（嵌套 `tags.language`）。
- `FormatInfo` 扩：`nbPrograms`/`startTimeSeconds`。字段类型经真 ffprobe JSON 锚定。

#### 变更

- `StreamInfo`/`FormatInfo`（record）新增字段使其**规范构造器签名变化**：直接 `new StreamInfo(...)`/`new FormatInfo(...)` 的调用方需注意——已提供**保留旧 10/6 参签名的便捷构造器**（新字段取缺失默认），故读侧访问器与旧构造调用均源码兼容。

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

[1.2.0]: https://github.com/pandong2015/ffmpeg4j/releases/tag/v1.2.0
[1.0.0]: https://github.com/pandong2015/ffmpeg4j/releases/tag/v1.0.0
