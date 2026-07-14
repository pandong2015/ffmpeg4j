# job-model Specification

## Purpose

L3 模型 + L4 门面：以不可变「流即值」为核心的编排 API。`Stream` 是值，滤镜是纯函数 `Stream → Stream`；音/视/字幕三态一等公民，扇出走值语义。提供 16 个类型化 curated 滤镜与逃生舱（`rawFilter`/位置感知 `rawArg`），硬字幕以文件为源、软字幕走流映射/codec 路径，并由 L4 一行式门面（transcode/remux/clip/extractAudio/thumbnail/concat/burnSubtitles/probe）委托 L3 模型、media-probe 与 L1 执行引擎完成整段任务。

## Requirements

### Requirement: 不可变流值编排模型
库 SHALL 提供以不可变 `Stream` 值为核心的编排 API。滤镜 MUST 表现为纯函数：接收一个或多个 `Stream`，返回新的 `Stream`，且 MUST NOT 修改任何既有 `Stream`。用户 MUST NOT 需要手写或引用 ffmpeg 的 pad 名（如 `[0:v]`、`[out]`）。

#### Scenario: 链式滤镜返回新流
- **WHEN** 用户对一个 `Stream` 依次应用 `scale` 与 `drawText` 滤镜
- **THEN** 每次调用返回一个新的 `Stream`，原 `Stream` 保持不变
- **AND** 用户全程未书写任何 pad 名

#### Scenario: 汇聚多路输入
- **WHEN** 用户将一个 logo `Stream` 叠加到主视频 `Stream` 上（overlay）
- **THEN** 得到的输出 `Stream` 表示两路输入的汇聚，编译后由库自动生成对应的 `-filter_complex` 与 `-map`

#### Scenario: 一个任务声明多个输出
- **WHEN** 用户在同一任务里声明两个输出文件，各映射一组流并共享同一条缩放子链
- **THEN** 库编译为单次 ffmpeg 调用，缩放子链经 `split` 复用（见 command-compiler 去重），无需用户手写 pad

### Requirement: 音视频字幕三态流
`Stream` MUST 携带媒体类型 `VIDEO | AUDIO | SUBTITLE`，对齐 ffmpeg 的流类型。视频滤镜 MUST 仅接受 `VIDEO` 流，音频滤镜 MUST 仅接受 `AUDIO` 流。

#### Scenario: 类型不匹配在编译前被拒
- **WHEN** 用户尝试把一个 `AUDIO` 流传入只接受 `VIDEO` 的 `overlay` 滤镜
- **THEN** 库在编译/构建阶段抛出类型错误，而非在 ffmpeg 运行时才失败

#### Scenario: 音频流对称组合
- **WHEN** 用户对两路 `AUDIO` 流应用 `amix`
- **THEN** 得到一路新的 `AUDIO` 流，行为与视频滤镜组合对称

### Requirement: 扇出值语义
当同一个 `Stream` 被作为输入消费多于一次时，库 MUST 表现出值语义：该 `Stream` 可被引用任意次，库在编译期自动处理底层 pad 的一次性约束（见 command-compiler 的 `split` 插入）。用户 MUST NOT 需要手动复制或声明分裂。

#### Scenario: 同一流被消费两次
- **WHEN** 用户把同一个缩放后的 `Stream` 分别用于两个不同的后续滤镜链
- **THEN** 两条链均能正确引用该流，用户无需调用任何显式分裂 API

### Requirement: 逃生舱——原始滤镜与原始参数
库 SHALL 提供 `rawFilter(String)` 以插入任意未被类型化建模的 ffmpeg 滤镜。库 SHALL 提供位置感知的原始参数逃生舱：因 ffmpeg 参数有位置语义（输入侧选项如 `-ss`/`-f` 须置于对应 `-i` 之前，输出侧选项置于输出之前），原始参数 MUST 能注入到指定输入之前、指定输出之前与全局位，而不仅是末尾追加（否则输入侧选项无法表达）。`rawFilter`/`rawArg` 的内容 SHALL NOT 参与 compile 期类型校验，正确性由调用方负责。

#### Scenario: 使用未建模的滤镜
- **WHEN** 用户对一个 `VIDEO` 流调用 `rawFilter("unsharp=5:5:1.0")`
- **THEN** 该滤镜被并入滤镜图并出现在生成的 `-filter_complex` 中
- **AND** 输出仍是一个可继续组合的 `Stream`

#### Scenario: 追加输出侧原始参数
- **WHEN** 用户对某输出调用原始参数逃生舱追加 `-movflags +faststart`
- **THEN** 该参数原样出现在最终 argv 的对应输出参数位置

