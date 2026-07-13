# execution-engine Specification

## Purpose

L0 环境 + L1 执行：发现 `ffmpeg`/`ffprobe` 二进制并探测版本与构建开关（libass/libfreetype），据 IO 拓扑联动进度通道与取消能力，稳健地执行子进程。职责包括每一路流的专职排空防死锁、`-progress` 机器可读进度回调、阶梯式优雅取消与超时、结构化 `FfmpegException` 组装（退出码/命令/stderr 尾/可读原因），以及把内部管道故障与用户媒体错误区分开来。

## Requirements

### Requirement: 二进制发现与版本校验
执行引擎 MUST 能发现 `ffmpeg` 与 `ffprobe` 二进制（PATH 查找并允许显式配置路径），并 MUST 在使用前探测其版本。发现失败与版本过低是两种语义相反的情形，引擎 MUST 区别处理：二进制缺失/不可用 MUST 抛出可诊断错误并拒绝继续；已发现但版本低于声明的最低支持版本（4.2）时，引擎 MUST 记录可诊断警告并继续运行，MUST NOT 因版本号本身硬失败（真实特性 floor ~2.3）。

#### Scenario: 二进制缺失
- **WHEN** 目标机器 PATH 中不存在 `ffmpeg` 且未配置显式路径
- **THEN** 引擎抛出清晰错误说明未找到二进制，而非产生模糊的进程启动失败

#### Scenario: 版本过低仅警告不失败
- **WHEN** 发现的 `ffmpeg` 版本为 3.4（低于最低支持版本 4.2 但可运行）
- **THEN** 引擎记录一条指明版本过低的警告并继续执行，而非中止

### Requirement: 构建开关能力探测
执行引擎 MUST 在启动时探测 `ffmpeg` 的构建开关/滤镜可用性（`--enable-libass` 决定 `burnSubtitles`/`burnAss`（subtitles=/ass=），`--enable-libfreetype` 决定 `drawText`），这是比版本号更决定性的硬能力门槛。当所需构建开关缺失而用户请求依赖它的滤镜/门面时，引擎 MUST 在编译/启动阶段以指明缺失开关的可诊断错误失败，而非产出一条注定在 ffmpeg 运行时才失败的命令行。探测机制为实现细节。

#### Scenario: 缺 libass 调用烧字幕被提前拒绝
- **WHEN** ffmpeg 构建未含 `--enable-libass`，用户调用 `burnSubtitles`
- **THEN** 引擎抛出指明 libass 缺失的可诊断错误，而非放任 ffmpeg 运行时报模糊的 `No such filter`

#### Scenario: 具备 libfreetype 时 drawText 正常编译
- **WHEN** ffmpeg 构建含 `--enable-libfreetype`
- **THEN** `drawText` 正常编译通过

### Requirement: IO 拓扑驱动的运行时配置
引擎 MUST 依据本次任务 stdin/stdout 是否被媒体管道占用推导「IO 拓扑」，并据此联动决定进度通道与取消能力：stdout 空闲时进度走 `-progress pipe:1`，stdout 传媒体时进度走 `-progress tcp://127.0.0.1:<port>`（引擎监听本地随机端口）。tcp 进度模式下，引擎 MUST 于启动子进程前绑定 `ServerSocket`（bind-before-spawn 消除连接竞态），并 MUST 为 `accept` 设置超时、在进程退出（`waitFor` 返回）时主动 `close` 该 socket，以避免 ffmpeg 在建立进度连接前就退出时 accept 线程永久阻塞/泄漏；「进程已退出而进度连接从未建立」MUST 作为正常终止路径处理，不作错误。

#### Scenario: 写盘任务用 pipe 进度
- **WHEN** 一次转码任务输出到磁盘文件、stdin/stdout 均不走管道
- **THEN** 引擎使用 `-progress pipe:1` 采集进度

#### Scenario: 传帧任务用 tcp 进度
- **WHEN** 一次任务通过 stdout 输出媒体数据
- **THEN** 引擎改用 `-progress tcp://127.0.0.1:<port>` 并在本地监听该端口采集进度

#### Scenario: ffmpeg 启动即失败不致进度监听挂起
- **WHEN** tcp 进度模式下 ffmpeg 在建立 `-progress` 连接前就以错误退出
- **THEN** 引擎的进度监听不无限阻塞（`accept` 超时 + 进程退出后 `close`），按正常终止路径收口，不泄漏线程

### Requirement: 流排空防死锁
引擎 MUST 为 ffmpeg 写出的每一路（stderr 恒有；stdout 视情况；stdin 视情况）配备专职消费者持续排空，以避免管道缓冲区写满导致子进程阻塞、进而 `waitFor` 永久挂起。对称地，喂 stdin 的拓扑下引擎 MUST 在输入耗尽后 `close` 子进程 stdin 以发出 EOF（否则 ffmpeg 会持续等待输入、`waitFor` 永久挂起——与死锁同源、方向相反的陷阱）；当任务已进入取消或进程已退出而喂入侧仍在写时，引擎 MUST 静默 broken-pipe/`IOException`（区别于真正的读源错误），不将其误报为任务失败。

