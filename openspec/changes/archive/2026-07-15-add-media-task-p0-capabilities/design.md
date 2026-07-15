## Context

`ocs-media-task` 逐一核对 ffmpeg4j 1.0.x 源码，得出 4 项 P0 缺口。本变更在既有 L0–L4 架构与「不可变流即值」约束内**纯增补**能力，不重构。核对到的关键事实：

- `Filters.rawFilterVideo(VideoStream, String)` 仅接单输入（`Filters.java:210`）；但 `overlay(base, over, …)`（`Filters.java:50`）证明 `FilterNode` **已支持 2 输入 curated 滤镜**。
- `command-compiler` spec 已含「扇出侦测与自动 `split` 插入 / 菱形图正确重连 / 按引用去重」→ GIF 两遍调色板**无需动编译器**。
- `buildExtractAudio`（`FacadeSupport.java:206`）只出 `-vn -c:a <codec>`；`buildThumbnail`（`:226`）固定输入侧 `-ss`。
- `StreamInfo`（10 字段）/`FormatInfo`（6 字段）与 `ProbeMapper` 未映射 Info 白名单其余字段（原生 ffprobe JSON 已含）。

## Goals / Non-Goals

**Goals:**
- 让 type3/5/7 与 type4 Info **在库内有一等或近一等路径**，消除「临时留 core 手写 argv」。
- 全程 additive、默认行为不变、读侧源码兼容。
- 新滤镜/门面严守「流即值」纯函数与 argv 隔离。

**Non-Goals:**
- 不做 P1（转码滤镜链/码控、pad 表达式、overlay shortest、2 输入原始逃生舱）——另立 `add-transcode-filtergraph-capabilities`。
- 不把 `StreamInfo` 重构为 sealed `VideoStreamInfo`/`AudioStreamInfo` 谱系（见 D3 否决）。
- 不承接 HLS/DASH 切片（P2，留 core `cmd`）。
- ffmpeg4j **不**替 executor 做外部输入的语义校验（见 D4）。

## Decisions

### D1: GIF 走纯 curated（palettegen + 2 输入 paletteuse + `Ffmpeg.gif` 门面），不引入 2 输入原始逃生舱
GIF 的 `split[a][b];[a]palettegen[p];[b][p]paletteuse` 在「流即值」模型里是：对 `fps→scale` 后的 `base` 流，`paletteGen(base)` 得 `palette`，`paletteUse(base, palette)` 得成品——`base` 被消费两次，**编译器现成的扇出自动 `split`** 负责重连（菱形图）。`paletteUse` 是 2 输入 curated 滤镜，与 `overlay` 同构，`FilterNode` 已支持。

因此 P0-1 **不需要** `Filters.rawFilterVideo(base, over, raw)` 2 输入逃生舱。需求文档把该逃生舱列为「性价比最高的底座」，但核对后它真正解的是 **P1-4 的任意多输入 overlay 表达式**，与 GIF 解耦，下沉到变更 B。

**备选（否决）**：临时留 core 手写单条 `-filter_complex`。否决理由：库有干净的模型级表达，手写 argv 违背「用户永不手写 pad 名」的立项承诺。

### D2: `Ffmpeg.gif` 的 `-ss`/`-t` **均置输入侧**；缩放用无 flags 的 `Filters.scale`（与 type3 逐字节等价）
**地面真相更正 2（真 ffmpeg 8.0.1 实测）**：曾拟「输入侧 `-ss` + 输出侧 `-t`」，但实测输入侧 `-t` 与输出侧 `-t` 产出的 GIF **逐字节不同**——输入侧 `-t` 限制解码输入长度后再 `fps` 重采样，输出侧 `-t` 让 `fps` 看到整段再截，选帧不同。buildable-spec type3（`ffmpeg -ss {start} [-t {duration}] -i {original} ...`）两者**均在输入侧**，故 GIF 走 `Input.withInputArgs("-ss", start, "-t", duration)`（`duration` 未设则不加 `-t`），与 type3 对齐。

**GifOptions 默认（对齐 type3）**：`fps` 默认 **15**、`start` 默认 **0**；`duration` 可选（未设=从 `start` 到片尾）；`width` **可选**（未设=不加 `scale`、原分辨率；type3 的 `scale` 本就是可选段）、`height` 缺省 `-1` 按比例。`paletteUse` 渲染为裸 `paletteuse`（不设 `dither`，用 ffmpeg 默认 `sierra2_4a`，与 type3 一致）。

