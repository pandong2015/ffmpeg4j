## Why

当前 Spring 集成在缺少唯一 `TaskExecutor` 时会回退到 common pool/pump 线程，缺少有界并发保护；任务进度事件的任务标识仍为可选且没有 started/completed/failed 等完整生命周期；多项“成功但降级”的行为只能从日志获知。生产调用方因此难以控制资源、关联一次任务的全链路状态，也无法用结构化方式处理降级。

## What Changes

- Spring Boot 自动配置提供 ffmpeg4j 专用的默认有界 `TaskExecutor`，支持核心线程数、最大线程数、队列容量、线程名前缀、关闭等待与拒绝策略配置；用户自定义执行器仍优先。
- 为每个门面任务分配稳定 `taskId`，统一发布 started/progress/cancelling/completed/failed 生命周期事件，并让进度、日志与 Micrometer 指标共享 operation/task 上下文。
- 引入有界代码集合的结构化 `FfmpegWarning`，承载进度通道降级、版本偏低、可选流缺失、字幕丢弃、ABR 梯度裁剪等“非致命但需要调用方知晓”的情况。
- 通过新增任务句柄/执行报告 API 暴露 `taskId`、终态、结果与 warnings；保留现有同步/异步门面签名，避免破坏既有调用。
- 为线程池饱和、生命周期顺序、任务关联、监听器隔离和 warnings 保真增加纯逻辑及 Spring 上下文测试。

## Capabilities

### New Capabilities

- `task-lifecycle`: 定义任务标识、生命周期状态/事件、执行报告和结构化警告的公共契约。

### Modified Capabilities

- `spring-async-events`: 默认提供并绑定有界 Spring 执行器，并把进度事件扩展为可关联的完整任务生命周期。
- `spring-boot-starter`: 增加默认执行器的强类型配置项、用户执行器优先级与关闭语义。
- `spring-observability`: 指标和日志关联 operation/task 上下文，并统计结构化警告与执行器拒绝。

## Impact

- 影响 `ffmpeg4j-core` 的任务执行/门面结果扩展，以及 `ffmpeg4j-spring-boot-autoconfigure` 的属性、执行器装配、事件桥接和 Micrometer 埋点。
- 新增公共类型与重载，但保留现有 `FfmpegClient` 方法及其返回类型，默认行为保持源码兼容。
- 不新增 core 运行时依赖；Spring 模块复用现有 Spring Framework、Boot Actuator 与 Micrometer 依赖。
- 默认异步执行从无界/共享执行资源转为 ffmpeg4j 专用有界资源，饱和时以可诊断拒绝快速失败。
