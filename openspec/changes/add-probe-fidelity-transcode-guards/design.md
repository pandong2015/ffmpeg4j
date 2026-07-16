## Context

本变更是下游 `ocs-media-task` 能力台账（逐一核对 ffmpeg4j 1.4.0 源码）挖出的 **5 处库侧残留 gap** 的集中清偿。这批欠账已被已归档的 `add-hls-segment-facade` 在其 `design.md` Non-Goals 里显式登记为「独立 backlog」（audio `-ar`/`-strict`、probe `codec_tag` hex、data/attachment 流；h265 VBV 为既定边界）。台账把它们分成两族：

- **probe 保真族（gap 1+2）**：typed 便利字段（`double`/`long`）对下游「逐字节复刻 ffprobe 输出」「区分缺失与真实 0」两个契约有损——`double→toString` 丢精度/尾零、缺失塌缩为哨兵。
- **transcode 守卫/进阶族（gap 3+4+5）**：codec 无 null 守卫（真实缺陷）、纯音/视频无 typed 退化路径、`-ar`/`-strict`/h265 VBV 只能走 `extraOutputArgs`（其整体替换 List 是脚枪）、`bufsize` 不派生不门控。

关键源码事实（核对确认）：

- `StreamInfo`/`FormatInfo` 是**只读结果 `record`**（含兼容构造器扩字段的既定范式，见 watchout #10）；`ProbeMapper` 是纯函数映射层，`optInt`/`optString` 已具「缺失→null」能力，只是 `start_time`/`duration` 走了 `asDouble(0.0)`。
- `TranscodeOptions` 是 `final class + 私有构造 + defaults() + wither`（**刻意不用 record**，因规范构造器是公开 API）；`buildTranscode`（`FacadeSupport.java:89-158`）是零副作用纯函数，产 argv 顺序固定：`-c:v` → crf/preset/`-b:v` → `-r` → `-maxrate`/`-bufsize` → `-c:a` → `-b:a` → GOP → force_key_frames → `extraOutputArgs`。
- `buildTranscode` 无 videoFilter 时映射 `input.videoOptional()` + `input.audioOptional()`（双可选 `0:v:0?`/`0:a:0?`）；有 videoFilter 时视频必选。

## Goals / Non-Goals

**Goals:**
- probe 保真：并存策略——保留全部既有 typed 便利字段（byte-compat），**新增** raw String 字段忠实复刻 ffprobe 原始串、缺失→`null`；补齐唯一 `coverage=NONE` 的 hex `codec_tag`。
- transcode 守卫：消除 `args.add(null)` 污染（codec null → build 期 fail-fast）；提供纯音频/纯视频的 typed 退化（`-vn`/`-an`）。
- transcode 进阶：`-ar`/`-strict`/x265 VBV typed 化，摆脱 `extraOutputArgs` 整体替换 List 脚枪；`bufsize=maxrate×2` 便利派生 + 孤立 bufsize 门控。
- 全程 additive，既有默认 argv **逐字节不变**；新行为仅在显式启用新字段（或触发错误配置的 fail-fast）时出现。

**Non-Goals:**
- **不做 data/attachment 流映射**：`ProbeMapper` 仍只保留 video/audio/subtitle 流；下游 `OtherStreams` 若须 data/attachment，另起变更（台账登记，非本变更范围）。
- **不自动翻译 `maxrate`/`bufsize` → x265-params**：延续既定边界「库不耦合 codec 字符串解析」；`x265Params(String)` 是**显式**通道，非自动侦测 codec。
- **不改 `extraOutputArgs` 整体替换 List 语义**：typed 化 `-ar`/`-strict`/`x265Params`/`vbv` 后对它的依赖已大幅收窄，改其语义是另一范畴的破坏性变更。
- **不改既有 typed 便利字段**：`startTimeSeconds`/`durationSeconds`/`bitRate`/`codecTag` 语义与哨兵一律不动。
- 不引入任何第三方依赖。

## Decisions

### D1: 一个 change 收齐 5 项残留（不拆碎）
沿用 `add-media-task-p0-capabilities`「一个变更收多个同源 gap」的既定范式。5 项同源于同一台账、同一发版窗口（1.5.0），且 gap 4/5 与 gap 3 同改 `TranscodeOptions.java`/`buildTranscode`，拆开反而制造 diff 交叉。probe 族与 transcode 族经**独立 delta spec 文件**（`specs/media-probe/` vs `specs/job-model/`）隔离表达。

