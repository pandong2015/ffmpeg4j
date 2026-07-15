## ADDED Requirements

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
