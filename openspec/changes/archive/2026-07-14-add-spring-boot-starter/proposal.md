## Why

`ffmpeg4j-core` 目前只提供**静态门面** `Ffmpeg`（8 个 public static 方法），并在 `FacadeSupport.execute` 里写死 `FfmpegEnvironment.shared()`。这在 Spring Boot 生态里很别扭：二进制路径、超时、取消宽限期无法经 `@ConfigurationProperties` 统一配置；进程执行器与门面无法作为 bean 注入、无法被测试替换；缺二进制只能等运行时才炸，而非启动即 fail-fast；进度回调也接不进 Spring 的 `TaskExecutor`，健康检查与指标更是无从谈起。

好在底层早已为 DI 就绪——L1 引擎 `FfmpegExecutor(FfmpegEnvironment env)` 本就是实例化构造，`FfmpegBinaries.of(...)` 支持显式路径，`MediaProbe.probe(file, ffprobeBinary)` 已有走配置 ffprobe 的重载。唯一横在中间的就是那层静态门面。本变更给 Spring Boot 3.x 用户提供**开箱即用**的 ffmpeg 集成：注入 `FfmpegClient`、配置即绑定、启动即校验、进度事件经 Spring 线程池派发、健康与指标随 classpath 自动装配。

## What Changes

- **单模块 → 多模块**：拆出 `ffmpeg4j-parent`（聚合 pom + `dependencyManagement`），其下 `ffmpeg4j-core`（坐标不变）、`ffmpeg4j-spring-boot-autoconfigure`、`ffmpeg4j-spring-boot-starter` 三个子模块。
- **core 小重构（方案 A）**：core 新增实例门面 `facade/FfmpegClient(FfmpegEnvironment env, RunOptions defaultRunOptions)`，把 8 个门面（transcode/remux/clip/extractAudio/thumbnail/concat/burnSubtitles/probe）实现为实例方法；`FacadeSupport.execute` 增 `execute(cmd, env, ro)` 形参版本、去掉硬编码 `shared()`；静态 `Ffmpeg.xxx` 委托给默认实例（`FfmpegClient(FfmpegEnvironment.shared(), RunOptions.defaults())`），**行为与现有单测保持不变**。
- **autoconfigure 模块**：`Ffmpeg4jProperties`（`@ConfigurationProperties(prefix = "ffmpeg4j")`）＋ `@AutoConfiguration`，装配 `FfmpegEnvironment` / `FfmpegExecutor` / `FfmpegClient` bean，显式路径优先、否则走 PATH 发现，启动 fail-fast（默认开、可关），全部 `@ConditionalOnMissingBean` 允许用户覆盖。
- **async + 事件**：把 core 的 `callbackExecutor` 桥接到 Spring `TaskExecutor`，进度事件 `FfmpegProgressEvent` 经 executor 派发（**绝不占 pump 线程**，呼应 core「回调必须非阻塞」铁律），暴露异步 `runAsync`/`CompletableFuture` API。
- **observability**：Actuator `HealthIndicator`（二进制存在 + 版本 + libass/libfreetype 构建开关）、`InfoContributor`（版本与构建开关）、Micrometer 指标（门面计时 + 失败按 `ErrorPattern` 计数），全部按 classpath 条件装配。
- **starter 模块**：空 pom，只聚合 `autoconfigure` 依赖 + 常用可选依赖；`META-INF/spring/*.AutoConfiguration.imports` 注册装配类，附配置元数据。
- **依赖卫生**：core 继续零重型依赖；spring 相关依赖只落在 autoconfigure/starter；actuator 与 micrometer 一律 `optional = true`，靠 classpath 条件装配。

## Capabilities

### New Capabilities
- `spring-boot-starter`: 自动装配 `FfmpegEnvironment`/`FfmpegExecutor`/`FfmpegClient` bean、`@ConfigurationProperties(prefix = "ffmpeg4j")` 绑定发现顺序与 `RunOptions`、显式路径优先否则 PATH 发现、启动 fail-fast（可关）、`@ConditionalOnMissingBean` 允许覆盖、`-autoconfigure` 与 `-starter` 模块分层。
- `spring-async-events`: `callbackExecutor` 接 Spring `TaskExecutor`、进度事件 `FfmpegProgressEvent` 经 executor 派发而绝不占 pump 线程、异步 `runAsync`/`CompletableFuture` API 暴露。
- `spring-observability`: Actuator `HealthIndicator`（二进制/版本/构建开关）、`InfoContributor`（版本与开关）、Micrometer 指标（门面计时 + 失败按 `ErrorPattern` 计数），全部 classpath 条件装配。

### Modified Capabilities
- `job-model`: 新增 core 实例门面 `FfmpegClient`（8 门面为实例方法、注入 `FfmpegEnvironment` 与默认 `RunOptions`），静态 `Ffmpeg` 委托默认实例保持向后兼容。

## Impact

- 新增两个可发布模块 `ffmpeg4j-spring-boot-autoconfigure` 与 `ffmpeg4j-spring-boot-starter`（并引入聚合 `ffmpeg4j-parent`）；`ffmpeg4j-core` 坐标 `io.github.pandong2015:ffmpeg4j-core` **不变**。
- core 侧仅新增实例门面并让静态门面转为委托，**不破坏现有公共 API**，现有全部单测须保持全绿；`MediaProbe`（已有显式重载）、`buildXxx`（纯函数）、`FfmpegExecutor`（已吃 env）均不动。
- 新增对 `spring-boot` 的**可选**依赖：autoconfigure 依赖 `spring-boot-autoconfigure` + core；actuator/micrometer 为 `optional = true`，仅在用户 classpath 具备时启用；core 保持零重型依赖、可脱离 Spring 独立使用。
- 明确基线：Spring Boot 3.x / jakarta.* / Java 17，**不支持 Boot 2.x/javax**；响应式（WebFlux `Mono`/`Flux`）v1 不做，`runAsync`/`CompletableFuture` 逃生舱够用。
- 运行时依赖不变：仍需目标机预装 `ffmpeg`/`ffprobe`；版本 < 4.2 仅告警不硬失败（沿用 core 语义），fail-fast 校验缺二进制才启动失败。
