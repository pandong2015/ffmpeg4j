## Context

HLS 切片是 media-task SLICE 唯一仍手写 argv 的场景。核对（Workflow 4 路对抗性研究 + 本机 ffmpeg 8.0.1 实测 `-h muxer=hls` + 直接读源码）确证：HLS **不是滤镜图的事**，而是 **muxer/output-args + 一个 sidecar 密钥文件**——它天然落在 L4 门面层，经 `Output.to(playlist).withArgs(-hls_*)` 既有编译路径即可下发，`command-compiler` 无需改动。关键源码事实：

- 门面家族全部 `(File in, File out, …) → RunResult`（`Ffmpeg.java`），`FacadeSupport.buildXxx` 是**零副作用纯函数**（只产 argv，不碰磁盘）。HLS 引入两处新副作用：产出多文件、须写临时 key_info_file——本设计以 D10 分层隔离。
- `TranscodeOptions.gop(int)` 派生 `-keyint_min N -g N -sc_threshold 0`（`FacadeSupport.java:122-131`），是**帧基**规整 GOP；全库无 `force_key_frames`（按秒/VFR 稳的关键帧强制无 typed 入口）。
- `Output.withArgs(...)` 逐字追加输出 argv（`GraphCompiler`），`-hls_*`/`-force_key_frames`/`-hls_key_info_file` 天然可表达，无需新编译器能力。
- Options 家族用 `final class + 私有构造 + defaults() + wither`（`TranscodeOptions`/`RemuxOptions`/`GifOptions`），**刻意不用 record**（record 规范构造器是公开 API，加字段即二进制不兼容）；而只读结果值 `StreamInfo` **是 record**（含兼容构造器扩字段）。本设计沿此二分：`HlsOptions`/`HlsKey`=final class，`HlsResult`=record。

## 地面真相验证（真 ffmpeg 8.0.1 `-h muxer=hls` + 明确的待测项）

**已实测确认**（本机 ffmpeg 8.0.1，`--enable-openssl`，`ffmpeg -hide_banner -h muxer=hls` 枚举）：

- hls muxer 默认值：`hls_time=2`、`hls_list_size=5`、`start_number=0`、`hls_segment_type=mpegts`、`hls_playlist_type=0`（未设/live 滚动）。→ **VOD MUST 覆写** `-hls_list_size 0`（否则默认只留最后 5 段、丢整片索引）与 `-hls_playlist_type vod`（否则不写 `#EXT-X-ENDLIST`、播放器按 live 处理无法整片 seek）。`playlist_type=vod` 会隐式令 list_size=0，但仍显式冗余写一遍以自证并防未来行为变动。
- `-hls_key_info_file` 逐行读三行：**第1行**=写进 `#EXT-X-KEY` 的 key URI（明文进 m3u8，播放器据此取密钥）；**第2行**=ffmpeg 本地读 16 原始字节的**密钥文件路径**（不进 m3u8，按 ffmpeg 工作目录解析——故 MUST 写**绝对路径**）；**第3行（可选）**=32 hex 的 IV。省略第3行时 ffmpeg 以**段序号**作 IV（VOD 标准、天然各段 IV 不同，避免 IV 复用）。密钥文件=16 **原始字节**（非 hex/base64）。此路径存在于 4.2（仅 8.0.1 实测，4.2 未单独验证），优于 8.0 才完善的 `-hls_enc_*`（且后者把密钥塞进 argv，可被 `ps` 窥视——刻意不用）。
- 段切点**只落关键帧**：`-hls_time` 是「≥ 该时长的下一关键帧」下限。`-c copy` 无法插关键帧，段长受源 GOP 支配、通常不均；转码需 `-force_key_frames expr:gte(t,n_forced*T)` 使每段起点为 IDR（研究实测最小 VOD argv 即用此式，无需额外 `-g`）。

**已实测证伪 → 改默认策略**（完备性核验在本机 8.0.1 实跑设计原命令）：

