## Context

现有 `FfmpegClient` 同步方法直接返回 `RunResult`/`ProbeResult`，异步方法返回 `CompletableFuture`；进度仅通过 `ProgressListener` 传播，任务标识可缺失，且 Spring 在没有合适执行器时可能退回共享资源。与此同时，版本偏低、进度通道降级、可选流缺失等非致命情况只进入日志，调用方无法稳定地按代码处理。

本变更横跨 core 的公共任务契约、Spring 异步装配、事件桥接和 Micrometer 埋点。它必须保持现有 `FfmpegClient` 方法源码兼容，且不能给 `RunResult` record 增字段，否则其规范构造器、模式解构及序列化形状都会发生破坏性变化。

## Goals / Non-Goals

**Goals**

- 为一次门面调用建立稳定、非空且全链路一致的 `taskId`。
- 提供 started/progress/cancelling/completed/failed 的有序生命周期。
- 用公共、不可变、有界代码集合表达非致命警告。
- 通过新增 `TaskHandle`/`TaskReport` API 暴露任务上下文，同时保留既有方法。
- 默认使用 ffmpeg4j 专用的有界 Spring 执行器，明确饱和拒绝和应用关闭行为。
- 让事件、日志和低基数指标共享 operation 上下文；高基数 taskId 仅进入事件与日志。

**Non-Goals**

- 不引入响应式 API、持久化任务队列或跨进程任务注册中心。
- 不改变现有同步/异步方法的签名、返回类型或 `RunResult` 结构。
- 不保证事件监听器的全局顺序；只保证同一任务的事件顺序。
- 不把警告升级成失败，也不允许监听器异常改变媒体任务结果。

## Decisions

### D1：新增旁路任务 API，保留既有门面

core 新增不可变 `TaskHandle<T>` 与 `TaskReport<T>`。可选的 `XxxTask(...)`/`submitXxx(...)` 重载返回 handle；handle 在提交后立即暴露 `taskId` 和可取消的 completion，completion 以 `TaskReport<T>` 收口。同步便捷 API 仍返回原类型，内部可委托同一任务管线后只取 `report.result()`；现有异步 API 仍返回原 `CompletableFuture<T>`。

`RunResult` 不增加字段。这样既有源码、二进制调用形状和调用方 JSON 映射不受影响；需要 taskId、终态和 warnings 的新调用方显式选择任务 API。

### D2：taskId 在门面边界一次生成

每次顶层门面调用生成一个非空、进程内唯一的字符串 taskId，并在该调用的事件、日志上下文、警告和报告中复用。内部 probe、编译和执行步骤不另建顶层 taskId。允许高级重载注入调用方提供的 taskId，但须拒绝空白值；库不从文件名或命令行推导标识。

### D3：单调状态机与终态唯一

公共状态为 `SUBMITTED`、`RUNNING`、`CANCELLING`、`COMPLETED`、`FAILED`、`CANCELLED`。正常序列是 `SUBMITTED → RUNNING → COMPLETED|FAILED`；取消序列允许 `SUBMITTED|RUNNING → CANCELLING → CANCELLED`，若取消竞争输给自然完成，则只发布实际赢得的一个终态。每个任务最多发布一次 started、一次 cancelling 和一个终态；progress 只允许出现在 started 之后、终态之前。

实现以一次性原子收尾门控防止 Future 完成、执行器拒绝、取消和进程退出竞争造成重复事件或活跃计数泄漏。

### D4：警告是稳定代码加诊断数据

`FfmpegWarning` 为不可变值，至少包含 `code`、message 和不可变 details；`WarningCode` 是有界枚举，首批覆盖 progress 降级、版本偏低、可选流缺失、字幕丢弃和 ABR 梯度裁剪。message 面向人，调用方分支只依赖 code。报告中的 warnings 保持产生顺序并使用不可变快照。

警告收集是任务上下文的一部分；日志仍可记录，但不是唯一出口。现有 API 不返回报告，因此继续保持原行为，调用方选择新任务 API后才能结构化消费 warnings。

### D5：Spring 默认执行器专用且有界

当 `async.use-spring-executor=true` 时，优先使用用户明确提供的 ffmpeg4j 执行器，其次使用唯一/`@Primary` 的合适 `TaskExecutor`；均不存在时自动创建名为 `ffmpeg4jTaskExecutor` 的 `ThreadPoolTaskExecutor`。默认核心/最大线程数有限、队列容量有限，绝不使用无界队列或 common pool。

属性控制 core-size、max-size、queue-capacity、thread-name-prefix、await-termination、await-termination-period 和拒绝策略。拒绝策略仅允许清晰、非阻塞的 `ABORT`（默认）或调用方显式选择的 `CALLER_RUNS`；不提供静默丢弃。`ABORT` 被翻译为可诊断的任务拒绝异常，并生成 failed 终态，不能泄漏 active 指标。

Spring 管理默认执行器的初始化与销毁。关闭时停止接收新任务，等待已提交任务至配置期限；超时后取消/中断剩余任务并复用既有取消阶梯。用户提供的执行器生命周期由其 bean 所有者管理。

### D6：事件桥接按任务串行、监听失败隔离

Spring 事件统一携带 taskId、operation、时间戳和对应 payload。桥接层为同一任务维持提交顺序，started 必须先于 progress，终态必须最后。不同任务可并发。事件发布和直接 listener 均在选定执行器上进行；监听器异常仅记录，不能回传到 pump 或改变任务终态。

执行器在任务启动前即拒绝时，没有 started，发布 failed（reason=REJECTED）；由此事件与“实际开始运行”语义一致。

### D7：指标避免 taskId 高基数

Micrometer Timer/Counter 继续使用低基数 `operation`、`result`、`error`、`warning.code` 标签；新增拒绝计数和警告计数。`taskId` 进入事件与结构化日志/MDC，但不得作为 meter tag，以免时间序列爆炸。活跃 Gauge 在实际 started 时自增，并通过同一个一次性终态门控自减；提交即拒绝不自增。

## Risks / Trade-offs

- 新任务 API 增加公共类型数量，但避免了修改 `RunResult` 的更大兼容风险。
- 有界默认池会让过去被共享/无界资源吸收的突发流量显式失败；这是预期的背压，需提供清楚异常和可调属性。
- 同任务事件串行需要轻量串行化状态；若复用执行池，慢监听器仍会占用工作线程。监听异常被隔离，但耗时应由用户自行约束。
- `CALLER_RUNS` 可能阻塞调用线程，因此不是默认值，并在配置文档中明确风险。
- taskId 不作为指标 tag 降低单任务指标查询能力，单任务追踪改由事件和日志承担。

## Migration Plan

1. 先增加 task lifecycle 公共值类型、状态机和兼容任务重载，旧 API 委托新管线。
2. 增加 warnings 收集点及回归测试，确保旧 API 行为和 `RunResult` 形状不变。
3. 增加 Spring 属性与默认有界执行器，保留用户 bean 优先。
4. 扩展事件桥接和 Micrometer，验证顺序、拒绝、取消、监听器隔离及计数回落。
5. 文档给出从旧异步 API 迁移到 `TaskHandle` 的可选示例；无需强制迁移。

回滚时可移除新重载和自动配置 bean；旧 API 与原返回类型始终保留，因此调用方不需要回滚代码。

## Open Questions

- `TaskHandle` 的命名采用统一 `submit(...)` 还是每个门面的 `transcodeTask(...)`，在实现前由 API 评审确定；规格只约束能力与兼容性。
- 默认线程池数值应依据现有 starter 的目标部署基线在实现任务中确定，并写入配置元数据与文档。
