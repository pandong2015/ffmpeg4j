## 1. 项目脚手架

- [x] 1.1 建立 Maven 项目，单模块 `ffmpeg4j-core`（groupId/artifactId、Java 版本、无重型依赖）
- [x] 1.2 选定并接入轻量 JSON 解析（极轻第三方依赖 minimal-json/org.json 或自研微型解析器；JDK 无受支持 JSON API），确认 core 不引入 Jackson
- [x] 1.3 配置测试框架（JUnit）与 CI 骨架
- [x] 1.4 在 README/常量中声明最低支持的 ffmpeg 版本 = **4.2**（支持/测试下限；真实特性 floor ~2.3，低于 4.2 仅警告不硬失败），并探测 `--enable-libass`/`--enable-libfreetype` 构建开关

## 2. L0 环境层

- [x] 2.1 实现 `ffmpeg`/`ffprobe` 发现：PATH 查找 + 显式路径配置
- [x] 2.2 实现版本探测：二进制缺失/不可用→硬错拒绝继续；已发现但版本 < 4.2→仅记警告并继续（MUST NOT 因版本号硬失败）
- [x] 2.3 实现构建开关探测（`ffmpeg -filters` 匹配 ` subtitles `/` ass ` + `-version` 配置行核 `--enable-libass`/`--enable-libfreetype`）；缺失开关而调用依赖它的滤镜/门面时提前可诊断报错
- [x] 2.4 单元测试：二进制缺失（硬错）、版本过低（仅警告继续）、缺 libass 调 burnSubtitles 提前报错三条路径

## 3. L2 图数据结构与编译器

- [x] 3.1 定义内部 `FilterGraph` 数据结构（节点=滤镜，边=带类型 pad）
- [x] 3.2 从 `Stream` 引用图构建 DAG，记录每条 pad 的消费边
- [x] 3.3 引用计数扇出侦测 + 自动插入 `split`/`asplit` 并重连
- [x] 3.4 共享子链去重
- [x] 3.5 拓扑排序 + 分配内部 pad 名
- [x] 3.6 编译期校验：悬空 pad、媒体类型不匹配，抛描述性错误
- [x] 3.7 生成 argv：`-i` 输入、`-filter_complex`、`-map`、codec/输出参数
- [x] 3.8 汇聚归一化：音频 `aresample`/`aformat` 编译器内部推导共同目标；视频 `scale`/`setsar`/`fps`/`format` 按门面/调用方给定目标接线（MUST 含 setsar）；流集合异构（缺音/视轨段）注入 anullsrc/占位或可诊断拒绝
- [x] 3.9 filtergraph 转义器：drawText 文本与字幕/字体路径（冒号/百分号/引号/反斜杠、Windows 盘符冒号），或 `textfile=` 旁路；含空格/中文/盘符冒号路径的回归测试
- [x] 3.10 去重按引用标识（不合并结构相等的独立链）；SUBTITLE 流进 filtergraph 且扇出→编译期报错
- [x] 3.11 单元测试：直链、二次扇出、菱形图、多输出去重、汇聚归一化（含 setsar）、转义、非法图——断言产出的 argv

## 4. L3 编排模型

- [x] 4.1 定义不可变 `Stream`（携带 `mediaType: VIDEO|AUDIO|SUBTITLE`）与 `Input`/`Output`
- [x] 4.2 滤镜为纯函数 `Stream → Stream` 的骨架，接入 L2 图
- [x] 4.3 实现前 15 个 curated 滤镜（第 16 个字幕烧录族见 4.4）——视频 9（scale/crop/pad/overlay/trim/fps/format/fade/drawText）、音频 5（volume/amix/atrim/atempo/afade）、双型 concat；命名统一 Java 驼峰（drawText→ffmpeg drawtext）；trim/atrim 自动补 setpts/asetpts，atempo >2.0 自动拆链（单实例范围 [0.5,100]）
- [x] 4.4 `burnSubtitles(File)`（subtitles=）与 `burnAss(File)`（ass=，无 force_style）：字幕源建模为文件参数而非 pad
- [x] 4.5 软字幕流操作：mux/透传/抽取、srt↔vtt↔ass 转换（`-map` + `-c:s`）
- [x] 4.6 逃生舱 `rawFilter(String)` 与 `rawArg(...)`（位置感知：Input.withInputArgs / Output.withArgs）
- [x] 4.7 单元测试：不可变性、类型校验拒绝、扇出值语义、字幕路径

