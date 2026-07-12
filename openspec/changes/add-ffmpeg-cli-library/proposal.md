## Why

Java 生态缺少一个既「像 libav 一样能自由组合底层能力」、又不必承担原生绑定（JNI/FFI）复杂度与崩溃风险的 ffmpeg 库。现有 CLI 封装大多把滤镜图退化成手写字符串，用户仍要自己编排 `[0:v]`/`[out]` 这类 pad 名，谈不上「组合」。本变更提供一个基于**预装 ffmpeg 二进制**的通用视频处理库：用户以不可变的「流值」思考并自由组合，脏活（滤镜图编译、进程编排、错误/取消）全部封装在库内。

## What Changes

- 新增一个纯 Java（依赖预装 `ffmpeg`/`ffprobe` 二进制）的视频处理库，采用**路线 A（CLI 封装）**，不引入原生绑定。
- 提供 **L3「流即值」不可变编排模型**：`Input` / `Stream` / 滤镜 / `Output`，音频/视频/字幕三态对称；滤镜是「拿一个 `Stream` 返回新 `Stream`」的纯函数。
- 提供 **L2 图编译器**：把用户的流引用图编译成 `ffmpeg` 命令行——引用计数侦测扇出、**自动插入 `split`**、拓扑排序并自动分配 pad 名、去重、compile 期类型校验（音/视/字幕接错滤镜即报错）。
- 提供 **L1 执行引擎**：以「IO 拓扑」驱动——按 stdin/stdout 是否走管道，联动决定进度通道（`-progress pipe:1` 或 `tcp://` 自适应）与取消能力；每路输出必排空以防死锁；取消**默认优雅**（向 stdin 写 `q`，被输入占用时降级 SIGTERM），`.cancel(FORCE)` 跳过收尾；失败时保留 stderr 尾部并组装带原因的 `FfmpegException`；提供 `run()` / `runAsync()` 双 API。
- 提供 **ffprobe 元数据读取**（media-probe）。
- 提供 **16 个类型化 curated 滤镜**（视频 scale/crop/pad/overlay/trim/fps/format/fade/drawText，音频 volume/amix/atrim/atempo/afade，双型 concat，字幕烧录 burnSubtitles/burnAss）＋ 万能 `rawFilter()` / `rawArg()` 逃生舱，使「任意组合底层能力」从第一天成立；split/asplit/setpts/aresample/aformat 等归一化滤镜由编译器内部处理。
- 提供**软字幕流操作**（mux/透传/抽取、srt↔vtt↔ass 转换）与**硬字幕烧录**（`burnSubtitles(File)`，字幕源诚实建模为文件参数而非 pad）。
- 提供 **L4 门面首批 8 个**覆盖最常见整段任务：transcode/remux/clip/extractAudio/thumbnail/concat/burnSubtitles/probe（含烧字幕与结构化 probe 以展示本库差异化）。
- Maven 打包为 **`ffmpeg4j-core` 单模块**（probe 内置、JSON 用轻依赖，不背 Jackson）；帧进出 JVM 的逃生舱模块 `ffmpeg4j-frame` **明确不在 v1.0 范围**（架构预留接缝）。

## Capabilities

### New Capabilities
- `job-model`: L3 不可变「流即值」编排 API——输入/流/滤镜/输出、音视频字幕三态、扇出可复用（值语义）、curated 滤镜与 `rawFilter`/`rawArg` 逃生舱、L4 门面。
- `command-compiler`: L2 图编译语义——建 DAG、引用计数与自动 `split`、拓扑排序与 pad 命名、去重、compile 期类型/连接校验，产出 `ffmpeg` argv。
- `execution-engine`: L1 进程运行时——ffmpeg/ffprobe 发现、版本校验与构建开关探测（libass/libfreetype）、IO 拓扑推导、流排空防死锁、`-progress` 解析与回调、优雅/强制取消与超时、`FfmpegException` 错误组装（内部管道故障不外泄）、run/runAsync。
- `media-probe`: 基于 `ffprobe` 的媒体元数据读取（容器/流/时长/编解码器等），JSON 解析走轻依赖。

### Modified Capabilities
<!-- 无既有 spec，全部为新建 -->

## Impact

- 全新 greenfield 项目，无现存代码受影响。
- 新增外部运行时依赖：目标机器须预装 `ffmpeg` / `ffprobe`（推荐 ≥ 4.2；真实特性 floor ~2.3，低于门槛仅警告不硬失败，启动时 probe 版本与构建开关）。
- Maven 坐标与模块：v1.0 仅 `ffmpeg4j-core`；`ffmpeg4j-frame`、更多滤镜、硬件加速、HLS/DASH、字幕高级样式等为后续版本增量（加数据、不改架构）。
- 依赖 `java.desktop`（BufferedImage/ImageIO）的能力被隔离在未来的 `ffmpeg4j-frame`，`core` 保持纯 JDK、可在无头/裁剪 JRE 运行。
