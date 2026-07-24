## MODIFIED Requirements

### Requirement: 配置属性绑定
autoconfigure MUST 提供 `@ConfigurationProperties(prefix = "ffmpeg4j")` 的强类型配置。除既有二进制、fail-fast、超时、取消与进度配置外，绑定 MUST 覆盖：`async.core-pool-size`、`async.max-pool-size`、`async.queue-capacity`、`async.thread-name-prefix`、`async.await-termination` 与 `async.await-termination-period`。线程数 MUST 为正，max MUST 不小于 core，队列容量 MUST 大于等于零，等待时长 MUST 非负；非法配置 MUST 在启动期给出可诊断绑定/校验错误。

#### Scenario: 默认执行器属性采用有界值
- **WHEN** 应用未配置 async 线程池属性
- **THEN** core/max/queue 均采用文档化的有限正整数，线程名前缀为 `ffmpeg4j-`，不得形成无界队列

#### Scenario: 自定义线程池属性生效
- **WHEN** 配置 core=2、max=4、queue-capacity=8、await-termination-period=20s
- **THEN** 默认执行器按该容量创建，并在容器关闭时最多等待 20 秒

#### Scenario: 非法容量启动失败
- **WHEN** max-pool-size 小于 core-pool-size 或 queue-capacity 为负
- **THEN** 应用上下文启动失败并明确指出非法属性，MUST NOT 静默纠正

## ADDED Requirements

### Requirement: 专用默认执行器条件装配与关闭
autoconfigure MUST 在启用 Spring 执行器且没有用户唯一/主候选时创建名称稳定的 ffmpeg4j 专用 `ThreadPoolTaskExecutor`。执行器 MUST 使用有界队列、命名线程、拒绝时抛可诊断异常，并由 Spring 管理初始化与销毁；存在用户候选时默认执行器 MUST 退让。

#### Scenario: 饱和时快速拒绝
- **WHEN** 活跃线程和有界队列均已满
- **THEN** 新异步任务以可诊断拒绝异常快速失败，不得无限排队或阻塞提交线程

#### Scenario: 容器关闭等待任务
- **WHEN** await-termination 开启且 Spring 上下文关闭
- **THEN** 默认执行器停止接收新任务并按配置等待已提交任务收口，超时后继续关闭
