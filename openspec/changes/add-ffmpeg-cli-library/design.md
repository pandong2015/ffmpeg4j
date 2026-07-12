## Context

Greenfield Java 库，目标：让用户「像 libav 一样自由组合底层能力」处理视频，但不承担原生绑定的复杂度与崩溃风险。约束：

- 依赖目标机器**预装的 `ffmpeg`/`ffprobe` 二进制**（不自带、不链接 libav* .so）。
- 处理逻辑活在 ffmpeg 里（用 Java 编排滤镜/编解码），v1.0 不要求把解码帧送入 JVM 处理。
- 用户偏好不可变编程风格；面向用户的叙述用中文，代码/标识符可用英文。

本库整体形态是一个「把不可变流值图编译成 ffmpeg 命令行、再稳健地执行子进程」的系统。

## Goals / Non-Goals

**Goals:**
- L3 提供不可变「流即值」编排模型，音/视/字幕三态对称，用户永不手写 pad 名。
- L2 图编译器：扇出自动 `split`、拓扑排序与 pad 命名、去重、compile 期类型/连接校验。
- L1 执行引擎：IO 拓扑驱动、流排空防死锁、`-progress` 回调、优雅取消、有用的错误。
- 「任意组合」承诺从第一天成立：16 个类型化滤镜 + 万能 `rawFilter`/`rawArg` 逃生舱。
- 每一抽象层都提供「掉到下一层」的逃生舱，避免「不如自己拼命令行」。

**Non-Goals:**
- 原生绑定（JNI/JavaCPP/Panama）—— 显式排除，属另一条路线。
- v1.0 不含 `ffmpeg4j-frame`（帧进出 JVM、`BufferedImage`、逐帧变换）；架构预留接缝，后续版本再做。
- v1.0 不建模全部 400+ 滤镜（长尾靠 `rawFilter`），不含硬件加速、HLS/DASH 切片、字幕高级样式/fontconfig 的一等支持。
- 不做流跑到一半的程序化改参数（CLI 做不到，属路线 B）。

## Decisions

### D1: 路线 A（CLI 封装）而非路线 B（原生绑定）
libav* 无稳定 ABI，SO major 随 ffmpeg 大版本跳（58/59/60/61），跨版本可致 segfault 拖垮 JVM。JavaCPP 方案又自带库、违背「用预装二进制」约束。CLI 参数跨版本基本稳定，进程崩溃不波及 JVM。代价：每任务起进程、拿不到逐帧零拷贝数据——但均非 v1.0 目标。

### D2: 分层 L0–L4，每层都有逃生舱
L0 环境 / L1 执行 / L2 编译器 / L3 模型 / L4 门面。高层无法表达时允许掉到低层（`rawFilter`→L2、`rawArg`→argv）。**无逃生舱的封装最终都会被绕过。**

### D3: L3 用不可变「流即值」为主力（B），原始串为逃生舱（A），显式 FilterGraph 节点为底层（C）
`Stream` 是不可变值，滤镜是纯函数 `Stream → Stream`。用户不碰 `[0:v]`/`[bg]`。三种风格共享同一 `FilterGraph` 数据结构，是同一模型的三个抽象高度，而非三选一。备选「扁平 builder + 滤镜字符串」被否：DAG 退化成手写串，不算「组合」。

### D4: 扇出自动插入 `split`（选项 i）
同一 `Stream` 被消费多次时，编译器自动插 `split=N` 并重连，使 `Stream` 表现得像真正的值（值可被引用任意次）。备选「要求用户显式 `.split(n)`」被否：把 ffmpeg「一个 pad 只能消费一次」的底层约束泄漏给用户，等于半途而废。代价：L2 升级为需引用计数 + 图重写的真正图编译器。

### D5: L2 是图编译流水线，不是字符串拼接
建 DAG → 引用计数(扇出侦测) → 图重写(插 split) → 拓扑排序 + 分配内部 pad 名 → 输出 `-filter_complex` + `-map` + codec 参数（转义好）。顺带做去重与 compile 期校验（漏接 pad、音/视/字幕类型接错滤镜在编译期报错，而非等 ffmpeg 吐错）。

### D6: L1 由「IO 拓扑」驱动一切
按本次任务 stdin/stdout 是否走管道，推导拓扑，联动决定进度通道与取消能力：

| stdin | stdout | 进度通道 | 取消 |
|---|---|---|---|
| 空闲 | 空闲(写盘) | `-progress pipe:1` | 优雅(写 `q`) |
| 空闲 | 传帧 | `-progress tcp://127.0.0.1:port` | 优雅 |
| 喂输入 | 空闲 | `-progress pipe:1` | 降级 SIGTERM |
| 喂输入 | 传帧 | `-progress tcp://…` | 降级 SIGTERM |

