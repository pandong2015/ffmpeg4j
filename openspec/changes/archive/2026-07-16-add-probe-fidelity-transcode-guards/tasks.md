## 1. probe 原始保真字段（media-probe · gap 1+2）

- [x] 1.1 `StreamInfo` record 末尾 append 三字段：`String codecTagHex`（`codec_tag` 十六进制）、`String rawStartTime`（`start_time` 原始定点串）、`String rawDuration`（`duration` 原始定点串）；规范构造器 26→29 参；补 Javadoc（`codecTagHex` vs 既有 `codecTag`=`codec_tag_string` 的区分、raw 缺失→`null` 语义）
- [x] 1.2 `StreamInfo` 兼容构造器：既有 10 参便利构造器补 3 个新缺省（`null`）；**新增** 26 参便利构造器（原 26 参签名，3 个新字段填 `null`），使既有满参构造点源码兼容
- [x] 1.3 `FormatInfo` record 末尾 append `String rawStartTime`、`String rawDuration`；规范构造器 8→10 参；既有 6 参便利构造器补 2 个 `null`；**新增** 8 参便利构造器
- [x] 1.4 `ProbeMapper.mapStreams` 传新字段：`optString(s,"codec_tag")`、`optString(s,"start_time")`、`optString(s,"duration")`（**新增采集**，既有 `asDouble(0.0)` 的 `startTimeSeconds`/`durationSeconds` 保留不动）
- [x] 1.5 `ProbeMapper.mapFormat` 传新字段：`optString(format,"start_time")`、`optString(format,"duration")`（既有 `asDouble` 保留）
- [x] 1.6 单元测试（脱进程，样本 JSON 驱动 `MediaProbe.fromJson`）：`codec_tag="0x31637661"` + `codec_tag_string="avc1"` → `codecTagHex`/`codecTag` 并存；`start_time="0.000000"`/`duration="12.500000"` → raw 逐字符保留且 `startTimeSeconds==0.0`/`durationSeconds==12.5`；缺 `codec_tag`/`start_time`/`duration` 键 → 三 raw 字段为 `null` 而 `startTimeSeconds==0.0` 哨兵不变；format 层同款 raw 保真/缺失→null

## 2. transcode 流禁用与 codec null 守卫（job-model · gap 3）

- [x] 2.1 `TranscodeOptions` 增 `disableVideo(boolean)`/`disableAudio(boolean)`（wither，默认 `false`）+ 访问器 `disableVideo()`/`disableAudio()`；补私有构造器/`defaults()`/全部既有 wither 的字段透传
- [x] 2.2 `buildTranscode` 视频段：`disableVideo` → 产 `-vn`、跳过 `-c:v` 及全部视频码控（crf/preset/`-b:v`/`-r`/maxrate/bufsize/gop/force_key_frames/x265Params）；否则 `videoCodec()==null` → 抛可诊断 `FfmpegException`（点名 videoCodec 为 null）后再产 `-c:v`
- [x] 2.3 `buildTranscode` 音频段：`disableAudio` → 产 `-an`、跳过 `-c:a`/`-b:a`/`-ar`；否则 `audioCodec()==null` → 抛可诊断 `FfmpegException`
- [x] 2.4 `buildTranscode` 输出映射按禁用调整：`disableVideo` → `Output.to(out, input.audioOptional())`（仅音频）；`disableAudio` → 仅视频（含 videoFilter 分支）；均不禁用 → 既有双可选/videoFilter 逻辑不变
- [x] 2.5 冲突 fail-fast：`disableVideo && disableAudio` → 抛错（空输出）；`disableVideo && videoFilter!=null` → 抛错（无视频可滤）
- [x] 2.6 单元测试（脱进程 argv）：`disableVideo(true).audioCodec("aac")` → 含 `-vn -c:a aac`、无 `-c:v`/视频码控、仅音频 `-map`；`disableAudio(true).videoCodec("libx264")` → 含 `-an -c:v libx264`、无 `-c:a`/`-ar`；`videoCodec(null)` 未禁用 → 抛错且 argv 无 `null`；双禁用抛错；`disableVideo`+`videoFilter` 抛错；默认不产 `-vn`/`-an`（既有 argv 逐字节不变）

## 3. transcode 进阶 typed 码控 -ar/-strict/-x265-params（job-model · gap 4，位置精确钉死）

- [x] 3.1 `TranscodeOptions` 增 `audioSampleRate(int)`（`<=0` 即时抛 IAE）/`strict(String)`/`strictExperimental()`（=`strict("-2")`）/`x265Params(String)` + 访问器；字段透传所有既有 wither（见 §4a 护栏）
- [x] 3.2 `buildTranscode` 渲染（精确位）：`x265Params` → `-x265-params <value>` **紧接视频码控段尾**（`-bufsize` 后、`-c:a` 前，`disableVideo` 时不产）；`audioSampleRate` → `-ar <hz>` **紧接 `-b:a`**（音频段尾，`disableAudio` 时不产）；`strict` → `-strict <level>` 于**全部码控段后、`extraOutputArgs` 前**（紧接 GOP/force_key_frames），**与禁用标志无关**（始终产出）
- [x] 3.3 `x265Params` Javadoc + 类文档：注明「显式 x265 通道，仅对 libx265 有意义、库不校验 codec、不自动翻译 maxrate/bufsize；libx264 的 -x264-params 仍走 extraOutputArgs」；`strict(String)` Javadoc 列合法值（`-2..2`/`experimental`/`unofficial`/`normal`/`strict`/`very`）；更新 `TranscodeOptions` 类注释既有「h265 VBV 走 extraOutputArgs」措辞为「走 x265Params 或 extraOutputArgs」
- [x] 3.4 单元测试（脱进程 argv，**精确相邻断言**）：`audioCodec("aac").audioBitrate("128k").audioSampleRate(44100)` → 音频段恰为 `-c:a aac -b:a 128k -ar 44100`；`gop(50).strictExperimental().extraOutputArgs("-movflags","+faststart")` → `-strict -2` 在 GOP 段后、`-movflags` 前，且 `strict("-2")`≡`strictExperimental()`；`videoCodec("libx265").maxrate("2M").bufsize("4M").x265Params("…")` → `-x265-params` 在 `-bufsize 4M` 后、`-c:a` 前；`audioSampleRate(0)`/`(-1)` 抛 IAE；`disableAudio` 时不产 `-ar`、`disableVideo` 时不产 `-x265-params`；默认不产三者（既有 argv 逐字节不变）