## 5. L1 执行引擎

- [x] 5.1 IO 拓扑推导（stdin/stdout 是否走管道）与运行时配置联动
- [x] 5.2 进程启动 + 每路专职 pump 线程（stderr 恒有；stdout；stdin）防死锁；喂 stdin 拓扑喂完后 close 触发 EOF（防反向死锁），取消/进程退出态下静默喂入侧 broken-pipe
- [x] 5.3 进度采集：`-progress pipe:1` 与 `-progress tcp://127.0.0.1:<port>` 自适应；tcp 模式 ServerSocket bind-before-spawn，accept 设 setSoTimeout、进程退出即 close（防 ffmpeg 启动即失败时 accept 永挂/泄漏）
- [x] 5.4 解析 `key=value` 进度块并触发回调；`.callbackExecutor(exec)` 逃生舱
- [x] 5.5 取消阶梯：优雅写 `q` → SIGTERM → SIGKILL；stdin 占用时降级；`.cancel(FORCE)`
- [x] 5.6 超时复用取消阶梯
- [x] 5.7 stderr 环形缓冲留尾 + `FfmpegException`（exitCode/command/stderrTail/reason）
- [x] 5.8 `run()` 与 `runAsync()` 双 API
- [x] 5.9 已知错误模式库（最具体优先，通用 errno 末尾兜底）：file-not-found / invalid-data / encoder-unavailable(`Unknown encoder`+`Automatic encoder selection failed`) / encoder-open-failed / odd-dimensions(`(width|height) not divisible by 2`) / codec-container-incompatible / no-matching-stream / output-format-unknown(两式含 `is not known`) / permission-denied / decoder-unavailable / disk-full / unknown-filter(`No such filter`) / filtergraph-unconnected-pad / filter-init / generic-failure(`Conversion failed!`)；兼容新老 errno 措辞（`file: <errno>` 与 5.x `Error opening input:`）；最具体优先（通用 errno 末尾兜底）
- [x] 5.10 内部管道故障（progress-plumbing tcp `Connection refused`/端口绑定失败）归内部错误类别，不作媒体类 `FfmpegException` 外泄
- [x] 5.11 集成测试：大量 stderr 不挂起、优雅取消不损坏输出、FORCE 跳过收尾、pipe 输入降级+喂完 EOF、tcp 进度 ffmpeg 启动即失败不挂起、超时

## 6. media-probe

- [x] 6.1 调用 `ffprobe -print_format json` 读取容器/流元数据
- [x] 6.2 映射为结构化结果（时长/码率/流类型/编解码器/分辨率/帧率/采样率/声道）
- [x] 6.3 失败路径（文件不存在/非法媒体）抛可诊断错误
- [x] 6.4 单元/集成测试：音视频文件、含字幕文件、不存在文件

## 7. L4 门面

- [x] 7.1 首批 8 门面：transcode(强制)/remux/clip/extractAudio/thumbnail/concat/burnSubtitles/probe；每个门面便捷位置重载 + 可选 `XxxOptions` 进阶重载（probe 豁免 Options：`ProbeResult probe(File)`）
- [x] 7.2 实现门面，内部委托 L3 模型 + media-probe + L1 引擎
- [x] 7.3 门面正确性约束：remux 处理容器不兼容字幕（文本转 mov_text / 图形字幕丢弃）、clip 用 `-ss start -t (end-start)` 无歧义截取并区分快切/精切、concat 门面前置归一化（含 setsar）+ 流集合异构注入 anullsrc/占位或可诊断拒绝、extractAudio 按扩展名判断是否需重编码（用 `-map 0:a` 选流避开封面图）
- [x] 7.4 端到端测试：一行式转码、remux 换容器、clip 截段、抽音频、抓帧、拼接、烧字幕、probe

## 8. 收尾

- [x] 8.1 README 与用法示例（含 overlay/字幕/probe/取消）
- [x] 8.2 文档化逃生舱与「pipe 输入无法优雅取消」等约束
- [x] 8.3 覆盖率核对，补齐关键路径测试
- [x] 8.4 发布准备（Maven 坐标、版本、变更说明）
