## Why

下游 `ocs-media-task`（media-executor 8 任务 + worker CODEC/SLICE）逐一核对 ffmpeg4j 1.0.x 源码后，梳理出一份能力需求清单（`sk-media-task/docs/media-task/ffmpeg4j-capability-requirements.md`）。其中 **4 项 P0 缺口**不补则相应任务**无法忠实实现或行为不一致**：

- **type3 GIF** 需两遍调色板（`palettegen`/`paletteuse`），当前 `Filters` 无 curated 调色板滤镜，`rawFilterVideo` 仅接单输入 → **无可行路径**。
- **type7 WAV**（字幕识别前置抽音频）需 `-ar`（ASR 常需 16k 单声道），`ExtractAudioOptions` 无采样率/声道字段。
- **type5 截图**要求输出侧精确 seek，`buildThumbnail` 固定用输入侧关键帧快 seek → 截图时间点可能偏移，与原实现不一致。
- **type4 Info** 回调白名单需 `Profile`/`PixelFormat`/`Bitrate`/`ChannelLayout` 等字段，`StreamInfo`/`FormatInfo` 未映射（原生 ffprobe JSON 已含）。

本变更收敛这 4 项 P0，**解锁 type3/5/7 与 type4 Info 的忠实实现**。P1（type1 转码滤镜链/码控、pad 表达式、overlay shortest、2 输入原始逃生舱）另立变更 `add-transcode-filtergraph-capabilities` 承接。

## What Changes

- **GIF 门面与调色板滤镜（P0-1）**：新增 curated `Filters.paletteGen(VideoStream)`（1 入 1 出）与 `Filters.paletteUse(VideoStream video, VideoStream palette)`（2 入 1 出），以及 L4 门面 `Ffmpeg.gif(in, out, GifOptions)`。**复用 command-compiler 现成的扇出自动 `split`**（源流被 paletteGen 与 paletteUse 各消费一次 = 菱形汇聚），编译器无需改动。
- **extractAudio 采样率/声道（P0-2）**：`ExtractAudioOptions` 增 `sampleRate(int)`→`-ar`、`channels(int)`→`-ac`，均可选、未设不追加（保持既有 argv）。
- **thumbnail 精确 seek（P0-3）**：`ThumbnailOptions` 增 `seekMode(SeekMode)`，`SeekMode ∈ {INPUT_FAST, OUTPUT_ACCURATE}`，**默认 `INPUT_FAST`**（既有行为不变）；`OUTPUT_ACCURATE` 把 `-ss` 置于输出侧。
- **probe 字段扩展（P0-4）**：`StreamInfo` 扩充白名单 `profile`/`codecTag`/`hasBFrames`/`pixelFormat`/`level`/`timeBase`/`startTime`/`duration`/`bitRate`/`frames`/`sampleFormat`/`channelLayout`，另收 `sampleAspectRatio`/`displayAspectRatio`（SAR/DAR）与嵌套提取的 `attachedPic`（`disposition.attached_pic`，稳健识别封面图流）/`language`（`tags.language`，多音轨语言）；`FormatInfo` 扩充 `numberPrograms`/`startTime`。flat `record` 续扩（sealed 子类型属未来 2.0），字段类型经真 ffprobe JSON 锚定；`ProbeMapper` 映射对应键（含嵌套对象），缺失填 `null`/`false`/哨兵值。

## Capabilities

### Modified Capabilities

- `job-model`: 在既有「类型化 curated 滤镜集」增补调色板族（palettegen/paletteuse）；在「L4 高层门面」增补 `gif`；`ExtractAudioOptions`/`ThumbnailOptions` 增补采样率/声道与 seek 模式选项。
- `media-probe`: 扩展 `StreamInfo`/`FormatInfo` 覆盖 Info 回调白名单字段（附赠 DG-3「多音轨/多视频流不覆盖」，因 `ProbeResult` 本已返回分组 List）。

### Unaffected Capabilities

- `command-compiler`: **无需改动**。GIF 两遍调色板是「同一流被消费两次」的菱形图，正是既有「扇出侦测与自动 split 插入 / 菱形图正确重连 / 按引用去重」覆盖的模式；`paletteUse` 是 2 输入 curated 滤镜，与既有 `overlay(base, over)` 同构。
- `execution-engine`: 无需改动（仅新增 argv 形状，不涉及进程/进度/取消语义）。

## Impact

- **纯 additive、读侧源码兼容**：新增门面方法、新增 Options 访问器、`record` 组件扩展（唯一构造点为内部 `ProbeMapper`）。语义版本走 **1.1.0**（minor）。
- **破坏点评估**：`StreamInfo`/`FormatInfo` 为 `record`，扩充分量会改变其规范构造器签名——但库内唯一构造点是 `ProbeMapper`，外部代码通常只读访问器，故对读者源码兼容。若下游存在直接 `new StreamInfo(...)` 的测试，需同步更新。
- **默认行为不变**：thumbnail 默认仍输入侧快 seek；extractAudio 未设采样率时 argv 与既有逐字节一致。
- **安全边界不因换库免除**：新门面/新选项的值若源自外部 `parameter.*`（`width`/`ar`/`start` 等），边界校验（正整数、时间戳正则、字符集白名单）仍是 **调用方（executor）责任**；ffmpeg4j 仅保证 argv 隔离（见 design 决策 D4）。
