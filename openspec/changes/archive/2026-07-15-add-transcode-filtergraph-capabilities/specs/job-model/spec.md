## ADDED Requirements

### Requirement: pad 表达式重载与 padToEven
`Filters` MUST 提供 `pad` 的表达式重载 `pad(VideoStream in, String width, String height, String x, String y, String color)`，其 `width`/`height`/`x`/`y` 接受 ffmpeg 表达式（如 `ceil(iw/2)*2`、`(ow-iw)/2`），经 `Arg.of`（`escape=false`）逐字进入 filtergraph。MUST 保留既有 `pad(VideoStream, int width, int height, String x, String y, String color)` 重载不变。MUST 提供 curated `padToEven(VideoStream in)`，固定产出 `pad=w=ceil(iw/2)*2:h=ceil(ih/2)*2`（仅 w/h，x/y 默认 0、color 默认 black，与 type1 一致），使调用方无需手写表达式即可把尺寸补到最近偶数。表达式值 MUST NOT 走 `Arg.escaped`（会破坏表达式中的字符）。

#### Scenario: padToEven 补齐偶数尺寸
- **WHEN** 用户对某视频流调用 `Filters.padToEven(v)` 并编译
- **THEN** 该滤镜体为 `pad=w=ceil(iw/2)*2:h=ceil(ih/2)*2`（仅 w/h，x/y/color 取默认，与 type1 一致），表达式逐字未被转义
- **AND** 对奇数维输入（如 `scale=100:-1` 得 100x75）产出最近偶数尺寸（100x76）

#### Scenario: pad 表达式重载逐字下发
- **WHEN** 用户调用 `Filters.pad(v, "ceil(iw/2)*2", "ceil(ih/2)*2", "(ow-iw)/2", "(oh-ih)/2", "black")`
- **THEN** 滤镜体含逐字表达式（`w=ceil(iw/2)*2` 等），未出现 `\:` 之类转义

#### Scenario: int 重载行为不变
- **WHEN** 用户调用既有 `Filters.pad(v, 1280, 720, "0", "0", "black")`
- **THEN** 滤镜体为 `pad=w=1280:h=720:x=0:y=0:color=black`（与既有一致）

### Requirement: overlay 的 shortest 收尾
`Filters` MUST 提供 `overlay` 的重载支持 `shortest`，至少 `overlay(VideoStream base, VideoStream over, String x, String y, boolean shortest)`；当 `shortest == true` 时在 overlay 参数追加 `shortest=1`。MAY 一并支持 `eof_action`。用于以 `-loop 1` 循环的水印/图片输入，使输出在主视频结束时收尾而非无限循环。MUST 保留既有仅 x/y 的两个重载不变。

#### Scenario: shortest 使循环水印收尾
- **WHEN** 用户调用 `Filters.overlay(base, over, "W-w-6", "H-h-6", true)`
- **THEN** 滤镜体含 `overlay=x=W-w-6:y=H-h-6:shortest=1`

#### Scenario: 既有 x/y 重载不变
- **WHEN** 用户调用既有 `Filters.overlay(base, over, 0, 0)`
- **THEN** 滤镜体为 `overlay=x=0:y=0`，不含 `shortest`

### Requirement: 2 输入原始视频滤镜逃生舱
`Filters` MUST 提供 `rawFilterVideo(VideoStream base, VideoStream over, String rawFilter)`，把 `rawFilter` 作为滤镜体逐字下发、接两路视频输入（`base` 为主、`over` 为叠加），产出一路视频。其内容 MUST NOT 参与类型校验与转义——正确性与必要的 filtergraph 转义（含表达式逗号 `\,`）由调用方自负。与既有单输入 `rawFilterVideo(VideoStream, String)` 对称，解锁任意多输入滤镜（如复杂水印 overlay 表达式）。