- **段 URI 前缀假设不成立（原为最大押注，现证伪）**：曾赌「m3u8 段 URI = 相对 `ts/index0.ts`，ffmpeg 剥 `outDir/` 公共前缀」——8.0.1 实跑（playlist=`outDir/index.m3u8`、`-hls_segment_filename outDir/ts/index%d.ts`，绝对与相对路径都试）证伪：单播放列表分支对段 URI 取 `av_basename`，写出的是 `index0.ts`（**不含 `ts/`**）；播放器相对 `outDir/` 解析成 `outDir/index0.ts`，而段实落 `outDir/ts/` → **404、整片不可播**。故**改为默认无条件注入 `-hls_base_url <segmentDir>/`**（即 `ts/`），实测确定性产出 `ts/index0.ts`。`-hls_base_url` 是远早于 4.2 的老选项、显式前缀=**版本无关**，顺带抹平「basename vs 绝对路径」的跨版本发散。用户设 `segmentUriPrefix`（CDN）时**覆盖**内部 `<segmentDir>/`（二者互斥，此时前缀须由用户自带子目录，如 `https://cdn/ts/`）。

**待验证（floor 与端到端）**：

- **版本验证口径（决定：仅文档说明 8.0.1，不设 4.2 CI lane）**：全部地面真相在本机 **8.0.1** 得出，项目 floor=**4.2**。key_info_file 三行解析、省略 IV 时段序号派生、`-hls_time` 小数秒、`-force_key_frames expr:` 的 `n_forced`、`-hls_segment_type mpegts` 在 4.2 均**未实测**。**决定不投入 4.2 CI lane**，改在文档诚实声明「HLS/AES 行为仅于 ffmpeg 8.0.1 实测；4.2 为支持下限但未在该版本单独验证，如遇差异请反馈」；design/USAGE 各处不写「跨 4.2 稳定」的强断言。改用 `-hls_base_url` 后段 URI 已版本无关，风险面收窄。
- AES 段实际可用：集成断言 m3u8 含 `#EXT-X-KEY:METHOD=AES-128` 且 `URI==` 传入 keyUri，`.ts` 首字节与未加密段不同。

## Goals / Non-Goals

**Goals:**
- HLS 单码率 VOD 切片（可选 AES-128）在库内一行式可达。**已确认（用户）**：worker SLICE 需**自适应码率（ABR 多码率梯）**才能 100% 脱离手写 argv。故本变更定位为「单码率 VOD **基础**」，SLICE 完全脱手写需 **ABR 能力**（复用本变更的 `HlsKey`/`alignKeyframes`/布局/base_url/`HlsResult` 基座）。ABR 的落地形态（折入本变更 vs 独立后续变更 `add-hls-abr-ladder`）**待定**（见文末决策）——本变更目标暂限「单码率 + 通用关键帧」，不过度承诺 full SLICE escape。
- 「按秒强制关键帧」提为通用 `TranscodeOptions` 能力，不只服务 HLS。
- 全程 additive，既有 argv 逐字节不变；密钥处理经对抗式核验的安全基线。

**Non-Goals:**
- 本变更不做多码率梯（ABR）——**已确认为独立后续变更** `add-hls-abr-ladder`（复用本变更 `HlsKey`/AES/`alignKeyframes`/布局/base_url/`HlsResult` 基座，非永久边界）；fMP4 / live·event / 密钥轮换 / SAMPLE-AES / WebVTT 字幕轨仍走逃生舱。
- 不自动把 `-c copy` 翻成转码（`alignKeyframes` 与 copy 冲突时 fail-fast，不隐式改 codec，D5/D7）。
- 不在门面内做密钥分发 / key URI 可达性校验（B2：调用方责任，D8）。
- 不引入任何第三方依赖（AES 随机仅用 JDK `SecureRandom`，D9）。
- **不夹带其它 backlog**：本变更仅在 `TranscodeOptions` 增 `forceKeyframesEverySeconds`；audio `-ar`/`-strict`、probe `codec_tag` hex、data/attachment 流均属独立 backlog，即便 seg2(b) 与本变更同改 `TranscodeOptions.java` 也**不搭车**（防 scope creep 污染 diff）。h265 VBV 已是既定边界（`TranscodeOptions` 类注释）。

## Decisions