### Requirement: 类型化 curated 滤镜集
v1.0 SHALL 提供 16 个类型化常用滤镜，每个 MUST 具带参数的静态工厂/方法签名，支持 IDE 补全与编译期类型检查：视频 9（scale、crop、pad、overlay、trim、fps、format、fade、drawText）、音频 5（volume、amix、atrim、atempo、afade）、双型 1（concat，产出视频+音频双输出）、字幕烧录 1 族（burnSubtitles/burnAss）。`atempo` 超出单实例范围（[0.5,100]）或 >2.0 的因子 MUST 由库自动拆解为链而非交给 ffmpeg。`split`/`asplit`、`setpts`/`asetpts`、`aresample`/`aformat` MUST 为编译器内部滤镜，MUST NOT 作为用户可见 curated 滤镜暴露。未建模的长尾滤镜靠 `rawFilter` 逃生舱可达。

#### Scenario: 类型化滤镜带签名
- **WHEN** 用户调用 `scale(1280, 720)`
- **THEN** 参数经由方法签名传入，无需拼接字符串
- **AND** 生成的命令行包含 `scale=1280:720`

#### Scenario: 超范围 atempo 自动拆链
- **WHEN** 用户请求 4.0 倍速的 atempo
- **THEN** 库为音质自动产出 `atempo=2.0,atempo=2.0` 的链，而非把 >2.0 的因子直接交给单个 atempo 实例

#### Scenario: trim 自动补 setpts 重基时间线
- **WHEN** 用户对一段应用 `trim`
- **THEN** 生成命令行在 `trim` 后自动追加 `setpts=PTS-STARTPTS`（`atrim` 同理追加 `asetpts`），避免输出时间线留空白

#### Scenario: 内部归一化滤镜不出现在公共 API
- **WHEN** 检视公共 curated 滤镜 API
- **THEN** 不存在名为 `split`/`asplit`/`setpts`/`asetpts`/`aresample`/`aformat` 的公共工厂方法——它们仅由编译器内部产生

### Requirement: 硬字幕烧录以文件为源
`burnSubtitles`（subtitles= 滤镜）与 `burnAss`（ass= 滤镜）MUST 将字幕来源建模为文件（或带流索引的输入容器）参数，而非流经 pad 的 `Stream`。二者均作用于 `VIDEO` 流并返回 `VIDEO` 流。`burnSubtitles` MAY 接受 `force_style` 覆盖样式；`burnAss` MUST NOT 暴露 `force_style`（ass 滤镜无此选项）。

#### Scenario: 烧录字幕文件
- **WHEN** 用户对一个 `VIDEO` 流调用 `burnSubtitles(new File("s.srt"))`
- **THEN** 生成的滤镜为 `subtitles=s.srt`（或等价 `ass=`），字幕作为文件参数烤入滤镜字符串
- **AND** 用户未将字幕作为流 pad 连接

#### Scenario: burnAss 不暴露 force_style
- **WHEN** 检视 `burnAss` 的方法签名
- **THEN** 其签名不含 `force_style` 参数（与可接受 `force_style` 的 `burnSubtitles` 相对），因为 ffmpeg `ass` 滤镜无此选项

### Requirement: 软字幕流操作
库 SHALL 支持将 `SUBTITLE` 流 mux 进输出容器、透传、抽取，以及在字幕格式间转换（srt/vtt/ass），走流映射与 codec 路径。

#### Scenario: 转封装并保留字幕轨
- **WHEN** 用户把一路视频、一路音频与一路 `SUBTITLE` 流映射到同一 mp4 输出并指定字幕 codec
- **THEN** 生成的命令行包含对应的 `-map` 与 `-c:s`，字幕作为软字幕轨保留

#### Scenario: 抽取字幕轨到独立文件
- **WHEN** 用户把 mkv 中的一路 srt 字幕轨抽取到独立 `.srt`
- **THEN** 生成命令行含 `-map 0:s:0` 与 `-c:s srt`（或 `copy`）

#### Scenario: 字幕格式转换
- **WHEN** 用户把一路 srt 字幕转换为 vtt
- **THEN** 生成命令行含 `-c:s webvtt`

### Requirement: L4 高层门面
库 SHALL 提供一批一行式门面覆盖最常见整段任务，内部委托给 L3 模型、media-probe 与 L1 执行引擎。v1.0 首批 MUST 至少包含：transcode、remux、clip、extractAudio、thumbnail、concat、burnSubtitles、probe。每个门面 SHALL 提供便捷位置重载（最常见一行式用法）与可选的 `XxxOptions` 进阶重载；`probe` 豁免 Options 模式（`ProbeResult probe(File)`）。remux 以 `-c copy` 透传时 MUST 正确处理目标容器不兼容的流（如 MP4 的文本字幕转 `mov_text`、图形字幕丢弃），MUST NOT 宣称无条件透传所有流。clip MUST 以无歧义的时长表达截取区间。concat 门面 MUST 在拼接异构输入前对各段做参数归一化，并 MUST 处理各段流集合不一致的情形（见 command-compiler「汇聚滤镜前的自动归一化」）。

