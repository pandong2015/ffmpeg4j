# media-probe Specification

## Purpose

以 `ffprobe -print_format json` 读取媒体元数据并解析为结构化 `ProbeResult`：至少暴露容器级信息（格式、时长、总码率）与每条流的信息（类型 video/audio/subtitle、编解码器、分辨率、帧率、采样率、声道等）。JSON 解析 MUST 使用极轻量依赖或自研微型解析器，`ffmpeg4j-core` 不引入重型 JSON 库；probe 失败以携带 `ffprobe` 失败信息的可诊断错误上抛。
## Requirements
### Requirement: 基于 ffprobe 的媒体元数据读取
库 SHALL 提供通过 `ffprobe` 读取媒体元数据的能力，至少暴露容器级信息（格式、时长、总码率）与每条流的信息（流类型 video/audio/subtitle、编解码器、分辨率、帧率、采样率、声道等）。

#### Scenario: 读取含音视频的文件元数据
- **WHEN** 用户对一个含视频轨与音频轨的文件调用 probe
- **THEN** 返回结构化结果，包含时长、各流的类型与编解码器等字段

#### Scenario: 探测识别字幕流
- **WHEN** 用户 probe 一个内嵌字幕轨的容器
- **THEN** 结果中该轨的流类型被标识为 subtitle

### Requirement: 轻量 JSON 解析与依赖约束
`ffprobe` 输出的 JSON MUST 使用极轻量第三方依赖（如 minimal-json/org.json）或自研微型 recursive-descent 解析器解析；`ffmpeg4j-core` MUST NOT 引入重型 JSON 库（如 Jackson）。JDK 不含公开受支持的 JSON 解析 API，故不作为选项。

#### Scenario: core 不含重型 JSON 依赖
- **WHEN** 检视 `ffmpeg4j-core` 的依赖树
- **THEN** 不存在 Jackson 等重型 JSON 库

### Requirement: probe 失败的结构化错误
当 `ffprobe` 无法解析目标（文件不存在或非法媒体）时，probe MUST 抛出可诊断错误，携带 `ffprobe` 的失败信息。

#### Scenario: 对不存在的文件 probe
- **WHEN** 用户对一个不存在的路径调用 probe
- **THEN** 抛出清晰错误说明文件不可读/不存在，而非返回空结果

#### Scenario: 对非法媒体 probe
- **WHEN** 用户对一个内容损坏/非媒体文件调用 probe
- **THEN** 抛出携带 `ffprobe` 失败信息的可诊断错误

### Requirement: 流级扩展字段（Info 回调白名单 + SAR/DAR + disposition/tags）
`StreamInfo` MUST 扩展覆盖 Info 回调白名单所需的流级字段，至少包含：`profile`、`codecTag`（`codec_tag_string`）、`hasBFrames`（`has_b_frames`，`Integer`）、`pixelFormat`（`pix_fmt`）、`level`（`Integer`）、`timeBase`（`time_base`）、`startTimeSeconds`（`start_time`，`double`）、`durationSeconds`（`duration`，`double`）、`bitRate`（`bit_rate`，`long`）、`nbFrames`（`nb_frames`，`long`）、`sampleFormat`（`sample_fmt`）、`channelLayout`（`channel_layout`）。命名对齐既有 `FormatInfo.durationSeconds`/`bitRate`/`nbStreams`（秒值统一 `xxxSeconds`、计数统一 `nbXxx`）。此外 MUST 收：`sampleAspectRatio`（`sample_aspect_ratio`）、`displayAspectRatio`（`display_aspect_ratio`）、`attachedPic`（`boolean`，取自嵌套 `disposition.attached_pic == 1`）、`language`（取自嵌套 `tags.language`）。这些字段 MUST 由 `ProbeMapper` 从 ffprobe JSON 的对应键（含嵌套对象）映射；某字段在 JSON 中缺失时 MUST 以 `null`（对象类型）/`false`（`attachedPic`）/既有哨兵填充，MUST NOT 因缺字段而失败。字段的类型专属性（视频专属如 `pixelFormat`/`level`/`hasBFrames`；音频专属如 `sampleFormat`/`channelLayout`）遵循 ffprobe 语义，不适用者为 `null`。数字型宽松解析（ffprobe 常把数字写成带引号字符串）复用既有 `JsonValue.asLong/asDouble`。

#### Scenario: 读取视频流的编码画质与画幅比字段
- **WHEN** 用户 probe 一个 H.264 视频并读取其视频流
- **THEN** 可获得 `profile`、`pixelFormat`、`hasBFrames`、`level`、`sampleAspectRatio`、`displayAspectRatio` 等字段（存在于源时非空）

#### Scenario: 读取音频流的采样格式与声道布局
- **WHEN** 用户 probe 一个含 AAC 音频的文件并读取其音频流
- **THEN** 可获得 `sampleFormat`、`channelLayout` 字段（存在于源时非空）

#### Scenario: 从嵌套 disposition 识别封面图流
- **WHEN** 用户 probe 一个内嵌封面图（`disposition.attached_pic=1` 的 mjpeg 视频流）的音频文件
- **THEN** 该流的 `attachedPic` 为 `true`，据此可与真实视频流区分

#### Scenario: 从嵌套 tags 读取语言标识
- **WHEN** 用户 probe 一个多音轨且各轨带 `tags.language` 的文件
- **THEN** 各流的 `language` 反映其 ffprobe `tags.language`（缺失为 `null`）

#### Scenario: 缺失字段填空而非失败
- **WHEN** 某流的 ffprobe JSON 不含 `nb_frames`/`bit_rate`/`disposition`/`tags` 等键
- **THEN** 对应字段为 `null`/`false`/哨兵值，probe 正常返回，不抛异常

