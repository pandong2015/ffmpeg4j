## ADDED Requirements

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
