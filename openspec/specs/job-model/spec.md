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

### Requirement: HLS 单码率 VOD 切片门面
`Ffmpeg` 与 `FfmpegClient` MUST 提供 `hlsSegment(File in, File outDir)` 与 `hlsSegment(File in, File outDir, HlsOptions options)`；`FfmpegClient` MUST 另提供对称 `hlsSegmentAsync` 两档，返回 `CompletableFuture<HlsResult>`；因 HLS 有写盘/清理副作用，MUST 用带 `try/finally` 的专用异步骨架（`executeAsync` 无 finally、返回 `RunResult`、取消与后台 await 解耦，不能原样复用）。门面 MUST 产出**单码率 VOD** HLS，布局固定为：播放列表 `outDir/<playlistName>`（默认 `index.m3u8`）、分段 `outDir/<segmentDir>/…`（默认子目录 `ts`，模板默认 `index%d.ts`），启用 AES 时密钥文件落 `outDir/<keyDir>/<keyFileName>`（默认 `key/enc.key`）。库 MUST 自动创建 `segmentDir`/`keyDir` 子目录（子目录是库的布局约定）。库 MUST 内部固定注入 `-hls_playlist_type vod` 与 `-hls_list_size 0`，且 MUST NOT 将其暴露为 typed 字段（暴露 `event`/滚动值即越出单码率 VOD 盒子）。段名 MUST 经显式 `-hls_segment_filename <outDir>/<segmentDir>/<template>` 下发；库 MUST **默认注入 `-hls_base_url <segmentDir>/`** 以使 m3u8 段 URI 带 `<segmentDir>/` 前缀（ffmpeg 单播放列表对段 URI 取 basename、**不**隐式相对化——8.0.1 实测证伪，故不能省 base_url）。库 MUST 显式映射**首视频 + 首音频**（`videoOptional()`+`audioOptional()`；多余音/视/字幕轨不纳入，多轨走 `extraOutputArgs -map` 或 L3）。默认视频/音频 codec MUST 为 `copy`（切片直拷、不 probe）。段数为 0（m3u8 无段）时门面 MUST 抛可诊断 `FfmpegException`，MUST NOT 返回空 `HlsResult`。`hlsTime` MUST 为 `double` 秒（默认 `8.0`），`-hls_time` 的 argv 渲染 MUST 复用 locale 无关去尾零渲染器（`8.0→-hls_time 8`、`6.5→-hls_time 6.5`）。返回 MUST 为 `HlsResult`（`record`：playlist 路径、段路径清单、可选 keyFile 路径、内嵌 `RunResult`；段数取 `segments.size()`，MUST NOT 设冗余计数字段）。`segments` MUST 由**解析新写出的 m3u8**（`#EXTINF`/段 URI 行按出现顺序）得到、天然有序，MUST NOT 由 glob 段目录推导（`-y` 不清旧段，glob 会混入孤儿段且词典序 `index10<index2` 错序）。内嵌 `run.exitCode()≠0`（取消/部分产物）时门面 MUST NOT 装配成功态 `HlsResult`（改抛 `FfmpegException`）。

#### Scenario: copy 路径最小 VOD argv
- **WHEN** 用户以默认 `HlsOptions`（无 key、默认 `-c copy`、hlsTime 8）调用 `hlsSegment(in, outDir)`
- **THEN** argv 含 `-c copy`、`-f hls`、`-hls_time 8`、`-hls_playlist_type vod`、`-hls_list_size 0`、`-hls_segment_type mpegts`、`-hls_segment_filename <outDir>/ts/index%d.ts`、`-hls_base_url ts/`，输出参数为 `<outDir>/index.m3u8`
- **AND** `-hls_segment_filename` 的模板逐字下发（printf 风格 `%d`），MUST NOT 被当作 Java `String.format` 处理

#### Scenario: 三分离目录自动创建
- **WHEN** 用户对一个尚无 `ts/`、`key/` 子目录的 `outDir` 调用 `hlsSegment`
- **THEN** 库在下发命令前创建 `outDir/ts/`（及启用 AES 时 `outDir/key/`），playlist 落 `outDir` 根

#### Scenario: VOD 双标签固定注入且不可作为字段
- **WHEN** 用户构造 `HlsOptions`
- **THEN** `HlsOptions` 无暴露 `playlistType`/`listSize` 的 wither；编译产物恒含 `-hls_playlist_type vod` 与 `-hls_list_size 0`

#### Scenario: 段 URI 带子目录前缀（集成，经 base_url）
- **WHEN** 存在 ffmpeg，用户对 `-f lavfi -i testsrc` 素材调用 `hlsSegment(in, outDir)` 并读回 `outDir/index.m3u8`
- **THEN** m3u8 内各分段 URI 形如 `ts/index0.ts`（由默认 `-hls_base_url ts/` 保证，**非**依赖 ffmpeg 隐式相对化），且这些段文件确实存在于 `outDir/ts/`
- **AND** `HlsResult.segments` 由解析该 m3u8 得到、与 m3u8 段行一一对应

