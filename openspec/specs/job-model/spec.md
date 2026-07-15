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

### Requirement: GIF 生成门面与调色板滤镜
库 MUST 提供 L4 门面 `Ffmpeg.gif(in, out, GifOptions)` 生成高质量 GIF，内部走两遍调色板（`palettegen`→`paletteuse`）。为此 MUST 新增两个类型化 curated 滤镜：`Filters.paletteGen(VideoStream)`（1 入 1 出，产出调色板流）与 `Filters.paletteUse(VideoStream video, VideoStream palette)`（2 入 1 出，输入顺序有语义：`video` 为主输入、`palette` 为调色板输入）。当同一 `fps`/`scale` 后的源流同时被 `paletteGen` 与 `paletteUse` 消费时，编译器 MUST 依赖既有扇出自动 `split` 重连，用户 MUST NOT 手写 `split` 或 pad 名。`GifOptions` MUST 支持 `start`（默认 `0`）、`fps`（默认 `15`，对齐 type3）、可选 `duration`（未设=从 `start` 到片尾）、可选 `width`（未设=不加 `scale`、保原分辨率）与可选 `height`（缺省按比例 `-1`）。`paletteUse` MUST 渲染为裸 `paletteuse`（不设 `dither`，用 ffmpeg 默认）。

#### Scenario: 一行式生成 GIF
- **WHEN** 用户调用 `Ffmpeg.gif(in, out, GifOptions.defaults().fps(10).width(320))`
- **THEN** 编译产物的 `-filter_complex` 含 `fps`→`scale`→`palettegen`/`paletteuse` 的等价链
- **AND** `fps`/`scale` 子链因扇出去重只出现一次，经一个 `split` 节点分别喂给 `palettegen` 与 `paletteuse`

#### Scenario: paletteuse 为 2 输入且顺序为 [video][palette]
- **WHEN** 用户用 `Filters.paletteUse(base, Filters.paletteGen(base))` 组图并编译
- **THEN** 编译产物中 `paletteuse` 节点接两条输入，顺序为先主视频流、后调色板流

#### Scenario: 时间裁剪的 -ss 与 -t 均置输入侧（与 type3 对齐）
- **WHEN** 用户调用 `Ffmpeg.gif` 且 `GifOptions` 指定 `start`/`duration`
- **THEN** argv 在 `-i` 之前依次含 `-ss <start>` 与 `-t <duration>`（`-t` 亦在输入侧，非输出侧）

#### Scenario: 未设 width 时不加 scale
- **WHEN** 用户调用 `Ffmpeg.gif` 且 `GifOptions` 未设 `width`
- **THEN** `-filter_complex` 含 `fps`→`palettegen`/`paletteuse`，不含 `scale`

### Requirement: extractAudio 采样率与声道选项
`ExtractAudioOptions` MUST 提供 `sampleRate(int)`（映射输出侧 `-ar`）与 `channels(int)`（映射 `-ac`），二者均为可选。未设置时 MUST NOT 追加对应参数，保持既有 argv 逐字节不变。设置的值 MUST 为正整数，否则 MUST 在选项构造期抛 `IllegalArgumentException`（而非放任 ffmpeg 运行期报错）。当 `sampleRate` 或 `channels` 任一被设置时，`buildExtractAudio` MUST NOT 使用 `-c:a copy`——须回退到目标扩展名的自然编码器以真正重采样/改声道（因 `-c:a copy` 会**静默忽略** `-ar`/`-ac`，产出错误参数而不报错）。

#### Scenario: 指定 16k 单声道（ASR 前置）
- **WHEN** 用户以 `ExtractAudioOptions.defaults().sampleRate(16000).channels(1)` 抽取 WAV
- **THEN** argv 含 `-ar 16000` 与 `-ac 1`

#### Scenario: 重采样时对可 copy 源强制重编码
- **WHEN** 用户对一个 aac 源抽取 `.m4a`（原会解析为 `-c:a copy`）且设置了 `sampleRate(16000)`
- **THEN** argv 使用重编码器（如 `-c:a aac`）而非 `-c:a copy`，`-ar 16000` 真正生效

#### Scenario: 未指定采样率/声道时与既有行为一致
- **WHEN** 用户以默认 `ExtractAudioOptions` 抽取音频
- **THEN** argv 不含 `-ar`/`-ac`，且 `copy` 优化保持不变，与历史版本逐字节相同

#### Scenario: 非正整数在构造期被拒
- **WHEN** 用户调用 `sampleRate(0)` 或 `channels(-1)`
- **THEN** 抛出 `IllegalArgumentException`，说明采样率/声道须为正整数

### Requirement: thumbnail 精确 seek 模式
`ThumbnailOptions` MUST 提供 `seekMode(SeekMode)`，`SeekMode` 至少含 `INPUT_FAST` 与 `OUTPUT_ACCURATE`，默认 `INPUT_FAST`（保持既有输入侧关键帧快 seek 行为不变）。当 `seekMode == OUTPUT_ACCURATE` 时，`-ss` MUST 置于输出侧（`-i` 之后的输出参数区），使截图时间点等价于「先解码到目标时刻再取帧」的精确 seek。

#### Scenario: 默认输入侧快 seek
- **WHEN** 用户以默认 `ThumbnailOptions` 抓帧
- **THEN** `-ss` 位于 `-i` 之前，argv 与历史版本一致

#### Scenario: 精确输出侧 seek
- **WHEN** 用户以 `ThumbnailOptions.defaults().seekMode(OUTPUT_ACCURATE)` 抓帧
- **THEN** `-ss` 位于 `-i` 之后、与 `-frames:v 1` 同处输出参数区

### Requirement: 逃生舱与外部输入的边界校验责任划分
库 MUST 通过 argv 隔离（每个值都是独立 argv 元素）消除 shell 注入；但库 MUST NOT 声称对逃生舱内容或门面/选项接收的外部值做**语义**校验。对源自外部参数的值（分辨率、采样率、时间戳、文件名等），语义合法性校验（正整数、时间戳正则、字符集白名单、扩展名白名单）是调用方责任。库仅对自身**类型化选项**施加基本约束（如采样率/声道须正整数），并在文档中明示此边界。

#### Scenario: argv 隔离使注入值不逃逸
- **WHEN** 调用方把形如 `;rm -rf /` 或 `$(...)` 的字符串作为某个选项/逃生舱值传入
- **THEN** 该字符串作为单个 argv 元素传给 ffmpeg，不经 shell 解释，不发生命令注入

#### Scenario: 语义非法值不被库静默接受为「安全」
- **WHEN** 调用方未先做边界校验即把非法的滤镜片段经逃生舱传入
- **THEN** 库不保证其语义正确性（正确性由调用方自负），库仅保证其不逃逸为 shell 注入

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