### D1: 刻意最小盒子 = 单码率 VOD + 可选 AES-128；越界走逃生舱
HLS 表面巨大（多码率梯/fMP4/live/密钥轮换/字幕轨）。本变更**只**收 media-task SLICE 需要的单码率 VOD（TS 段）+ 可选 AES-128。多码率/fMP4/live/轮换等 MUST 经 `HlsOptions.extraOutputArgs(String...)`（轻度越界，同一 hlsSegment 脚手架叠加原始 `-hls_*`）或 L3 `Input.of(in)+Output.to(playlist).withArgs("-var_stream_map",…)`（重度越界，超出单 in/单 playlist 形状）。库诚实地说「这不在 typed 门面，是你的原始 argv 出口」，而非假装支持。**理由**：这是唯一硬缺口；盒子越小、公开契约越可控（已发 Maven Central）。

### D2: 签名 `hlsSegment(File in, File outDir, HlsOptions)` + 库自持三分离布局
`outDir` 为唯一输出正参（两档重载：便捷 + Options；`FfmpegClient` 另加对称 `hlsSegmentAsync`；**不发** `(in,outDir,baseName)` 等多正参——每个正参都是永久契约）。产物布局：
```
<outDir>/index.m3u8           播放列表（根；名字 = HlsOptions.playlistName，默认 index.m3u8）
<outDir>/ts/index%d.ts        分段（子目录 segmentDir 默认 ts；模板 segmentTemplate 默认 index%d.ts）
<outDir>/key/enc.key          AES 密钥文件（启用时；子目录 keyDir 默认 key、文件名 keyFileName 默认 enc.key）
```
库 MUST 自动 `Files.createDirectories(outDir/ts, outDir/key)`——**子目录是库的布局约定**，与其它门面「不自动建目录」不同（否则调用方须预知库内部布局）。段名经显式 `-hls_segment_filename <outDir>/ts/index%d.ts` 下发；m3u8 段 URI 的 `ts/` 前缀**经默认注入 `-hls_base_url ts/` 保证**（不能靠 ffmpeg 隐式相对化——已在 8.0.1 证伪，见地面真相）。**取舍**：scope 研究曾荐「playlist 当正参」(方案 a)；本设计选 outDir(方案 b 精简版，无 baseName 正参)，因用户要的 m3u8/ts/key **三分离**更利部署（ts 公开、key 保护）；子目录名全部 `HlsOptions` 可配，保证「未来布局需求=加选项而非改签名」的前向兼容。

### D3: 返回 `HlsResult`（有意偏离「全家族 RunResult」）
HLS 一次产出 m3u8 + N 段（+ 可选密钥文件），`RunResult` 只有 exitCode/progress/command，**装不下段清单**。故返回 `HlsResult`（`record`：`Path playlist`、**按段序号（`start_number` 起递增）有序**的 `List<Path> segments`、`Path keyFile`（可空）、内嵌 `RunResult run`；段数取 `segments.size()`，**不设冗余 `segmentCount`**）。**承认这是对家族返回约定的有意偏离**——多产物是 HLS 的本质，裸 RunResult 会逼调用方自行 glob。`HlsResult` 为库构造的只读 record，仿 `StreamInfo` 用兼容构造器扩字段（加分量对读取方是加访问器）。

### D4: AES 责任模型 B2 默认 + B1 便利；key 收进 `HlsOptions.key(HlsKey)`
- **B2（默认）**：`HlsKey.of(byte[] keyBytes, String keyUri)` 与 `HlsKey.of(byte[] keyBytes, String keyUri, byte[] iv)`——调用方给密钥字节/URI/可选 IV，库据此写密钥文件（`outDir/key/enc.key`，16 原始字节，`0600`）+ 临时 key_info_file 并接 `-hls_key_info_file`；**机密的存储/权限/清理/是否复用由调用方负责**。
- **B1（便利）**：`HlsKey.random(String keyUri)`——JDK `SecureRandom` 生成 16 字节，字节**可读回**（否则调用方无法解密自己的流），落盘同 B2。
- key **不作 `hlsSegment` 独立形参**（避免有/无 key × 有/无 opts 的重载爆炸，且未来密钥演进都要动签名），经 `HlsOptions.key(HlsKey)` 承载（默认 `null`=不加密）——仍显式可见（`opts.key(...)`）。

### D5: 默认 `-c copy`；转码/对齐为 opt-in
`HlsOptions.videoCodec/audioCodec` 默认 **`copy`**（切片直拷，最小失败面、不 probe、无质量损失——SLICE 输入常为已转码成片）。给具体编码器即转码路径。`hlsTime` 为 **`double` 秒**（默认 `8.0`，渲染去尾零：`8.0→-hls_time 8`、`6.5→-hls_time 6.5`；`-hls_time` 接受小数秒，发布为 `int` 日后要小数段即二进制不兼容）。`-c copy` 下段长受源关键帧支配、`Javadoc` MUST 诚实标注「`hlsTime` 只是尽量」（统一命名，全文不用 `segmentDuration`）；需均匀段用 D7 的 `alignKeyframes`。

