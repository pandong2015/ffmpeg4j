## 1. 多模块脚手架

- [x] 1.1 引入聚合父 POM `ffmpeg4j-parent`（packaging=pom，`<modules>` 列 core/autoconfigure/starter），把 Java 17、编码、编译插件与版本号上提为父级统一管理
- [x] 1.2 在父 POM 的 `<dependencyManagement>` 锁定 Spring Boot 3.x BOM、`ffmpeg4j-core`、`ffmpeg4j-spring-boot-autoconfigure` 版本，供子模块免版本号引用
- [x] 1.3 迁移现有 core 为子模块 `ffmpeg4j-core`：坐标 `io.github.pandong2015:ffmpeg4j-core` **保持不变**，`<parent>` 指向 parent，源码/测试目录原样保留
- [x] 1.4 建 `ffmpeg4j-spring-boot-autoconfigure` 骨架模块（依赖 core + `spring-boot-autoconfigure`；actuator/micrometer 以 `optional=true` 声明），空包结构 `...ffmpeg4j.spring.autoconfigure`
- [x] 1.5 建 `ffmpeg4j-spring-boot-starter` 骨架模块（packaging=jar、空 `src`，仅 POM 聚合），确认 `mvn -q -o install` 三模块反应堆整体编译通过

## 2. core 重构：实例门面 + 静态委托

- [ ] 2.1 新增 `facade/FfmpegClient(FfmpegEnvironment env, RunOptions defaultRunOptions)`：把 8 门面（transcode/remux/clip/extractAudio/thumbnail/concat/burnSubtitles/probe）落为实例方法，含各自便捷重载与 `XxxOptions` 进阶重载
- [ ] 2.2 微调 `FacadeSupport`：新增 `execute(CompiledCommand cmd, FfmpegEnvironment env, RunOptions ro)` 重载，去掉写死的 `FfmpegEnvironment.shared()`；`buildXxx` 纯函数签名不动
- [ ] 2.3 `FfmpegClient` 各门面调用点：execute 传注入的 `env`，probe 走 `MediaProbe.probe(file, env.binaries().ffprobeCommand())`，调用点 `XxxOptions` 与 `defaultRunOptions` 合并
- [ ] 2.4 改造静态 `Ffmpeg`：8 个 public static 方法委托给默认实例 `new FfmpegClient(FfmpegEnvironment.shared(), RunOptions.defaults())`，签名与行为保持不变（向后兼容）
- [ ] 2.5 新增 `FfmpegClientTest`：断言实例门面与静态门面对同一入参产出一致 argv/CompiledCommand；跑 `mvn -q test` 确认现有全部单测保持全绿

## 3. autoconfigure：属性绑定与条件装配

- [ ] 3.1 新增 `Ffmpeg4jProperties`（`@ConfigurationProperties(prefix="ffmpeg4j")`）：`ffmpegPath`/`ffprobePath`/`failFast`(默认 true)/`defaultTimeout`(Duration)/`cancelGracePeriod`(默认 5s)/`terminateGracePeriod`(默认 5s)/`minVersionCheck` 及嵌套 `async.useSpringExecutor`(默认 true)/`async.progressChannel`(枚举 application-event/listener/both，默认 application-event)
- [ ] 3.2 新增 `Ffmpeg4jAutoConfiguration`（`@AutoConfiguration` + `@EnableConfigurationProperties(Ffmpeg4jProperties.class)`），承载后续各 `@Bean`
- [ ] 3.3 `@Bean @ConditionalOnMissingBean FfmpegEnvironment ffmpegEnvironment(...)`：显式 `ffmpeg-path`/`ffprobe-path` 都给→`FfmpegBinaries.of(...)`；否则走 PATH `locate` 发现，据此构造 `FfmpegEnvironment`
- [ ] 3.4 `@Bean @ConditionalOnMissingBean FfmpegExecutor ffmpegExecutor(FfmpegEnvironment env)` 与 `@Bean @ConditionalOnMissingBean FfmpegClient ffmpegClient(FfmpegEnvironment env, RunOptions defaultRunOptions)`；由属性映射装配 `RunOptions`(timeout/两 grace)
- [ ] 3.5 启动 fail-fast：`failFast=true` 时在 bean 初始化阶段调 `FfmpegEnvironment.detect()` + 版本/能力校验，二进制缺失直接令 context 启动失败；`min-version-check` <4.2 仅告警不硬失败（沿用 core 语义）
- [ ] 3.6 新增 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 登记 `Ffmpeg4jAutoConfiguration`，使 auto-config 生效

## 4. async + 进度事件桥接