### Requirement: 容器级扩展字段
`FormatInfo` MUST 扩展覆盖 `nbPrograms`（`nb_programs`，`int`）与 `startTimeSeconds`（`start_time`，`double`）两个容器级字段，由 `ProbeMapper` 从 ffprobe `-show_format` JSON 映射；缺失时以 `0`/哨兵值填充。命名对齐既有 `nbStreams`/`durationSeconds`。

#### Scenario: 读取容器起始时间与节目数
- **WHEN** 用户 probe 一个含 `start_time`/`nb_programs` 的容器
- **THEN** `FormatInfo` 暴露 `startTimeSeconds` 与 `nbPrograms` 字段

### Requirement: 分组流 List 天然支持多轨不覆盖
`ProbeResult` 已按类型返回分组的流 List（`videoStreams()`/`audioStreams()`/`subtitleStreams()`），本能力对多视频/多音轨文件 MUST 逐轨保留而非以单键覆盖，使下游 Info 回调可忠实呈现每一路轨（对齐下游 DG-3「多音轨/多视频流不覆盖」）。

#### Scenario: 双音轨文件逐轨呈现
- **WHEN** 用户 probe 一个含两条音轨的文件
- **THEN** `audioStreams()` 返回两个 `StreamInfo` 元素，各带独立的 `index`/编解码器/声道等字段，互不覆盖

### Requirement: 流级原始保真字段（codec_tag 十六进制 + start_time/duration 原始定点串）

`StreamInfo` MUST 新增三个**原始保真**字段，与既有 typed 便利字段并存、既有字段语义与哨兵**一律不变**：

- `codecTagHex`（`String`）：映射 ffprobe 原始 `codec_tag`（十六进制串，如 `"0x31637661"`），与既有 `codecTag`（=`codec_tag_string`，如 `"avc1"`）**并列**。`-show_streams` 命令已含 `codec_tag` 键，仅映射层新增采集。
- `rawStartTime`（`String`）：映射 ffprobe 原始 `start_time` **定点串**（如 `"0.000000"`），byte-exact 保留精度与尾零。
- `rawDuration`（`String`）：映射 ffprobe 原始 `duration` **定点串**，byte-exact。

这三个字段 MUST 由 `ProbeMapper` 以 `optString` 从对应 ffprobe 键映射：**存在则原样保留字符串、缺失则为 `null`**（区别于既有 `startTimeSeconds`/`durationSeconds` 的缺失塌缩为 `0.0`）。既有便利字段 `codecTag`/`startTimeSeconds`/`durationSeconds` MUST 保持不变（供「要可读串/要数值」的调用方零改动使用）。record 扩字段 MUST 沿用「append 到规范构造器末尾 + 保留旧 arity 便利构造器」范式：`StreamInfo` 规范构造器 26→29 参、保留既有 10 参便利构造器（新字段填缺省）并新增 26 参便利构造器（三个新字段填 `null`），使既有直接构造点源码兼容。

#### Scenario: 读取 codec_tag 十六进制与可读串并存
- **WHEN** 用户 probe 一个 H.264 视频并读取其视频流
- **THEN** `codecTagHex` 为原始十六进制串（如 `"0x31637661"`，存在于源时），`codecTag` 仍为可读 `codec_tag_string`（如 `"avc1"`），二者互不覆盖

#### Scenario: 逐字节复刻 start_time/duration 定点串
- **WHEN** 用户 probe 一个 `start_time="0.000000"`、`duration="12.500000"` 的流
- **THEN** `rawStartTime` 恰为 `"0.000000"`、`rawDuration` 恰为 `"12.500000"`（原始串逐字符保留，不经 `double` 往返丢精度/尾零）
- **AND** 既有 `startTimeSeconds`/`durationSeconds` 仍为对应 `double`（`0.0`/`12.5`）

#### Scenario: 缺失字段为 null 而非哨兵
- **WHEN** 某流的 ffprobe JSON 不含 `codec_tag`/`start_time`/`duration` 键
- **THEN** `codecTagHex`/`rawStartTime`/`rawDuration` 均为 `null`（下游据此区分「缺失」与「真实 0」），probe 正常返回不抛异常
- **AND** 既有 `startTimeSeconds`/`durationSeconds` 仍以 `0.0` 哨兵填充（既有语义不变）

#### Scenario: 既有满参构造点源码兼容
- **WHEN** 既有代码以旧的 26 参构造器直接构造 `StreamInfo`（含测试）
- **THEN** 经新增的 26 参便利构造器编译通过，三个新字段取 `null`，无需改动调用点

### Requirement: 容器级原始保真字段（start_time/duration 原始定点串）

`FormatInfo` MUST 新增 `rawStartTime`（`String`，`start_time` 原始定点串）与 `rawDuration`（`String`，`duration` 原始定点串），由 `ProbeMapper` 以 `optString` 从 `-show_format` JSON 映射：存在则 byte-exact 保留、缺失则为 `null`。既有 `startTimeSeconds`/`durationSeconds`（`double`）MUST 保持不变。record 扩字段沿用「append + 兼容构造器」范式：规范构造器 8→10 参、保留既有 6 参便利构造器并新增 8 参便利构造器（新字段填 `null`）。

#### Scenario: 容器级 duration 定点串保真
- **WHEN** 用户 probe 一个 `format.duration="60.024000"` 的容器
- **THEN** `FormatInfo.rawDuration` 恰为 `"60.024000"`，既有 `durationSeconds` 仍为 `60.024`

#### Scenario: 容器级缺失为 null
- **WHEN** 容器 JSON 不含 `start_time`
- **THEN** `FormatInfo.rawStartTime` 为 `null`，`startTimeSeconds` 仍为 `0.0`

