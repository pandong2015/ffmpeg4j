## Why

下游 `ocs-media-task` 的 **type1 转码**（7 种水印 overlay + `scale`/`pad` 补偶 + GOP + `maxrate`/`bufsize` + h264/h265 + aac）当前在 ffmpeg4j 无一等路径：`FacadeSupport.buildTranscode`（`FacadeSupport.java:86`）只挂 `-c:v`/`-crf`/`-preset`/`-b:v`/`-c:a`/`-b:a`，**无滤镜入口、无 fps/GOP/码控**；`Filters.pad` 的 `w`/`h` 是 `int`（`Filters.java:43`）不能表达 `pad=ceil(iw/2)*2`；`Filters.overlay` 仅 `x`/`y`（`Filters.java:50`）无 `shortest`（`-loop 1` 循环水印必须 `shortest=1` 收尾）；`rawFilterVideo` 仅单输入（`Filters.java:210`）。

这些是需求文档的 **P1 项**——「可用逃生舱实现，但收为一等项更安全/更省事」。本变更补齐使 type1 在库内可组合。**接续变更 `add-media-task-p0-capabilities`（P0）**。

> **调查结论（Workflow 对抗性核对 4 路，见 design）**：多输入水印在 L3 **今天就结构性可编译**——`Input.of(logo).withInputArgs("-loop","1").video()` + `Filters.overlay(base, logo, …)`，编译器 `discover` 自动补第二路 `-i`（`GraphCompiler.java:189-203`）；`Output.withArgs(…)` 已能表达任意 `-r`/`-maxrate`/`-g`。**故本变更不新增任何模型/编译器能力，仅在 `job-model` 补齐 4 类滤镜/门面缺口，把逃生舱路径「收为一等项」。**

## What Changes

- **pad 表达式重载与 padToEven（P1-3）**：新增 `Filters.pad(VideoStream, String w, String h, String x, String y, String color)`（表达式重载，走 `Arg.of` 逐字下发）与 curated `Filters.padToEven(VideoStream)`（固定 `pad=ceil(iw/2)*2:ceil(ih/2)*2`，使调用方无需手写表达式）。保留既有 `int` 重载不变。
- **overlay 的 shortest（P1-4）**：新增 `Filters.overlay(base, over, String x, String y, boolean shortest)`（MAY 含 `eof_action`），供 `-loop 1` 循环水印以 `shortest=1` 收尾。保留既有 x/y 重载不变。
- **2 输入原始视频滤镜逃生舱（P1-4 底座）**：新增 `Filters.rawFilterVideo(VideoStream base, VideoStream over, String rawFilter)`，逐字下发滤镜体、接两路视频输入。与既有单输入版对称，解锁复杂多输入 overlay 表达式（转义自负）。
- **transcode 滤镜链入口（P1-1）**：`TranscodeOptions.videoFilter(Function<VideoStream,VideoStream>)`。设置时视频走**必选**映射（起点 `input.video()`）、音频仍 `audioOptional()`；未设置时行为**逐字节不变**（双可选）。仿既有 `buildBurnSubtitles` 映射形态。
- **transcode 类型化码控与 extraOutputArgs（P1-2）**：`TranscodeOptions` 增 `fps`（→`-r`）、`maxrate`、`bufsize`、`gop`（帧数，→`-keyint_min`/`-g`/`-sc_threshold 0`），与 `extraOutputArgs(String...)` 逃生舱。类型化字段渲染在前、逃生舱在后。

## Capabilities

### Modified Capabilities

- `job-model`: 在「类型化 curated 滤镜集」增补 pad 表达式重载/padToEven、overlay shortest；在「逃生舱——原始滤镜与原始参数」增补 2 输入 `rawFilterVideo`；在「L4 高层门面」经 `TranscodeOptions` 增补 videoFilter、类型化码控、extraOutputArgs。

### Unaffected Capabilities

- `command-compiler`：**无需改动**。多输入 `discover`/自动 `-i` 编号、扇入 pad 命名、`Arg.of` 逐字渲染均已就位；表达式经 `Arg.of`（`escape=false`）安全进 argv。
- `execution-engine`/`media-probe`：不涉及。

## Impact

- **纯 additive**：全为新增重载、新增 Options 字段/方法；既有 `int` pad、x/y overlay、单输入 rawFilter、无 videoFilter 的 transcode argv **逐字节不变**。语义版本 **1.2.0**（minor）。
- **行为回退可接受**：仅当**主动**设置 `videoFilter` 时视频变必选映射（滤镜一旦介入，缺视频轨会 `matches no streams` 硬失败，而非静默跳过）——这是「宣告本次转码确有视频」的自洽语义；常规转码不受影响。
- **逃生舱纪律**：`rawFilterVideo(base, over, raw)`、`extraOutputArgs`、`withInputArgs` 内容不参与类型校验与转义；表达式内逗号须由调用方预转义（`\,`）。凡值源自外部 `parameter.*`，边界校验仍是调用方（executor）责任（承接变更 A 的 D5）。
- **非目标（见 design D6）**：7 种 watermarkType 的具体 overlay 表达式是**下游业务规则**，不作为 ffmpeg4j curated——core 只提供通用底座（overlay shortest / 2 输入逃生舱 / `-loop` 输入 / pad 表达式），下游据此组合全部 7 种。