## 4. transcode VBV 便利派生（job-model · gap 5，build 期派生 + 孤立 bufsize 不 hard-fail）

- [x] 4.1 `FacadeSupport` 增纯函数 `doubleRate(String rate)`：正则 `^([0-9]*\.?[0-9]+)\s*([a-zA-Z]*)$` 拆数值+单位，数值 `×2` 经去尾零渲染（复用 `num` 语义）回拼后缀；不匹配（空串/纯字母/非法字符）抛 `IllegalArgumentException`
- [x] 4.2 `TranscodeOptions` 增 `vbv(String maxrate)`（设 maxrate + 打 `vbvDeriveBufsize=true` 标志字段）与 `vbv(String maxrate, String bufsize)`（显式二参、清标志）；任何显式 `bufsize(String)` 亦清标志（用户显式优先）；标志字段随所有 wither 透传
- [x] 4.3 `buildTranscode` **build 期派生**（非 wither 当刻）：若 `vbvDeriveBufsize && bufsize()==null` → 依**最终** `maxrate()` 求 `bufsize=doubleRate(maxrate)`；裸 `maxrate`/`bufsize` 渲染逻辑**完全不改**（含孤立 bufsize 仍产出，**不** hard-fail）
- [x] 4.4 `bufsize(String)` Javadoc 告警：「孤立 bufsize（无 maxrate）通常是配置疏漏，VBV 请配 maxrate 或改用 vbv()」（引导替代 hard-fail）
- [x] 4.5 单元测试：`doubleRate` 纯函数（`"2M"→"4M"`、`"2000k"→"4000k"`、`"3000000"→"6000000"`、`"2.5M"→"5M"`、空串/纯字母/非法字符抛 IAE）；`vbv("2M")` → `-maxrate 2M -bufsize 4M`；`vbv("2M","6M")` → `-bufsize 6M`（不派生）；`vbv("2M").maxrate("3M")` → `-maxrate 3M -bufsize 6M`（跟随最终值）；`vbv("2M").bufsize("5M")` → `-bufsize 5M`（显式清标志）；`bufsize("4M")` 无 maxrate → **仍产孤立 `-bufsize 4M`、不抛**（byte-compat）；`maxrate("2M")` 裸设不产 bufsize

## 4a. wither 透传护栏（评审采纳 · 全类字段）

- [x] 4a.1 往返保真测试矩阵：对每个 wither（含新增 disableVideo/disableAudio/audioSampleRate/strict/x265Params/vbv 标志）设一个可区分非默认值，再链式调用若干其它 wither，断言目标字段值仍保留——捕获 ~20 参位置透传的漏传/相邻 String 转置（编译器不报错的静默丢字段）

## 5. 文档与版本

- [x] 5.1 `pom.xml` 版本 `1.4.0` → `1.5.0`
- [x] 5.2 `CHANGELOG.md` 增 `[1.5.0]`：probe 保真字段（codecTagHex/rawStartTime/rawDuration）+ transcode 流禁用/null 守卫/进阶 typed 码控（audioSampleRate/strict/x265Params/vbv/孤立 bufsize 门控）；声明纯 additive、既有 argv 逐字节不变
- [x] 5.3 `USAGE.md`/`README.md`：补 probe 保真字段与 transcode 新 typed 码控示例（纯视频 `disableAudio`、h265 `x265Params`、`-ar`/`strictExperimental()`、`vbv`）；`disableVideo` 示例旁**重申「纯音频抽取首选 `extractAudio` 门面」**（台账 #11）；顺带修正台账 §五列明的既有滞后（门面/滤镜计数口径、`extractAudio` 实为 `-map 0:a:0` 而非 `0:a`）

## 6. 校验与收尾

- [x] 6.1 `mvn -o compile` 全绿（证 record 扩字段的旧构造点源码兼容）
- [x] 6.2 `mvn -q test` 全 reactor 全绿（无 ffmpeg 环境经 `assumeTrue` 跳过集成仍全绿）
- [x] 6.3 既有 transcode/probe 测试**零改动全通过**（byte-compat 自证）——本变更不改任何既有 argv 路径（含孤立 bufsize 不 hard-fail），预期无既有测试需改动；若有则说明属真实 byte-compat 破坏、须回退
- [x] 6.4 `openspec validate add-probe-fidelity-transcode-guards --strict` 通过
- [x] 6.5 集成测试（`assumeTrue(commandExists("ffprobe"))`）：对 `-f lavfi` 现场素材 probe，断言 `codecTagHex`/`rawDuration` 非空且 `rawDuration` 与 `durationSeconds` 数值一致