#### Scenario: 复用非空 outDir 不混入孤儿段（集成）
- **WHEN** 对同一 `outDir` 先跑一次产 N 段、再跑一次只产 M<N 段（旧的高序号段仍残留在 `ts/`）
- **THEN** 第二次的 `HlsResult.segments` 恰等于第二次 m3u8 列出的 M 段，**不含**残留的孤儿段（因 segments 源自 m3u8 而非 glob）

#### Scenario: 段数≥10 有序
- **WHEN** 输入足够长产出 ≥10 段
- **THEN** `HlsResult.segments` 按段序号数值递增（`index2.ts` 在 `index10.ts` 之前），非文件名词典序

#### Scenario: HlsResult 携有序段清单
- **WHEN** `hlsSegment` 成功返回
- **THEN** `HlsResult` 的 `segments` 按段序号（`start_number` 起）递增列出实际产出的段路径（`segments.size()>0`）、`playlist` 指向 `outDir/index.m3u8`；启用 AES 时 `keyFile` 指向 `outDir/key/enc.key`，否则为 `null`
- **AND** 无独立 `segmentCount` 字段（段数经 `segments.size()`）

### Requirement: HLS AES-128 加密（B2 默认 / B1 便利）
`HlsOptions` MUST 经可空字段 `key(HlsKey)` 承载 AES-128（默认 `null`=不加密），MUST NOT 把密钥作为 `hlsSegment` 的独立形参。`HlsKey` MUST 为 `final class` + 静态工厂：**B2** `HlsKey.of(byte[] keyBytes, String keyUri)` 与 `HlsKey.of(byte[] keyBytes, String keyUri, byte[] iv)`；**B1** `HlsKey.random(String keyUri)`（JDK `SecureRandom` 生成 16 字节、字节可读回）。启用时库 MUST 生成 key_info_file（三行：第1行 keyUri 原样、第2行密钥文件**绝对**路径、可选第3行 32-hex IV），把 16 **原始字节**写入 `outDir/key/enc.key`，并接线 `-hls_key_info_file`。库对密钥的处理 MUST 满足：`SecureRandom`（非 `java.util.Random`）；`byte[]` 构造/读取 `clone`；`toString` 脱敏；密钥字节不进 argv/日志/异常。**密钥文件 `enc.key` 与临时 key_info_file 二者** MUST 以 `0600` 经 `PosixFilePermissions.asFileAttribute` **原子创建**（非先建后 chmod——真密钥文件与接线文件同等严格）；临时 key_info_file MUST 在 `finally`（含异常/取消路径）删除（不靠 `deleteOnExit`），`enc.key` 为持久产物。非 POSIX（Windows）无 `0600` 等价时，请求 AES MUST 一次性显式告警「密钥落盘无 OS 级权限保护」，MUST NOT 静默降级。`HlsKey.of(…, iv)` 的固定 IV 会施于每一段（AES-128-CBC 跨段 IV 复用、削弱机密性），其 `Javadoc` MUST 告警「VOD 优先省略 IV 以采用段序号 IV」。key URI 明文进 m3u8，其 `Javadoc` MUST 告警「勿内嵌 token/凭证」，可达性与密钥分发由调用方负责（B2）。因 `outDir/<keyDir>` 在 outDir 之下，`Javadoc` MUST 告警「严禁将 `outDir/<keyDir>` 纳入静态托管根（否则明文密钥可被 HTTP 直下），密钥端点须独立鉴权」。失败/取消/中断路径 MUST 清理孤儿 `enc.key`（明文密钥不得静默留盘）或 `Javadoc` 明确告警残留需调用方清 `outDir`——注意异常路径下调用方拿不到 `HlsResult`、不知 keyFile 路径。启用 AES 而目标机 ffmpeg 未 `--enable-openssl/gnutls` 时 MUST 在启动期诊断（对齐 `burnSubtitles`/`requireLibass` 前置校验）；`ErrorPatterns` MUST 为 `Invalid key size`/`Encryption not supported`/openssl 类 stderr 提供可读 `reason`（否则 `FfmpegException.reason=null` 无诊断）。

#### Scenario: B2 加密 argv 与 key_info_file 文本
- **WHEN** 用户设 `key(HlsKey.of(key16, "https://k/s.key"))`（无显式 IV），`buildHls` 传入一个**给定的** key_info_file 路径（`FfmpegClient` 先确定该路径，纯函数不建文件）
- **THEN** `buildHls` 产出的 argv 含 `-hls_key_info_file <该给定路径>`（脱进程可精确断言）；key_info_file 文本恰两行 = `https://k/s.key\n<outDir 绝对>/key/enc.key\n`（无第3行，ffmpeg 用段序号作 IV）
- **AND** `outDir/key/enc.key` 为 16 原始字节

