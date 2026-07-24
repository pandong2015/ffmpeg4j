## 1. 默认有界执行器

- [x] 1.1 扩展 `Ffmpeg4jProperties.Async`，增加线程数、队列、线程名与关闭等待配置及边界校验
- [x] 1.2 条件装配 `ffmpeg4jTaskExecutor`，实现用户唯一/主执行器优先与默认有界执行器回退
- [x] 1.3 将默认/用户执行器同时接入异步门面和 progress callback，饱和拒绝保持可诊断
- [x] 1.4 增加配置绑定、候选优先级、队列饱和与关闭语义测试

## 2. Core 任务生命周期

- [x] 2.1 新增不可变 `TaskId`/`TaskStatus`/`TaskEvent`/`TaskHandle`/`TaskReport` 公共模型
- [x] 2.2 实现单调任务状态机、一次性终态门控与取消传播
- [x] 2.3 为门面增加兼容任务入口，保持既有同步/异步签名和 `RunResult` 结构不变
- [x] 2.4 增加成功、失败、取消竞争、执行器拒绝与旧 API 兼容测试

## 3. 生命周期事件与指标

- [x] 3.1 扩展 Spring 事件桥，使 started/progress/cancelling/terminal 共享 taskId 与 operation
- [x] 3.2 保证同任务事件顺序并隔离 application-event/listener 异常
- [x] 3.3 增加低基数生命周期/拒绝指标，确保 taskId 不进入 Meter tag
- [x] 3.4 增加事件顺序、监听器隔离、取消和指标基数测试

## 4. 结构化警告

- [x] 4.1 新增 `WarningCode` 与不可变 `FfmpegWarning`，并接入 `TaskReport`
- [x] 4.2 收集 progress 降级、版本偏低、可选流缺失、字幕丢弃与 ABR 梯度裁剪警告
- [x] 4.3 增加 warning Micrometer counter，并保持 message/details 不作高基数标签
- [x] 4.4 增加警告顺序、不可变性、成功伴随警告和旧 API 行为测试

## 5. 文档与验证

- [x] 5.1 更新配置元数据、README/USAGE，说明默认容量、拒绝背压、任务 API 与 warning 消费
- [x] 5.2 运行全量测试、覆盖率与 `git diff --check`
- [x] 5.3 独立复核公共 API 兼容性、并发终态、取消传播和资源关闭
