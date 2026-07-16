## 1. B · 通用按秒强制关键帧（先做，A 复用）

- [x] 1.1 `FacadeSupport.forceKeyFramesArgs(double seconds)` 纯函数：产 `["-force_key_frames","expr:gte(t,n_forced*"+fmt(seconds)+")"]`；`fmt` locale 无关、去尾零（`8.0→8`、`1.5→1.5`）
- [x] 1.2 `TranscodeOptions.forceKeyframesEverySeconds(double)`（wither，`<=0` 即时抛 `IllegalArgumentException`）+ 访问器；与既有 `gop(int)` 互补共存（不互斥、不覆盖）
- [x] 1.3 `buildTranscode` 接线：`forceKeyframesEverySeconds` 生效且视频 codec 为 `copy`（`videoCodec` 未设编码器）→ build 期抛可诊断 `FfmpegException`（点名需重编码）
- [x] 1.4 单元测试（脱进程 argv）：`forceKeyframesEverySeconds(1)` → `-force_key_frames expr:gte(t,n_forced*1)`；`gop(50)+forceKeyframesEverySeconds(2)` 两段并存；`1.5` 去尾零渲染；`seconds=0` 与 `seconds<0` **均抛**、`seconds>0` 通过；copy 冲突抛错；默认不产该参数（既有 argv 逐字节不变）

## 2. A · HlsKey 值对象（AES-128，B2 默认 / B1 便利）

- [x] 2.1 `HlsKey` `final class`：`of(byte[] keyBytes, String keyUri)`、`of(byte[] keyBytes, String keyUri, byte[] iv)`、`random(String keyUri)`（`java.security.SecureRandom` 16 字节）
- [x] 2.2 构造/wither 即时校验：`keyBytes.length==16`、`iv`（若给）`==16`、`keyUri` 非空且无 CR/LF/控制字符/内嵌引号 → 违者 `IllegalArgumentException`
- [x] 2.3 不可变与脱敏：`byte[]` 构造入参 + 访问器 `clone`；`toString` = `HlsKey[redacted,16B]`；不实现泄露密钥的 `equals`/日志
- [x] 2.4 key_info_file **文本**生成为纯函数：两行（URI\n 绝对密钥路径\n）或三行（+ 32 hex IV\n）；单测断言精确文本与非法输入抛错
- [x] 2.5 单元测试：15/17 字节 key、空/含换行 URI、非 16 字节 iv 立即抛；`random` 用 `SecureRandom` 且长度 16、字节可读回；`toString` 不含密钥
- [x] 2.6 **AES 失败诊断**（从备选上提，因 AES 是本变更卖点）：`ErrorPatterns` 增 HLS/crypto 模式（`Invalid key size`/`Encryption not supported`/openssl 串 → 可读 `reason`，置于通用 errno 前）；启用 AES 而 ffmpeg 未 `--enable-openssl/gnutls` → `FfmpegCapabilities` 探测 + 启动期诊断（对齐 `requireLibass` 前置范式），不放任运行期 `reason=null`

## 3. A · HlsOptions（不可变 + 逃生舱）

- [x] 3.1 `HlsOptions` `final class` + 私有构造 + `defaults()` + wither（照抄 `TranscodeOptions` 风格，**不用 record**）
- [x] 3.2 字段：`hlsTime`(**`double`** 秒，默认 `8.0`，`>0`，渲染去尾零)、`playlistName`(默认 `index.m3u8`)、`segmentDir`(默认 `ts`)、`segmentTemplate`(默认 `index%d.ts`)、`keyDir`(默认 `key`)、`keyFileName`(默认 `enc.key`)、`startNumber`(默认 0)、`videoCodec`/`audioCodec`(默认 `copy`)、`key(HlsKey)`(默认 null)、`alignKeyframes`(默认 false)、`segmentUriPrefix`(→`-hls_base_url`，basename 语义)、`extraOutputArgs(String...)`、`onProgress`、`timeout`。**不**含裸 `forceKeyframesEverySeconds`(段对齐经 `alignKeyframes`，`T`=hlsTime)
- [x] 3.3 `toRunOptions()` 复用 `FacadeSupport.runOptions(timeout, onProgress)`
- [x] 3.4 单元测试：wither 不可变（返回新副本）、`extraOutputArgs` 置于类型化 `-hls_*` 之后、未设字段不产参数
- [x] 3.5 布局参数 build 期 fail-fast：`segmentTemplate` 无 `%d`/`%0Nd`、`outDir` 非目录、`playlistName`/`segmentDir`/`keyDir` 含非法路径分隔符 → 即时抛 `IllegalArgumentException`；`cleanSegmentDir(boolean)`（默认 false）便利；单测钉死非法模板/布局抛错

