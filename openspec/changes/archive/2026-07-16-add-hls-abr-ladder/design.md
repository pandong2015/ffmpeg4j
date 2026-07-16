## Context

本变更建立在 `add-hls-segment-facade`（单码率 VOD）之上，复用其 `HlsKey`/AES B2+B1、`FacadeSupport.forceKeyFramesArgs`、VOD 双标签固定注入、`buildXxx` 纯函数 + `FfmpegClient` 副作用隔离（含 async try/finally 骨架 D10）、`HlsResult.segments` 解析 m3u8（D12）、失败清理孤儿 `enc.key`（D13）、唯一临时 key_info_file（D14）、`ErrorPatterns` HLS/crypto（D15）等基座。ABR 只新增码率梯建模、`var_stream_map`/`master_pl_name` 拼装、master 解析、多档结果。

**核心架构判断（实测确认）**：ABR 不掀翻单码率的「`command-compiler` 无需改动」——split+scale 正是 `GraphCompiler` 对「同一 `input.video()` 被 N 档 `scale` 消费」的原生扇出（`GraphCompiler.java:98-134` 自动插 `split=N`、逐输出分配 `[vN]` 标签）。ABR 是纯 L4 门面活。

## 地面真相验证（全部本机 ffmpeg 8.0.1 实跑，非推断）

- **`-var_stream_map` 语法**：空格分隔档、逗号分隔档内流引用；引用用**输出侧类型索引** `v:i/a:i`（按 `-map` 出现顺序），与 `-map` 的**输入侧** `1:a:0` 是两套命名空间。踩坑实证：`-map a:0`（省输入号）报 `Invalid argument` EXIT=234，须 `-map 1:a:0`。
- **`-master_pl_name` 生成 master.m3u8**：`#EXT-X-STREAM-INF` 的 `BANDWIDTH/AVERAGE-BANDWIDTH/RESOLUTION/CODECS` **全由 ffmpeg 从实测编码结果自动填**（如 `RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"`），库无需也无法预算——只解析回读。
- **`%v` 与布局**：输出 `outDir/v%v/index.m3u8` → master 落 `outDir/master.m3u8`、变体 URI 为相对 `v0/index.m3u8`。每档 playlist 与段**共位** `v%v/`，段 URI 取 `av_basename`（`seg0.ts`）相对同目录解析即命中。
- **base_url 反转（关键，勿照搬单码率）**：`-hls_base_url` **不展开 `%v`**（实测写字面 `cdn/%v/seg0.ts`）；且每档共位布局下段 URI=basename 天然自洽 → **ABR 默认完全不注入 base_url**（单码率 D11 因段在 `ts/`、playlist 在根才必须注入，ABR 反之）。设扁平 base_url 会让各档 `index0.ts` 碰撞、错播。
- **`%v` 强制**：N>1 缺 `%v` → ffmpeg 硬报错 `More than 1 variant streams... %v is expected` + `Could not write header`（**非** exit 0 静默坏结果）。
- **目录创建**：ABR 的 `%v` 路径 ffmpeg **会自动建**（含深层，实测 `nested/v%v` 一次建齐）——与单码率「不建 `ts/` → 静默零段」相反；但库仍显式 `Files.createDirectories` 求确定 + 4.2 稳。
- **agroup 共享音频**：`-var_stream_map "v:0,agroup:aud v:1,agroup:aud a:0,agroup:aud,name:audio,default:yes"`（音频仅 `-map 1:a:0` 一次）→ `vaudio/` 只存一份音频段，master 自动 `#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="group_aud",...URI="vaudio/prog.m3u8"`、各 STREAM-INF 挂 `AUDIO="group_aud"`。非 agroup 则音频编 N 份。
- **关键帧对齐**：一条 `-force_key_frames "expr:gte(t,n_forced*4)"` 施于**全部变体**（基于共享源时间轴 `t`）→ 各档 `#EXTINF` 与关键帧 pts 逐一对齐（实测各档 pts_time 1.466667/5.466667 一致）。VOD 双标签逐档传播，master 自身无 `#EXT-X-ENDLIST`（正确）。
- **AES 跨档**：单个 `-hls_key_info_file` 加密所有档；每档 playlist 各写一行 `#EXT-X-KEY`（同 METHOD/URI），master 无 KEY 行。**已知安全项**：省略 IV + `var_stream_map` 时每档产 `IV=0x00…00` 跨段复用（异于单码率的段序号派生 IV）。
- **编译器契合实证**：编译器风格 argv 一次跑通：`-filter_complex "[0:v:0]split=3[s0][s1][s2];[s0]scale=1920:1080,setsar=1[v0];..." -map [v0] -map 0:a:0 -map [v1] -map 0:a:0 ... outDir/v%v/index.m3u8`，master 三档 RESOLUTION 正确；音频 `0:a:0` 被 3 个 map 消费（`needsGraphPresence=false`）渲染 3 次 `-map 0:a:0`、不插 asplit。