#### Scenario: 2 输入逃生舱接两路输入并逐字下发
- **WHEN** 用户以 `rawFilterVideo(base, wm, "overlay=shortest=1:x=if(eq(mod(n\\,200)\\,0)\\,sin(random(1))*W\\,x):y=0")` 组图（`base` 来自主视频、`wm` 来自另一 `-loop 1` 输入）并编译
- **THEN** 编译产物 `-filter_complex` 中该节点接两路输入（主视频在前、水印在后），滤镜体逐字为传入串

#### Scenario: 单输入逃生舱回归不变
- **WHEN** 用户以既有单输入 `rawFilterVideo(v, "pad=ceil(iw/2)*2:ceil(ih/2)*2")` 组图
- **THEN** 行为与既有一致（单输入、逐字下发）

### Requirement: transcode 视频滤镜链入口
`TranscodeOptions` MUST 提供 `videoFilter(Function<VideoStream, VideoStream>)`。当设置时，`buildTranscode` MUST 以 `input.video()`（必选映射 `0:v:0`）为起点应用该函数，其结果作为输出视频流，音频仍走 `audioOptional()`（缺音轨静默跳过）。当未设置（`null`）时 MUST 保持既有行为（视频/音频均可选映射），既有转码 argv 逐字节不变。函数内允许自建额外输入（如水印图 `Input.of(logo).withInputArgs("-loop","1").video()`）并叠加——编译器 MUST 自动发现并接线第二路 `-i`，故单参函数足以表达多输入水印。

#### Scenario: 挂滤镜链后视频必选、音频可选
- **WHEN** 用户设 `videoFilter(v -> Filters.scale(v, 1280, -1))` 并转码一个含音频的视频
- **THEN** 视频经 `-filter_complex` 处理并 `-map` 其输出，音频的 `-map` 带尾随 `?`（可选）

#### Scenario: 未设 videoFilter 时行为逐字节不变
- **WHEN** 用户以默认 `TranscodeOptions`（无 videoFilter）转码
- **THEN** argv 与历史版本逐字节一致（视频/音频双可选映射，无 `-filter_complex`）

#### Scenario: lambda 内多输入水印自动补 -i
- **WHEN** 用户在 `videoFilter` 内对第二个 `Input` 的视频流做 `overlay`
- **THEN** 编译产物含两路 `-i`，overlay 节点接 `[0:v:0][1:v:0]`，无需更宽的函数签名

### Requirement: transcode 类型化码控与 extraOutputArgs
`TranscodeOptions` MUST 增补类型化码控字段：`fps`（→输出 `-r`）、`maxrate`、`bufsize`、`gop`（关键帧间隔帧数，→派生 `-keyint_min N -g N -sc_threshold 0`）；以及 `extraOutputArgs(String...)` 原始逃生舱。类型化字段 MUST 渲染在前、`extraOutputArgs` 在后（同键 ffmpeg 取后者）。`maxrate`/`bufsize` 以 h264 惯用的 `-maxrate`/`-bufsize` 渲染；libx265 的 VBV（`x265-params vbv-maxrate=`）MUST 经 `extraOutputArgs` 表达（不自动翻译）。`extraOutputArgs` 内容不参与类型校验。未设的字段 MUST NOT 产出对应参数（保持既有 argv）。

#### Scenario: 类型化码控与 GOP 派生
- **WHEN** 用户设 `fps(25).maxrate("2M").bufsize("4M").gop(50)`
- **THEN** argv 含 `-r 25`、`-maxrate 2M`、`-bufsize 4M`，且 GOP 段为 `-keyint_min 50 -g 50 -sc_threshold 0`

#### Scenario: h265 VBV 经 extraOutputArgs 表达
- **WHEN** 用户设 `videoCodec("libx265").extraOutputArgs("-x265-params", "vbv-maxrate=2000:vbv-bufsize=4000")`
- **THEN** 这些参数追加在类型化码控字段之后，逐字进入 argv

#### Scenario: 未设码控字段时 argv 不变
- **WHEN** 用户以默认 `TranscodeOptions` 转码
- **THEN** argv 不含 `-r`/`-maxrate`/`-bufsize`/`-keyint_min`/`-g`/`-sc_threshold`（与既有逐字节一致）