## 4. A · HlsResult 与 FacadeSupport.buildHls（纯函数产 argv）

- [x] 4.1 `HlsResult` `record`：`Path playlist`、`List<Path> segments`(**按段序号递增有序**)、`Path keyFile`(可空)、`RunResult run`；**无冗余 `segmentCount`**(段数取 `segments.size()`)；`segments` 由**解析写出的 m3u8**（段 URI 行按序）得到、**非 glob 目录**（免疫 `-y` 遗留的孤儿段与词典序错序）；仿 `StreamInfo` 留兼容构造器余地
- [x] 4.2 `FacadeSupport.buildHls(File in, File outDir, HlsOptions o, Path keyInfoFile)` **纯函数**（keyInfoFile 路径由 `FfmpegClient` 先定并传入，纯函数不建文件）：经 `Output.to(<outDir>/playlistName, input.videoOptional(), input.audioOptional()).withArgs(...)` 产 argv（**显式映射首视频+首音频**）——固定 `-f hls -hls_time <fmt(hlsTime)> -hls_playlist_type vod -hls_list_size 0 -hls_segment_type mpegts -hls_segment_filename <outDir>/<segmentDir>/<template> -hls_base_url <segmentDir>/`（**base_url 默认注入**，`segmentUriPrefix` 覆盖之——8.0.1 实测 ffmpeg 不隐式相对化）；codec（默认 `-c copy`）；`alignKeyframes` 经 §1.1 渲染（`T`=hlsTime）；AES 经 `-hls_key_info_file <传入路径>`；`extraOutputArgs` 在后
- [x] 4.3 build 期校验（纯函数内）：`outDir` 及子目录父级路径合法；`alignKeyframes`/force 与 `copy` 冲突抛错；AES 参数经 HlsKey 已校验
- [x] 4.4 单元测试（脱进程 argv）：copy 路径最小 argv 精确（**含默认 `-hls_base_url ts/`**、`-map` 首视频+首音频形态）；VOD 双标签恒在；段模板子目录；AES 档含 `-hls_key_info_file`；转码+alignKeyframes 含 `-force_key_frames`；`segmentUriPrefix` 覆盖默认 base_url

## 5. A · 门面接线（写盘/清理隔离在 FfmpegClient）

- [x] 5.1 `Ffmpeg.hlsSegment(File in, File outDir)` + `(…, HlsOptions)` 静态两档
- [x] 5.2 `FfmpegClient.hlsSegment` 两档 + `hlsSegmentAsync` 两档，返回 `CompletableFuture<HlsResult>`；**不能原样复用 `executeAsync`**（`FfmpegClient.java:291-321` 无 finally、返回 RunResult、取消与后台 await 解耦）——新写带 `try/finally` 异步骨架：`try{ 写盘 → runAsync → await → 解析 m3u8 装配 HlsResult } finally{ 删临时 key_info_file }`，全程 executor 线程
- [x] 5.3 副作用隔离（在 FfmpegClient，非 buildHls）：`Files.createDirectories(outDir/ts, outDir/key)`；`enc.key`（16 原始字节）**与**临时 key_info_file 二者均以 `0600` 经 `PosixFilePermissions.asFileAttribute` **原子创建**（非先建后 chmod）；临时 key_info_file 经 `Files.createTempFile` **唯一命名**（防并发碰撞）、第2行写 `enc.key` **绝对**路径；`finally`（正常/异常/取消）删临时 key_info_file，**不**靠 `deleteOnExit`；**失败/取消（`run.exitCode≠0`）清理孤儿 `enc.key`**（明文密钥不留盘）或告警调用方清；非 POSIX 无 0600 等价 → 请求 AES 时**显式一次性告警**（不静默降级）
- [x] 5.4 组装 `HlsResult`：run 后**解析写出的 m3u8（段 URI 行按序）**得段清单（非 glob 目录，免疫孤儿段/词典序错序）、填 playlist/keyFile；`run.exitCode≠0` 不装配成功态、改抛 `FfmpegException`；0 段抛可诊断 `FfmpegException`；IO 失败抛 `FfmpegException(message, cause)`
- [x] 5.5 单元/集成测试：async 走**新异步骨架**（非 executeAsync）、取消后临时 key_info_file 与孤儿 enc.key 仍被清；密钥/临时文件写在 executor 线程；并发同 outDir 的边界（Javadoc 声明不支持）