## Goals / Non-Goals

**Goals：** ABR 多码率梯 VOD（可选 AES-128）在库内一行式可达，配合单码率门面使 SLICE 100% 脱离手写 argv；最大化复用单码率基座，L2/L3 零改动；默认梯开箱即用且可全量自定义。

**Non-Goals：** 不做 fMP4 ABR / live·event / 密钥轮换 / per-variant 独立密钥 / SAMPLE-AES / 字幕 rendition；不暴露 CDN 扁平 `segmentUriPrefix`（ABR 下各档 basename 碰撞，走整树相对托管）；不把 `-var_stream_map` 抽象成 typed「变体组」模型（v1 facade 手拼字符串）。

## Decisions

### D1: 独立 `hlsAbr` 门面，不重载 `hlsSegment`
三条硬理由：(1) 返回类型不同（`HlsAbrResult` vs `HlsResult`），Java 不能按返回类型重载；(2) 语义相反（`hlsSegment` 默认 `-c copy` 不转码不 probe，ABR 恒转码）；(3) ffmpeg 用 `var_stream_map` 时**即便 N=1 也产 master.m3u8**，与单码率无-master 布局本质不同。签名：`Ffmpeg.hlsAbr(File in, File outDir)` / `(…, HlsAbrOptions)`；`FfmpegClient` 对称 + `hlsAbrAsync` 两档 → `CompletableFuture<HlsAbrResult>`。`hlsSegment` 契约逐字不动。

### D2: 编码走路线 A（filter_complex split+scale+setsar），否决路线 B
路线 A：`VideoStream v = input.video(); 每档 = Filters.setsar(Filters.scale(v, w, h))`——同一 `v` 被 N 档消费触发编译器自动 `split=N`，契合「流即值」、L2/L3 零改动、天然含 `setsar`（项目归一化 MUST）。路线 B（`-map 0:v -s:v:N -b:v:N` 无 filtergraph）实测亦通，但把缩放变成不透明 `-s` 字符串、丢 `setsar`/SAR/aspect 归一化能力，与 design「视频 scale/setsar 由门面给、编译器接线、MUST 含 setsar」相悖——**否决为主路线**，仅作 Javadoc 提及的越界替代。

### D3: 布局 = master 在根 + 每档共位子目录 + 默认不注入 base_url
`outDir/<masterName>`（默认 `master.m3u8`）+ `outDir/<name>/<segmentTemplate>` + `outDir/<name>/index.m3u8` + `outDir/key/enc.key`（AES）。每档 playlist 与段共位 → 段 URI=basename 自洽，**默认不注入 `-hls_base_url`**（与单码率 D11 相反——实测 `%v` 不展开、共位布局不需要）。**变体目录名遵循单一真源的「已解析目录名」**：facade 在 probe 裁剪后**一次性算定**每档目录名——有显式 `name` 用之，否则用**数字索引** `0/1/2`（实测：`var_stream_map` 省略 `name:` 时 ffmpeg 按索引建目录 `0/1/2`；⚠️ `%v` 在 `name:` 字段内 **8.0.1 实测不展开**，`name:stream_%v` 会让各档写进同一字面目录 `stream_%v/` 互相覆盖、产坏 ABR，故**绝不可**把 `%v`/`stream_%v` 塞进 `name:`）。这份解析名列表 MUST 同时驱动：(1) `var_stream_map` 的 `name:`（null 则整段省略）、(2) `Files.createDirectories`、(3) master 变体 URI 与逐档 m3u8 解析路径、(4) `HlsVariantResult.name`——四处同名，杜绝「库建的目录 ≠ ffmpeg 写的目录」。masterName 走 wither 可配。

