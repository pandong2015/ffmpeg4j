## ADDED Requirements

### Requirement: 核心 bean 自动装配
starter 引入后，autoconfigure 模块 MUST 以 `@AutoConfiguration` 在应用上下文中自动装配三个层级递进的 bean：`FfmpegEnvironment`、`FfmpegExecutor`（吃前者构造）、以及门面 `FfmpegClient`（吃 `FfmpegEnvironment` 与合并后的 `RunOptions`）。装配 MUST 遵循 core 既有的构造依赖（引擎与门面本就实例化），MUST NOT 复制或旁路 core 的发现/执行逻辑。当 classpath 上不存在 autoconfigure 模块或其被显式排除时，MUST NOT 注册任何 bean。

#### Scenario: 默认上下文装配出可用门面
- **WHEN** 一个 Spring Boot 3.x 应用仅在 classpath 引入 `ffmpeg4j-spring-boot-starter`，未写任何自定义配置
- **THEN** 应用上下文中存在单例 `FfmpegEnvironment`、`FfmpegExecutor`、`FfmpegClient` bean，且 `FfmpegExecutor` 持有的正是被装配的 `FfmpegEnvironment`，`FfmpegClient` 可直接注入业务组件调用 transcode/probe 等门面

#### Scenario: 未引入 starter 时不装配
- **WHEN** 应用 classpath 未包含 `ffmpeg4j-spring-boot-autoconfigure`
- **THEN** 上下文中不存在任何 ffmpeg4j 相关 bean，自动装配对未引入方零副作用

### Requirement: 配置属性绑定
autoconfigure MUST 提供 `@ConfigurationProperties(prefix = "ffmpeg4j")` 的 `Ffmpeg4jProperties`，将 `application.yml`/`application.properties` 中的键绑定为强类型配置并驱动 bean 构造。绑定 MUST 覆盖：`ffmpeg-path`/`ffprobe-path`（显式二进制路径）、`fail-fast`（布尔，默认 `true`）、`default-timeout`（`Duration`，默认无）、`cancel-grace-period`（`Duration`，默认 `5s`）、`terminate-grace-period`（`Duration`，默认 `5s`）、`min-version-check`（布尔）、`async.use-spring-executor`（布尔，默认 `true`）、`async.progress-channel`（枚举 `application-event`/`listener`/`both`，默认 `application-event`）。`Duration` 类属性 MUST 支持 Spring 松弛绑定（如 `30s`/`PT30S`），并 MUST 映射进装配 `FfmpegClient` 所用的默认 `RunOptions`。

#### Scenario: 超时与宽限期绑定进 RunOptions
- **WHEN** `application.yml` 配置 `ffmpeg4j.default-timeout: 30s` 与 `ffmpeg4j.cancel-grace-period: 8s`
- **THEN** 装配出的 `FfmpegClient` 其默认 `RunOptions` 的超时为 30 秒、优雅取消宽限期为 8 秒，未显式配置的 `terminate-grace-period` 保持默认 5 秒

#### Scenario: 空配置采用文档化默认值
- **WHEN** 应用未提供任何 `ffmpeg4j.*` 键
- **THEN** `fail-fast` 为 `true`、`default-timeout` 为无（不超时）、两个 grace-period 均为 5 秒，绑定不因缺键而报错

### Requirement: 显式路径优先否则 PATH 发现
autoconfigure 构造 `FfmpegEnvironment` 时 MUST 遵循「显式路径优先，否则 PATH 发现」的发现顺序：当 `ffmpeg-path` 与 `ffprobe-path` 均被配置为非空时，MUST 经 `FfmpegBinaries.of(ffmpeg, ffprobe)` 使用该显式路径而不做 PATH 查找；当二者均未配置时，MUST 退回 core 的 PATH 发现（locate）逻辑。二者只配其一 MUST 被视为可诊断的配置错误而非静默半发现。

#### Scenario: 显式路径覆盖 PATH
- **WHEN** `application.yml` 配置 `ffmpeg4j.ffmpeg-path: /opt/ffmpeg/bin/ffmpeg` 与 `ffmpeg4j.ffprobe-path: /opt/ffmpeg/bin/ffprobe`
- **THEN** 装配的 `FfmpegEnvironment` 其 `binaries` 指向该显式路径，即便 PATH 中另有 ffmpeg 也不被采用

#### Scenario: 未配置路径时走 PATH 发现
- **WHEN** 应用未配置 `ffmpeg4j.ffmpeg-path` 与 `ffmpeg4j.ffprobe-path`
- **THEN** autoconfigure 调用 core 的 PATH 发现定位二进制，其行为与直接使用 `FfmpegEnvironment.shared()` 一致