#### Scenario: 显式 IV 产出第三行
- **WHEN** 用户设 `key(HlsKey.of(key16, uri, iv16))`
- **THEN** key_info_file 第3行为该 IV 的 32 个小写 hex 字符

#### Scenario: build 期 fail-fast 非法密钥参数
- **WHEN** 用户以 15 或 17 字节 keyBytes、或空 keyUri、或含换行的 keyUri、或非 16 字节 iv 构造 `HlsKey`
- **THEN** 在构造/wither 处即抛 `IllegalArgumentException`（不下发命令、不等 ffmpeg 运行期报错）

#### Scenario: 密钥不泄露
- **WHEN** 检视 `HlsKey.toString()` 与失败时 `FfmpegException` 的 command/stderrTail
- **THEN** 均不含密钥原始字节（`toString` 为 `HlsKey[redacted,16B]`；argv 仅含 key_info_file 路径）

#### Scenario: 密钥文件权限 0600（POSIX）
- **WHEN** 存在 ffmpeg 且运行于 POSIX 文件系统，用户启用 AES 完成一次 `hlsSegment`（以 `assumeTrue` 守卫非 POSIX）
- **THEN** `outDir/key/enc.key` 与库生成的临时 key_info_file 的 POSIX 权限均为 `rw-------`（0600）
- **AND** 临时 key_info_file 在门面返回后已被删除，`enc.key` 仍在

#### Scenario: B1 随机密钥可读回
- **WHEN** 用户用 `HlsKey.random("https://k/s.key")`
- **THEN** 生成 16 字节由 `SecureRandom` 产出、可经访问器（clone）读回并持久化，落盘于 `outDir/key/enc.key`

### Requirement: 通用按秒强制关键帧
`TranscodeOptions` MUST 提供 `forceKeyframesEverySeconds(double seconds)`，渲染为 `-force_key_frames expr:gte(t,n_forced*<seconds>)`，其中 `<seconds>` 以 locale 无关、去尾零的形式渲染（如 `8.0→8`、`1.5→1.5`）。该字段与既有帧基 `gop(int)` **互补共存**（可同时设，分别产 `-force_key_frames` 与 `-keyint_min/-g/-sc_threshold`），MUST NOT 互相覆盖。渲染 MUST 由单一纯函数 `FacadeSupport.forceKeyFramesArgs(double)` 产出（供 `TranscodeOptions` 与 `HlsOptions` 复用）。`seconds<=0` MUST 在 wither 内即时抛 `IllegalArgumentException`。因 `force_key_frames` 必然重编码，当其生效而视频 codec 为 `copy` 时 MUST 在 build 期抛可诊断异常（不隐式改 codec）。

#### Scenario: 按秒强制关键帧渲染
- **WHEN** 用户设 `forceKeyframesEverySeconds(1)`
- **THEN** argv 含 `-force_key_frames expr:gte(t,n_forced*1)`

#### Scenario: 与帧基 gop 互补共存
- **WHEN** 用户同时设 `gop(50)` 与 `forceKeyframesEverySeconds(2)`
- **THEN** argv 同时含 `-keyint_min 50 -g 50 -sc_threshold 0` 与 `-force_key_frames expr:gte(t,n_forced*2)`

#### Scenario: 与 copy 冲突 fail-fast
- **WHEN** 用户在 `-c:v copy`（未设编码器）下使 `force_key_frames` 生效
- **THEN** build 期抛可诊断异常，点名「关键帧强制需重编码，请设 videoCodec」

### Requirement: HLS 段与关键帧对齐 alignKeyframes
`HlsOptions` MUST 提供 `alignKeyframes(boolean)`（默认 `false`）。当为 `true` 时，库 MUST 以 `T = hlsTime` 复用 `FacadeSupport.forceKeyFramesArgs` 产出 `-force_key_frames expr:gte(t,n_forced*<hlsTime>)`，使段边界落在关键帧、得到均匀且可独立解码的分段。因需重编码，`alignKeyframes(true)` 而视频 codec 仍为 `copy` 时 MUST 在 build 期抛可诊断异常。

#### Scenario: 对齐复用 hlsTime
- **WHEN** 用户设 `videoCodec("libx264").hlsTime(6).alignKeyframes(true)`
- **THEN** argv 含 `-force_key_frames expr:gte(t,n_forced*6)`

#### Scenario: 对齐要求重编码
- **WHEN** 用户设 `alignKeyframes(true)` 但保留默认 `-c copy`
- **THEN** build 期抛可诊断异常（与「通用按秒强制关键帧」的 copy 冲突同款）

