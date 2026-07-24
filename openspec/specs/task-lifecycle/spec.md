# task-lifecycle Specification

## Purpose

为 core 门面任务提供稳定标识、有序且终态唯一的生命周期，以及可由调用方读取的结构化非致命警告，同时保持既有同步与异步 API 兼容。

## Requirements

### Requirement: 稳定任务标识与兼容入口
core MUST 为每次门面执行生成非空、进程内唯一且不可变的 `taskId`，并提供新增的任务句柄/执行报告 API 暴露该标识。现有同步与异步门面签名及返回类型 MUST 保持不变；实现 MUST NOT 通过给现有 `RunResult` record 增加组件破坏其构造器兼容性。

#### Scenario: 新任务句柄立即暴露标识
- **WHEN** 调用方通过新增任务入口提交异步转码
- **THEN** 调用方在任务完成前即可取得非空 taskId，且后续全部生命周期事件使用同一标识

#### Scenario: 既有门面保持兼容
- **WHEN** 既有代码继续调用返回 `RunResult` 或 `CompletableFuture<RunResult>` 的门面
- **THEN** 代码无需修改即可编译运行，执行语义与取消传播保持不变

### Requirement: 有序且终态唯一的生命周期
任务生命周期 MUST 使用 `STARTED`、`PROGRESS`、`CANCELLING`、`COMPLETED`、`FAILED`、`CANCELLED` 状态表达。每个任务 MUST 先出现一次 `STARTED`，随后可出现零到多个 `PROGRESS`；最终 MUST 且只能出现一个 `COMPLETED`、`FAILED` 或 `CANCELLED`。监听器异常 MUST 被隔离，不得改变任务结果。

#### Scenario: 成功任务事件有序
- **WHEN** 一个任务正常完成并产生多个进度块
- **THEN** 事件顺序为 STARTED、零到多个 PROGRESS、COMPLETED，且终态事件仅一个

#### Scenario: 取消任务终态唯一
- **WHEN** 调用方请求取消仍在运行的任务
- **THEN** 系统发布 CANCELLING，并最终仅发布 CANCELLED；取消与自然退出竞争不得同时产生 COMPLETED/FAILED

### Requirement: 结构化非致命警告
core MUST 提供不可变 `FfmpegWarning`，至少包含稳定的有界 `code`、中文 `message` 与不可变 `details`。警告 MUST 用于表达不阻断任务的降级，首批代码至少覆盖 `PROGRESS_UNAVAILABLE`、`VERSION_BELOW_MINIMUM`、`OPTIONAL_STREAM_MISSING`、`SUBTITLE_DROPPED`、`ABR_LADDER_TRIMMED`。警告 MUST 可经任务报告读取，且 MUST NOT 仅存在于日志。

#### Scenario: 成功伴随降级警告
- **WHEN** 任务成功但 TCP progress 无法建立并降级为无进度
- **THEN** 任务报告仍为成功，同时包含 code 为 PROGRESS_UNAVAILABLE 的警告

#### Scenario: 警告集合不可变
- **WHEN** 调用方取得任务报告的 warnings
- **THEN** 返回集合不可修改，details 不暴露可变内部状态
