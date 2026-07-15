## 1. P0-1 · GIF 门面与调色板滤镜

- [x] 1.1 `Filters.paletteGen(VideoStream)`：1 入 1 出 curated 滤镜，产出调色板流（`palettegen`）
- [x] 1.2 `Filters.paletteUse(VideoStream video, VideoStream palette)`：2 入 1 出 curated 滤镜（`paletteuse`），输入顺序 `[video][palette]`，与 `overlay(base, over)` 同构
- [x] 1.3 `GifOptions`（不可变、wither 风格）：`start`（默认 `0`）、`fps`（默认 `15`）、可选 `duration`、可选 `width`（未设=不加 scale）、可选 `height`（缺省 `-1`），及 `onProgress`/`timeout`
- [x] 1.4 `Ffmpeg.gif(in, out)` 便捷重载 + `Ffmpeg.gif(in, out, GifOptions)` 进阶重载；委托 `FfmpegClient`/`FacadeSupport.buildGif`
- [x] 1.5 `FacadeSupport.buildGif`：`-ss start` 与 `-t duration` **均置输入侧**；`fps`→（有 width 才）无 flags 的 `Filters.scale(width,-1)`→`paletteGen`/`paletteUse`；源流两次消费依赖编译器自动 `split`
- [x] 1.6 `GifOptions` 可选 `scaleFlags`（缺省不加 flags，保逐字节等价；显式设 `lanczos` 才追加）——**不**改 curated `scale` 签名
- [x] 1.7 单元测试（脱进程、断言 argv）：GIF 链含 `fps/scale/split/palettegen/paletteuse`、`paletteuse` 两输入序 `[base 分支][palette]`、`-ss` 与 `-t` 均在 `-i` 前、未设 width 时链中无 `scale`、scaleFlags 显式才追加
- [x] 1.8 集成测试（`assumeTrue`，`-f lavfi -i testsrc`）：`Ffmpeg.gif` 产出合法 GIF（校验 GIF 魔数）

## 2. P0-2 · extractAudio 采样率/声道

- [x] 2.1 `ExtractAudioOptions` 增 `sampleRate(int)`→`-ar`、`channels(int)`→`-ac`；wither 风格、可选
- [x] 2.2 构造期校验：非正整数抛 `IllegalArgumentException`（采样率/声道须为正整数）
- [x] 2.3 `FacadeSupport.buildExtractAudio`：设值时在输出侧追加 `-ar`/`-ac`，未设不追加；**sampleRate/channels 任一非空时禁用 `copy`**（回退自然编码器，实测 `-c:a copy -ar` 静默失效）
- [x] 2.4 单元测试：`sampleRate(16000).channels(1)` → argv 含 `-ar 16000 -ac 1`；默认 → 不含；`sampleRate(0)` 抛异常；aac 源抽 m4a + sampleRate → `-c:a aac` 而非 `copy`；集成验 16k 单声道输出

## 3. P0-3 · thumbnail 精确 seek

- [x] 3.1 定义 `SeekMode { INPUT_FAST, OUTPUT_ACCURATE }`（facade 包内枚举）
- [x] 3.2 `ThumbnailOptions.seekMode(SeekMode)`，默认 `INPUT_FAST`
- [x] 3.3 `FacadeSupport.buildThumbnail`：`INPUT_FAST` 维持 `Input.withInputArgs("-ss", t)`；`OUTPUT_ACCURATE` 把 `-ss t` 置于输出侧
- [x] 3.4 单元测试：默认 `-ss` 在 `-i` 前；`OUTPUT_ACCURATE` `-ss` 在 `-i` 后；带缩放时 `-ss` 与 `filter_complex` 共存

## 4. P0-4 · probe 字段扩展

- [x] 4.1 `StreamInfo` 扩字段（flat record 续扩）：白名单 12 项 + `sampleAspectRatio`/`displayAspectRatio`/`attachedPic`/`language`；命名对齐 `durationSeconds`/`nbStreams`；10 参便捷构造器保源码兼容；Javadoc 逐字段
- [x] 4.2 `FormatInfo` 扩充 `nbPrograms`(int)/`startTimeSeconds`(double) + 6 参便捷构造器
- [x] 4.3 `ProbeMapper` 映射对应键（含嵌套 `disposition.attached_pic`/`tags.language`）；缺失填 `null`/`false`/哨兵
- [x] 4.4 单元测试（样本 JSON 锚定真 ffprobe 8.0.1）：视频画质+SAR/DAR、音频采样格式/声道、嵌套 attachedPic/language、数字型字符串宽松解析、缺 disposition/tags 填空不抛、`FormatInfo` 新字段
- [x] 4.5 回归：`videoStreams()/audioStreams()` 分组 List 对双音轨/双视频不覆盖（DG-3，含双音轨逐轨保留测试）
- [ ] 4.6（可选增益，未做）P0-2 extractAudio 复用 `attachedPic` 稳健排除封面图——当前 `-vn`+`0:a:0` 已避开封面图，attachedPic 字段已供调用方使用，暂不改门面

## 5. 收尾

- [x] 5.1 更新 `USAGE.md`/`README.md`：新增 `gif` 门面（8→9 门面、16→18 滤镜）、extractAudio 采样率、thumbnail seekMode、probe 新字段
- [x] 5.2 更新 `CHANGELOG.md`（面向 1.1.0 additive，标注 `StreamInfo`/`FormatInfo` record 构造器签名变化 + 便捷构造器保兼容）
- [x] 5.3 `mvn -o test` 全 reactor 全绿（351 core 测试 + spring 模块；无 ffmpeg 环境经 `assumeTrue` 跳过集成仍全绿）
- [x] 5.4 `openspec validate add-media-task-p0-capabilities --strict` 通过