### Requirement: HlsOptions 不可变与出界逃生舱
`HlsOptions` MUST 为 `final class` + 私有构造 + `defaults()` + wither（同名 `xxx(v)` 返回新副本、无参 `xxx()` 只读；集合入参 `List.copyOf`），与 `TranscodeOptions`/`RemuxOptions`/`GifOptions` 一致，MUST NOT 用 `record`。字段至少含：`hlsTime`（**`double` 秒**，默认 `8.0`，`>0` 校验，渲染去尾零）、`playlistName`、`segmentDir`、`segmentTemplate`、`keyDir`、`keyFileName`、`startNumber`、`videoCodec`/`audioCodec`（默认 `copy`）、`key(HlsKey)`、`alignKeyframes`、`segmentUriPrefix`（→`-hls_base_url`）、`extraOutputArgs(String...)`、`onProgress`、`timeout`。HlsOptions MUST NOT 暴露裸 `forceKeyframesEverySeconds`（段对齐经 `alignKeyframes`，`T` 恒 = `hlsTime`，避免同一 `-force_key_frames` 的两个入口冲突）。`segmentUriPrefix`（`-hls_base_url`）对段 URI 取 **basename**、**不叠加** `segmentDir` 前缀（设它时段 URI = prefix + 段文件名；需保留子目录须自带，如 `.../ts/`）。`extraOutputArgs` MUST 置于类型化 `-hls_*` 之后（同键 ffmpeg 取后者）、内容不参与类型校验。`segmentTemplate` 的 wither MUST 即时校验含 `%d`/`%0Nd`（无序号占位符会让各段覆写同一文件、产不可播 m3u8 且 exit 0）；`outDir` 非目录、`playlistName`/`segmentDir`/`keyDir` 含非法路径分隔符等 MUST build 期 fail-fast。MAY 提供 `cleanSegmentDir(boolean)`（默认 `false`）在运行前清空 `segmentDir`；默认不清，但复用非空 `outDir` 会残留上次分段，`Javadoc` MUST 告警调用方自清。多码率梯/fMP4/live·event/密钥轮换等出界能力 MUST 经 `extraOutputArgs` 或 L3 `Output.withArgs` 表达，不作 typed 字段。

#### Scenario: 逃生舱追加原始 hls 参数
- **WHEN** 用户设 `extraOutputArgs("-hls_flags", "independent_segments")`
- **THEN** 该参数追加在类型化 `-hls_*` 之后，逐字进入 argv

#### Scenario: segmentUriPrefix 覆盖默认 base_url（不叠加子目录）
- **WHEN** 用户设 `segmentUriPrefix("https://cdn/")`（默认 `segmentDir=ts`）
- **THEN** argv 含 `-hls_base_url https://cdn/`（**覆盖**默认的 `ts/`，二者互斥不叠加），集成断言 m3u8 段 URI 为 `https://cdn/index0.ts`（basename，**不含** `ts/`；需保留子目录须自带 `https://cdn/ts/`）
- **AND** 未设 `segmentUriPrefix` 时 argv 含默认 `-hls_base_url ts/`，m3u8 段 URI 为 `ts/index0.ts`

#### Scenario: wither 不可变
- **WHEN** 用户对一个 `HlsOptions` 实例连续调用多个 wither
- **THEN** 每次返回新副本、原实例不变；未设字段不产出对应参数

#### Scenario: segmentTemplate 缺序号占位符 build 期抛错
- **WHEN** 用户设 `segmentTemplate("segment.ts")`（无 `%d`/`%0Nd`）
- **THEN** 在 wither 或 build 期即时抛 `IllegalArgumentException`，不下发命令（避免各段覆写同一文件、产不可播 m3u8 而 exit 0）

#### Scenario: 零分段抛可诊断异常
- **WHEN** 输入为空/零时长/损坏导致 ffmpeg exit 0 却产出 0 段（m3u8 无段行）
- **THEN** 门面抛可诊断 `FfmpegException`（如「HLS 未产出任何分段」），MUST NOT 返回 `segments` 为空的 `HlsResult`

#### Scenario: AES 失败到一半不残留明文密钥
- **WHEN** 启用 AES 的 `hlsSegment` 中途失败/被取消（内嵌 `run.exitCode()≠0`）
- **THEN** 门面抛 `FfmpegException`（不返回成功态 `HlsResult`），且孤儿 `enc.key` 与临时 key_info_file 已清理（或 Javadoc 已明确告警需调用方清 `outDir`）

