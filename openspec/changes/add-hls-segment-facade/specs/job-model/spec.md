## ADDED Requirements

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
