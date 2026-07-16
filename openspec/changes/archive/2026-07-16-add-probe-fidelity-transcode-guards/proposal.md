## Why

下游 `ocs-media-task` 的能力台账（`ocs-media-task/docs/media-task/ffmpeg4j-capability-requirements.md`，逐一核对 ffmpeg4j 1.4.0 源码得出）确认：8 任务 + worker 的 ffmpeg 处理**已全部门面化**，剩余仅是 5 处**库侧残留 gap**——它们目前逼迫下游走逃生舱、或缺失 typed 路径、或存在真实缺陷。这 5 项正是已归档的 `add-hls-segment-facade` 在其 `design.md` Non-Goals 里**明确登记为「独立 backlog」**的欠账（原文：「audio `-ar`/`-strict`、probe `codec_tag` hex、data/attachment 流均属独立 backlog…h265 VBV 已是既定边界」）。本变更集中清偿这批欠账，使 ffmpeg4j 完整满足下游要求。

**5 处残留（均已逐字核对源码确认仍存在）：**

1. **probe `codec_tag` 十六进制缺失**（唯一 `coverage=NONE`）：`StreamInfo` 只有 `codecTag`（=`codec_tag_string`，如 `"avc1"`），无原始 hex `codec_tag`（如 `"0x31637661"`）。`-show_streams` 输出已含该键，仅 `ProbeMapper.java:59` 未映射 → typed API **完全无路径**产出 hex。
2. **probe `start_time`/`duration` 原始定点串丢失 + 缺失哨兵非 null**：`ProbeMapper.java:64-67` 一律 `asDouble(0.0)` → ① 丢失 ffprobe 原始定点串（`"0.000000"`），`double→toString` 无法复刻精度/尾零；② 缺失塌缩为 `0.0` 而非 `null`，域内字段无法区分「真实 0」与「缺失」。
3. **transcode `videoCodec`/`audioCodec` 无 null 守卫 + 无纯音/视频退化路径**：`FacadeSupport.java:92-93,119-120` 无条件 `args.add(o.videoCodec())`，置 null 会 `args.add(null)` **污染 argv**（真实缺陷）；全仓无 `-vn`/`-an`，纯音频/纯视频无 typed 表达。
4. **transcode `-ar`/`-strict`/h265 VBV 无 typed 字段**：音频重采样 `-ar`、实验编码器 `-strict -2`、libx265 的 VBV 均须走 `extraOutputArgs`——而 `extraOutputArgs` **每次调用整体替换 List**（`TranscodeOptions.java:157-160`），多项须一次性传入，是脚枪。
5. **transcode `bufsize` 不派生 `maxrate×2`、不门控**：`FacadeSupport.java:111-118` 两个独立 `if`——设 `bufsize` 而不设 `maxrate` 时库仍渲染孤立 `-bufsize`（无效 VBV 配置，原命令永不产出）；`maxrate` 非空时不自动派生常用的 `bufsize=maxrate×2`。

## What Changes