### Requirement: HLS ABR 多码率梯门面
`Ffmpeg` 与 `FfmpegClient` MUST 提供独立门面 `hlsAbr(File in, File outDir)` 与 `hlsAbr(File in, File outDir, HlsAbrOptions options)`；`FfmpegClient` MUST 另提供对称 `hlsAbrAsync` 两档，返回 `CompletableFuture<HlsAbrResult>`（复用单码率的带 `try/finally` 异步骨架）。MUST NOT 以重载 `hlsSegment` 承载 ABR（返回类型不同、语义相反、ABR 恒产 master）。门面 MUST 单次 ffmpeg 调用产出：`outDir/<masterName>`（默认 `master.m3u8`）+ 每档 `outDir/<解析目录名>/index.m3u8` + `outDir/<解析目录名>/<segmentTemplate>`，启用 AES 时 `outDir/key/enc.key`。**解析目录名**=显式 `name`，否则数字索引 `0/1/2`（`%v` 在 `var_stream_map name:` 内**不展开**）。库 MUST 用**同一份「已解析目录名」列表**（probe 裁剪后算定）显式 `Files.createDirectories` 建各变体目录与 `key/`；该列表 MUST 同时驱动 `var_stream_map` 的 `name:`（null 则整段省略）、master/逐档 m3u8 解析路径、`HlsVariantResult.name`——四处同名，杜绝「库建目录 ≠ ffmpeg 写目录」。段与其变体 playlist **共位**同目录，库 MUST **默认不注入 `-hls_base_url`**（段 URI 取 basename 相对同目录自洽；`-hls_base_url` 不展开 `%v`，注入会写坏 URI）。ABR **恒转码**（每档不同码率/分辨率，无 `-c copy` 快路径）。VOD 双标签 `-hls_playlist_type vod` + `-hls_list_size 0` MUST 逐档固定注入。返回 MUST 为 `HlsAbrResult`。

#### Scenario: ABR 最小 argv（默认梯、无 AES）
- **WHEN** 用户对含视频的输入调用 `hlsAbr(in, outDir)`（默认梯经 probe 裁剪后为 N 档）
- **THEN** argv 含 `-filter_complex` 的 `split=N` + 逐档 `scale=...,setsar=1`、N 组 `-map [vN]`/`-c:v:N`/`-b:v:N`、`-var_stream_map`、`-master_pl_name master.m3u8`、`-f hls -hls_time 6 -hls_playlist_type vod -hls_list_size 0 -hls_segment_type mpegts`、`-hls_segment_filename <outDir>/<name 或 %v>/seg_%d.ts`，输出 `<outDir>/<name 或 %v>/index.m3u8`
- **AND** argv MUST NOT 含 `-hls_base_url`（默认）、MUST NOT 含 `-c copy`

#### Scenario: 产物布局与 master 自动元数据（集成）
- **WHEN** 存在 ffmpeg，用户对 `-f lavfi -i testsrc` 素材跑 3 档 `hlsAbr`
- **THEN** 产出 `outDir/master.m3u8` + 3 个 `outDir/<name>/index.m3u8` + 各档段；master 含 3 行 `#EXT-X-STREAM-INF`，其 `RESOLUTION` 与各档 scale 目标一致、`BANDWIDTH/CODECS` 由 ffmpeg 自动填
- **AND** 各档 media playlist 内段 URI 为 basename（如 `seg_0.ts`），段实存于该档子目录

#### Scenario: N>1 缺 %v 报错
- **WHEN** 变体目录名与段模板均不含 `%v` 而 N>1
- **THEN** 库在 build 期保证 `%v` 存在于变体子目录名或段模板（否则 ffmpeg 硬报错 `%v is expected`）

#### Scenario: N=1 仍产 master
- **WHEN** 默认梯裁剪到 1 档，或用户显式给单档 variants
- **THEN** 仍产 `master.m3u8` + 单行 `#EXT-X-STREAM-INF`，`HlsAbrResult.variants.size()==1`；Javadoc/USAGE 点明 N=1 分派（直拷单档用 `hlsSegment`、转码+master 用 `hlsAbr`）

#### Scenario: 复用非空 outDir 不混入孤儿变体目录
- **WHEN** 对同一 `outDir` 先跑 4 档、再跑 3 档（旧的第 4 档整个子目录残留）
- **THEN** 第二次 `HlsAbrResult.variants` 恰 3 档（源自解析 master，不混入孤儿目录）；库 Javadoc MUST 告警残留旧档目录需调用方自清

