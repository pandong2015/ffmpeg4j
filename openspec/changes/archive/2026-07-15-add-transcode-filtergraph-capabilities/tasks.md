## 1. P1-3 · pad 表达式重载与 padToEven

- [x] 1.1 `Filters.pad(VideoStream, String w, String h, String x, String y, String color)` 表达式重载：w/h/x/y 走 `Arg.of`（不转义），保留既有 int 重载
- [x] 1.2 curated `Filters.padToEven(VideoStream)`：固定渲染裸 `pad=w=ceil(iw/2)*2:h=ceil(ih/2)*2`（仅 w/h，x/y/color 取默认，对齐 type1；不经 6 参 pad），全用 `Arg.of`
- [ ] 1.3（备选，未做）String 重载对 w/h/x/y 加 fail-fast：值不得含裸 `:`——暂不做，避免误伤合法表达式；逃生舱纪律已在 spec/Javadoc 说明
- [x] 1.4 单元测试（断言 argv）：padToEven 体为 `pad=w=ceil(iw/2)*2:h=ceil(ih/2)*2`、pad 表达式重载逐字未转义、int 重载不变

## 2. P1-4 · overlay shortest

- [x] 2.1 `Filters.overlay(base, over, String x, String y, boolean shortest)`：shortest=true 时追加 `Arg.of("shortest","1")`；保留既有 String/int x-y 重载
- [ ] 2.2（可选，未做）`eof_action` 支持——当前 shortest 已覆盖循环水印收尾需求，eof_action 待按需再加
- [x] 2.3 单元测试：`overlay(...,true)` 体含 `overlay=x=...:y=...:shortest=1`；`overlay(...,false)` 无 shortest；既有 int 重载不变

## 3. P1-4 底座 · 2 输入原始视频滤镜逃生舱

- [x] 3.1 `Filters.rawFilterVideo(VideoStream base, VideoStream over, String rawFilter)`：接两路视频输入、body 逐字
- [x] 3.2 单元测试：2 输入逃生舱 body 逐字、接两路输入、输出 VIDEO；单输入版回归不变

## 4. P1-1 · transcode 视频滤镜链入口

- [x] 4.1 `TranscodeOptions.videoFilter(Function<VideoStream,VideoStream>)`（wither，可为 null）+ 访问器
- [x] 4.2 `FacadeSupport.buildTranscode` 分支：videoFilter==null 保持现状（videoOptional+audioOptional）；videoFilter!=null → `videoFilter.apply(input.video())` + `audioOptional()`（仿 buildBurnSubtitles）
- [x] 4.3 单元测试：挂 videoFilter 后视频经 filter_complex、音频 `-map` 带 `?`、视频非 `0:v:0?`；未设时无 filter_complex 且双可选；lambda 内第二 Input overlay → 两路 `-i`、overlay 接 `[0:v:0][1:v:0]`、含 shortest=1、`-loop 1`

## 5. P1-2 · transcode 类型化码控与 extraOutputArgs

- [x] 5.1 `TranscodeOptions` 增字段：`fps`(→`-r`)、`maxrate`/`bufsize`(String)、`gop`(int 帧数)、`extraOutputArgs`(String...)；均 wither、可选；fps/gop 正数校验
- [x] 5.2 `buildTranscode` 渲染：类型化码控在前（`-r`/`-maxrate`/`-bufsize`/`-keyint_min N -g N -sc_threshold 0`），`extraOutputArgs` 在后
- [x] 5.3 gop 语义：`gop(int)` 为关键帧间隔帧数，渲染 `-keyint_min N -g N -sc_threshold 0`（下游算 fps*秒 传入）
- [x] 5.4 单元测试：`fps(25).maxrate("2M").bufsize("4M").gop(50)` → argv 精确含各段；libx265 + extraOutputArgs x265-params；默认不含码控段
- [x] 5.5 Javadoc：h264/h265 码控分工（h265 VBV 走 extraOutputArgs）、同键取后者、videoFilter 与 optional 映射交互、gop 为帧数

## 6. 端到端与收尾

- [x] 6.1 集成测试（`assumeTrue`，`-f lavfi` 现场素材 + 生成水印图）：scale+padToEven+overlay(shortest 水印)+h264+码控+GOP 产出合法 mp4
- [x] 6.2 更新 `USAGE.md`/`README.md`：type1 风格示例（videoFilter + 码控 + 水印经 overlay/rawFilterVideo）；标注「7 水印预置属下游业务、core 提供底座」；滤镜 18→19
- [x] 6.3 更新 `CHANGELOG.md`（面向 1.2.0 additive）
- [x] 6.4 `mvn -o test` 全 reactor 全绿（367 core 测试 + spring 模块；无 ffmpeg 环境经 assumeTrue 跳过集成仍全绿）
- [x] 6.5 `openspec validate add-transcode-filtergraph-capabilities --strict` 通过
