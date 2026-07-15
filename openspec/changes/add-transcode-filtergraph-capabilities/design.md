## Context

变更 A（P0）解锁 type3/5/7/4；本变更（P1）解锁 type1 转码。核对（Workflow 4 路对抗性调查 + 直接读源码）确证：**type1 的多输入水印图 + 复杂 overlay 表达式 + 码控/GOP 在 L3 今天就结构性可编译**，缺的只是 `job-model` 层 4 类滤镜/门面便利入口。关键源码事实：

- `Input.withInputArgs("-loop","1")` 位置感知、可链式累积（`Input.java:51-55`）；多个 `Input` 的流喂一个 `Output`，编译器从 `Output.mapped` DFS `discover` 首见顺序编号 `-i`（`GraphCompiler.java:189-203`）。overlay 的 `inputs` 顺序 `[base,over]` 保证 `base=0:v:0`、`wm=1:v:0` 确定。
- `Output.withArgs(...)` 逐字追加输出 argv、置于输出文件前（`GraphCompiler.java:167-183`）——`-r/-maxrate/-bufsize/-g/-shortest` 天然可表达。
- 表达式值必须走 `Arg.of`（`escape=false`）：`GraphCompiler.java:236` 对 `escape=false` 逐字输出、`Arg.escaped` 会把 `:` 转 `\:` 破坏表达式。现有 `pad` 的 x/y 正是此惯例。
- `videoFilter` 与 optional 互斥：滤镜产出 `FilterOrigin`，丢失 optional 语义（`GraphCompiler.java:247-249` 对 FilterOrigin 返回 false）。

## 地面真相验证（真 ffmpeg 8.0.1，非推断）

全部设计断言已用真实 ffmpeg 实测确认：

- **padToEven keyed 形式**：`scale=w=100:h=-1,pad=w=ceil(iw/2)*2:h=ceil(ih/2)*2` 对 320x240 源 → 100x76，与 type1 positional `scale=100:-1,pad=ceil(iw/2)*2:ceil(ih/2)*2` **同尺寸**（scale=100:-1 得奇数 75，pad 补至 76）。
- **overlay shortest 必要性**：`-loop 1 -i logo` + `overlay=...:shortest=1` → 输出 2.02s（主视频长，收尾）；去掉 `shortest` + `-t 5` → 输出 5.0s（循环图不自停）。**证明 shortest 对循环水印必不可少**。
- **逗号转义命门**：`overlay=...:x=if(eq(mod(n,200),0),...)` 裸逗号 → `Error parsing a filter description`；预转义 `mod(n\,200)\,0)\,...` → 成功。**证明复杂表达式须调用方预转义、走 `rawFilterVideo` 逐字下发**（D3）。
- **完整 type1 链**：`scale+padToEven+overlay(shortest)+h264+-maxrate 2M -bufsize 4M+-keyint_min 50 -g 50 -sc_threshold 0` 两输入 → 退出 0、合法输出。
- **videoFilter + optional 音频**：`[0:v:0]scale[v]` + `-map [v] -map 0:a:0?`——有音轨源 → video+audio 双流；**无音轨源 → 仅 video、退出 0 不中止**（`0:a:0?` 优雅跳过）。**证明 D2**（videoFilter 视频必选、音频仍 optional）。
- **h265 VBV**：`-c:v libx265 -x265-params vbv-maxrate=2000:vbv-bufsize=4000` → hevc、退出 0。**证明 D5** 的 h265 码控经 extraOutputArgs 可行。

## Goals / Non-Goals

**Goals:**
- 让 type1（scale/pad 补偶、7 水印 overlay、GOP、h264/h265 码控、aac）在库内可组合，消除「临时留 core 手写 filter_complex」。
- 全程 additive、无 videoFilter 的转码 argv 逐字节不变。
- 通用底座优先于专用预置：core 提供 overlay shortest / 2 输入逃生舱 / pad 表达式，下游组合业务规则。