### D6: VOD 正确性内部固定注入 `-hls_playlist_type vod` + `-hls_list_size 0`，不作 typed 字段
二者由库内部固定注入（实测缺 `playlist_type` 不写 `#EXT-X-ENDLIST`、缺 `list_size 0` 只留 5 段）。MUST NOT 暴露为 typed 字段——一旦暴露 `event`/滚动 `list_size` 值就等于承诺支持 live，收不回。想要 live 走 `extraOutputArgs`（D1）。

### D7: 通用按秒关键帧 `forceKeyframesEverySeconds`（TranscodeOptions 独占旋钮，HLS 经 alignKeyframes 复用渲染器）
新增 `TranscodeOptions.forceKeyframesEverySeconds(double seconds)` → `-force_key_frames expr:gte(t,n_forced*T)`（`T` 经 locale 无关、去尾零渲染，如 `8.0→8`、`1.5→1.5`，钉死好单测）。与帧基 `gop(int)` **互补共存**（force 保证定时有 IDR；`-g` 兜最大间隔），非二选一。**共用=共用渲染器不共用字段**：单一真源 `FacadeSupport.forceKeyFramesArgs(double)`（纯函数、脱进程单测）。**`HlsOptions` 不暴露裸 `forceKeyframesEverySeconds`**——否则它与 `alignKeyframes` 成同一 `-force_key_frames` 的两个入口、冲突语义未定（同时设会产两条 `-force_key_frames`、`T≠hlsTime` 时段边界静默错位）。HLS 侧只暴露 sugar `HlsOptions.alignKeyframes(boolean)`——true 时以 `T=hlsTime` 复用渲染器（段对齐的唯一合理值就是段时长本身，故不给用户设别的秒数的机会）。校验：`seconds<=0` 在 wither 内即时 `IllegalArgumentException`；`force_key_frames`/`alignKeyframes(true)` 生效而视频 codec 仍为 `copy` 时 build 期抛可诊断异常（不隐式改 codec）。

### D8: AES 参数 build 期 fail-fast，落 `HlsKey` wither（不走 env 能力探测）
既有 wither（`fps`/`gop`）在 setter 内即抛 `IllegalArgumentException`；HLS/crypto 是 ffmpeg **自带**能力，无需 `requireLibass` 式 env 构建开关探测。故 AES **纯输入校验**落 `HlsKey.of/random` 与 `HlsOptions`，即时且脱进程可单测，MUST NOT 拖到 ffmpeg 运行期：
- `keyBytes` 恰 **16 字节**（否则 ffmpeg `Invalid key size`）；`iv`（若给）恰 **16 字节**（渲染 32 hex）。
- `keyUri` 非空、**不含 CR/LF/控制字符/内嵌引号**（否则注入/错位 key_info_file 三行结构；写坏 `#EXT-X-KEY`）。
- 输出目录/子目录可写（build 期校验，不放任 ffmpeg 半途 fopen 失败）。
- **可选（备选任务）**：若目标机 ffmpeg 未 `--enable-openssl/gnutls`，AES 会失败——可在 `FfmpegCapabilities` 探测并启动期诊断（非硬门槛，列备选）。

