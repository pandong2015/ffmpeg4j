## Why

`add-hls-segment-facade`（单码率 VOD 切片）落地后，worker SLICE 仍差**自适应码率（ABR 多码率梯）**才能 100% 脱离手写 argv——这是用户已确认的硬需求。ABR 是「一入 N 档不同分辨率/码率 → master.m3u8 + 每档 media playlist + 各档段」的单条 ffmpeg 命令（`-var_stream_map` + `-master_pl_name`），不是 N 次单码率循环。

本变更新增独立 `hlsAbr` 门面，**复用单码率变更的全部基座**（`HlsKey`/AES B2+B1、`forceKeyFramesArgs`、VOD 双标签、纯函数+副作用隔离、m3u8 解析段清单、失败清理），只新增 ABR 专属的码率梯建模与 master 结果。**地面真相全部在本机 ffmpeg 8.0.1 实跑验证**。

## What Changes

- **`hlsAbr` 独立门面**：`Ffmpeg.hlsAbr(File in, File outDir[, HlsAbrOptions])`；`FfmpegClient` 对称 + `hlsAbrAsync` → `CompletableFuture<HlsAbrResult>`。**不重载 `hlsSegment`**（返回类型不同、语义相反：copy vs 恒转码、ABR 即便 N=1 也产 master）。
- **码率梯**：`HlsVariant`（`final class`：`height` 必填 + `videoBitrate`，`width` 可选默认 `scale=-2:h` 保比偶宽，`maxrate`/`bufsize`/`audioBitrate`/codec/crf/preset 可选）；`HlsLadder.defaults()` 内置梯 **1080p@5M / 720p@3M / 480p@1.5M / 360p@800k**，**probe 源高度裁掉放大档**（ABR 恒转码，probe 可接受）。
- **编码走路线 A**（`filter_complex` split+scale+setsar）：契合「流即值」——同一 `input.video()` 被 N 档 `scale` 消费触发 `GraphCompiler` 原生 `split=N`，**L2/L3 结构零改动**。
- **音频 agroup 共享单音轨**：省 N× 存储、各档音频边界一致；master 自动写 `#EXT-X-MEDIA`；`HlsAbrResult` 单列 audio rendition。
- **强制跨档关键帧对齐**：一条 `-force_key_frames expr:gte(t,n_forced*hlsTime)` 覆盖全档（无缝切换的正确性前提，恒开、不暴露开关）；默认 `hlsTime=6.0`。
- **布局**：`outDir/master.m3u8` + `outDir/<name>/{index.m3u8,seg_%d.ts}` + `outDir/key/enc.key`。每档 playlist 与段**共位** → **默认不注入 base_url**（与单码率 D11 相反；且 `-hls_base_url` 不展开 `%v`）。
- **结果**：`HlsAbrResult`（`record`：master + `List<HlsVariantResult>` + 可选 audioRendition + keyFile + 内嵌 `RunResult`）。

## Capabilities

### Modified Capabilities

- `job-model`：在「L4 高层门面」新增 ABR 门面家族（`hlsAbr`/`HlsVariant`/`HlsLadder`/`HlsAbrOptions`/`HlsAbrResult`），复用 `add-hls-segment-facade` 的 `HlsKey`/`forceKeyFramesArgs`/AES 基座。

### Unaffected Capabilities

- `command-compiler`：**无需改动（已实测确认）**。ABR 的 split+scale 正是 `GraphCompiler` 对同一视频流被 N 次消费的原生扇出（`GraphCompiler.java:98-134` 自动 `split=N`）；N 变体 = 单个 `Output`（N×2 `-map` + `var_stream_map` 走 `withArgs`），单输出模型足以表达。**唯一新增的是隐式契约**：`var_stream_map` 的 `v:N` 下标依赖 `GraphCompiler` 按 `mapped()` 顺序发 `-map`（`GraphCompiler.java:167-183` 本就如此），本变更将其钉为显式契约 + argv 单测，不改行为。
- `execution-engine`：复用 `add-hls-segment-facade` 的 D10 异步骨架；不改引擎契约。
- `media-probe`：**新增只读依赖**——默认梯按源高度裁剪需 `ffprobe` 源分辨率（ABR 恒转码，与单码率 `-c copy` 不 probe 的取舍不冲突）。

## Impact

- **纯 additive**：新增门面/值类型/结果类型；`hlsSegment` 与既有 argv 逐字节不变。语义版本 **1.4.0**（minor）。
- **恒转码**：ABR 每档不同码率/分辨率，必经 filtergraph 重编码（无 `-c copy` 快路径——那是单码率 N=1 的场景，由 `hlsSegment` 承担）。
- **安全**：AES 复用单码率基座；**已知并接受**：ABR 省略 IV 时 ffmpeg 产 `IV=0x00…00` 跨段复用（异于单码率的段序号派生）——采用单密钥模型，VOD 场景风险低，`Javadoc` 标注（用户决策）。
- **非目标**：fMP4 ABR、live·event、密钥轮换、per-variant 独立密钥、SAMPLE-AES、字幕轨 rendition、CDN 扁平 `segmentUriPrefix`（ABR 下跨档段名碰撞）——走 `extraOutputArgs` 或后续变更。
