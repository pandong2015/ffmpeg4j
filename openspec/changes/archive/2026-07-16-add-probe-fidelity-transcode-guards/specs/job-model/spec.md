## ADDED Requirements

### Requirement: transcode 流禁用与 codec null 守卫

`TranscodeOptions` MUST 提供 `disableVideo(boolean)`（默认 `false`）与 `disableAudio(boolean)`（默认 `false`）。当 `disableVideo` 为 `true` 时，`buildTranscode` MUST 产出 `-vn`、跳过 `-c:v` 及全部视频码控参数（crf/preset/`-b:v`/`-r`/`-maxrate`/`-bufsize`/GOP/`-force_key_frames`/`-x265-params`）、且输出**只映射音频**（`input.audioOptional()`）。当 `disableAudio` 为 `true` 时，MUST 产出 `-an`、跳过 `-c:a` 及 `-b:a`/`-ar`、且输出**只映射视频**。当既未 `disableVideo` 而 `videoCodec()` 为 `null`（或既未 `disableAudio` 而 `audioCodec()` 为 `null`）时，`buildTranscode` MUST 在 build 期抛可诊断 `FfmpegException`（点名该 codec 为 `null` 需设编码器或调用对应 disable），MUST NOT 把 `null` 加入 argv。`disableVideo` 与 `disableAudio` 同为 `true`（空输出）、或 `disableVideo` 与 `videoFilter` 同时设置（无视频可供滤镜链消费）时 MUST build 期 fail-fast。默认（二者均 `false`、codec 非 `null`）行为 MUST 与既有逐字节一致。

#### Scenario: 纯音频禁用视频产 -vn
- **WHEN** 用户设 `disableVideo(true).audioCodec("aac")` 转码
- **THEN** argv 含 `-vn`、含 `-c:a aac`、不含 `-c:v`、不含任何视频码控参数，且输出只映射音频（无视频 `-map`）

#### Scenario: 纯视频禁用音频产 -an
- **WHEN** 用户设 `disableAudio(true).videoCodec("libx264")` 转码
- **THEN** argv 含 `-an`、含 `-c:v libx264`、不含 `-c:a`/`-b:a`/`-ar`，且输出只映射视频

#### Scenario: codec 为 null 时 build 期 fail-fast
- **WHEN** 用户设 `videoCodec(null)` 但未 `disableVideo`
- **THEN** build 期抛可诊断 `FfmpegException`（点名 videoCodec 为 null），argv 中 MUST NOT 出现 `null` 元素

#### Scenario: 同时禁用音视频 fail-fast
- **WHEN** 用户设 `disableVideo(true).disableAudio(true)`
- **THEN** build 期抛可诊断异常（空输出无意义）

#### Scenario: disableVideo 与 videoFilter 冲突 fail-fast
- **WHEN** 用户同时设 `disableVideo(true)` 与 `videoFilter(...)`
- **THEN** build 期抛可诊断异常（视频已禁用、无流可供滤镜链）

#### Scenario: 默认不产禁用标志
- **WHEN** 用户以默认 `TranscodeOptions` 转码
- **THEN** argv 不含 `-vn`/`-an`（与既有逐字节一致）

### Requirement: transcode 音频与编码器进阶 typed 码控（-ar / -strict / -x265-params）

`TranscodeOptions` MUST 提供进阶 typed 码控字段，均默认不设、未设时 MUST NOT 产出对应参数（保持既有 argv）。**本需求是这三个字段 argv 渲染位置的唯一权威**（钉死相对既有段的精确相邻位，与本项目「断言精确 argv」文化一致）：

