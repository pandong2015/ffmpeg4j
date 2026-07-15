## Why

下游 `ocs-media-task` 的 **worker SLICE**（HLS 切片 + AES-128）是 ffmpeg4j 1.2.0 唯一「ffmpeg 能做、库不做」的**硬缺口**——当前整段留 core 手写 `-f hls -hls_time -hls_key_info_file` argv 管道。证据：`Ffmpeg` 门面只有 8 个动作（transcode/remux/clip/extractAudio/thumbnail/gif/concat/burnSubtitles）+ probe，**无 HLS**（`Ffmpeg.java`）；`FacadeSupport` 无 hls muxer 接线；全库 `grep` **零处 `force_key_frames`**——「段边界=关键帧」这条 HLS 切片硬需求也无 typed 入口（现有 `TranscodeOptions.gop(int)` 只按**帧数**派生 `-keyint_min N -g N -sc_threshold 0`，`FacadeSupport.java:122-131`）。

本变更把 **HLS 单码率 VOD 切片（可选 AES-128）** 收为一等门面（A），并把「**按秒强制关键帧**」提为通用 typed 能力（B），使 worker SLICE 脱离手写 argv。B 独立可用（惠及所有转码调用方），被 A 的段对齐复用。

## What Changes

- **HLS 切片门面（A）**：新增 `Ffmpeg.hlsSegment(File in, File outDir[, HlsOptions])` 与 `FfmpegClient` 对称实例 + `hlsSegmentAsync` 变体；新增 `HlsOptions`（`final class` + wither）、`HlsKey`（AES 值对象，`final class` + 静态工厂）、`HlsResult`（`record`：playlist + 段清单/计数 + 可选 keyFile）。产物**三分离布局**：`outDir/index.m3u8` + `outDir/ts/index%d.ts` + `outDir/key/enc.key`（子目录名可配）。默认 `-c copy`，内部固定 `-hls_playlist_type vod -hls_list_size 0`。
- **AES-128（A）**：**B2 责任模型默认**——调用方给 `keyBytes`/`keyUri`/可选 `iv`，库拼 key_info_file 接线 `-hls_key_info_file`，机密生命周期归调用方；**B1 便利** `HlsKey.random(uri)`（JDK `SecureRandom` 生成 16 字节，字节可读回）。key 经 `HlsOptions.key(HlsKey)` 承载，不作独立形参。
- **通用按秒关键帧（B）**：新增 `TranscodeOptions.forceKeyframesEverySeconds(double)` → `-force_key_frames expr:gte(t,n_forced*T)`，与帧基 `gop(int)` **互补共存**；单一渲染器 `FacadeSupport.forceKeyFramesArgs(double)`。HLS 侧以 `HlsOptions.alignKeyframes(boolean)` 复用（T=hlsTime）。`force_key_frames` 必然重编码，与 `-c:v copy` 冲突时 **build 期 fail-fast**。

## Capabilities

### Modified Capabilities

- `job-model`：在「L4 高层门面」新增 HLS 切片门面家族（`hlsSegment`/`HlsOptions`/`HlsKey`/`HlsResult`，单码率 VOD + AES-128）；在 transcode 类型化码控新增通用 `forceKeyframesEverySeconds`（并由 HLS `alignKeyframes` 复用）。

### Unaffected Capabilities

- `command-compiler`：**无需改动**。HLS 是纯 muxer/output-args（无滤镜图），经 `Output.to(playlist).withArgs(-hls_*)` 既有路径下发；`-force_key_frames` 是输出 arg 而非滤镜，`Output.withArgs` 已能表达。
- `execution-engine`：**不改执行引擎契约**——同步路径复用 `FfmpegExecutor`/取消阶梯/进度。但 async **不能原样复用 `executeAsync`**（其无 finally、返回 `RunResult`、取消与后台 await 解耦，`FfmpegClient.java:291-321`）：`hlsSegmentAsync` 在 facade 层新写带 `try/finally` 的异步骨架承载写盘/清理/`HlsResult` 装配（见 design D10）。临时 key_info_file 的写/删仍在 facade 层，不触碰引擎。
- `media-probe`：不涉及（默认 `-c copy` 路径不 probe）。

## Impact

- **纯 additive**：全为新增门面/Options/值对象/`TranscodeOptions` 新方法；既有 argv **逐字节不变**。语义版本 **1.3.0**（minor）。
- **新副作用（受控）**：HLS 门面写盘产出多文件（m3u8 + N 段 + 密钥文件）+ 临时 key_info_file——**打破既有 `buildXxx` 零副作用惯例**。缓解：argv 组装（`FacadeSupport.buildHls` 纯函数）与写盘/删除（`FfmpegClient`）**分层隔离**，key_info_file 文本生成仍是可脱进程单测的纯函数（design D10）。
- **安全面**：AES 明文 16 字节密钥须落盘供 ffmpeg 读——库以 `0600` 落 `outDir/key/`；key URI **明文进 m3u8**。责任边界（B2=调用方持机密）、SecureRandom、`byte[]` clone、脱敏、清理与告警见 design D8/D9。
- **非目标**：多码率梯（master+variants）/fMP4/live/event/密钥轮换均**出界**，走 `HlsOptions.extraOutputArgs` 或 L3 `Output.withArgs`（design D1）。