### D2: probe 保真采「typed 便利 + raw String」并存，不改既有字段
既有 `startTimeSeconds`/`durationSeconds`（`double`）继续服务「要数值就好」的调用方（byte-compat、零改动）；**新增** `rawStartTime`/`rawDuration`（`String`）服务「要逐字节复刻 + 区分缺失」的调用方。二者并存、各取所需。理由：改既有 `double` 字段为 `String` 是破坏性变更且损失数值便利；新增并存是纯 additive。**只对 `start_time`/`duration` 加 raw**——因 `0.0` 是合法值、无法与缺失区分，且定点串有精度/尾零语义；而 `bit_rate`/`nb_frames`/`size` 的 `-1` 哨兵下游可无损 `if(<0)→null` 还原（台账明述），**不**加 raw（避免字段膨胀）。

### D3: `codec_tag` hex 作独立字段 `codecTagHex`，与 `codecTag`(=`codec_tag_string`) 并列
命名区分：`codecTag`=可读串（`"avc1"`，既有），`codecTagHex`=原始十六进制（`"0x31637661"`，新增，=ffprobe 键 `codec_tag`）。`-show_streams` 命令**已含** `codec_tag` 键，仅 `ProbeMapper` 未映射——本变更只加一行 `optString(s,"codec_tag")`，命令层零改动。缺失→`null`。

### D4: record 扩字段沿用「append + 兼容构造器」范式（watchout #10）
新字段一律 **append 到规范构造器末尾**（不中插，避免打乱既有便利构造器的定位委托）：`StreamInfo` 26→29 参（`codecTagHex`/`rawStartTime`/`rawDuration`），`FormatInfo` 8→10 参（`rawStartTime`/`rawDuration`）。逻辑分组经 Javadoc 表达而非物理相邻。各补**旧 arity 便利构造器**：`StreamInfo` 保留 v1.0 的 10 参、**新增** 26 参便利构造器（新字段填 `null`）；`FormatInfo` 保留 6 参、**新增** 8 参便利构造器。既有直接构造点（含测试）**源码兼容**、无需改动。`ProbeMapper` 改用新规范构造器传全部字段。

### D5: transcode 流禁用 `disableVideo`/`disableAudio` + codec null 守卫
- `disableVideo(boolean)`（默认 false）：`true` 时 `buildTranscode` 产 `-vn`、**跳过** `-c:v` 及全部视频码控（crf/preset/`-b:v`/`-r`/maxrate/bufsize/gop/force_key_frames）、输出**只映射音频**（`Output.to(out, input.audioOptional())`）。
- `disableAudio(boolean)`（默认 false）：`true` 时产 `-an`、跳过 `-c:a` 及 `-b:a`/`-ar`、输出**只映射视频**。
- **null 守卫**：未 `disableVideo` 而 `videoCodec()==null`（未 `disableAudio` 而 `audioCodec()==null`）→ build 期抛可诊断 `FfmpegException`（点名「videoCodec 为 null：请设编码器或调 disableVideo()」），**消除** `args.add(null)`。因 `defaults()` 恒置 `libx264`/`aac`，仅显式 `videoCodec(null)` 才触发、无既有回归。**取舍（有意决策）**：本门面是「强制转码」，**不**支持「省略 `-c:v` 由 ffmpeg 依容器自选默认编码器」这一合法 ffmpeg 工作流——须显式 `videoCodec` 或 `disableVideo`；Javadoc 明示此边界（避免读者误以为是疏漏）。
- **冲突处理的宽/严判据（有意不一致，Javadoc 明示）**：`disableVideo`+`disableAudio` 同真（空输出）、`disableVideo`+`videoFilter` 同设（滤镜链引用不存在的视频流）→ **fail-fast**（引用不存在的流/映射是真错）；而 `disableVideo` 下的 crf/preset/maxrate/bufsize/gop/force、`disableAudio` 下的 audioSampleRate → **静默跳过**（仅无处生效、无害）。判据：「引用不存在的流→抛错；无害而无效→静默」。`disableVideo`/`disableAudio` 的 Javadoc MUST 列明各自静默忽略哪些字段。
- **孤立-bufsize 与 disableVideo 的次序**：`disableVideo` 时整个视频段（含任何 bufsize 处理）短路，不评估 bufsize——故 `disableVideo(true).bufsize("4M")` 走纯音频、不涉 bufsize（且本变更 bufsize 本就不 hard-fail，见 D7）。
- argv 位置：`-vn` 取代整个视频段（段首）；`-an` 取代整个音频段（`-c:a` 原位）。