#### Scenario: 大量 stderr 日志不致挂起
- **WHEN** ffmpeg 输出远超管道缓冲区容量的 stderr 日志
- **THEN** 引擎持续排空 stderr，任务正常推进直至进程退出

#### Scenario: 喂完输入后关闭 stdin 触发 EOF
- **WHEN** 一次经 stdin 喂定长输入的任务把输入喂完
- **THEN** 引擎关闭子进程 stdin 发出 EOF，ffmpeg 正常收尾，`waitFor` 不挂起

### Requirement: 机器可读进度回调
引擎 MUST 解析 `-progress` 输出的 `key=value` 块（而非解析 stderr 的人类可读进度行），并将进度以回调形式暴露。回调默认 MAY 在进度 pump 线程触发，且引擎 SHALL 提供将回调派发到用户指定 `Executor` 的选项。

#### Scenario: 进度回调被触发
- **WHEN** ffmpeg 在转码过程中输出进度块
- **THEN** 引擎解析并触发进度回调，携带如已处理时间/帧数等字段

#### Scenario: 回调派发到用户 Executor
- **WHEN** 用户配置 `.callbackExecutor(exec)` 后任务产生进度
- **THEN** 进度回调在指定 `Executor` 而非进度 pump 线程上派发

### Requirement: 优雅取消与降级
取消默认 MUST 优雅进行：向 stdin 写 `q` 使 ffmpeg 收尾并 finalize 输出，等待后若仍存活再依次 `destroy()`(SIGTERM) 与 `destroyForcibly()`(SIGKILL)。当 stdin 已被输入媒体占用而无法写入 `q` 时，引擎 MUST 自动降级为 SIGTERM。引擎 SHALL 提供 `FORCE` 模式跳过优雅收尾。

#### Scenario: 默认优雅取消
- **WHEN** 用户取消一个 stdin 空闲的转码任务
- **THEN** 引擎向 stdin 写 `q`，ffmpeg 完成收尾，输出文件不被损坏

#### Scenario: pipe 输入下降级
- **WHEN** 用户取消一个正通过 stdin 喂输入的任务
- **THEN** 引擎因无法写入 `q` 而降级发送 SIGTERM，并记录该降级

#### Scenario: FORCE 取消跳过优雅收尾
- **WHEN** 用户以 `FORCE` 取消任务
- **THEN** 引擎跳过写 `q` 与优雅收尾，直接进入 `destroy()`/`destroyForcibly()`，输出可能未 finalize

### Requirement: 超时复用取消阶梯
引擎 SHALL 支持为任务设置超时；超时到达时 MUST 复用取消阶梯（先尝试优雅收尾，再逐级升级）。

#### Scenario: 任务超时被终止
- **WHEN** 任务运行超过配置的超时时间
- **THEN** 引擎按取消阶梯终止该进程

### Requirement: 结构化错误组装
当进程以非零码退出时，引擎 MUST 抛出 `FfmpegException`，其中 MUST 包含退出码、执行的命令、stderr 尾部（引擎以环形缓冲保留约最后 50 行），并 SHOULD 附一段由已知错误模式解析得到的可读原因。已知模式库 SHALL 按「最具体优先」求值（具体模式在前，通用 errno 作末尾兜底），并 SHALL 覆盖滤镜相关失败（如 `No such filter`、未连接的 filtergraph pad）。

#### Scenario: 未知编码器错误可诊断
- **WHEN** ffmpeg 因未知编码器以非零码退出
- **THEN** 抛出的 `FfmpegException` 携带退出码、命令与 stderr 尾部，并尽力给出可读原因

#### Scenario: 未知滤镜错误可诊断
- **WHEN** 编译产物含一个 ffmpeg 不识别的滤镜名，进程以 `No such filter: 'xyz'` 退出
- **THEN** `FfmpegException` 的可读原因指出是未知或未编译进的滤镜，而非泛泛的失败

#### Scenario: 最具体模式优先于通用兜底
- **WHEN** stderr 同时含具体的 `No such filter` 与末尾通用的 `Conversion failed!`
- **THEN** reason 解析为 unknown-filter 而非 generic-failure

### Requirement: 内部管道故障不外泄为媒体错误
引擎自身的内部管道故障（如 `-progress` TCP 通道 `Connection refused`、进度端口绑定失败）MUST 归为内部错误类别（记录/内部重试），MUST NOT 作为媒体类 `FfmpegException` 抛给调用方——它是库内部管道问题，非用户的媒体错误。

#### Scenario: 进度端口连接被拒不外泄
- **WHEN** `-progress` TCP 通道因引擎自身端口故障 `Connection refused`
- **THEN** 引擎将其归为内部错误（记录/重试），不向调用方抛出媒体类 `FfmpegException`

### Requirement: 同步与异步执行 API
引擎 MUST 同时提供阻塞式 `run()` 与返回 `Future` 的 `runAsync()`。

#### Scenario: 异步执行返回 Future
- **WHEN** 用户调用 `runAsync()`
- **THEN** 任务在后台执行并返回一个可用于等待结果或取消的句柄