### D4: `HlsVariant`（final class + 工厂 + wither），height 主 + width 可选
`HlsVariant.of(int height, String videoBitrate)` + withers。必填 `height`、`videoBitrate`；可选 `width`（默认 `null` → `scale=-2:h` 保比偶宽，H.264 要求偶数）、`maxrate`/`bufsize`（`null` 时由 `videoBitrate` 派生 ≈1.07×/1.5×）、`audioBitrate`（默认 `128k`）、`videoCodec`（默认 `libx264`）、`audioCodec`（默认 `aac`）、`crf`、`preset`、`name`（默认 **`null`** → 目录回退数字索引 `0/1/2`；给值才作 `var_stream_map name:` 与目录名）。wither 即时 `IllegalArgumentException`：`height<=0`、非法 bitrate、**`name` 含 `%v` 或 `var_stream_map` 结构元字符（空格/逗号/冒号/路径分隔符）**（实测 `name:"my 720p"`/`"a,b"` 会撕裂 var_stream_map → exit 0 却零产物）。用 final class 非 record（record 规范构造器公开、加字段二进制不兼容，全库 Options/值类型惯例）。

### D5: `HlsLadder.defaults()` 内置梯 + probe 裁剪（null 哨兵区分默认/显式）
默认梯（用户确认）：**1080p@5M / 720p@3M / 480p@1.5M / 360p@800k**（`maxrate≈1.07×`、`bufsize≈1.5×`，实测跑通）。**「默认 vs 显式」用 null 哨兵区分**：`HlsAbrOptions.variants` 内部默认 `null`（`defaults()` **不**急切填梯），仅 `null` 时门面才应用 `HlsLadder.defaults()` + probe 裁剪；非 null（用户显式给梯）**不裁剪**（尊重显式意图，仅 `Javadoc` 告警放大风险）——否则「用户显式传了同一梯」与「未设」不可区分、裁剪无法正确实现。`variants()` 只读访问器可在 null 时返回 defaults 视图，但裁剪判定读内部哨兵。
裁剪规则：`ffprobe` 源 `height`，过滤掉 `height>源height` 的档（不放大）。**极小源兜底（不放大原则贯彻到底）**：兜底档取「不超过源高度的最高默认档」；若所有默认档都 `>源height`，则**以源高度自身（取偶）生成单档**，而非强留会放大的 360 档。
**无视频轨 / probe 失败 fail-fast**：ABR 路线 A（split+scale）本质要求视频轨。输入无视频轨或 `ffprobe` 取不到 `height` 时 MUST 抛可诊断 `FfmpegException`（点名 ABR 需视频轨/probe 数据），MUST NOT 静默产空梯或拖到 ffmpeg 运行期。显式 variants 豁免 probe，但无视频输入仍会在 ffmpeg 层失败（Javadoc 标注）。这是 ABR 相对单码率的新 probe 依赖（ABR 恒转码，可接受）。

### D6: 音频 agroup 共享单音轨（默认）
默认梯采用 agroup（用户确认）：音频只 `-map 1:a:0 -c:a:0` 编一次，`var_stream_map` 每视频档追加 `agroup:<gid>`、末尾追加 `a:0,agroup:<gid>,name:audio,default:yes`。省 N× 音频存储、各档音频段边界一致。master 自动 `#EXT-X-MEDIA` + STREAM-INF `AUDIO=`。`HlsAbrResult` 单列 `audioRendition`（不属任何视频档目录）。agroup 下只一路音频：其编码参数取**独立 `HlsAbrOptions.audioBitrate`（默认 `128k`）/`audioCodec`**，`HlsVariant` 的 per-variant 音频字段在 agroup 下**不生效**（`Javadoc` 标注）。MAY 提供 `HlsAbrOptions.sharedAudio(false)` 退回每档独立音频：此时 MUST 用**带下标** `-c:a:N`/`-b:a:N` 逐档下发各档音频参数、`mapped()` 按 `v0,a0,v1,a1` 交错、`var_stream_map` 为 `v:i,a:i`（否则无下标参数对全部音轨施同值、per-variant 音频被静默忽略；下标须对齐 D10）。

