# command-compiler Specification

## Purpose

L2 编译器：把用户的不可变 `Stream` 引用图编译成完整的 `ffmpeg` 命令行（argv）。职责包括分配内部 pad 名并转义、按引用计数侦测扇出并自动插入 `split`/`asplit`、拓扑排序与按引用标识去重、编译期图校验（悬空 pad / 类型不匹配），以及汇聚滤镜（`concat`/`amix`）前的自动归一化（音频内部推导、视频接线并含 `setsar`）。用户永不手写 pad 名或内部滤镜。

## Requirements

### Requirement: 流值图编译为命令行
编译器 MUST 将用户的不可变 `Stream` 引用图编译为完整的 `ffmpeg` 参数列表（argv），包括输入、`-filter_complex`、`-map` 与编解码/输出参数。编译器 MUST 分配内部 pad 名并保证滤镜字符串正确转义；这些内部 pad 名 MUST NOT 要求用户提供或可见。

#### Scenario: 生成 filter_complex 与 map
- **WHEN** 编译一个含缩放与叠加的输出流
- **THEN** argv 包含一段 `-filter_complex` 表达该图，以及指向最终输出 pad 的 `-map`

### Requirement: 滤镜参数与文件路径转义
编译器 MUST 对进入 filtergraph 的用户文本（如 `drawText` 的 `text`）与文件路径（如 `burnSubtitles`/`burnAss` 的字幕文件名）做正确的 filtergraph 转义，至少处理冒号、百分号、单/双引号、反斜杠，以及 Windows 盘符冒号（如 `C\:/subs.srt`）；MAY 对 `drawText` 文本改用 `textfile=` 旁路以回避内联转义。编译器 MUST NOT 将用户文本/路径原样内插（否则直接产出非法命令行）。

#### Scenario: drawText 文本被转义
- **WHEN** 用户对一个 `VIDEO` 流调用 `drawText("12:30")`
- **THEN** 编译产物对特殊字符转义（如 `drawtext=text=12\:30`），冒号不被误当作选项分隔符

#### Scenario: 含冒号的字幕路径被转义
- **WHEN** 用户 `burnSubtitles` 一个路径含冒号的字幕文件（如 Windows `C:\subs.srt`）
- **THEN** 编译产物对盘符冒号转义，生成合法的 `subtitles=` 文件名参数

### Requirement: 扇出侦测与自动 split 插入
编译器 MUST 通过引用计数侦测被消费多于一次的 pad，并 MUST 自动插入 `split=N`（音频为 `asplit`）节点，重连各消费者。消费 N 次 MUST 产生一个 N 路分裂。`split`/`asplit` 按媒体类型分派仅适用于 VIDEO/AUDIO；若经 `rawFilter` 让 `SUBTITLE` 流进入 filtergraph 且被扇出，ffmpeg 无字幕版 split 滤镜，编译器 MUST 给出明确编译期错误，而非产出非法的 `split`。

#### Scenario: 二次扇出插入 split
- **WHEN** 某个中间流被两条后续链引用
- **THEN** 编译产物在该点包含一个 `split`（或 `asplit`）为 2 的节点，两条链各接一路输出

#### Scenario: 菱形图正确重连
- **WHEN** 一个流分裂后又在下游汇聚（菱形依赖）
- **THEN** 编译产物中每个 pad 恰好被消费一次，图在语义上等价于用户所表达

### Requirement: 拓扑排序与去重
编译器 MUST 对滤镜图做拓扑排序以产生合法的求值顺序，并 MUST 对被多个输出共享的相同子链去重，使其只出现一次。去重 MUST 按引用标识进行（同一 `Stream` 值被多次消费，等价于扇出 `split` 复用）；编译器 MUST NOT 对「结构相等但各自独立构造」的链做自动合并，以免误并含非确定性/时间戳的滤镜（如 `drawtext` 时间戳、`noise`）。

#### Scenario: 共享子链只编译一次
- **WHEN** 两个输出都基于同一条已缩放的滤镜链
- **THEN** 该子链在 `-filter_complex` 中只出现一次，两个输出经由 split 复用其结果

### Requirement: 编译期图校验
编译器 MUST 在生成 argv 之前校验图，至少检测：存在未连接/悬空的 pad、以及媒体类型不匹配（把某类型流接入不接受该类型的滤镜）。校验失败 MUST 抛出描述性错误而非产出非法命令行。

#### Scenario: 悬空 pad 被拒
- **WHEN** 图中存在一个既非输出、又无下游消费者的滤镜 pad
- **THEN** 编译器抛出校验错误并指明问题节点

#### Scenario: 类型不匹配被拒
- **WHEN** 一个 `SUBTITLE` 流被连入某个仅接受 `VIDEO` 的滤镜输入
- **THEN** 编译器在编译期抛出类型校验错误

### Requirement: 汇聚滤镜前的自动归一化
当把多路输入汇聚到 `concat` 或 `amix` 时，各输入参数必须一致，否则 ffmpeg 报 `Input link parameters do not match`。职责分层：**音频**归一化（采样率/采样格式/声道布局经 `aresample`/`aformat`）由编译器内部自动插入（编译器可推导共同目标）；**视频**归一化（分辨率/SAR/帧率/像素格式经 `scale`/`setsar`/`fps`/`format`）所需的**目标参数**由调用方/门面提供（唯其掌握 probe 数据以选定目标分辨率/SAR），编译器据此把归一化滤镜接入图。归一化 MUST 显式包含 `setsar`（`scale`/`pad` 不保证统一 SAR，异 SAR 输入仍会失败）。这些归一化滤镜均为编译器内部产物，用户 MUST NOT 手写。

此外，归一化只能改写已存在的流，无法凭空合成缺失的流。当各段的**流集合**不一致（如某段缺音轨或视轨）时，`concat` 会因 pad 数不符失败；此情形 MUST 由门面显式处理（按目标参数注入 `anullsrc`/`color`/`nullsrc` 占位），或以可诊断错误拒绝并说明前置条件，MUST NOT 静默产出失败命令行。

#### Scenario: 异构输入拼接前归一化
- **WHEN** 用户 concat 两段分辨率、SAR 与采样率不同的输入
- **THEN** 编译器为各段接入 `scale`/`setsar`/`fps`/`format` 与 `aresample`/`aformat`，使 concat 的输入参数一致

#### Scenario: 流集合异构被显式处理
- **WHEN** 用户 concat 一段有声视频与一段无音轨视频
- **THEN** 门面为缺音轨段注入静音（`anullsrc`）或以可诊断错误拒绝，而非产出注定失败的命令行
