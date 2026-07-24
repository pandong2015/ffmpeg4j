# spring-async-events Specification

## Purpose

把 core 执行引擎的进度回调接入 Spring 异步基建：将 `callbackExecutor` 绑定容器中的 `TaskExecutor`，提供 `application-event`/`listener`/`both` 三条可切换的进度递送通道，并为门面提供返回 `CompletableFuture` 的 `runAsync` 异步入口。所有递送经绑定 executor 异步派发，恪守 core「进度回调绝不占 pump 线程」的铁律，异常被桥接层隔离，取消复用 core 优雅取消阶梯。

## Requirements

### Requirement: callbackExecutor 绑定 Spring TaskExecutor
autoconfigure MUST 在 `ffmpeg4j.async.use-spring-executor` 为 `true`（默认）时，将 core 执行引擎的进度 `callbackExecutor` 与异步门面执行器绑定到 Spring `TaskExecutor`。存在用户候选时 MUST 优先唯一/`@Primary` 候选；无用户候选时 MUST 自动提供 ffmpeg4j 专用的有界默认执行器，MUST NOT 回退到 common pool 或进度 pump 线程。开关为 `false` 时 autoconfigure MUST NOT 覆盖 `callbackExecutor`，保留 core 默认语义。绑定 MUST 走既有 `RunOptions.callbackExecutor(...)` 与 `FfmpegClient` 构造注入面。

#### Scenario: 用户执行器优先
- **WHEN** 应用上下文含唯一或 `@Primary` 的用户 `TaskExecutor`，且 use-spring-executor 未关闭
- **THEN** FfmpegClient 的异步执行与进度回调均使用该用户执行器，默认 ffmpeg4j 执行器退让

#### Scenario: 缺少用户执行器时创建有界默认
- **WHEN** use-spring-executor 为默认 true 且上下文没有用户 TaskExecutor
- **THEN** autoconfigure 创建并绑定 ffmpeg4j 专用有界执行器，进度回调不占 pump 线程

#### Scenario: 开关关闭时保留 core 默认
- **WHEN** 配置 `ffmpeg4j.async.use-spring-executor=false`
- **THEN** autoconfigure 不覆盖 callbackExecutor，且不为 FfmpegClient 强制绑定默认 Spring 执行器

### Requirement: 进度递送双通道且经 executor 派发不占 pump 线程
starter MUST 提供两条可切换的进度递送通道，由 `ffmpeg4j.async.progress-channel` 选择——`application-event`（把进度桥接为 Spring 应用事件 `FfmpegProgressEvent`、携当前 `Progress` 快照与任务标识，经 `ApplicationEventPublisher.publishEvent(...)` 广播）、`listener`（直接回调容器中注入的 `FfmpegProgressListener` bean，若存在）、`both`（两者并投），默认 `application-event`。无论哪条通道，进度的构造与递送 MUST 经绑定的 `TaskExecutor` 异步派发，桥接层 MUST NOT 在进度 pump 线程上同步 `publishEvent`/回调 listener——呼应 core「进度回调必须非阻塞、绝不占 pump 线程」的铁律（pipe 模式下 pump 线程是子进程 stdout 的唯一排空者，任何阻塞都可能撑满管道致死锁）。任一事件监听器/listener 抛出的异常 MUST 被桥接层隔离（记录而不上抛），MUST NOT 传播回进度采集路径而影响后续进度块或任务本身的推进。

#### Scenario: 进度块转为异步事件
- **WHEN** 一次由 `FfmpegClient` 发起的转码任务输出进度块，且 `use-spring-executor` 为 `true`
- **THEN** 桥接层在绑定的 `TaskExecutor` 上构造并发布 `FfmpegProgressEvent`，`@EventListener` 监听方在 executor 线程而非进度 pump 线程上被回调

#### Scenario: 切换到 listener 直投通道
- **WHEN** 配置 `ffmpeg4j.async.progress-channel=listener` 且容器中存在一个 `FfmpegProgressListener` bean
- **THEN** 进度经 `TaskExecutor` 直接回调该 listener bean 而不经 `ApplicationEventPublisher` 广播；配置为 `both` 时两条通道均递送

#### Scenario: 慢监听器不阻塞 pump 线程
- **WHEN** 某个 `FfmpegProgressEvent` 监听器执行缓慢（如阻塞 IO）
- **THEN** 进度 pump 线程不被其阻塞、持续排空进度通道，慢监听在 executor 线程上独立进行，任务正常推进

#### Scenario: 监听器抛异常被隔离
- **WHEN** 某个 `FfmpegProgressEvent` 监听器抛出异常
- **THEN** 桥接层记录该异常但不将其上抛回进度采集路径，后续进度块与任务收尾均不受影响

### Requirement: 异步执行暴露为 CompletableFuture
`FfmpegClient` SHALL 为其阻塞门面（transcode/remux/clip/extractAudio/thumbnail/concat/burnSubtitles/probe）提供对应的 `runAsync` 风格异步入口，返回 `CompletableFuture`，在绑定的 Spring `TaskExecutor`（或用户显式提供的 `Executor`）上执行、不阻塞调用线程。异步失败 MUST 以原始 `FfmpegException` 异常完成该 `CompletableFuture`（`completeExceptionally`），MUST NOT 吞异常或替换为无诊断信息的泛化异常；返回的句柄 MUST 支持取消，取消 MUST 复用 core 既有的优雅取消阶梯（写 `q` → SIGTERM → SIGKILL，pipe 输入下降级）。v1 不引入响应式类型（无 `Mono`/`Flux`）。

#### Scenario: 异步转码返回 CompletableFuture
- **WHEN** 用户调用 `FfmpegClient` 的异步转码入口
- **THEN** 任务在绑定的 `TaskExecutor` 上后台执行，调用线程立即取得一个 `CompletableFuture`，完成时携带结果

#### Scenario: 异步失败以 FfmpegException 完成
- **WHEN** 异步任务因 ffmpeg 非零码退出而失败
- **THEN** 返回的 `CompletableFuture` 以携退出码/命令/stderr 尾部的 `FfmpegException` `completeExceptionally`，而非被静默吞掉或替换为泛化异常

#### Scenario: 取消异步任务复用优雅阶梯
- **WHEN** 用户取消一个仍在运行的异步任务句柄
- **THEN** 引擎按 core 既有优雅取消阶梯终止子进程（写 `q`，必要时降级 SIGTERM/SIGKILL），`CompletableFuture` 随之收口为取消/失败

### Requirement: Spring 生命周期事件关联 taskId
Spring 事件桥 MUST 发布携同一 taskId、operation、时间戳与状态的任务生命周期事件；`FfmpegProgressEvent` MUST 携非空 taskId。事件发布和 listener 直投 MUST 经绑定执行器派发，监听器异常 MUST 隔离。

#### Scenario: 进度与终态可关联
- **WHEN** Spring 门面任务产生进度并成功完成
- **THEN** STARTED、全部进度事件与 COMPLETED 携相同 taskId 和 operation

#### Scenario: 监听器异常不改变终态
- **WHEN** 生命周期监听器抛出 RuntimeException
- **THEN** 桥接层记录并隔离异常，任务仍按真实执行结果发布唯一终态