**地面真相更正（真 ffmpeg 8.0.1 实测）**：曾拟「默认 lanczos」，但实测——ffmpeg4j 编译器对 `input.video()→fps→scale`＋`paletteGen(base)`＋`paletteUse(base,palette)` 产出的链
`[0:v:0]fps=fps=10[v0];[v0]scale=w=320:h=-1[v1];[s4]palettegen[v2];[s5][v2]paletteuse[v3];[v1]split=2[s4][s5]`（`-map [v3]`）
生成的 GIF 与 buildable-spec 原 type3 链
`fps=10,scale=320:-1[s];[s]split[a][b];[a]palettegen[p];[b][p]paletteuse`
**逐字节相同**（`cmp` 通过）。且验证「`split` 定义在 palettegen/paletteuse 引用其 label **之后**」被 ffmpeg 接受（label 全局解析，与编译器注释相符）。原 type3 用无 flags 的 `scale`，故 GIF 默认亦用无 flags 的 `Filters.scale` 以保逐字节等价；lanczos 仅作 `GifOptions` 可选项、不默认。**因此无需**为 `scale` 增补 flags 重载（原 tasks 1.6 取消）。

补充承重验证：paletteuse 输入渲染为 `[s5][v2]` = `[base 分支][palette]`——顺序正确（`paletteUse(base,palette)` 的 `FilterNode.inputs=[base,palette]`，`GraphCompiler` 按 `node.inputs()` 序渲染消费者 label）。整个菱形（`base` 被 palettegen 与 paletteuse 各消费一次）由编译器现成扇出 `split=2` 自动重连，**零编译器改动**。

### D3: probe 用 flat `record` 续扩（不做 sealed），字段范围含 SAR/DAR + disposition/tags
**形态（已定）**：延续既有 flat `record`。新字段多类型专属（`pixelFormat`/`level`/`hasBFrames`=视频；`sampleFormat`/`channelLayout`=音频；`profile`/`codecTag`/`timeBase`/`startTime`/`duration`/`bitRate`/`frames`=共通），flat 里对另一类型恒 `null`；但唯一构造点是内部 `ProbeMapper`、读侧仅新增访问器、对读者源码兼容，代价最小、契合 1.1.0 additive。

**已否决（记录备选）**：仿 model 层 `VideoStream`/`AudioStream` 做 sealed `VideoStreamInfo`/`AudioStreamInfo` 最诚实、且与项目 sealed 哲学（CLAUDE.md「密封子类型把错配上提到 javac」）一致——但会破坏 `videoStreams()` 返回类型（`List<StreamInfo>`→`List<VideoStreamInfo>`）与基类型 `.width()` 调用点，属破坏性 2.0，不进 A，留作未来 major 另议。

**字段类型（真 ffprobe 8.0.1 JSON 实测锚定）**：`has_b_frames`/`level` 是裸整数 → `Integer`；`bit_rate`/`nb_frames`/`start_time`/`duration` 是带引号字符串 → 分别 `long`/`long`/`double`/`double`（`JsonValue.asLong/asDouble` 已宽松兼容字符串数字，`JsonValue.java:67-114`，无需新解析机制）；`profile`/`codec_tag_string`/`pix_fmt`/`sample_fmt`/`channel_layout`/`time_base` → `String`。类型专属字段在另一类型上 JSON 直接缺失（实测 `has_b_frames`/`level` 仅现于视频流、`sample_fmt`/`channel_layout` 仅音频流）→ `optInt`/`optString` 返回 `null`。

**字段范围（已定：白名单 + SAR/DAR + disposition/tags）**：除 §3 白名单，额外收：
- `sampleAspectRatio`/`displayAspectRatio`（`sample_aspect_ratio`/`display_aspect_ratio`，`String` 如 `"1:1"`/`"4:3"`）——与 ffmpeg4j 全局关注 SAR、归一化 MUST setsar 的哲学一致，成本极低。
- `attachedPic`（`boolean`，取自嵌套 `disposition.attached_pic == 1`）——让 P0-2 extractAudio 可**稳健**识别封面图流（不再仅靠 `-vn`+`0:a:0` 启发式；封面为 `mjpeg` 视频流带 `attached_pic=1`），亦供 Info 标注。
- `language`（`String`，取自嵌套 `tags.language`）——多音轨 Info 的语言标识，助 DG-3。
- `disposition`/`tags` 是嵌套对象，`ProbeMapper` 经 `s.opt("disposition").opt("attached_pic").asInt(0)`、`s.opt("tags").opt("language").asString(null)` 提取（`JsonValue.opt` 对缺失返回 `NULL` 单例，天然健壮，不抛）。