### D9: 密钥安全基线（SecureRandom + 不可变 clone + 脱敏 + 0600 + 清理）
对抗式安全核验的硬项：
- B1 MUST 用 `java.security.SecureRandom`（**非** `java.util.Random`：LCG 可预测=加密形同虚设）。
- `HlsKey` 持 `byte[]`：构造入参与读取访问器 MUST `clone`（`byte[]` 可变，防外部改内部）；`toString` MUST 脱敏为 `HlsKey[redacted,16B]`；密钥字节 MUST NOT 进 argv（argv 只出现 key_info_file 路径）、日志、`FfmpegException` 的 command/stderrTail。
- **密钥文件 `outDir/key/enc.key` 与临时 key_info_file 二者** MUST 以 `0600` **原子创建**（`PosixFilePermissions.asFileAttribute("rw-------")`，避免先建后 chmod 的 TOCTOU——真密钥文件与接线文件同等严格，不可只保护 key_info_file）。临时 key_info_file MUST 在 `finally` 删除（正常/异常/取消路径均清理，不靠 `deleteOnExit`——SIGKILL 不触发）；`enc.key` 为持久产物交调用方 serving/清理（B2）。
- **非 POSIX（Windows）** 无 `0600` 等价：请求 AES 时 MUST 一次性**显式告警**「密钥落盘无 OS 级权限保护，同机用户可读」，而非静默降级——此约束抬进 spec（不只 design）。
- **IV 复用告警**：`HlsKey.of(…, iv)` 的固定 IV 会施于同一密钥的**每一段**（AES-128-CBC 跨段 IV 复用，相同明文前缀→相同密文首块，削弱机密性）；`Javadoc` MUST 告警「VOD 优先**省略 IV**，由 ffmpeg 用段序号派生（各段 IV 天然不同）」。
- **部署 footgun**：`outDir/<keyDir>` 在 outDir 之下，若整体静态托管 outDir 即可 HTTP 直下明文密钥、加密形同虚设。`Javadoc` MUST 告警「严禁把 `outDir/<keyDir>` 纳入静态托管根，密钥端点须独立鉴权」——真正的密钥访问走 caller 给的 **key URI**（与磁盘路径解耦，可指受保护端点）。
- key URI **明文进 m3u8**，`Javadoc` MUST 告警「勿在 URI 内嵌 token/凭证」；build 期校验 URI 无 CR/LF/控制字符（防 key_info_file 三行注入）。

### D10: 纯函数/副作用分层——`buildHls(…, Path keyInfoFile)` 纯产 argv + 三行文本；写盘/删除隔离在 `FfmpegClient`
保住 `buildXxx` 零副作用约定：`FacadeSupport.buildHls(...)` 是**纯函数**，产 argv + key_info_file 三行文本（+ 密钥字节与目标 `enc.key` 路径）。为让含 `-hls_key_info_file` 的 argv 也能**脱进程精确断言**，临时 key_info_file 的**路径由 `FfmpegClient` 先确定并作为参数传入 `buildHls`**（纯函数不建文件，只把该路径拼进 argv；单测传固定路径即可断言完整 argv）——避免「纯函数产不出含运行期临时路径的 argv」的自相矛盾。**写盘/删除**（mkdir 子目录、写 `enc.key` 与临时 key_info_file、run 后解析 m3u8 装配 `HlsResult`、清理）隔离在 `FfmpegClient.hlsSegment`。**async 不能原样复用 `executeAsync`**——核验读实现证实：`executeAsync`（`FfmpegClient.java:291-321`）无 `finally`、返回 `CompletableFuture<RunResult>`、且取消时 `super.cancel()` 与后台 `await()` 解耦（写盘若塞进 cmdSupplier 只在 await 前执行，删除永远轮不到）。故 `hlsSegmentAsync` MUST **新写带 `try/finally` 的异步骨架**：`try{ 写盘 → runAsync → await → 解析 m3u8 装配 HlsResult } finally{ 删临时 key_info_file }`，全程 executor 线程，返回 `CompletableFuture<HlsResult>`。IO 失败抛 `FfmpegException(message, cause)`，不静默吞。

### D11: 段 URI 前缀经默认 `-hls_base_url <segmentDir>/`（隐式相对化已证伪）
见地面真相：8.0.1 实测 ffmpeg 单播放列表对段 URI 取 `av_basename`，不产 `ts/` 前缀。故库默认无条件注入 `-hls_base_url <segmentDir>/`（版本无关、确定），而非赌 ffmpeg 隐式相对化。`segmentUriPrefix` 设值时覆盖内部前缀（互斥）。这是本变更从「押注隐式行为」到「显式版本无关参数」的关键订正。