## 6. 端到端与收尾

- [x] 6.1 集成测试（`assumeTrue(commandExists("ffmpeg"))`，`-f lavfi -i testsrc` → `@TempDir`）：copy 路径产 `outDir/index.m3u8` + `outDir/ts/*.ts`（段数>0）
- [x] 6.2 集成测试**钉死段 URI**（默认 `-hls_base_url ts/`——8.0.1 已证实 ffmpeg 单播放列表取 basename、不隐式相对化）：读回 m3u8 断言段 URI = `ts/index0.ts` 且段实存于 `outDir/ts/`；**复用非空 outDir** 断言 `HlsResult.segments` 只含本轮 m3u8 段（不含孤儿）；**≥10 段**断言数值序（`index2` 在 `index10` 前）；子用例 `segmentUriPrefix("https://cdn/")` → `https://cdn/index0.ts`（覆盖默认）
- [x] 6.3 集成测试 AES：设 `HlsKey.of(...)`，断言 m3u8 含 `#EXT-X-KEY:METHOD=AES-128` 且 `URI==` 传入 keyUri、`.ts` 首字节与未加密档不同
- [x] 6.3b 集成测试**密钥文件权限**（`assumeTrue` POSIX）：AES 完成后断言 `enc.key` 与（若可捕获）临时 key_info_file 权限为 `rw-------`；临时 key_info_file 已删、`enc.key` 仍在
- [x] 6.4 更新文档：`USAGE.md`/`README.md` 新增第 9 个动作门面 hlsSegment（B2/B1 两路 + alignKeyframes 示例）+ `TranscodeOptions.forceKeyframesEverySeconds` 通用示例 + 修正门面计数口径；**同步 in-source 漂移**：`FfmpegClient.java:26` 类 javadoc（现写「8 个一行式」且漏 gif → 「9 个动作门面 + probe」）、`Ffmpeg.java` 分节编号
- [x] 6.5 更新 `CHANGELOG.md`（面向 1.3.0 additive：HLS 门面 + 通用按秒关键帧）
- [x] 6.6 `mvn -o test` 全 reactor 全绿（无 ffmpeg 环境经 `assumeTrue` 跳过集成仍全绿）
- [x] 6.7 `openspec validate add-hls-segment-facade --strict` 通过
- [x] 6.8 **版本验证口径**（决定：不设 4.2 CI lane）：USAGE/README/Javadoc 声明「HLS/AES 仅于 ffmpeg 8.0.1 实测；4.2 为支持下限但未单独验证，遇差异请反馈」；移除各处「跨 4.2 稳定」强断言
- [x] 6.9 **SLICE argv 差集核对**（已完成，取证于 `ocs-media-task/docs/media-task/ffmpeg4j-capability-requirements.md` §二 HLS 台账）：worker SLICE 逐条比对确认为**单码率** `-c copy` VOD HLS + 可选 AES-128，`hlsSegment` typed 全覆盖（分段 MD5 混淆重命名/两阶段提交为下游业务，非本门面职责）；goal 措辞无需降级

## 7. 备选（按需，不阻断）

- [x] 7.1 `HlsOptions.createSegmentDir(false)`（备选，**决定不实现**）：自动建 `ts/`/`key/` 子目录是本门面的布局约定（已在 §5.3 落地），关闭开关属 scope creep 且无下游需求，登记归档不再跟进

> 注：openssl 能力诊断已上提为 **2.6**（AES 是本变更卖点）、`cleanSegmentDir` 已并入 **3.5**，不再列备选。