- [ ] 4.1 新增 `FfmpegProgressEvent`（Spring `ApplicationEvent` 或 POJO + `ApplicationEventPublisher`）：承载进度 `key=value` 快照（帧/时间/码率/speed 等）
- [ ] 4.2 `async.use-spring-executor=true` 时，把 core 的 `.callbackExecutor(...)` 逃生舱接到注入的 Spring `TaskExecutor`（`@ConditionalOnMissingBean` 提供默认 `ThreadPoolTaskExecutor`，允许用户覆盖）
- [ ] 4.3 进度桥接铁律：进度回调**必经 TaskExecutor 派发**（`publishEvent` 在 executor 线程），**绝不阻塞/占用 pump 线程**，呼应 core「回调必须非阻塞」约束
- [ ] 4.4 在 `FfmpegClient`/autoconfigure 暴露异步入口：基于 core `runAsync()` 返回 `CompletableFuture`，其回调经上面 executor 派发（响应式 WebFlux 明确不做）
- [ ] 4.5 新增 `FfmpegProgressEventTest`：断言事件在 TaskExecutor 线程而非 pump 线程触发、`use-spring-executor=false` 时退回 core 默认行为
- [ ] 4.6 新增 `FfmpegProgressListener` 函数式接口与进度递送双通道：`ffmpeg4j.async.progress-channel`（application-event 广播 / listener 直投 / both）切换，两通道均经 TaskExecutor 派发、绝不占 pump 线程；补测通道切换与 listener 直投路径

## 5. observability：健康/信息/指标

- [ ] 5.1 新增 `FfmpegHealthIndicator`（`@ConditionalOnClass(HealthIndicator.class)` + `@ConditionalOnEnabledHealthIndicator`）：二进制缺失 **或 libass/libfreetype 任一构建开关缺失** → `Status.DOWN`（details 指明缺失项）；版本 <4.2 仅告警仍 `UP`；探测只读无副作用
- [ ] 5.2 新增 `FfmpegInfoContributor`（`@ConditionalOnClass(InfoContributor.class)`）：向 `/actuator/info` 贡献 ffmpeg 版本与构建开关（libass/libfreetype）
- [ ] 5.3 新增 `FfmpegMetrics`（`@ConditionalOnClass(MeterRegistry.class)`）：门面调用计时（Timer，按门面名+结果打 tag）+ 失败计数按 `ErrorPattern` 分类打 tag + 运行中 ffmpeg 子进程数 `Gauge`（由引擎活跃计数驱动，成功/失败/取消三路径都正确回落，不泄漏虚高）
- [ ] 5.4 新增 `FfmpegObservabilityAutoConfiguration`（`@AutoConfiguration(after=Ffmpeg4jAutoConfiguration.class)`）汇总上述三 bean，全部 classpath 条件装配（无 actuator/micrometer 时静默跳过）
- [ ] 5.5 补 `AutoConfiguration.imports` 登记 `FfmpegObservabilityAutoConfiguration`

## 6. starter 聚合 POM

- [ ] 6.1 `ffmpeg4j-spring-boot-starter` POM `compile` 依赖 `ffmpeg4j-spring-boot-autoconfigure`（传递引入 core），不含任何 Java 源码
- [ ] 6.2 以 `optional=true` 声明常用可选依赖（`spring-boot-starter-actuator`、`micrometer-core`），由使用方按需引入触发条件装配
- [ ] 6.3 依赖卫生核查：确认 core 反应堆仍**零重型依赖**，Spring 相关依赖只落在 autoconfigure/starter；`mvn -q dependency:tree` 校验无 Jackson/Guava 渗入 core

## 7. 测试：切片装配 + 冒烟

- [ ] 7.1 `Ffmpeg4jAutoConfigurationTest`：用 `ApplicationContextRunner` 断言默认装配出 `FfmpegEnvironment`/`FfmpegExecutor`/`FfmpegClient` 三 bean 且类型正确
- [ ] 7.2 覆盖与条件测试：用户自定义同名 bean 时 `@ConditionalOnMissingBean` 让用户 bean 胜出；`ffmpeg-path`/`ffprobe-path` 显式属性优先于 PATH 发现（用 `withPropertyValues` 注入）
- [ ] 7.3 fail-fast 测试：`fail-fast=true` + 指向不存在二进制→context 启动失败并抛可诊断异常；`fail-fast=false`→启动放行不硬失败
- [ ] 7.4 observability 切片：无 actuator/micrometer classpath 时相关 bean 不装配；有则 `FfmpegHealthIndicator`/`FfmpegInfoContributor`/`FfmpegMetrics` 就位
- [ ] 7.5 `@SpringBootTest` 冒烟：真实调一次 transcode/probe，真机 ffmpeg 用 `assumeTrue(commandExists("ffmpeg"), ...)` 守卫（缺失即跳过而非失败）

## 8. 收尾

- [ ] 8.1 完成并核对 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（含 `Ffmpeg4jAutoConfiguration` 与 `FfmpegObservabilityAutoConfiguration`）
- [ ] 8.2 生成 `META-INF/spring-configuration-metadata.json`（注解处理器）并手写 `additional-spring-configuration-metadata.json` 补全 `ffmpeg4j.*` 各属性的描述/默认值/枚举提示（IDE 补全）
- [ ] 8.3 更新 README（Spring Boot 快速上手：引 starter、`ffmpeg4j.*` 配置样例、注入 `FfmpegClient`、进度事件订阅、Actuator 端点）与 CHANGELOG（记多模块化 + starter 新增）
- [ ] 8.4 发布准备：确认 core 坐标不变、新增 `ffmpeg4j-spring-boot-autoconfigure`/`ffmpeg4j-spring-boot-starter` 坐标与版本对齐父 POM，跑 `mvn -q -o install` 三模块整体验证