- **probe 原始保真字段（media-probe，gap 1+2）**：`StreamInfo` 新增 `codecTagHex`（`codec_tag`）、`rawStartTime`（`start_time` 原始串）、`rawDuration`（`duration` 原始串）；`FormatInfo` 新增 `rawStartTime`、`rawDuration`。三者均经 `ProbeMapper` 以 `optString` 映射：**byte-exact 保留原始定点串、缺失→`null`**。既有 typed 便利字段（`codecTag`/`startTimeSeconds`/`durationSeconds`/`bitRate` 等）**一律不动**（byte-compat），新字段并存。record 扩字段沿用「append + 兼容构造器」范式（`StreamInfo` 26→29 参、`FormatInfo` 8→10 参，各补旧 arity 便利构造器）。
- **transcode 流禁用与 codec null 守卫（job-model，gap 3）**：`TranscodeOptions` 新增 `disableVideo(boolean)`/`disableAudio(boolean)`；启用时 `buildTranscode` 分别产 `-vn`/`-an`、跳过对应 `-c:v`/`-c:a` 与流映射。未禁用而 `videoCodec`/`audioCodec` 为 `null` → build 期 fail-fast（消除 `args.add(null)`）。二者同禁、`disableVideo`+`videoFilter` 冲突均 build 期 fail-fast。
- **transcode 音频/编码器进阶码控 typed（job-model，gap 4）**：新增 `audioSampleRate(int)`（→`-ar`）、`strict(String)` + typed 便利 `strictExperimental()`（→`-strict -2`）、`x265Params(String)`（→`-x265-params`）。三者 argv 插入位精确钉死（`-ar` 紧接 `-b:a`、`-x265-params` 紧接视频码控段尾、`-strict` 于全部码控段后 extraOutputArgs 前）。`x265Params` 是**显式 x265 通道**（非自动翻译 `maxrate`/`bufsize`，仍遵「不耦合 codec 字符串解析」既定边界），让 h265 VBV 摆脱 `extraOutputArgs` 整体替换 List 的脚枪。
- **transcode VBV 便利派生（job-model，gap 5）**：新增便利 `vbv(String maxrate)`（设 `maxrate` 并**在 build 期**、依最终 `maxrate` 派生 `bufsize=maxrate×2`，数值翻倍保留 K/M 单位；`vbv("2M").maxrate("3M")`→`bufsize=6M`）、`vbv(String maxrate, String bufsize)`（显式二参清除派生意图）。**裸 `maxrate()`/`bufsize()` 完全不变**（不派生、不门控，含孤立 `bufsize` 仍产出，保 byte-compat）；孤立 bufsize 仅 `Javadoc` 告警引导、**不** hard-fail（评审采纳：hard-fail 会破坏 byte-compat 且误伤合法 `-b:v`+`-bufsize`；gating 松紧可配，见 design D7b）。

## Capabilities

### Modified Capabilities

- `media-probe`：新增流级/容器级**原始保真字段**（`codecTagHex`、`rawStartTime`、`rawDuration`），与既有 typed 便利字段并存，忠实复刻 ffprobe 原始串并以 `null` 表达缺失。
- `job-model`：在 transcode 门面新增流禁用（`disableVideo`/`disableAudio` → `-vn`/`-an`）与 codec null 守卫、音频/编码器进阶 typed 码控（`audioSampleRate`/`strict`/`x265Params`）、VBV 便利派生与孤立 `bufsize` 门控（`vbv`）。

### Unaffected Capabilities

- `command-compiler`：**无需改动**。所有新增均为 output-args（`-vn`/`-an`/`-ar`/`-strict`/`-x265-params`/`-bufsize`）或流映射的取舍，经 `Output.to(...).withArgs(...)` 既有路径下发；probe 保真字段纯在映射层。
- `execution-engine`：不涉及。
- `spring-boot-starter`/`spring-observability`/`spring-async-events`：不涉及（纯 core 值/门面选项扩展）。

## Impact

- **纯 additive、byte-compat**：全为新增 record 字段（append + 兼容构造器）、`TranscodeOptions` 新 wither/访问器与 `buildTranscode` 新分支。**既有默认 argv 逐字节不变**（含孤立 `bufsize` 等既有可产出路径均不改）；新 argv 仅在**显式启用新字段**时出现。语义版本 **1.5.0**（minor）。
- **一处新 fail-fast + 两处新冲突守卫（均针对错误配置，非既有正常路径）**：① 未禁用而 codec 为 `null`（原会 `args.add(null)` 污染 argv）→ build 期可诊断异常；② `disableVideo`+`disableAudio` 同真（空输出）、`disableVideo`+`videoFilter`（引用不存在视频流）→ build 期抛错。均把「静默产坏 argv/坏映射」改为「build 期可诊断异常」；因 `defaults()` codec 恒非 null，对既有默认/合法配置零影响。（原拟的「孤立 bufsize hard-fail」经评审改为非破坏的 Javadoc 引导，见 gap 5 与 design D7。）
- **record 构造器 arity 变更**：`StreamInfo` 规范构造器 26→29 参、`FormatInfo` 8→10 参；旧 arity（含 v1.0 10 参 / 6 参）便利构造器保留，新字段默认 `null`——既有直接构造点（含测试）源码兼容。
- **非目标（登记后续 backlog）**：ProbeMapper 仍**跳过 data/attachment 流**（只保留 v/a/s）——若下游 `OtherStreams` 桶须纳 data/attachment，另起变更；不自动把 `maxrate`/`bufsize` 翻译为 x265-params（`x265Params` 显式通道即出路）；不改 `extraOutputArgs` 整体替换 List 语义（`vbv`/`x265Params`/`audioSampleRate`/`strict` typed 化后已大幅减少对它的依赖）。