### Requirement: 码率梯 HlsVariant 与默认梯 HlsLadder
`HlsVariant` MUST 为 `final class` + 静态工厂 `of(int height, String videoBitrate)` + wither（不用 `record`）。必填 `height`、`videoBitrate`；可选 `width`（默认 `scale=-2:h` 保宽高比、偶数宽）、`maxrate`/`bufsize`（默认由 `videoBitrate` 派生）、`audioBitrate`、`videoCodec`（默认 `libx264`）、`audioCodec`（默认 `aac`）、`crf`、`preset`、`name`（默认 **`null`** → 目录回退数字索引 `0/1/2`）。wither MUST 即时抛 `IllegalArgumentException`：`height<=0`、非法 bitrate、**`name` 含 `%v` 或 `var_stream_map` 结构元字符（空格/逗号/冒号/路径分隔符）**（`%v` 在 `name:` 内不展开会致各档目录碰撞、后档覆盖前档；元字符会撕裂 var_stream_map → exit 0 却零产物）。`HlsLadder.defaults()` MUST 返回内置梯 **1080p@5M / 720p@3M / 480p@1.5M / 360p@800k**。`HlsAbrOptions.variants` 内部 MUST 用 **`null` 哨兵**区分「未设」与「显式」：仅内部 `null` 时门面 MUST `ffprobe` 源高度、应用默认梯并**过滤掉 `height>源height` 的档**（不放大）；**极小源**（所有默认档都 `>源height`）MUST 以源高度自身（取偶）生成单档，而非强留会放大的最低档。非 null（用户显式给梯）MUST NOT 裁剪（仅 `Javadoc` 告警放大）。**输入无视频轨或 `ffprobe` 取不到 `height`** 时门面 MUST 抛可诊断 `FfmpegException`（ABR 需视频轨/probe 数据），MUST NOT 静默产空梯或拖到 ffmpeg 运行期。

#### Scenario: 默认梯按源高度裁剪不放大
- **WHEN** 源高度 720、用户不给显式 variants
- **THEN** 默认梯保留 720/480/360 档、**剔除 1080 档**（不 `scale=-2:1080` 放大）

#### Scenario: width 缺省保比偶宽
- **WHEN** `HlsVariant.of(480, "1500k")`（不给 width）
- **THEN** 该档滤镜为 `scale=-2:480`（宽由 ffmpeg 按宽高比取偶数），非写死宽

#### Scenario: 显式梯不裁剪
- **WHEN** 用户 `variants(List.of(HlsVariant.of(1080,"5000k")))` 而源仅 720
- **THEN** 保留 1080 档（尊重显式意图），不静默剔除

#### Scenario: 极小源单档不放大
- **WHEN** 源高度 240（低于默认梯所有档），用户不给显式 variants
- **THEN** 以源高度自身（取偶）生成单档，MUST NOT 强留会放大的 360 档

#### Scenario: 无视频轨/probe 失败 fail-fast
- **WHEN** 输入无视频轨或 `ffprobe` 取不到 height，且未给显式 variants
- **THEN** 门面抛可诊断 `FfmpegException`（点名 ABR 需视频轨/probe），MUST NOT 静默产空梯或拖到 ffmpeg 运行期

#### Scenario: 非法 name build 期抛错
- **WHEN** `HlsVariant.of(720,"3000k").name("stream_%v")` 或 `.name("a,b")`/`.name("my 720p")`
- **THEN** wither 即时抛 `IllegalArgumentException`（`%v`/逗号/空格/冒号会致目录碰撞或撕裂 var_stream_map）

### Requirement: ABR 强制跨档关键帧对齐
`hlsAbr` MUST 恒注入 `-force_key_frames expr:gte(t,n_forced*<hlsTime>)`（复用 `FacadeSupport.forceKeyFramesArgs` 单一渲染器，一条覆盖全档），使各档段边界跨档一致（无缝切换的正确性前提）。`HlsAbrOptions` MUST NOT 暴露 `alignKeyframes` 开关（对齐是 ABR 定义性行为，不给关闭机会）。`hlsTime` 默认 `6.0`（`double` 秒，去尾零渲染），可配。ABR 恒转码，无 `-c copy` 冲突分支。

#### Scenario: 全档统一 force_key_frames
- **WHEN** `hlsAbr` 以 `hlsTime(6.0)` 跑 N 档
- **THEN** argv 含单条 `-force_key_frames expr:gte(t,n_forced*6)`（施于全部视频编码器）
- **AND** 集成断言各**视频**变体 media playlist 的 `#EXTINF` 序列逐档一致（`audioRendition` 因 aac 帧边界天然略偏，不参与逐档一致比较）

### Requirement: ABR 音频 agroup 共享
`hlsAbr` 默认（`sharedAudio=true`）MUST 用 agroup 共享单音轨：音频只映射/编码一次（`-map <in>:a:0 -c:a:0`），`var_stream_map` 每视频档追加 `agroup:<gid>`、末尾追加 `a:0,agroup:<gid>,name:audio,default:yes`。`HlsAbrResult` MUST 把该音频 rendition 单列为 `HlsAudioRendition`（不属任何视频档目录）；其编码参数取 `HlsAbrOptions.audioBitrate`（默认 `128k`），`HlsVariant` 的 per-variant 音频字段在 agroup 下不生效（`Javadoc` 标注）。MAY 提供 `sharedAudio(false)` 退回每档独立音频：此时 MUST 用带下标 `-c:a:N`/`-b:a:N`、`mapped()` 交错 `v0,a0,v1,a1`、`var_stream_map` `v:i,a:i`（否则 per-variant 音频参数被无下标参数静默覆盖、下标漂移）。