### D7: 强制跨档关键帧对齐（恒开，不暴露开关），hlsTime 默认 6.0
ABR 各档段边界必须一致播放器才能无缝切码率——不对齐 = 切档卡顿/花屏的隐性正确性 bug。故 `hlsAbr` **恒注入** `-force_key_frames expr:gte(t,n_forced*<hlsTime>)`（复用单码率 `FacadeSupport.forceKeyFramesArgs` 纯函数，单一真源，一条覆盖全档）。`HlsAbrOptions` **不暴露 `alignKeyframes` 开关**（对齐是 ABR 定义性行为）。ABR 恒转码 → 无单码率的 copy 冲突分支。`hlsTime` 默认 **6.0**（用户确认，ABR 惯用、切档粒度较 8.0 更细），可配。

### D8: AES 复用单码率基座，单密钥覆盖全档，接受 IV=0 复用
`HlsAbrOptions.key(HlsKey)` 直接复用单码率 `HlsKey`（B2 `of` / B1 `random`、`SecureRandom`、`byte[]` clone、`toString` 脱敏、16 字节校验、0600 原子创建、finally 删临时 key_info_file、失败清理孤儿 `enc.key`）。库只写**一个** `outDir/key/enc.key` + **一个**临时 key_info_file 供全档共享；无 per-variant 密钥逻辑。**IV：单密钥模型（用户决策）**——省略 IV 时 ffmpeg 在 `var_stream_map` 下产 `IV=0x00…00` **跨段/跨 rendition 复用**（实测：`v0/seg_0.ts` 与 `vaudio/seg_0.ts` 首个密文块完全相同——CBC IV 复用把相同明文前缀 TS 头泄露成相同密文块），接受（VOD 单密钥风险低）。**这是相对单码率的隐性回归**：`hlsSegment` 省略 IV → 段序号派生（各段 IV 不同、安全）；`hlsAbr` 省略 IV → 全零复用。`Javadoc` MUST 显式点破此对比 + 跨段/跨 rendition 结构泄露，重申高机密需求应自管每段 IV/密钥轮换（越界）。
**密钥托管 footgun（补回单码率被删的告警）**：`enc.key` 落 `outDir/key/enc.key`，而 ABR 主推「整树相对托管」——若把整个 `outDir` 纳入静态托管根，`GET /key/enc.key` 直接下发明文密钥、AES 形同虚设。`Javadoc`/spec MUST 告警：`key/` MUST 排除在 CDN/静态托管根之外（或密钥落 outDir 之外），「整树相对托管」表述 MUST 显式标注「**不含 `key/`**」；真正取密钥走 caller 的 key URI（与磁盘解耦，指受保护端点）。

### D9: `HlsAbrResult`（record），段清单解析各档 m3u8
`HlsAbrResult`（`record`）：`Path master`、`List<HlsVariantResult> variants`（按 `var_stream_map` 顺序）、可空 `HlsAudioRendition audioRendition`（agroup 时）、可空 `Path keyFile`、内嵌 `RunResult run`。`HlsVariantResult`（`record`）：`String name`、`int width`/`height`、`long bandwidth`、`Path playlist`、`List<Path> segments`。**音频 rendition 用专属 record `HlsAudioRendition`**（`String name`、`String groupId`、`Path playlist`、`List<Path> segments`）——master 的 `#EXT-X-MEDIA` 行**无 RESOLUTION/BANDWIDTH**，不复用视频 record 的分辨率/带宽字段（否则只能塞 0 误导）；其 `name` 取自 **URI 目录名**（非 ffmpeg 会加后缀改写的 `NAME` 属性，实测 `audio`→`audio_2`）。`segments` 沿用单码率 D12——解析各 playlist 的 `#EXTINF` 段行（有序、免疫 `-y` 孤儿段）。`width/height/bandwidth` 解析 master `EXT-X-STREAM-INF`，解析 MUST **引号感知**（`CODECS="avc1.640028,mp4a.40.2"` 值内含逗号）或用定向正则 `BANDWIDTH=(\d+)`/`RESOLUTION=(\d+)x(\d+)`——朴素 `split(',')` 会被 CODECS 内逗号误分割。仿 `StreamInfo` 兼容构造器扩字段。`run.exitCode≠0` 或任一档 0 段 → 抛可诊断 `FfmpegException`，不装配成功态。