TCP 作第 4 通道：JVM `ProcessBuilder` 只给 3 个标准流，进度需与媒体分离时用 `ServerSocket` 接 `-progress tcp://`，比临时文件/命名管道更跨平台、无需清理。

### D7: 每一路输出必须持续排空（防死锁）
管道缓冲区满会阻塞子进程 `write()`，进而 `waitFor()` 永久挂起。规则：ffmpeg 会写的每一路都配专职 pump 线程持续消费（stderr 恒有；stdout 收进度或帧；stdin 按需喂）。

### D8: 进度走 `-progress`（机器可读），不解析 stderr 那行
stderr 的 `frame= time= speed=` 跨版本会变、与日志混杂、易碎；`-progress` 输出稳定 `key=value` 块，是官方给程序用的。

### D9: 取消默认优雅，stdin 被输入占用时降级
默认向 stdin 写 `q\n` 让 ffmpeg flush/finalize（避免损坏 mp4 的 moov）。阶梯：`q`(等 N 秒) → `destroy()` SIGTERM(等 M 秒) → `destroyForcibly()` SIGKILL。`.cancel(FORCE)` 跳过收尾。**约束**：pipe 输入模式下 stdin 被占，`q` 塞不进，自动降级 SIGTERM 并在文档/日志说明。

### D10: 失败组装带原因的 `FfmpegException`
stderr pump 维护环形缓冲留尾（~50 行）；非零退出时组装 `{ exitCode, command, stderrTail, reason }`，`reason` 由已知错误模式尽力解析成一句人话。

### D11: run/runAsync 双 API；进度回调默认 pump 线程 + Executor 逃生舱
`run()` 阻塞、`runAsync()` 返回 Future。进度回调默认在进度 pump 线程触发（文档警告「别干重活」），提供 `.callbackExecutor(exec)` 将派发移出。符合「安全默认 + 逃生舱」。

### D12: 音/视/字幕三态一等公民
`Stream` 带 `mediaType: VIDEO | AUDIO | SUBTITLE`（对齐 ffmpeg 流类型）。音频几乎零架构成本且加固 D5 的类型校验；字幕软字幕流(mux/透传/抽取/转格式)随之对称掉出。**硬字幕**是视频滤镜 `subtitles=`/`ass=`，其字幕源诚实建模为**文件参数**（`burnSubtitles(File)`），不强塞进流/pad 抽象——抽象贴现实，遇到 ffmpeg 真实不对称就诚实暴露。术语澄清：本 change 中「编译期/compile 期类型校验」指 **L2 图编译**阶段（ffmpeg 启动前）的校验，非 javac 静态检查；实现上推荐用 `VideoStream`/`AudioStream`/`SubtitleStream` 密封子类型收窄 curated 签名，把错配再上提到 javac 编辑期，`mediaType` 枚举退居 `rawFilter` 产物的运行时兜底。

### D13: 「shipping」与「建模」解耦——curated 滤镜 + `rawFilter` 兜底
v1.0 手工建模 16 个常用滤镜（有签名、可补全、compile 期校验），其余 385+ 靠 `rawFilter("name=k=v")` 可达。「任意组合底层能力」第一天即成立，不被「建模 400 个滤镜」阻塞；加滤镜是加数据、不改架构。

### D14: 打包 v1.0 仅 `ffmpeg4j-core`，沿依赖接缝预留 `ffmpeg4j-frame`
不按层过度模块化。唯一真接缝：帧逃生舱会拉进 `java.desktop`(ImageIO)，无头 JRE 可能裁掉——故隔离为未来 `ffmpeg4j-frame`。probe 留 core（人人要、又小），JSON 用极轻第三方依赖（minimal-json/org.json）或自研微型解析器（JDK 无受支持 JSON API），core 不背 Jackson。

### D15: ffmpeg 版本兼容——声明最低版本 + 启动 probe 校验
CLI 参数跨版本稳定，无需按版本分叉代码。L0 启动探测版本，低于门槛警告/报错。