### D6: 进阶码控 typed——`audioSampleRate`/`strict`/`strictExperimental`/`x265Params`（位置精确钉死）
段序参照现 `buildTranscode`：`[视频码控: -c:v … -maxrate -bufsize]` → `[音频: -c:a -b:a]` → `[GOP: -keyint_min -g -sc_threshold]` → `[-force_key_frames]` → `[extraOutputArgs]`。三字段插入位精确钉死（delta spec 的 ADDED「进阶 typed 码控」需求是唯一权威，本处仅综述）：
- `audioSampleRate(int hz)`（`>0` 校验）→ `-ar <hz>`，**紧接 `-b:a`**（音频段尾）；`disableAudio` 时不产出。
- `strict(String level)`（默认 null）→ `-strict <level>`（`strict("-2")`→`-strict -2`；值以独立 argv 元素下发）。渲染于**全部码控段之后、`extraOutputArgs` 之前**（紧接 GOP/`-force_key_frames`）；**与禁用标志无关**（始终产出——`-strict` 是编码器通用旗标，可作用于视频或音频，故不绑音频段、独立位置，一并解决 LOW「-strict 绝对位置欠定」）。另提供 typed 便利 `strictExperimental()`（=`strict("-2")`，最常见诉求）；`strict(String)` 留作通用逃生舱、Javadoc 列合法值。
- `x265Params(String)`（默认 null）→ `-x265-params <value>`，**紧接视频码控段尾**（`-bufsize` 之后、`-c:a` 之前）；`disableVideo` 时不产出。**显式 x265 通道**：不侦测 codec、不翻译 `maxrate`/`bufsize`；仅让 h265 VBV 有 typed 位置（免 `extraOutputArgs` 整体替换 List）。Javadoc 注明「仅对 libx265 有意义、库不校验 codec；libx264 的 `-x264-params` 仍走 extraOutputArgs（单侧供给因 h265 VBV 是唯一驱动）」。

### D7: VBV 便利 `vbv`（build 期派生）+ 孤立 `bufsize` 以引导替代 hard-fail（评审采纳）
评审三视角一致指出：把「孤立 bufsize（无 maxrate）」升为 build 期 hard-fail 会 ①破坏本仓库神圣的「既有 argv 逐字节不变」（现库 `FacadeSupport.java:115-118` 确实会产出孤立 `-bufsize`）、②误伤合法 `-b:v`+`-bufsize`（无 maxrate 的缓冲约束）、③超出台账 #13「下游纪律」的处方。故**改为非破坏形态**：
- **裸 `maxrate()`/`bufsize()` 完全不变**：各自独立渲染、不派生、不门控——含孤立 `-bufsize` 仍产出（byte-compat）。孤立 bufsize 仅由 `bufsize()` 的 Javadoc **告警引导**（「请配 maxrate 或改用 vbv()」），**不** hard-fail。台账所述「门控」意图由此引导 + `vbv()` 正道达成。
- `vbv(String maxrate)`：设 `maxrate` 并打 `vbvDeriveBufsize=true` 标志；`vbv(String maxrate, String bufsize)` 或任何显式 `bufsize(String)` 清除该标志（用户显式优先）。
- **派生在 build 期**（非 wither 当刻，消除评审点名的顺序耦合脚枪）：`buildTranscode` 时若 `vbvDeriveBufsize && bufsize()==null` → 依**最终** `maxrate()` 求 `bufsize=maxrate×2`（D8）。故 `vbv("2M").maxrate("3M")`→`bufsize=6M`、`vbv("2M").vbv("3M")`→`6M`（跟随最终值、无陈旧 4M）。

### D7b: gating 松紧是可配决策点（待用户确认）
本设计选「引导（不 hard-fail）」以保 byte-compat。若产品上更希望 opinionated 硬约束（孤立 bufsize → 抛错，代价：破坏 byte-compat + 拒绝合法 `-b:v`+`-bufsize`），是一行开关的事，留待确认；默认取非破坏形态。

### D8: rate 串翻倍算法（`maxrate×2`）
纯函数：以正则 `^([0-9]*\.?[0-9]+)\s*([a-zA-Z]*)$` 拆「数值前缀 + 单位后缀」；数值 `×2`、经 locale 无关去尾零渲染器（复用 `FacadeSupport.num` 语义，`4.0→"4"`、`5.0→"5"`）、原样回拼后缀。例：`"2M"→"4M"`、`"2000k"→"4000k"`、`"3000000"→"6000000"`、`"2.5M"→"5M"`。不匹配（如空串、纯字母、含非法字符）→ `IllegalArgumentException`（fail-fast，不产垃圾 argv）。单位语义（K=1000 等）与翻倍无关，翻数值前缀恒正确。

