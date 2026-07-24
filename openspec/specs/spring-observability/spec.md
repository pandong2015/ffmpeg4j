# spring-observability Specification

## Purpose

在 Spring Boot 应用中把 ffmpeg 工具链的健康、元数据与运行指标暴露为标准可观测面：Actuator `HealthIndicator`（复用 `FfmpegEnvironment.detect()` 判定二进制/版本/构建开关）、`InfoContributor`（贡献版本与构建开关到 `/actuator/info`）、Micrometer 门面计时与按 `ErrorPattern` 分类的失败计数、以及运行中子进程数 `Gauge`。全部组件按 classpath 条件装配，可选依赖以 `optional=true` 声明，缺失时静默跳过不报错。

## Requirements

### Requirement: Actuator 健康指示器
autoconfigure MUST 提供一个 Spring Boot Actuator `HealthIndicator`，把「ffmpeg 工具链是否可用」暴露为可观测健康项。其健康判定 MUST 复用 core 的 `FfmpegEnvironment.detect()` 语义，全部条件满足才报 `UP`：`ffmpeg`/`ffprobe` 二进制均可发现、探测到的版本达标（低于最低支持版本 4.2 时沿用 core「仅告警不硬失败」语义，MUST NOT 因版本号本身判 `DOWN`）、且 `libass` 与 `libfreetype` 两个构建开关均探测到存在。任一二进制缺失/不可用，或 `libass`/`libfreetype` 任一构建开关缺失时，MUST 报 `DOWN`（把构建开关缺失视为工具链不完整），并在 health `details` 中携带可诊断信息（缺失的二进制名或构建开关名、探测到的版本、失败原因），MUST NOT 只给一个不透明的 `DOWN`。健康探测 MUST 是只读的，MUST NOT 触发任何转码/写盘副作用。

#### Scenario: 工具链就绪报 UP
- **WHEN** `ffmpeg`/`ffprobe` 均可发现、版本达标，且 `libass` 与 `libfreetype` 均探测到存在
- **THEN** `HealthIndicator` 报 `UP`，`details` 携带 ffmpeg 版本与已探测到的构建开关

#### Scenario: 二进制缺失报 DOWN 带诊断
- **WHEN** 目标机器缺失 `ffprobe` 二进制且未配置显式路径
- **THEN** `HealthIndicator` 报 `DOWN`，`details` 指明是哪个二进制缺失，而非仅给一个不透明的 `DOWN`

#### Scenario: 缺构建开关报 DOWN
- **WHEN** `ffmpeg`/`ffprobe` 二进制均可用，但 `libass` 构建开关缺失
- **THEN** `HealthIndicator` 报 `DOWN`，`details` 指明缺失的构建开关（libass）并提示 `burnSubtitles`/`burnAss` 不可用，而非仅二进制缺失才判 `DOWN`

#### Scenario: 版本过低不判 DOWN
- **WHEN** 发现的 `ffmpeg` 版本为 3.4（低于最低支持版本 4.2 但可运行）
- **THEN** `HealthIndicator` 仍报 `UP` 并在 `details` 标注版本偏低告警，MUST NOT 因版本号判 `DOWN`

### Requirement: Actuator 信息贡献者
autoconfigure MUST 提供一个 Spring Boot Actuator `InfoContributor`，把 ffmpeg 工具链的静态元数据贡献到 `/actuator/info` 端点。贡献内容 MUST 至少包含探测到的 `ffmpeg` 版本，以及决定门面可用性的关键构建开关状态（`libass` → `burnSubtitles`/`burnAss`，`libfreetype` → `drawText`）。元数据 MUST 取自 core 的环境探测结果而非硬编码，MUST NOT 在贡献信息时执行会失败的媒体操作。

#### Scenario: info 端点暴露版本与构建开关
- **WHEN** 应用启动且 ffmpeg 工具链可用，用户访问 `/actuator/info`
- **THEN** 响应携带 ffmpeg 版本与 `libass`/`libfreetype` 开关状态，取自 core 环境探测结果

### Requirement: Micrometer 指标
autoconfigure MUST 在 Micrometer `MeterRegistry` 存在时，为 `FfmpegClient` 门面执行埋点：每次门面调用（transcode/remux/clip/extractAudio/thumbnail/concat/burnSubtitles/probe）MUST 以 `Timer` 计量其耗时，且 SHALL 以 tag 区分门面操作类型与成功/失败结果。门面执行失败时，MUST 以 `Counter`（或计时器的失败维度）按 core 结构化错误的 `ErrorPattern` 类别（如 unknown-filter、unknown-encoder、generic-failure 等）分桶计数，使失败可按原因归因，MUST NOT 把所有失败混计为单一无区分计数。指标注册 MUST 通过依赖注入的 `MeterRegistry` 完成，MUST NOT 自建全局静态 registry。