### D12: `HlsResult.segments` 由**解析新写出的 m3u8** 得到，不 glob 段目录
`GraphCompiler` 恒注入 `-y`（`GraphCompiler.java:156`），它覆写 `index.m3u8` 但**不清空** `outDir/ts/`。故复用同一 `outDir` 重跑（上次 20 段、本次 8 段）会残留 12 个孤儿 `.ts`；若 `HlsResult.segments` 靠 `glob(ts/*.ts)` 枚举，会把孤儿段错误计入、且词典序下 `index10.ts < index2.ts` 顺序错。**决策**：`HlsResult.segments` MUST 由**解析刚写出的 m3u8**（`#EXTINF`/段 URI 行按出现顺序）得到——唯一权威来源，天然有序、免疫孤儿。**复用非空 `outDir`**：不静默清理（避免误删调用方文件），但 `Javadoc` MUST 告警「复用非空 outDir 会残留上次分段，调用方须自清」；备选 `HlsOptions.cleanSegmentDir(boolean)`（默认 false）。

### D13: 失败/取消/中断的产物与孤儿 `enc.key` 语义
核验引擎终局分支（`FfmpegRunImpl.java:294-311`）：**取消**→`classifyTermination` 返回 `CANCELLED`、`await()` 得 `RunResult(exitCode=255)` 而非异常；**超时**→`TIMEOUT` 抛异常（但盘上可能已 finalize）；**同步调用线程被中断**→`destroyForcibly` SIGKILL、跳过写 `q`、playlist 无 `#EXT-X-ENDLIST`。共同后果：**run 前就落盘的明文 `enc.key`（0600）在所有失败/取消路径残留**，而异常路径下调用方拿不到 `HlsResult`→不知密钥路径去清。**决策**：(1) `hlsSegment` MUST 在内嵌 `run.exitCode()≠0` 时**不装配成功态 `HlsResult`**（改抛 `FfmpegException` 或让 `HlsResult` 暴露 `complete` 信号）；(2) 失败/取消/中断路径 MUST 清理孤儿 `enc.key` 与临时 key_info_file（对称于成功路径），或 `Javadoc` MUST 明确告警残留明文密钥需调用方清 `outDir`——**不得让明文 AES 密钥静默留盘**。

### D14: 布局/并发参数 build 期 fail-fast + 唯一临时文件
既有 wither 即时校验的延伸（补前两轮遗漏的边界）：`segmentTemplate` MUST 含 `%d`/`%0Nd`（否则每段覆写同一文件→不可播 exit 0）；`outDir` 存在且为目录（非文件）、`playlistName` 不含路径分隔符、`segmentDir`/`keyDir` 合法——均 build 期 fail-fast，不放任 ffmpeg 运行期含糊报错。**并发**：临时 key_info_file MUST 经 `Files.createTempFile` 生成**唯一名**（配 0600 原子创建），避免并发调用相互删除/交错；`Javadoc` 声明「同一 `outDir` 并发 `hlsSegment` 不支持，调用方须串行或用不同 `outDir`」（`enc.key`/段名在同 outDir 下必然碰撞）。

### D15: AES 失败诊断——`ErrorPatterns` 增 HLS/crypto + `openssl` 探测上提为启动期
`ErrorPatterns`（`ErrorPatterns.java:20-106`）无任何 HLS/加密模式，故 `openssl` 缺失 / `Invalid key size` 落 `MEDIA_FAILURE` 但 `reason=null`（`FfmpegRunImpl.java:309-310`）——用户面对最常见的「目标机 ffmpeg 未 `--enable-openssl`」得到无诊断裸退出码。**决策**：`ErrorPatterns` 增补 HLS 加密模式（`Encryption not supported`/`Invalid key size`/openssl 串 → 可读 `reason`）；并把 `openssl/gnutls` 能力探测从「备选」上提为**启用 AES 时的启动期诊断**（对齐 `burnSubtitles` 的 `requireLibass` 前置范式），而非运行期 `reason=null`。

### D16: 转码路径边界、流映射、纯音频/0 段
- **流映射**：`buildHls` MUST 显式映射（同全家族），单码率 VOD 取**首视频 + 首音频**（`videoOptional()`+`audioOptional()`），`Javadoc` 诚实标注「多余音/视/字幕轨不纳入，多轨走 L3 或 `extraOutputArgs -map`」——避免多语言音轨被静默丢弃且无提示。
- **alignKeyframes 转码路径无码控旋钮=有意最小**：`HlsOptions` 只有 `videoCodec`/`audioCodec`，无 `crf`/`preset`/`bitrate`/`scale`。故 `alignKeyframes(true)` 转码产 libx264 默认 CRF23 画质。**决策**：不在 `HlsOptions` 长出一排码控旋钮（避免与 `TranscodeOptions` 重复）；`Javadoc` 明确「需控质量/码率/分辨率：先用 `transcode` 门面产成片，再 `hlsSegment` 直拷」，劝阻把 `hlsSegment` 当转码器。
- **纯音频输入**：设计目标为含视频（可选带音频）；纯音频 HLS 出界，`alignKeyframes` 对无视频输入无意义（其 copy 冲突 fail-fast 会自相矛盾）。`Javadoc` 声明纯音频走 `extraOutputArgs`。
- **0 段**：m3u8 无段（空/损坏输入 exit 0）时门面 MUST 抛可诊断 `FfmpegException`（「HLS 未产出任何分段」），不返回空 `HlsResult`。