### Requirement: 启动期 fail-fast 校验
当 `ffmpeg4j.fail-fast` 为 `true`（默认）时，autoconfigure MUST 在应用启动阶段触发 `FfmpegEnvironment.detect()` 校验二进制存在与可执行；若二进制缺失/不可用，MUST 令上下文启动失败（抛出携带发现顺序与缺失项的可诊断异常），MUST NOT 把失败推迟到首次调用门面时。当 `fail-fast` 显式置为 `false` 时，MUST 跳过启动期校验、允许上下文正常启动（缺失在实际调用时才暴露）。版本低于 4.2 沿用 core 语义——仅告警不硬失败，MUST NOT 因版本号导致启动失败。

#### Scenario: 缺二进制且 fail-fast 时启动失败
- **WHEN** `ffmpeg4j.fail-fast` 采用默认 `true`，但目标机 PATH 与显式路径均无可用 `ffmpeg`
- **THEN** 应用上下文启动失败，异常消息清晰指明未发现二进制及其查找位置，而非在运行期产生模糊的进程启动错误

#### Scenario: 关闭 fail-fast 允许延迟失败
- **WHEN** `application.yml` 配置 `ffmpeg4j.fail-fast: false` 且二进制暂不可用
- **THEN** 上下文正常启动，二进制缺失延后到首次调用 `FfmpegClient` 门面时以 `FfmpegException`/发现错误暴露

#### Scenario: 版本过低不阻断启动
- **WHEN** `fail-fast` 为 `true` 且发现的 ffmpeg 版本为 3.4（低于 4.2 但可运行）
- **THEN** 启动仅记录版本过低告警并继续，上下文成功装配

### Requirement: 用户 bean 覆盖优先
autoconfigure 装配的每一个默认 bean（`FfmpegEnvironment`/`FfmpegExecutor`/`FfmpegClient` 及后续能力 bean）MUST 标注 `@ConditionalOnMissingBean`，使用户在自身配置中定义同类型 bean 时，用户 bean 生效而自动装配退让。自动装配 MUST NOT 强制覆盖或与用户显式声明的 bean 冲突。

#### Scenario: 自定义 FfmpegEnvironment 被采用
- **WHEN** 用户在自身 `@Configuration` 中声明了一个 `@Bean FfmpegEnvironment`（如指向容器内特定路径或注入自定义构建开关探测结果）
- **THEN** 上下文使用用户的 `FfmpegEnvironment`，autoconfigure 不再装配默认实例，且下游 `FfmpegExecutor`/`FfmpegClient` 构造于用户提供的 environment 之上

#### Scenario: 自定义 FfmpegClient 完全接管
- **WHEN** 用户声明了自己的 `@Bean FfmpegClient`
- **THEN** 该 bean 生效，autoconfigure 的默认 `FfmpegClient` 因 `@ConditionalOnMissingBean` 退让，注入点得到用户实例

### Requirement: 模块分层与依赖卫生
本能力 MUST 拆分为独立的 `ffmpeg4j-spring-boot-autoconfigure` 与 `ffmpeg4j-spring-boot-starter` 两个 Maven 模块（遵 Spring 官方约定）：autoconfigure 承载 `@AutoConfiguration`、`@ConfigurationProperties` 与条件装配逻辑并依赖 `ffmpeg4j-core` 与 `spring-boot-autoconfigure`；starter MUST 为空 pom，仅聚合 autoconfigure 与常用可选依赖。`ffmpeg4j-core` MUST 保持零重型运行时依赖不变——所有 Spring 相关依赖 MUST 只落在 autoconfigure/starter，MUST NOT 反向渗入 core。核心 `io.github.pandong2015:ffmpeg4j-core` 坐标 MUST 保持不变。

#### Scenario: core 依赖树无 Spring
- **WHEN** 对 `ffmpeg4j-core` 模块执行依赖分析（如 `mvn dependency:tree`）
- **THEN** 结果中不含任何 `org.springframework.*` 依赖，core 可脱离 Spring 独立使用，坐标仍为 `io.github.pandong2015:ffmpeg4j-core`

#### Scenario: starter 仅聚合不含逻辑
- **WHEN** 检视 `ffmpeg4j-spring-boot-starter` 模块
- **THEN** 其为不含 Java 源码的空 pom，仅通过依赖聚合 `ffmpeg4j-spring-boot-autoconfigure`（后者再传递 `ffmpeg4j-core`），装配逻辑全部驻留于 autoconfigure 模块