### D16: 最低支持 ffmpeg 版本 = 4.2（支持/测试下限，非特性下限）
调研确认所有依赖特性在 ~2.3 即全部具备（`-progress url`/`pipe:1` 与 `-progress tcp://` 自 1.0、`filter_complex`+`split`/`asplit` 自 1.0、`subtitles=`/`ass=` 自 1.1 且真正门槛是 `--enable-libass`、软字幕 srt/ass ~1.0 而 webvtt 编码器 2.3、`ffprobe -print_format json` ~1.0），版本非功能约束。故 **4.2** 是「承诺测试/支持的下限」而非能力门槛：覆盖至今仍维护的最老基线——RHEL/Rocky/Alma 8（EPEL+RPM Fusion，支持到 2029）、Ubuntu 20.04(ESM)、Debian 11+ 及一切更新环境。运行时按 D15 探测版本：低于 4.2 仅**警告不硬失败**（真实 floor ~2.3）；真正的硬门槛是构建开关（`--enable-libass` 决定字幕烧录、`--enable-libfreetype` 决定 drawText），启动应探测滤镜/配置而非仅版本号。备选被否：4.3 的唯一理由「覆盖 Debian 11」在 2026-08-31 到期且丢 EL8；5.x 零特性收益却排除 Ubuntu 20.04+22.04 两大 LTS。

### D17: curated 滤镜首批 16 个 + 归一化职责分层
用户可见 16 个（选项名已逐一比对官方 ffmpeg-filters 文档核实、零错误）：视频 9（scale/crop/pad/overlay/trim/fps/format/fade/drawText）、音频 5（volume/amix/atrim/atempo/afade）、双型 1（concat，返回 `{video,audio}` 双 pad）、字幕烧录 1 族（`burnSubtitles`=subtitles=、`burnAss`=ass=，均以**文件参数**为源）。较原案增 `afade` 补齐 A/V 淡入淡出对称。**归一化职责分层**（评审 F1/SAR 修正）：**音频**归一化 `aresample`/`aformat` 由编译器内部自动插入（可推导共同目标）；**视频**归一化 `scale`/`setsar`/`fps`/`format` 的**目标参数**由门面/调用方提供（唯其有 probe 数据选目标分辨率/SAR），编译器据此接线——MUST 含 `setsar`（`scale`/`pad` 不保证统一 SAR，异 SAR 输入仍报错）。`split`/`asplit`（扇出）、`setpts`/`asetpts`（trim/atrim 自动补的 PTS 重基）与上述归一化滤镜一律**编译器内部**，不作用户 curated 滤镜。核查修正：`atempo` 单实例范围实为 **[0.5,100]**，>2.0 为**音质**（避免跳采样）自动拆链而非因报错；`burnAss` **无** `force_style` 选项（force_style/charenc/stream_index 仅 subtitles 有）。命名统一 Java 驼峰（`drawText`→ffmpeg `drawtext`），单词滤镜与原名同形。长尾 385+ 靠 `rawFilter` 兜底；transpose/hflip/vflip（手机视频转向）为首个增量候选。

### D18: FfmpegException.reason 错误模式库——最具体优先、含 filter/plumbing 专属
首批模式库（stderr 措辞均已核实为 ffmpeg 真实输出），按「最具体优先」求值：具体模式在前，三条通用 errno（`No such file or directory`→改名 file-not-found 并拓宽至输出目录/字幕/字体缺失、`Permission denied`、`Invalid data found`）挪到末尾兜底。覆盖 invalid-data、encoder-unavailable（`Unknown encoder`+`Automatic encoder selection failed`）、encoder-open-failed（`Error while opening encoder…`）、odd-dimensions（`(width|height) not divisible by 2`）、codec-container-incompatible、no-matching-stream（`Stream map… matches no streams`/`does not contain any stream`）、output-format-unknown（两式，含 2023 改词 `is not known`）、decoder-unavailable、disk-full，及对本库高价值的 **unknown-filter**（`No such filter`——本库是 filter_complex 编译器，高频）、**filtergraph-unconnected-pad**（`Cannot find a matching stream for unlabeled input pad`——正对 auto-split/pad 命名失败）、filter-init（`Error initializing filter…`，烧字幕文件不可读）、**generic-failure** 兜底（`Conversion failed!`）、**progress-plumbing**（tcp 进度端口 `Connection refused`——本库自身内部故障，不应作媒体错误抛给调用方）。匹配整段 stderr 尾部，兼容新老措辞（`file: <errno>` 与 5.x 的 `Error opening input: <errno>`）。