#### Scenario: 门面执行被计时
- **WHEN** 用户经 `FfmpegClient` 完成一次 `transcode` 且 classpath 存在 Micrometer
- **THEN** 对应 `Timer` 记录一次执行耗时，tag 标注操作类型为 transcode、结果为成功

#### Scenario: 失败按 ErrorPattern 分类计数
- **WHEN** 一次门面调用因 `No such filter` 抛出 `FfmpegException`
- **THEN** 失败计数在 `ErrorPattern` 类别 unknown-filter 维度上递增，而非混入一个无区分的通用失败计数

### Requirement: 运行中子进程数 Gauge
autoconfigure MUST 在 Micrometer `MeterRegistry` 存在时注册一个 `Gauge`，实时反映当前**运行中的 ffmpeg 子进程数**（已启动尚未终止的门面/引擎任务数）。该 gauge MUST 由执行引擎/门面维护的活跃计数驱动（任务启动自增、结束/取消/失败自减），MUST NOT 依赖轮询外部进程表。活跃计数 MUST 在成功、失败、取消三种收口路径下都正确回落，MUST NOT 因异常路径而泄漏虚高。gauge 注册 MUST 通过注入的 `MeterRegistry` 完成，MUST NOT 自建全局静态 registry。

#### Scenario: 并发任务反映在 gauge
- **WHEN** 两个门面任务并发运行且 classpath 存在 Micrometer
- **THEN** 该 gauge 读数为 2，两者分别结束后回落至 0

#### Scenario: 失败任务不泄漏计数
- **WHEN** 一个门面任务因 `FfmpegException` 失败结束
- **THEN** 活跃计数在其失败收口时自减，gauge 不因失败路径而虚高

### Requirement: 观测组件条件装配
上述 `HealthIndicator`、`InfoContributor` 与 Micrometer 指标 MUST 全部为条件装配：仅当对应能力所需的类存在于 classpath 时才装配——Actuator 组件依赖 `spring-boot-actuator`，Micrometer 指标依赖 `micrometer-core` 的 `MeterRegistry`。当这些可选依赖缺失时，autoconfigure MUST 静默跳过对应 bean 的注册，MUST NOT 因缺类抛出 `ClassNotFoundException`/`NoClassDefFoundError` 或导致应用上下文启动失败。这些依赖在 autoconfigure/starter 中 MUST 以 `optional=true` 声明，观测能力靠 classpath 条件按需启用。

#### Scenario: 无 actuator 依赖不装配也不报错
- **WHEN** 应用 classpath 不含 `spring-boot-actuator`
- **THEN** `HealthIndicator`/`InfoContributor` 均不装配，应用上下文正常启动，无 `NoClassDefFoundError`

#### Scenario: 无 micrometer 依赖不装配也不报错
- **WHEN** 应用 classpath 不含 Micrometer 且无 `MeterRegistry` bean
- **THEN** 指标埋点组件不装配，门面照常执行，应用上下文正常启动

#### Scenario: 依赖齐备时观测能力启用
- **WHEN** classpath 同时含 `spring-boot-actuator` 与 Micrometer 且存在 `MeterRegistry`
- **THEN** `HealthIndicator`、`InfoContributor` 与门面计时/失败分类指标均被装配启用

### Requirement: 生命周期指标携 operation 上下文
Micrometer 存在时，任务计时、失败计数、活跃计数与警告计数 MUST 使用有界标签；至少包含 operation/result 或 warning-code。taskId 具有高基数，MUST NOT 作为 Meter tag；它 SHALL 仅进入事件与结构化日志上下文。

#### Scenario: 警告按稳定代码计数
- **WHEN** 一个任务产生 SUBTITLE_DROPPED 警告
- **THEN** warning counter 以 operation 和 warning-code=SUBTITLE_DROPPED 递增，不使用 message 或 taskId 作为标签

#### Scenario: taskId 不进入指标标签
- **WHEN** 大量不同 taskId 的任务执行
- **THEN** Meter 数量不会按 taskId 线性增长，taskId 仅用于事件和日志关联

### Requirement: 执行器拒绝可观测
默认执行器拒绝任务时，系统 MUST 记录 operation、taskId 与容量上下文，并递增有界的 rejected counter；返回给调用方的异常 MUST 保留拒绝原因。

#### Scenario: 队列饱和产生拒绝指标
- **WHEN** 默认有界执行器饱和并拒绝一个异步任务
- **THEN** rejected counter 递增，日志可用 taskId 关联该提交，Future 以原始可诊断拒绝异常失败