- `audioSampleRate(int hz)`（`hz>0` 校验，否则 wither 即时抛 `IllegalArgumentException`）→ 渲染 `-ar <hz>` **紧接 `-b:a` 之后**（音频段末尾、GOP 段之前）；`disableAudio` 时 MUST NOT 产出（属音频段，随 `-an` 一并跳过）。
- `strict(String level)` → 渲染 `-strict <level>`（`level` 以独立 argv 元素下发，故 `strict("-2")` 产 `["-strict","-2"]`，`-2` 是 `-strict` 的参数值不被误判为新 flag）。渲染位置 MUST 为**全部类型化码控段之后、`extraOutputArgs` 之前**（即紧接 GOP/`-force_key_frames` 段之后）；与音/视频禁用标志**无关**（始终产出，因 `-strict` 是编码器通用旗标、可作用于视频或音频编码器）。另 MUST 提供便利 `strictExperimental()`（等价 `strict("-2")`，覆盖最常见的「允许实验编码器」诉求）；`strict(String)` 保留为通用逃生舱、`Javadoc` MUST 列出合法值（`-2..2` / `experimental`/`unofficial`/`normal`/`strict`/`very`）。
- `x265Params(String value)` → 渲染 `-x265-params <value>` **紧接视频码控段末尾**（`-r`/`-maxrate`/`-bufsize` 之后、`-c:a` 之前）；`disableVideo` 时 MUST NOT 产出（属视频段，随 `-vn` 一并跳过）。此字段为**显式 x265 通道**：库 MUST NOT 侦测 codec、MUST NOT 自动把 `maxrate`/`bufsize` 翻译为 x265-params（延续「不耦合 codec 字符串解析」既定边界）；其存在仅为让 h265 VBV 有 typed 位置、免受 `extraOutputArgs` 整体替换 List 的脚枪。`x265Params` 的 `Javadoc` MUST 注明「仅对 libx265 有意义，库不校验 codec；libx264 的 `-x264-params` 仍走 `extraOutputArgs`」。

#### Scenario: 音频采样率紧接 -b:a
- **WHEN** 用户设 `audioCodec("aac").audioBitrate("128k").audioSampleRate(44100)`
- **THEN** argv 音频段为精确相邻的 `-c:a aac -b:a 128k -ar 44100`（`-ar` 紧接 `-b:a`）

#### Scenario: strict 于码控段尾、extraOutputArgs 前
- **WHEN** 用户设 `gop(50).strictExperimental().extraOutputArgs("-movflags", "+faststart")`
- **THEN** argv 含 `-strict -2`（两个独立元素），且位于 GOP 段（`-keyint_min 50 -g 50 -sc_threshold 0`）之后、`-movflags +faststart` 之前
- **AND** `strict("-2")` 与 `strictExperimental()` 产出逐字节相同

#### Scenario: x265Params 紧接视频码控段尾
- **WHEN** 用户设 `videoCodec("libx265").maxrate("2M").bufsize("4M").x265Params("vbv-maxrate=2000:vbv-bufsize=4000")`
- **THEN** argv 视频码控段为精确相邻的 `... -maxrate 2M -bufsize 4M -x265-params vbv-maxrate=2000:vbv-bufsize=4000 -c:a ...`（`-x265-params` 在 `-bufsize` 之后、`-c:a` 之前）

#### Scenario: 未设进阶字段时 argv 不变
- **WHEN** 用户以默认 `TranscodeOptions` 转码
- **THEN** argv 不含 `-ar`/`-strict`/`-x265-params`（与既有逐字节一致）

## MODIFIED Requirements

### Requirement: transcode 类型化码控与 extraOutputArgs
`TranscodeOptions` MUST 增补类型化码控字段：`fps`（→输出 `-r`）、`maxrate`、`bufsize`、`gop`（关键帧间隔帧数，→派生 `-keyint_min N -g N -sc_threshold 0`）；以及 `extraOutputArgs(String...)` 原始逃生舱。类型化字段 MUST 渲染在前、`extraOutputArgs` 在后（同键 ffmpeg 取后者）。`maxrate`/`bufsize` 以 h264 惯用的 `-maxrate`/`-bufsize` 渲染；libx265 的 VBV MUST 经**显式** `x265Params(String)`（→`-x265-params`）或 `extraOutputArgs` 表达（库不自动翻译 `maxrate`/`bufsize`，不耦合 codec 字符串解析）。`extraOutputArgs` 内容不参与类型校验。未设的字段 MUST NOT 产出对应参数（保持既有 argv）。