## Risks / Trade-offs

- **段 URI 前缀靠 base_url（不靠隐式相对化）**：ffmpeg 单播放列表对段 URI 取 `av_basename`（8.0.1 实测写 `index0.ts` 而非 `ts/index0.ts`）。故库**默认注入 `-hls_base_url ts/`** 显式给前缀——这是段能被同目录规则播放器找到的唯一可靠方式，也版本无关。`segmentUriPrefix`（CDN）设值时**覆盖**（非叠加）内部 `ts/`：`segmentUriPrefix("https://cdn/")` 产 `https://cdn/index0.ts`（basename），需保留子目录须自带 `https://cdn/ts/`。二者互斥，MUST 在 `Javadoc` 与 base_url 集成子用例中点破。
- **段模板须含 `%d`**：`segmentTemplate` 无序号占位符（如误传 `segment.ts`）会让 ffmpeg 把每段写同一文件（后覆前）、m3u8 全指一个文件 → 不可播且 exit 0。故 wither MUST 即时校验模板含 `%d`/`%0Nd`（见 D8 扩充的布局参数 fail-fast）。
- **密钥落盘泄露**：明文 16 字节 key 在磁盘。缓解：0600、临时 key_info_file finally 删、密钥字节不进日志/异常/argv、URI 泄露面文档告警（D9）。跨用户主机威胁模型写入 Javadoc。
- **copy 段长不均**：`-c copy` 段长受源 GOP 支配、播放器 seek 不准且无报错。缓解：Javadoc 明确前提；`alignKeyframes(true)` 一键切转码对齐（与 copy 冲突则 fail-fast，D5/D7）。
- **返回类型偏离**：`HlsResult`≠家族 `RunResult`。取舍已接受（D3）：多产物是本质，`HlsResult` 内嵌 `RunResult` 保留退出/进度信息。
- **副作用破例**：HLS 是家族首个写盘的门面。缓解：D10 分层，纯函数部分照旧可单测。

## Migration / Rollout

- 无数据迁移。下游 SLICE opt-in（B2，切片直拷 + AES）：
  ```java
  HlsKey key = HlsKey.of(keyBytes16, "https://keys.example.com/s1.key");   // 或 HlsKey.random(uri)
  HlsResult r = Ffmpeg.hlsSegment(in, outDir, HlsOptions.defaults()
          .hlsTime(8)                 // 默认即 8
          .key(key));                 // 默认 -c copy
  // 需均匀段（重编码对齐）：.videoCodec("libx264").alignKeyframes(true)
  // 越界（fMP4/多码率）：.extraOutputArgs("-hls_segment_type","fmp4", ...) 或 L3 Output.withArgs
  ```
- 通用关键帧（非 HLS）：`TranscodeOptions.defaults().videoCodec("libx264").forceKeyframesEverySeconds(1)` → 每 1 秒一个关键帧（VFR 稳，区别于帧基 `gop(fps)`）。
- 测试遵循仓库约定：**脱进程断言 argv**（copy/转码两路的 `-hls_*` 段、VOD 双标签固定注入、`-hls_segment_filename` 子目录模板、`-force_key_frames` 表达式、key_info_file 三行精确文本、非法 key/URI/iv 抛错、force_key_frames 与 copy 冲突抛错）；**集成测试** `assumeTrue(commandExists("ffmpeg"))` + `-f lavfi -i testsrc` 现场素材到 `@TempDir`，断言产出 `.m3u8` + 段数>0、段 URI 带 `ts/` 前缀、AES 档含 `#EXT-X-KEY` 且 URI 匹配。