### D10: `-map` 顺序契约 —— ABR 首次硬依赖，钉为显式契约 + argv 单测
`var_stream_map` 的 `v:N/a:N` 与 `-b:v:N` 下标按输出流「同类型出现次序」计数，该次序完全由 `GraphCompiler` 发 `-map` 的顺序决定（`GraphCompiler.java:167-183` 严格按 `output.mapped()` 列表顺序、不重排）。单码率从不依赖此不变量，ABR 首次依赖。故：facade 以**单一确定构造顺序**生成 `mapped()`（如 v0,a,v1,a,…）与 `var_stream_map` 字符串，避免两处次序漂移；并把「`GraphCompiler` 按 `mapped()` 顺序发 `-map`」加脱进程 argv 单测（断言 N×2 个 `-map` 精确顺序 + `var_stream_map`/`-b:v:N` 下标一一对应）。

### D11: `%v`/索引强制、库按「已解析目录名」建目录、结果解析路径自洽
N>1 时各档必须落不同目录——由 `%v`（省略 `name:` 时 ffmpeg 用索引 `0/1/2`）或显式 `name:` 保证。库用 **D3 的「已解析目录名」列表**（probe 裁剪后一次性算定）`Files.createDirectories(outDir/<每个解析名> + outDir/key)`（AES 时）——虽 ffmpeg 会自动建目录，但显式建求确定 + 4.2 稳 + 统一单码率约定，关键是**与 ffmpeg 实际写入目录同名**（否则预建目录变死目录、`HlsVariantResult` 路径指错、逐档 m3u8 解析读不到产物）。build 期 fail-fast：masterName/变体 name 的路径分隔符、`%v`、`var_stream_map` 结构元字符（空格/逗号/冒号）合法性（见 D4）。

### D12: `HlsAbrOptions`（final class），无单 codec、无 align 开关、默认不暴露 segmentUriPrefix
`HlsAbrOptions.defaults()` 字段：`variants(List<HlsVariant>)`（内部默认 **`null`** 哨兵=未设 → 门面用 `HlsLadder.defaults()`+probe 裁剪；非 null 不裁，见 D5）、`hlsTime`（默认 6.0）、`key(HlsKey)`、`audioBitrate`（默认 `128k`，agroup 共享音频用）、`masterPlaylistName`（默认 `master.m3u8`）、`segmentTemplate`（默认 `seg_%d.ts`，MUST 含 `%d`）、`startNumber`、`sharedAudio`（默认 true=agroup）、`extraOutputArgs`、`onProgress`、`timeout`。**无** `videoCodec`/`audioCodec`（下沉到 `HlsVariant`）、**无** `alignKeyframes`（恒开）。**不暴露 `segmentUriPrefix`**（ABR 扁平前缀跨档 basename 碰撞、`%v` 不展开）——CDN 走整树相对托管（**不含 `key/`**，见 D8）；确需绝对段 URL 属逃生舱/后续（段 basename 须全局唯一）。

### D13: 纯函数/副作用分层复用单码率 D10（probe+裁剪在门面，buildHlsAbr 纯）
沿用 `buildRemux`/`buildConcat` 范式（`ProbeResult` 注入、`buildXxx` 零副作用，`FacadeSupport.java:151/227/330`）：**probe 源高度 + 默认梯裁剪 + 算定「已解析目录名」在门面（`FfmpegClient`）**完成，把**裁剪后的 variants（含解析名）**传给 build——`buildHlsAbr` **绝不跑 ffprobe 子进程、保持纯函数**（否则破坏零副作用约定）。`FacadeSupport.buildHlsAbr(File in, File outDir, HlsAbrOptions oCropped, Path keyInfoFile)` 纯函数：产 argv（split+scale+setsar 经建流交 `GraphCompiler`；`-c:v:N`/`-b:v:N`/`var_stream_map`/`master_pl_name`/`-hls_*`/`force_key_frames` 经 `Output.withArgs`）+ key_info_file 三行文本；keyInfoFile 路径由 `FfmpegClient` 先定传入。**probe/写盘/mkdir/master 与各档 m3u8 解析/清理**隔离在 `FfmpegClient.hlsAbr`，async 走带 `try/finally` 的骨架（复用单码率 D10）。IO 失败抛 `FfmpegException(message, cause)`。

