## ADDED Requirements

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
