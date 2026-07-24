## MODIFIED Requirements

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

## ADDED Requirements

### Requirement: Spring 生命周期事件关联 taskId
Spring 事件桥 MUST 发布携同一 taskId、operation、时间戳与状态的任务生命周期事件；`FfmpegProgressEvent` MUST 携非空 taskId。事件发布和 listener 直投 MUST 经绑定执行器派发，监听器异常 MUST 隔离。

#### Scenario: 进度与终态可关联
- **WHEN** Spring 门面任务产生进度并成功完成
- **THEN** STARTED、全部进度事件与 COMPLETED 携相同 taskId 和 operation

#### Scenario: 监听器异常不改变终态
- **WHEN** 生命周期监听器抛出 RuntimeException
- **THEN** 桥接层记录并隔离异常，任务仍按真实执行结果发布唯一终态