裸 `maxrate(String)`/`bufsize(String)` 的独立渲染行为 MUST 保持**完全不变**（各自独立 `if`、不自动派生、不互相门控），以维持既有 argv 逐字节——**包括**「只设 `bufsize` 不设 `maxrate`」时仍产出孤立 `-bufsize`（既有可产出路径，本变更**不**改、不 hard-fail：ffmpeg 接受该参数，配 `-b:v` 时是合法的缓冲约束，硬失败会误伤且破坏 byte-compat）。`bufsize(String)` 的 `Javadoc` MUST 告警「孤立 `bufsize`（无 `maxrate`）通常是配置疏漏，VBV 请配 `maxrate` 或改用 `vbv()`」——以**引导**替代 hard-fail 达成台账所述「门控」意图。此外 MUST 补充 **VBV 便利派生**：

- MUST 提供 `vbv(String maxrate)`：设 `maxrate` 并标记「待派生 bufsize」意图；`vbv(String maxrate, String bufsize)`：显式设二者并清除该意图。任何显式 `bufsize(String)` 调用亦 MUST 清除该意图（用户显式优先）。
- 派生 MUST 发生在 **build 期**（`buildTranscode`），而非 wither 调用当刻：当「待派生」意图为真且 `bufsize()` 仍为 `null` 时，依**最终** `maxrate()` 求 `bufsize = maxrate×2`（数值前缀翻倍、保留 K/M 等单位后缀、locale 无关去尾零渲染，如 `"2M"→"4M"`、`"2000k"→"4000k"`）。由此 `vbv("2M").maxrate("3M")` MUST 得 `bufsize=6M`（跟随最终 maxrate，不留陈旧值）、`vbv("2M").vbv("3M")` MUST 得 `bufsize=6M`。`maxrate` 不可解析为「数值+可选单位」时 MUST 即时抛 `IllegalArgumentException`。

#### Scenario: 类型化码控与 GOP 派生
- **WHEN** 用户设 `fps(25).maxrate("2M").bufsize("4M").gop(50)`
- **THEN** argv 含 `-r 25`、`-maxrate 2M`、`-bufsize 4M`，且 GOP 段为 `-keyint_min 50 -g 50 -sc_threshold 0`

#### Scenario: h265 VBV 经 x265Params 或 extraOutputArgs 表达
- **WHEN** 用户设 `videoCodec("libx265").extraOutputArgs("-x265-params", "vbv-maxrate=2000:vbv-bufsize=4000")`
- **THEN** 这些参数追加在类型化码控字段之后，逐字进入 argv（库不把 `maxrate`/`bufsize` 自动翻译为 x265-params）

#### Scenario: vbv 便利派生 bufsize=maxrate×2
- **WHEN** 用户设 `vbv("2M")`（不显式设 bufsize）
- **THEN** argv 含 `-maxrate 2M` 与 `-bufsize 4M`（build 期依 maxrate 数值翻倍、保留 `M` 单位派生）

#### Scenario: vbv 显式二参不派生
- **WHEN** 用户设 `vbv("2M", "6M")`
- **THEN** argv 含 `-maxrate 2M` 与 `-bufsize 6M`（显式 bufsize 清除派生意图、不被覆盖）

#### Scenario: vbv 后改 maxrate 派生跟随最终值
- **WHEN** 用户设 `vbv("2M").maxrate("3M")`（不显式设 bufsize）
- **THEN** argv 含 `-maxrate 3M` 与 `-bufsize 6M`（build 期依最终 maxrate `3M` 翻倍，不留 `4M` 陈旧值）

#### Scenario: 孤立 bufsize 保持既有行为（byte-compat，不 hard-fail）
- **WHEN** 用户设 `bufsize("4M")` 但未设 `maxrate`、未调 `vbv`
- **THEN** argv 仍含孤立 `-bufsize 4M`（既有行为逐字节不变，不抛异常）；该用法仅由 `Javadoc` 告警引导，不硬失败

#### Scenario: 裸 maxrate 不自动派生 bufsize
- **WHEN** 用户设 `maxrate("2M")`（不设 bufsize、不调 vbv）
- **THEN** argv 含 `-maxrate 2M` 但**不含** `-bufsize`（裸字段行为逐字节不变、不自动派生）

#### Scenario: 未设码控字段时 argv 不变
- **WHEN** 用户以默认 `TranscodeOptions` 转码
- **THEN** argv 不含 `-r`/`-maxrate`/`-bufsize`/`-keyint_min`/`-g`/`-sc_threshold`（与既有逐字节一致）