### D19: L4 门面首批 8 个——含差异化能力 + 正确性约束
首批 8：transcode（强制）、remux、clip、extractAudio、thumbnail、concat、**burnSubtitles**、**probe**。较原案增烧字幕与结构化 probe——二者高频且展示本库差异化（前者干净归约为单个 subtitles=/ass= 滤镜；后者复用 media-probe 返回结构化元数据、是多数流程第一步）。核实出的正确性约束必须落实：**remux** 不能对 mkv→mp4 一刀切 `-c copy`（文本字幕转 `mov_text`、图形字幕 `-sn` 丢弃或文档化跳过，MP4 装不下 SRT/PGS）；**clip** 用无歧义的 `-ss start -t (end-start)` 而非 input 侧 `-to`（`-ss 10 -to 20` 会得 [10,30]），并区分 copy 快切(按关键帧对齐)与重编码精切；**concat** 门面必须真为异构输入插入 scale/pad/fps/format+aresample/aformat 前置归一化（裸 concat 遇参数不一致报 `Input link parameters do not match`），是本批复杂度最高项；门面签名统一 `XxxOptions` 模式。延后仍以 DEFERRED 文档化：extractSubtitles（仅文本字幕互转，图形字幕不可转文本、ass→srt 丢样式）、toGif（可单次 filter_complex 用 split+palettegen/paletteuse 完成，非真两遍）、addSubtitles（软字幕挂轨，配 burnSubtitles）。

### D20: 评审加固（独立多镜头评审后回填）
一轮独立评审（一致性/完整性/技术/API/OpenSpec 格式五镜头 + 对抗性验证）后回填的修正，均为文档级、不动摇路线 A 架构：新增 execution-engine「构建开关能力探测」requirement（libass/libfreetype 缺失即提前可诊断报错——D16 称其为真正硬门槛却曾零 spec 覆盖）；「二进制发现与版本校验」拆为「缺失→硬错 / 版本过低→仅警告继续」两条可证伪 MUST；tcp 进度补 `ServerSocket` accept 生命周期（setSoTimeout + 进程退出即 close，防启动即失败时 accept 永挂/泄漏）；「流排空防死锁」补喂完 stdin 后 close 触发 EOF 的反向死锁与取消时 broken-pipe 静默；concat 归一化补 `setsar` 与「流集合异构」（缺音/视轨段注入 anullsrc/占位或可诊断拒绝）；「内部管道故障不外泄为媒体错误」独立成 requirement；补齐一批锁定 MUST 的 scenario（clip 无歧义时长、内部滤镜不暴露、burnAss 无 force_style、软字幕抽取/转换、rawArg、probe 非法媒体、多输出）；`rawArg` 改为位置感知、`MAY 绕过校验`→`SHALL NOT 参与校验`；JSON 去掉不实的「JDK 内建」措辞；drawText 命名统一 Java 驼峰。**api-design 镜头「须引入 Stream 子类型否则编译期校验落空」被验证者 REFUTED**——本 change「编译期」一致指 L2 图编译校验（ffmpeg 启动前拦截），卖点成立，子类型列为推荐实现（见 D12）而非 spec 硬约束。评审总判定：**GO（带上述必修项）**，无 NO-GO 阻断，路线 A 架构技术成立。

## Risks / Trade-offs

- [每任务起子进程的开销] → 面向整段任务，可接受；高频逐帧场景本就应走路线 B（v1.0 非目标）。
- [`rawFilter`/`rawArg` 绕过类型校验，可能生成非法命令] → 逃生舱本就是「自负其责」；错误由 D10 的 `FfmpegException` 尽力翻译。
- [`-progress` 的 TCP 通道被防火墙/端口占用干扰] → 仅绑 `127.0.0.1` 随机端口，`ServerSocket(0)` 取空闲端口。
- [pipe 输入模式无法优雅取消，可能损坏输出] → 文档明确；该模式主要服务未来 frame 场景，v1.0 影响小。
- [自动 `split` 图重写实现复杂、可能有 bug] → 以单元测试覆盖扇出/菱形/多输出去重等图形态；编译产物（argv）可断言。
- [curated 滤镜集初期覆盖窄] → `rawFilter` 兜底保证能力不缺；滤镜按需增量补齐。
- [跨版本仍可能有个别参数差异] → 最低版本声明 + probe 校验 + 已知差异在 L0 处理。

## Open Questions

原 4 个开放问题均已在实现前敲定（见 D16–D19：版本 4.2、16 个 curated 滤镜、错误模式库、L4 首批 8 门面）。本节留待实现期的收尾细节：

- libass/libfreetype 构建开关的启动探测方式（`ffmpeg -filters` 精确匹配 ` subtitles `/` ass ` 行 + `-version` 配置行核对 `--enable-libass`），及缺失时的降级/报错措辞。
- drawText/burnSubtitles 中用户文本与文件路径的转义器实现（冒号/百分号/引号/反斜杠、Windows 盘符冒号），或改用 `textfile=` 旁路。
- concat 前置归一化的默认目标参数（分辨率/SAR/fps/pix_fmt、采样率/声道布局）如何选取（取首段 vs 显式指定）。