### D9: 全 additive + byte-compat 自证 + wither 透传护栏（评审采纳）
- **byte-compat 自证**：默认 `TranscodeOptions` 不产 `-vn`/`-an`/`-ar`/`-strict`/`-x265-params`；裸 `maxrate("2M")`（不设 bufsize、不调 vbv）仍只产 `-maxrate 2M`（不派生）；孤立 `bufsize("4M")` 仍产孤立 `-bufsize`（不 hard-fail）；probe 既有构造点经便利构造器编译通过、既有字段值不变。
- **wither 透传护栏**：新增 5 字段后私有构造器/`defaults()`/每个 wither 达 ~20 个**位置**参数（多为相邻 `String`，漏传/转置编译器不报错、字段静默丢失）。仅测「默认 argv」无法捕获此类漏配。故 MUST 补**往返保真测试矩阵**：对每个 wither 设一个可区分的非默认值，再链式调用若干其它 wither，最后断言目标字段值仍在（每字段 × 抽样 wither）。（保留位置构造以最小化对既有类的改动；不强制重构为 copy-with，但测试矩阵是硬要求。）

## Risks / Trade-offs

- **孤立-bufsize 改为「引导不 hard-fail」（评审采纳，见 D7）**：既有若有测试断言「设 bufsize 不设 maxrate 产孤立 `-bufsize`」——本设计下**仍通过**（行为不变），无回归。放弃 hard-fail 的代价是台账「门控」由硬约束降为 Javadoc 引导；收益是 byte-compat 完好 + 不误伤合法 `-b:v`+`-bufsize`。松紧可配（D7b）。
- **record arity 变更的源码兼容**：靠便利构造器保证；风险是遗漏某个用满参构造的调用点。缓解：`mvn -o compile` 全绿即证源码兼容；`ProbeMapper` 是唯一满参生产构造点，改它传全字段。
- **`codecTagHex`/`codecTag` 命名易混**：靠 Javadoc 明确「Hex=原始 `codec_tag`、无 Hex=`codec_tag_string`」区分；两字段 ffprobe 语义确实并列存在。
- **x265Params 不校验 codec + 单侧供给**：用户对 libx264 设 `x265Params` 会产无效 argv → ffmpeg 运行期报错；且只为 h265 开 typed 通道（h265 VBV 是唯一驱动），libx264 的 `-x264-params` 仍走 extraOutputArgs。取舍：延续「库不耦合 codec 字符串解析」既定边界（自动侦测会引入脆弱的 codec 名单维护）；Javadoc 告警 + 说明单侧供给理由即可。
- **`FormatInfo` raw 字段属对称推断**：台账 #14 的下游回填讨论集中在 stream 级；`mapFormat` 同样 `asDouble(0.0)` 有同类损失，对称加 format raw 合理且低成本，但下游 `MediaFormatReq` 是否要求 format 级 byte-exact 未明示——保留为无害的对称附赠、Javadoc 注明用途；若下游确认不需可后续删，不阻断。

## Migration / Rollout

- 语义版本 **1.5.0**（minor，additive）。既有调用方**零改动**、既有 argv 逐字节不变。
- 下游 `ocs-media-task` 采纳后可：type4 Info 回调填 hex `codec_tag` + 逐字节 `start_time`/`duration`；type1 纯视频用 `disableAudio`、h265 VBV 用 `x265Params`、`-ar`/`-strict`（`strictExperimental()`）用 typed、VBV 用 `vbv`——**减少逃生舱面**、消除 codec-null 脚枪。
- **纯音频路由**：`disableVideo` 提供了从含视频源转码剥离视频的 typed 次选路径，但台账 #11 明确**纯音频抽取首选 `Ffmpeg.extractAudio` 门面**；CHANGELOG/USAGE 的 `disableVideo` 示例旁 MUST 重申此路由建议，避免被读作等价推荐。
- 文档：`CHANGELOG.md` 记 1.5.0；`USAGE.md`/`README.md` 补 probe 保真字段与 transcode 新 typed 码控示例，并顺带修正既有 USAGE 滞后（门面计数、curated 滤镜数、`extractAudio` `-map 0:a:0` 口径——台账 §五列明）。