### D4: thumbnail `seekMode` 默认 `INPUT_FAST`，`OUTPUT_ACCURATE` 为纯输出侧
默认保持既有输入侧关键帧快 seek（不改历史行为、大文件不从 0 解码）。`OUTPUT_ACCURATE` 把 `-ss` 置于 `-i` 之后，**逐字节等价**于原 type5 命令（`-i in -ss t -frames:v 1`），media-task 显式 opt-in。

**备选（记录未来优化）**：ffmpeg 惯用的「`-ss`(粗) 放 `-i` 前 + `-ss`(细) 放 `-i` 后」两段式 seek，对大文件比纯输出侧快得多。本变更**不做**——纯输出侧才与原命令逐字节等价，两段式待后续按需引入第三种 `SeekMode`。

### D5: 安全边界——argv 隔离由库保证，语义校验仍属 executor
ffmpeg4j 消除 shell 注入（所有值都是独立 argv 元素）。但新门面/新选项若接收源自外部 `parameter.*` 的值（`width`/`ar`/`start`/`gif fps` 等），其**语义合法性**（正整数、时间戳正则、字符集白名单）**不**由 ffmpeg4j 校验——那是调用方（executor 的 shared-core-security 边界校验）责任。库仅对自身**类型化选项**做基本约束（如 `sampleRate`/`channels` 须正整数，构造期拒绝非法值）。此边界写入 spec 附注，避免「换了库就以为免了校验」的误解。

### D6: extractAudio 设了 `sampleRate`/`channels` 即**强制重编码**，绝不 `copy`
**地面真相（真 ffmpeg 8.0.1 实测）**：`-c:a copy -ar 16000` 会**静默忽略** `-ar`（输出仍 44100，ffmpeg 不报错）。而 `buildExtractAudio` 对 m4a/aac 源会解析成 `copy`（`audioCodecForExtension`：`"m4a","aac" -> "aac".equals(source) ? "copy" : "aac"`）。若用户此时又设 `sampleRate`/`channels`，将静默产出错误采样率。

**决策**：当 `sampleRate` 或 `channels` 任一非空时，`buildExtractAudio` MUST NOT 用 `copy`——须回退到该扩展名的自然编码器（m4a/aac→`aac`）以真正重采样。实现：`audioCodecForExtension` 增一个「是否需重采样」入参，或在 `buildExtractAudio` 内于 `copy` 结果上按需覆盖。无重采样诉求时维持既有 `copy` 优化不变。

### D7: probe 新字段命名对齐既有 `durationSeconds`/`bitRate`/`nbStreams` 约定
既有 `FormatInfo` 用 `durationSeconds`/`bitRate`/`size`/`nbStreams`。为一致：`StreamInfo` 新字段用 `startTimeSeconds`(double)/`durationSeconds`(double)/`bitRate`(long)/`nbFrames`(long)（而非 `startTime`/`duration`/`frames`）；`FormatInfo` 新字段用 `startTimeSeconds`(double)/`nbPrograms`(int)。秒值统一 `xxxSeconds` 后缀、计数统一 `nbXxx` 前缀，避免同库两套命名。

## Risks / Trade-offs

- **record 扩字段的下游测试**：若 `ocs-media-task` 或第三方直接 `new StreamInfo(...)`，本变更会破其编译。缓解：库内改 `ProbeMapper` 单点；发布说明提示下游改用访问器而非构造器。
- **GIF scale flags**：若为 `scale` 增补 flags 形参，需保证既有 `scale(in, w, h)` 重载不变（additive 重载）。
- **paletteuse 的 2 输入顺序语义**：`paletteUse(video, palette)` 顺序有语义（video 在前、palette 在后），与 `overlay(base, over)` 一致；spec 用 scenario 锁定 `[video][palette]` 顺序，防实现接反。

## Migration / Rollout

- 无数据迁移。下游按需 opt-in 新能力：type3 改调 `Ffmpeg.gif`、type7 加 `.sampleRate(16000).channels(1)`、type5 加 `.seekMode(OUTPUT_ACCURATE)`、type4 直接读 `StreamInfo`/`FormatInfo` 新访问器。
- 测试遵循仓库约定：纯逻辑（argv 产物、probe 映射）脱离进程单测、断言精确值；GIF 端到端用 `-f lavfi -i testsrc` 现场生成素材并 `assumeTrue(commandExists("ffmpeg"))` 守卫。
