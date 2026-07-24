## ADDED Requirements

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
