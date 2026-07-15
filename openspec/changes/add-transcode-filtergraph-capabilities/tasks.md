## 1. P1-3 · pad 表达式重载与 padToEven

- [ ] 1.1 `Filters.pad(VideoStream, String w, String h, String x, String y, String color)` 表达式重载：w/h/x/y 走 `Arg.of`（不转义），保留既有 int 重载
- [ ] 1.2 curated `Filters.padToEven(VideoStream)`：固定渲染裸 `pad=w=ceil(iw/2)*2:h=ceil(ih/2)*2`（仅 w/h，x/y/color 取默认，对齐 type1；**不**经 6 参 pad），全用 `Arg.of`
- [ ] 1.3（备选）String 重载对 w/h/x/y 加 fail-fast：值不得含裸 `:`（防畸形表达式被 `:` 静默错拼）
- [ ] 1.4 单元测试（断言 argv）：padToEven 体为 `pad=w=ceil(iw/2)*2:h=ceil(ih/2)*2`、pad 表达式重载逐字未转义、int 重载 `pad=w=1280:h=720:...` 不变；集成可加 `scale=100:-1`→padToEven 产 100x76 的尺寸断言（已实测）

## 2. P1-4 · overlay shortest

- [ ] 2.1 `Filters.overlay(base, over, String x, String y, boolean shortest)`：shortest=true 时追加 `Arg.of("shortest","1")`；保留既有 String/int x-y 重载
- [ ] 2.2（可选）`eof_action` 支持：重载或用枚举收窄合法值（repeat/endall/pass）
- [ ] 2.3 单元测试：`overlay(...,true)` 体含 `overlay=x=...:y=...:shortest=1`；既有 `overlay(base,over,0,0)` 体 `overlay=x=0:y=0` 无 shortest

## 3. P1-4 底座 · 2 输入原始视频滤镜逃生舱

- [ ] 3.1 `Filters.rawFilterVideo(VideoStream base, VideoStream over, String rawFilter)`：`new FilterNode(rawFilter, List.of(), List.of(base, over), List.of(MediaType.VIDEO))`
- [ ] 3.2 单元测试：2 输入逃生舱编译后节点接 `[0:v:0][1:v:0]`、body 逐字；单输入版回归不变

## 4. P1-1 · transcode 视频滤镜链入口

- [ ] 4.1 `TranscodeOptions.videoFilter(Function<VideoStream,VideoStream>)`（wither 风格，可为 null）+ 访问器
- [ ] 4.2 `FacadeSupport.buildTranscode` 分支：videoFilter==null 保持现状（videoOptional+audioOptional）；videoFilter!=null → `videoFilter.apply(input.video())` + `audioOptional()`（仿 buildBurnSubtitles）
- [ ] 4.3 单元测试：挂 videoFilter 后视频经 filter_complex、音频 `-map` 带 `?`；未设时 argv 逐字节不变；lambda 内第二 Input overlay → 两路 `-i`、overlay 接 `[0:v:0][1:v:0]`

## 5. P1-2 · transcode 类型化码控与 extraOutputArgs

- [ ] 5.1 `TranscodeOptions` 增字段：`fps`（Double/Integer→`-r`）、`maxrate`（String）、`bufsize`（String）、`gop`（int 帧数）、`extraOutputArgs`（String...）；均 wither、可选
- [ ] 5.2 `buildTranscode` 渲染：类型化码控在前（`-r`/`-maxrate`/`-bufsize`/`-keyint_min N -g N -sc_threshold 0`），`extraOutputArgs` 在后
- [ ] 5.3 gop 语义：`gop(int)` 为关键帧间隔帧数，渲染 `-keyint_min N -g N -sc_threshold 0`（下游算 fps*gop 传入）
- [ ] 5.4 单元测试：`fps(25).maxrate("2M").bufsize("4M").gop(50)` → argv 精确含各段；libx265 + extraOutputArgs x265-params 顺序在后；默认 options argv 不含码控段（逐字节不变）
- [ ] 5.5 Javadoc：h264/h265 码控分工（h265 VBV 走 extraOutputArgs）、同键 ffmpeg 取后者、maxrate 建议配 bufsize、videoFilter 与 fps 滤镜两条路径不重叠

## 6. 端到端与收尾

- [ ] 6.1 集成测试（`assumeTrue(commandExists("ffmpeg"))`，`-f lavfi -i testsrc` 现场素材）：单张水印 overlay（Static 风格）转码产出合法 mp4；scale+padToEven+overlay(shortest) 组合链
- [ ] 6.2 更新 `USAGE.md`/`README.md`：type1 风格示例（videoFilter + 码控 + 水印经 overlay/rawFilterVideo 组合）；标注「7 水印预置属下游业务、core 提供底座」
- [ ] 6.3 更新 `CHANGELOG.md`（1.2.0 additive）
- [ ] 6.4 `mvn -q test` 全绿（无 ffmpeg 环境经 assumeTrue 跳过集成仍全绿）
- [ ] 6.5 `openspec validate add-transcode-filtergraph-capabilities --strict` 通过