**Non-Goals:**
- 不把 7 种 watermarkType 的具体 overlay 表达式建模进 core（D6）。
- 不做 overlay 表达式逗号自动转义（D3）。
- 不把 `normalizeVideo` 从 scale 拉伸升级为 scale+pad letterbox（另一关注点，越界）。
- 不在 `buildTranscode` 内引入 probe（保持纯函数契约）。

## Decisions

### D1: `videoFilter` 用单参 `Function<VideoStream,VideoStream>`，足以表达多输入水印
**修正先前假设**：曾以为单参 Fn 无法表达「需要第二输入的水印 overlay」。Workflow reader 4 对抗性核对证伪：编译器 `discover` 从 `Output.mapped` DFS，**自动发现 lambda 闭包内新建的第二个 `Input` 并补 `-i`**（concat 多输入、overlay 双输入已佐证）。故用户写 `v -> Filters.overlay(v, Input.of(logo).withInputArgs("-loop","1").video(), "W-w-6", "H-h-6", true)` 即可编译出正确的 `[0:v:0][1:v:0]overlay=...`。

**取舍**：单参 Fn **结构上足够**，张力仅在人体工学（是否额外给 `watermark(File,x,y)` 便捷方法）。本变更**不**引入更宽签名（BiFunction / builder / 专用 watermark 门面）；高级多输入场景由调用方在 lambda 内自建 `Input` 并自负正确性。专用水印便捷方法留待下游或后续按需（见 D6）。

### D2: `videoFilter` 存在时视频必选、音频仍 optional，仿 `buildBurnSubtitles`
滤镜输入 pad 无法消费「可能缺失」的输入，故起点须 `input.video()`（必选 `0:v:0`）而非 `videoOptional()`。`buildTranscode` 分支：`videoFilter==null` 保持现状（`videoOptional()`+`audioOptional()` 双可选，argv 逐字节不变）；`videoFilter!=null` 则 `videoFilter.apply(input.video())` + `audioOptional()`——与 `buildBurnSubtitles`（`FacadeSupport.java:352`）映射形态完全一致，是现成先例。

对纯音频输入误挂 `videoFilter` = 使用者错误，交由 ffmpeg 报 `matches no streams`；**不**在 `buildTranscode` 引入 probe（保持零 probe 纯函数、可脱进程断言 argv）。

### D3: 表达式值走 `Arg.of` 逐字下发；逗号预转义责任在调用方
pad/overlay 的表达式（含 `iw`/`ih`/`ceil()`/`if`/`mod`/括号）MUST 走 `Arg.of`（`escape=false`），逐字进 filtergraph；MUST NOT 走 `Arg.escaped`（会破坏表达式）。filtergraph 中逗号分隔滤镜，故表达式内逗号（如 `mod(n,200)`）须预转义为 `\,`——本变更**不**做自动转义（脆弱：难与 `if(a,b)` 的结构性逗号区分），由调用方在传入前预转义，作为逃生舱纪律写入 spec/Javadoc。

**附带 fail-fast（可选）**：pad 的 `String` 重载可对 `x`/`y`/`w`/`h` 值加一道「不得含裸 `:`」的边界校验，防畸形表达式被 `:` 静默错拼——列入 tasks 备选。

### D4: pad 提供 String 重载 + curated `padToEven` 双路，保留 int 重载
`padToEven(in)` 是「用户永不手写表达式」的 curated 首选（契合项目约束），**渲染为裸 `pad=w=ceil(iw/2)*2:h=ceil(ih/2)*2`**（仅 w/h，x/y 默认 0、color 默认 black，与 type1 line 98 的 `pad=ceil(iw/2)*2:ceil(ih/2)*2` 等价——非居中版；实测 keyed 与 positional 同产 100x76）；故 `padToEven` 是仅设 w/h 的独立节点，**不**经 6 参 `pad(in,w,h,x,y,color)`。`pad(in, String w, String h, ...)` 是更灵活的表达式逃生舱。二者均新增**重载**（`int` 重载 w/h 无法复用 `String`，须并存以免破坏既有调用点）。