### D14: N=1 经 ABR 的行为 + 重跑孤儿变体目录
**N=1**（默认梯裁到 1 档，或用户显式给单档）：`var_stream_map` 仍产 `master.m3u8` + 单行 `STREAM-INF`，`%v` 非必需但布局照旧（单目录）。`hlsAbr` MUST 仍产 master + `HlsAbrResult.variants.size()==1`。`Javadoc`/USAGE 点明**分派建议**：只要一档且直拷 → 用 `hlsSegment`（copy 快、无 master）；要转码 + master → 用 `hlsAbr`。
**重跑孤儿变体目录**：ABR 新增一类孤儿——复用非空 `outDir` 且档数变少（probe 变化或改梯）时，旧的整个变体子目录（含 index.m3u8+段）残留。`HlsAbrResult` 因解析 master 不混入（OK），但静态托管 `outDir` 时残留旧档目录=陈旧可播资源 footgun。`Javadoc` MUST 告警「复用非空 outDir 且档数减少会残留孤儿变体目录，调用方须自清」（对齐单码率 D12）；MAY 提供 `cleanOutputDir` 备选。

## Risks / Trade-offs

- **base_url 反转易踩**：实现者若从单码率无脑复用 `-hls_base_url <dir>/` 会写坏 ABR URI（`%v` 不展开 + 碰撞）。缓解：D3 明确 ABR 默认不注入 + 集成测试断言各档段 URI=basename。
- **`-map` 顺序漂移**：facade 两处（`mapped()` 与 `var_stream_map` 串）次序不一致 → 下标错配、档张冠李戴。缓解：D10 单一构造顺序 + argv 单测钉死。
- **IV=0 复用**：已知、接受（用户决策，单密钥 VOD）；`Javadoc` 标注。
- **probe 依赖**：默认梯裁剪需 probe 源；probe 失败或无视频轨的处理须 fail-fast。缓解：显式 variants 时不 probe。
- **agroup 复杂度**：音频独立 rendition 使结果模型非对称（audioRendition 单列）。缓解：`HlsAbrResult.audioRendition` 显式建模 + `sharedAudio(false)` 退路。

## Migration / Rollout

- 无数据迁移。SLICE opt-in（默认梯 + AES）：
  ```java
  HlsAbrResult r = Ffmpeg.hlsAbr(in, outDir, HlsAbrOptions.defaults()
          .hlsTime(6.0)                                  // 默认即 6.0
          .key(HlsKey.of(keyBytes16, "https://k/s.key")));  // 复用单码率 HlsKey
  // 自定义梯：.variants(List.of(HlsVariant.of(1080,"5000k"), HlsVariant.of(720,"3000k").width(1280)))
  ```
- 测试遵循仓库约定：**脱进程断言 argv**（split=N + 逐档 scale+setsar、N×2 `-map` 精确顺序、`-c:v:N`/`-b:v:N`、`var_stream_map` 串与下标对应、`master_pl_name`、恒注入 `force_key_frames`、VOD 双标签、无 base_url、AES `-hls_key_info_file`、key_info_file 三行）；**集成测试** `assumeTrue(commandExists("ffmpeg"))` + `-f lavfi -i testsrc` 现场素材到 `@TempDir`，断言产 `master.m3u8` + 各档 `<name>/index.m3u8`+段、master 含 N 行 `EXT-X-STREAM-INF`（RESOLUTION 对）、各档段 URI=basename、agroup 时 `#EXT-X-MEDIA` + `vaudio/`、AES 各档含 `#EXT-X-KEY`、各档 `#EXTINF` 跨档一致（对齐）。