#### Scenario: agroup 音频只存一份
- **WHEN** 默认 `sharedAudio` 跑 3 档
- **THEN** 音频只 `-map <in>:a:0 -c:a:0` 一次、`var_stream_map` 含 `agroup:<gid>` 分组；集成断言只有一个音频 rendition 目录、master 含 `#EXT-X-MEDIA:TYPE=AUDIO` 且各 STREAM-INF 带 `AUDIO=`
- **AND** `HlsAbrResult.audioRendition` 指向该音频 playlist

### Requirement: ABR AES 复用单码率基座（单密钥全档）
`HlsAbrOptions.key(HlsKey)` MUST 直接复用单码率 `HlsKey`（B2 `of`/B1 `random`、`SecureRandom`、`byte[]` clone、`toString` 脱敏、16 字节 build 期校验、`enc.key` 与临时 key_info_file 0600 原子创建、finally 删临时文件、失败清理孤儿 `enc.key`）。启用时库 MUST 写**单个** `outDir/key/enc.key` + **单个**临时 key_info_file 供全档共享（无 per-variant 密钥）。每档 media playlist MUST 各写一行 `#EXT-X-KEY`（同 METHOD/URI），master MUST NOT 含 KEY 行；启用 AES 时 agroup 音频 rendition 的 media playlist MUST 同样含 `#EXT-X-KEY`、其音频段 MUST 被加密（不得明文泄露）。省略 IV 时 ffmpeg 在 ABR 下产 `IV=0x00…00` **跨段/跨 rendition 复用**——采用单密钥模型接受之；**这是相对 `hlsSegment`（省略 IV=段序号派生、安全）的隐性回归**，`Javadoc` MUST 显式点破此对比 + 跨段/跨 rendition 结构泄露、重申高机密需求应自管每段 IV/密钥轮换（越界）。**密钥托管告警**：`enc.key` 落 `outDir/key/`，`Javadoc`/文档 MUST 告警「`key/` MUST 排除在 CDN/静态托管根之外（否则明文密钥可 HTTP 直下）」，ABR 主推的「整树相对托管」表述 MUST 标注**不含 `key/`**；真正取密钥走 caller 的 key URI。失败/取消（`run.exitCode≠0` 或任一档 0 段）时 MUST 抛 `FfmpegException` 且清理孤儿 `enc.key`（覆盖已产多档段+master 的终局态）或 `Javadoc` 告警残留需调用方清 `outDir`。

#### Scenario: 单 key 覆盖全档
- **WHEN** `hlsAbr` 设 `key(HlsKey.of(key16, "https://k/s.key"))` 跑 3 档
- **THEN** argv 含单个 `-hls_key_info_file`；集成断言 `outDir/key/enc.key` 唯一、每档 playlist 含 `#EXT-X-KEY:METHOD=AES-128,URI="https://k/s.key"`、master 无 KEY 行

#### Scenario: agroup 音频 rendition 亦被加密
- **WHEN** 默认 `sharedAudio` + AES 跑
- **THEN** 音频 rendition 的 media playlist MUST 含 `#EXT-X-KEY`，其音频段 MUST 被加密（集成断言首字节非 TS 同步字节 0x47、ffprobe 不可直读）

#### Scenario: 部分失败不残留明文密钥
- **WHEN** 启用 AES 的 `hlsAbr` 中途失败/取消（`run.exitCode≠0` 或某档 0 段），此时盘上已有 master 与多档已产段 + `outDir/key/enc.key`
- **THEN** 门面抛可诊断 `FfmpegException`（不返回成功态 `HlsAbrResult`），孤儿 `enc.key` 与临时 key_info_file 已清理，或 Javadoc 已明确告警需调用方清 `outDir`

### Requirement: ABR 编译器契合与 -map 顺序契约
`hlsAbr` MUST 走路线 A（`filter_complex` split+scale+setsar），MUST NOT 用 `-s:v:N` 无 filtergraph 路线（丢 `setsar`/归一化）。ABR MUST NOT 改动 `command-compiler`/`Output` 结构——同一 `input.video()` 被 N 档 `scale` 消费经 `GraphCompiler` 原生 `split=N`；N 变体 = 单个 `Output`（N×2 `-map` + `var_stream_map`/`master_pl_name`/`-b:v:N`/`-hls_*` 经 `withArgs`）。因 `var_stream_map` 的 `v:N/a:N` 与 `-b:v:N` 下标依赖 `-map` 顺序，「`GraphCompiler` 按 `mapped()` 列表顺序发 `-map`、不重排」MUST 作为显式契约并加脱进程 argv 单测；facade MUST 以单一确定顺序生成 `mapped()` 与 `var_stream_map` 字符串。