### D5: h264/h265 码控分叉——类型化字段渲染 h264 惯用形，h265 VBV 走 extraOutputArgs
h264 用 `-maxrate {v}K -bufsize {v}K`；h265 折叠进 `-x265-params vbv-maxrate={v}:vbv-bufsize={v}`（无 K）。**取舍**：`TranscodeOptions.maxrate/bufsize` 渲染为 h264 惯用的 `-maxrate`/`-bufsize`；libx265 的 VBV 经 `extraOutputArgs("-x265-params","vbv-maxrate=...")` 表达。**不自动翻译**——自动把 maxrate→vbv-maxrate 会把 TranscodeOptions 耦合到编解码器字符串解析，脆弱且反直觉。文档注明此分工。

### D6: 7 种 watermarkType 是下游业务规则，不进 core curated
7 种类型的输入拼装（几张图、是否 `-loop 1`）与 overlay 表达式（`if/mod/sin/random/pow` 时间函数）是 **ocs-media 的固定业务常量**，非通用 ffmpeg 能力。把它们建模进 ffmpeg4j-core 会用一个下游的业务规则污染通用库。**边界**：core 提供通用底座（overlay shortest、2 输入 `rawFilterVideo`、`-loop` 输入、pad 表达式），**足以让下游组合全部 7 种**；watermark 预置枚举/便捷方法（若需要）活在下游 executor 或未来独立模块。需求文档亦把「curated 水印助手」标为**可选**。

### D7: type1 单流 scale/pad 不强制 setsar
项目「MUST 含 setsar」的约束**scope 是汇聚滤镜（concat/amix）前的归一化**——多段 SAR 不一致才需统一。type1 是**单输入** scale/pad，无汇聚，`setsar` 非必需；对齐 ocs-media 原 vf（scale+pad 无 setsar）。若下游确需，可自行 `rawFilterVideo` 追加，不由本变更强加。

### D8: gop 以「帧数」入参，下游算 fps×gop
buildable-spec 的 `-g = fps*gop`（gop 为秒倍率）是下游业务算法。为保持 core 通用且 `buildTranscode` 纯函数可断言，`TranscodeOptions.gop(int)` 取**关键帧间隔帧数**，直接渲染 `-keyint_min N -g N -sc_threshold 0`；下游 type1 自行计算 `fps*gop` 后传入。避免 core 依赖 fps 才能派生 gop 的隐式耦合。

## Risks / Trade-offs

- **表达式转义踩坑**：调用方漏转义逗号会产出非法 filtergraph（编译期 ffmpeg 报错）。缓解：Javadoc 显式示例 + spec 逃生舱纪律；`padToEven`/curated 路径完全规避手写表达式。
- **maxrate/bufsize 键冲突**：若同时给类型化 `maxrate` 与 `extraOutputArgs("-maxrate",...)`，ffmpeg 取后者。缓解：Javadoc 注明顺序与「后者覆盖」，建议二选一。
- **videoFilter 误用于纯音频**：硬失败而非静默跳过。缓解：Javadoc 说明「设 videoFilter 即宣告有视频轨」；下游可自行先 probe。

## Migration / Rollout

- 无数据迁移。下游 type1 opt-in：`TranscodeOptions.defaults().videoCodec("libx264").fps(25).maxrate("2M").bufsize("4M").gop(50).videoFilter(v -> Filters.padToEven(Filters.scale(v, w, -1)))`；水印在 videoFilter 内经 `Input.withInputArgs("-loop","1")` + `overlay(..., shortest=true)` 或 `rawFilterVideo(base, wm, raw)` 组合。
- 测试遵循仓库约定：脱进程断言 argv（pad 表达式、overlay shortest、2 输入逃生舱 `[0:v:0][1:v:0]`、videoFilter 后视频必选/音频 `?`、码控与 GOP 派生、extraOutputArgs 顺序）；端到端用 `-f lavfi -i testsrc` + `assumeTrue(commandExists("ffmpeg"))` 守卫。
