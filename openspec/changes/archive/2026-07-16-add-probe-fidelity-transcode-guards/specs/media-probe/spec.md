## ADDED Requirements

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