#### Scenario: 一行式转码
- **WHEN** 用户调用转码门面并给定输入路径、输出路径与目标编码器
- **THEN** 库完成一次完整的转码任务，无需用户手工构建滤镜图

#### Scenario: remux 处理容器不兼容的字幕流
- **WHEN** 用户 remux 一个含 SRT 字幕的 mkv 到 mp4
- **THEN** 门面将文本字幕转为 `mov_text`（或按策略丢弃图形字幕），而非以裸 `-c copy` 失败

#### Scenario: clip 以无歧义时长截取
- **WHEN** 用户调用 `clip(start=10s, end=20s)`
- **THEN** 生成 argv 使用 `-ss 10 -t 10`（时长=end−start），截取区间恰为 [10,20]，而非 input 侧 `-ss 10 -to 20`（那会得 [10,30]）

### Requirement: 实例化门面 FfmpegClient

core SHALL 新增一个可实例化的门面 `FfmpegClient(FfmpegEnvironment env, RunOptions defaultRunOptions)`，将 8 个高层门面（transcode、remux、clip、extractAudio、thumbnail、concat、burnSubtitles、probe）承载为实例方法，使 Spring 容器可将其作为普通 bean 注入。实例门面 MUST 使用注入的 `FfmpegEnvironment` 执行子进程（经 `FacadeSupport.execute(cmd, env, ro)`），MUST NOT 硬编码 `FfmpegEnvironment.shared()`。`probe` MUST 走该 env 配置的 ffprobe——即以 `MediaProbe.probe(file, env.binaries().ffprobeCommand())` 探测，而非 PATH 默认。每个门面的调用点 `XxxOptions`（若给定）MUST 与构造时的 `defaultRunOptions` 合并，调用点显式设定的字段覆盖默认值。`buildXxx` 纯函数与 `FfmpegExecutor` 行为 MUST NOT 因本次新增而改变。

#### Scenario: 显式配置的二进制路径生效

- **WHEN** 以 `FfmpegClient(env, ro)` 构造，其中 `env` 的 `FfmpegBinaries` 指向显式 ffmpeg/ffprobe 路径
- **THEN** 该实例的每个门面执行时使用注入 env 的 ffmpeg 二进制，而非 PATH 发现的默认二进制
- **AND** 其 `probe(file)` 经 `MediaProbe.probe(file, env.binaries().ffprobeCommand())` 走同一 env 配置的 ffprobe

#### Scenario: 调用点 Options 与默认 RunOptions 合并

- **WHEN** 实例以某 `defaultRunOptions`（如含默认 `timeout`）构造，随后某次门面调用传入仅设定 `overwrite=true` 的调用点 Options
- **THEN** 该次执行的生效 `RunOptions` 由二者合并——调用点显式设定的字段覆盖默认，其余字段沿用 `defaultRunOptions`
- **AND** 未传调用点 Options 的其它调用继续沿用完整的 `defaultRunOptions`

### Requirement: 静态门面向后兼容

静态门面类 `Ffmpeg` 的 8 个 public static 方法 MUST 委托给一个「默认实例」`FfmpegClient`，该默认实例以 `FfmpegEnvironment.shared()` 与 `RunOptions.defaults()` 构造，从而在未显式配置任何环境时保持与现状完全一致的行为。此重构 MUST NOT 改变 `Ffmpeg` 现有方法签名与可观察行为，现有全部单测 MUST 保持全绿。`FacadeSupport.execute` 在新增带 env 形参的重载 `execute(cmd, env, ro)` 后，其去硬编码化 MUST NOT 影响静态路径的产出 argv 与执行语义。

#### Scenario: 未配置时委托默认实例

- **WHEN** 用户直接调用静态 `Ffmpeg.transcode(...)`（未经任何 Spring 或显式实例配置）
- **THEN** 调用被委托给以 `FfmpegEnvironment.shared()` 与 `RunOptions.defaults()` 构造的默认 `FfmpegClient`
- **AND** 其产出的 argv 与执行行为与重构前逐字节一致，`Ffmpeg` 的公共方法签名不变

#### Scenario: 现有单测在重构后保持全绿

- **WHEN** 在完成 FfmpegClient + 静态委托重构后运行既有测试套件
- **THEN** 依赖静态 `Ffmpeg.xxx` 与 `FacadeSupport` 的全部单测（含 argv 精确断言与集成守卫测试）无需修改即全部通过
- **AND** 未观察到任何面向用户的 API 破坏或行为回归