#### Scenario: split 扇出由编译器原生产生
- **WHEN** facade 以 `input.video()` 建 N 档 `scale` 流并编译
- **THEN** 产物 `-filter_complex` 含一个 `split=N`（编译器对同一 pad 被 N 次消费自动插入），各档 `scale=...,setsar=1` 接其一路

#### Scenario: -map 顺序与 var_stream_map 下标一致
- **WHEN** N=3 档编译
- **THEN** argv 的 `-map` 顺序（v0,a,v1,a,v2,a 或 agroup 形态）与 `var_stream_map` 的 `v:0..v:2`、`-b:v:0..2` 下标一一对应（脱进程 argv 单测钉死）

### Requirement: HlsAbrResult 多档结果
`hlsAbr` MUST 返回 `HlsAbrResult`（`record`：`Path master`、按 `var_stream_map` 顺序的 `List<HlsVariantResult> variants`、可空 `HlsAudioRendition audioRendition`、可空 `Path keyFile`、内嵌 `RunResult`）。`HlsVariantResult`（`record`）：`String name`、`int width`/`height`、`long bandwidth`、`Path playlist`、`List<Path> segments`。音频 rendition MUST 用专属 record `HlsAudioRendition`（`String name`、`String groupId`、`Path playlist`、`List<Path> segments`）——master 的 `#EXT-X-MEDIA` 行无 RESOLUTION/BANDWIDTH，MUST NOT 复用视频 record 的分辨率/带宽字段；其 `name` 取自 **URI 目录名**（非 ffmpeg 会加后缀改写的 `NAME` 属性）。`segments` MUST 由解析各 playlist 的 `#EXTINF` 段行得到（有序、非 glob）；`width/height/bandwidth` MUST 解析 master 的 `EXT-X-STREAM-INF`，解析 MUST **引号感知**（`CODECS="…,…"` 值内含逗号，朴素 `split(',')` 会误分割）或用定向正则 `BANDWIDTH=(\d+)`/`RESOLUTION=(\d+)x(\d+)`。`run.exitCode≠0` 或任一档 0 段 MUST 抛可诊断 `FfmpegException`，MUST NOT 装配成功态。

#### Scenario: 结果携各档有序段与元数据
- **WHEN** `hlsAbr` 成功返回
- **THEN** `HlsAbrResult.master` 指向 `outDir/master.m3u8`；每个 `HlsVariantResult` 的 `segments` 由该档 m3u8 解析、按序、`bandwidth`/`height` 取自 master；启用 AES 时 `keyFile` 指向 `outDir/key/enc.key`

#### Scenario: 任一档零段抛错
- **WHEN** 某档 media playlist 无段（空/坏输入）
- **THEN** 门面抛可诊断 `FfmpegException`，不返回成功态 `HlsAbrResult`

### Requirement: HlsAbrOptions 不可变与逃生舱
`HlsAbrOptions` MUST 为 `final class` + 私有构造 + `defaults()` + wither（不用 `record`）。字段至少含：`variants`（内部默认 **`null` 哨兵**=未设→门面用 `HlsLadder.defaults()`+probe 裁剪；非 null 不裁；`List.copyOf`）、`hlsTime`（`double`，默认 `6.0`，`>0`）、`key(HlsKey)`、`audioBitrate`（默认 `128k`，agroup 共享音频用）、`masterPlaylistName`（默认 `master.m3u8`）、`segmentTemplate`（默认 `seg_%d.ts`，MUST 含 `%d`）、`startNumber`、`sharedAudio`（默认 `true`）、`extraOutputArgs(String...)`、`onProgress`、`timeout`。MUST NOT 含 `videoCodec`/`audioCodec`（下沉 `HlsVariant`）或 `alignKeyframes`（恒开）。MUST NOT 暴露 `segmentUriPrefix`（ABR 扁平前缀跨档 basename 碰撞、`%v` 不展开）。`extraOutputArgs` 置于类型化 `-hls_*`/`var_stream_map` 之后、不参与校验。fMP4/live/密钥轮换/per-variant 密钥等出界经 `extraOutputArgs`。

#### Scenario: 逃生舱与不暴露的字段
- **WHEN** 用户构造 `HlsAbrOptions`
- **THEN** 无 `videoCodec`/`audioCodec`/`alignKeyframes`/`segmentUriPrefix` 的 wither；`extraOutputArgs(...)` 追加在类型化参数之后逐字进 argv

