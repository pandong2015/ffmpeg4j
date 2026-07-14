## Context

`ffmpeg4j-core` 现状：单模块纯 Java 库，封装目标机预装的 `ffmpeg`/`ffprobe` 二进制（路线 A，无 JNI），core 零重型运行时依赖。本次目标：让 Spring Boot 3.x 应用「加一个 starter 依赖即得开箱可用的 ffmpeg4j」——bean 自动装配、外部化配置、启动 fail-fast、异步与进度事件桥接、Actuator/Micrometer 可观测——同时不破坏现有 API 与测试、不侵蚀 core 的依赖卫生。

探索已定论的关键架构事实（决定了改动的最小面）：

- L1 引擎 `FfmpegExecutor(FfmpegEnvironment env)` **本就是实例化的**，天然是构造器注入的 bean。
- `FfmpegBinaries.of(ffmpeg, ffprobe)` 与 `FfmpegEnvironment`(record) **已支持显式路径**；`FfmpegEnvironment.detect()`/`locate()` 提供 PATH 发现。
- `MediaProbe.probe(Path file, String ffprobeBinary)` **已存在**，可直接走配置的 ffprobe——**无需改 MediaProbe**。
- 唯一硬绑静态单例的是门面：`Ffmpeg` 的 8 个 public static 方法 + package-private `FacadeSupport.execute` 写死 `FfmpegEnvironment.shared()`；`FacadeSupport` 是 package-private final，别模块够不着——故实例门面必须落在 core。

因此本 change 的形态是：**core 侧一次小重构（补实例门面 + 拆掉硬编码 shared()），Spring 侧一套标准的 autoconfigure/starter 两模块**。

## Goals / Non-Goals

**Goals:**
- core 新增实例门面 `FfmpegClient(FfmpegEnvironment, RunOptions)`，8 门面为实例方法；静态 `Ffmpeg` 委托默认实例，行为与现有测试保持不变。
- 单模块升多模块：parent pom 聚合 + dependencyManagement，core 坐标不变，新增 `-spring-boot-autoconfigure` 与 `-spring-boot-starter`。
- `@ConfigurationProperties(prefix="ffmpeg4j")` 外部化：显式路径优先否则 PATH 发现、fail-fast 开关、RunOptions（超时/取消/终止宽限）、async、min-version-check。
- 条件装配：env/executor/client bean 由 autoconfigure 提供，`@ConditionalOnMissingBean` 允许用户全量覆盖；启动可选 fail-fast 校验二进制与版本/能力。
- 进度事件经 Spring `TaskExecutor` 派发（绝不占 pump 线程），异步 `runAsync`/`CompletableFuture` 暴露。
- Actuator `HealthIndicator`/`InfoContributor` + Micrometer 指标，全部 classpath 条件装配。

**Non-Goals:**
- **不支持 Spring Boot 2.x / javax.***：基线锁 Boot 3.x（jakarta.*、Java 17），与 core 对齐，不做双轨兼容。
- **v1 不做响应式**：WebFlux `Mono`/`Flux` 不在范围，`runAsync`/`CompletableFuture` 逃生舱够用。
- **不改 core 语义**：不动 `MediaProbe`（已有显式重载）、不动 L2 编译器/`buildXxx` 纯函数、不动 `FfmpegExecutor` 的 env 消费方式；现有全部单测须保持全绿。
- 不引入重型依赖进 core；不提供 GraalVM native-image 专项支持（留待后续）。

## Decisions

### D1: 方案 A——引擎/构建已实例化，只补实例门面 FfmpegClient + 静态委托（core 小重构）
引擎 `FfmpegExecutor` 与 `buildXxx` 纯函数早已实例化/无状态，唯一挡路的是静态门面 `Ffmpeg` 与写死 `shared()` 的 `FacadeSupport`。故不重写 core，只新增 `FfmpegClient(env, defaultRunOptions)` 承载 8 门面实例方法，并给 `FacadeSupport.execute` 增 env 形参版本。备选「让 starter 直接调静态 `Ffmpeg`」被否：env 无法注入、`FacadeSupport` 包私有别模块够不着，等于没做 DI。

### D2: 单模块 → 多模块（parent pom + core + autoconfigure + starter），core 坐标不变
Spring 集成必须与 core 依赖解耦，单模块无法既「core 零重型依赖」又「autoconfigure 依赖 spring」。故升为 parent pom（packaging=pom）聚合三模块并统一 dependencyManagement。约束：core 的 GAV `io.github.pandong2015:ffmpeg4j-core` **不变**，老用户升级无感；新增两个 starter/autoconfigure 坐标。代价：发布流程从单 artifact 变为多 artifact（见 Risks）。

### D3: -autoconfigure 与 -starter 分离（starter 仅聚合依赖，遵 Spring 官方约定）
遵循 Spring 官方 starter 约定：`-autoconfigure` 放 `@AutoConfiguration`/`@ConfigurationProperties`/条件装配的全部实现代码；`-starter` 是**空 pom**，只聚合 autoconfigure + core + 常用可选依赖，方便用户「一个坐标全带齐」。分离的价值：需要精细控制依赖的用户可只依赖 autoconfigure；starter 承担「便利聚合」而不塞实现，符合生态惯例与工具（如 spring-boot-configuration-processor）预期。

### D4: Spring Boot 3.x / jakarta / Java 17 基线，明确不支持 Boot 2.x
core 已是 Java 17，Boot 3.x 同样要求 Java 17 且全面 jakarta.* 命名空间，二者天然对齐。锁定 3.x 单轨：不为 Boot 2.x（javax.*、EOL 中）做兼容分支——双轨会污染 import、翻倍测试矩阵、拖慢演进，收益极低。用户仍在 Boot 2.x 者可直接用 core 手工装配 `FfmpegClient`，不被 starter 阻塞。

### D5: @ConfigurationProperties(prefix=ffmpeg4j) 映射发现顺序 + RunOptions
以 `prefix="ffmpeg4j"` 的不可变 properties 承接全部外部化配置：`ffmpeg-path`/`ffprobe-path`（非空走 `FfmpegBinaries.of`，空则 `locate` PATH 发现，映射「显式优先否则发现」的既有语义）、`fail-fast`、`default-timeout`/`cancel-grace-period`/`terminate-grace-period`（Duration，映射 `RunOptions`）、`async.use-spring-executor`、`min-version-check`。用 properties 而非散落 `@Value`：类型安全、可生成配置元数据、IDE 可补全，与 core 不可变风格一致。

### D6: 启动 fail-fast 校验默认开、可关，复用 FfmpegEnvironment.detect() + 版本/能力校验
`fail-fast=true`（默认）时，autoconfigure 在 bean 初始化即调 `FfmpegEnvironment.detect()`：二进制缺失直接启动失败（快速暴露部署环境问题，胜过运行到第一次调用才崩）；版本 < 4.2 **仅告警不硬失败**，沿用 core 语义（真实特性 floor 更低）。可置 `fail-fast=false` 关闭（如镜像构建期无 ffmpeg、运行期才挂载）。复用 core 既有 detect/版本探测，不在 Spring 侧另造校验逻辑。

### D7: 进度事件必须经 TaskExecutor 派发，不占 pump 线程（呼应 core 回调非阻塞铁律）
core 铁律：进度回调默认在 pump 线程触发，重活会阻塞流排空进而死锁。故 autoconfigure 把 `callbackExecutor` 接到 Spring `TaskExecutor`（`async.use-spring-executor=true` 时），进度事件（`FfmpegProgressEvent`，可选经 `ApplicationEventPublisher`）一律经 executor 异步派发，**绝不在 pump 线程执行用户监听器**。这把 core 的「安全默认 + executor 逃生舱」提升为 Spring 应用的默认姿势，用户监听器再重也不威胁子进程。

### D8: Actuator + Micrometer 均 optional 依赖 + classpath 条件装配
可观测组件（`HealthIndicator`/`InfoContributor`/`MeterBinder`）对 actuator、micrometer 类型有编译期引用，但不应强制未用可观测的用户拉入这些依赖。故在 autoconfigure 中把二者声明 `optional=true`，并用 `@ConditionalOnClass` 守卫：classpath 有 actuator 才装 Health/Info，有 micrometer 才装指标。无这些依赖的应用照常获得 env/executor/client bean，零额外负担。

### D9: core 零重型依赖不变；spring 相关依赖只落在 autoconfigure/starter
core 的核心卖点之一是零重型运行时依赖（JSON 走自研微解析器），本 change 必须守住：所有 spring-boot-autoconfigure、actuator、micrometer 依赖**只落在 autoconfigure/starter 模块**，core 的 pom 依赖面不新增一行。这保证不用 Spring 的纯 Java 用户升级到多模块后依赖树无变化，也让 core 可继续被任意框架（或无框架）复用。

### D10: 静态 Ffmpeg 向后兼容（委托默认实例），不破坏现有 API 与测试
`Ffmpeg` 的 8 个 static 方法保留签名，内部改为委托一个默认实例 `FfmpegClient(FfmpegEnvironment.shared(), RunOptions.defaults())`。对既有调用方与现有全部单测：签名不变、行为不变（仍走 shared env 与默认 options），测试须保持全绿。这让「引入实例门面」成为**纯增量**：老用户零改动，Spring 用户拿注入版 `FfmpegClient`，二者共享同一套 `buildXxx`/`execute` 实现。

### D11: 响应式 v1 不做（runAsync/CompletableFuture 逃生舱够用）
不引入 WebFlux/Reactor，不提供 `Mono<...>`/`Flux<FfmpegProgressEvent>` API。理由：ffmpeg 任务是粗粒度长任务，`runAsync`/`CompletableFuture` + 事件派发已覆盖异步与进度推送需求；引入 Reactor 会给 autoconfigure 增一层重依赖与背压语义复杂度，性价比低。留作未来版本，接缝上不设阻碍（事件已经过 executor，后续可加 reactive 适配层）。

### D12: 测试策略——ApplicationContextRunner 切片 + @SpringBootTest；真实 ffmpeg 用 assumeTrue 守卫
autoconfigure 的条件装配、bean 覆盖（`@ConditionalOnMissingBean`）、properties 绑定、fail-fast 行为，用 `ApplicationContextRunner` 切片测试——无需起完整 context、可精确断言「有/无某 bean」「某 classpath 下装/不装」。端到端冒烟用 `@SpringBootTest`。凡真正 fork ffmpeg 的用例，一律 `assumeTrue(commandExists("ffmpeg"), ...)` 守卫，缺二进制则跳过而非失败——与 core 现有 CI 约定一致，保证无 ffmpeg 环境仍全绿。

### D13: 指标口径——失败按 `ErrorPattern` 打 tag + 暴露「运行中子进程数」gauge
门面计时 `Timer` 按操作类型（transcode/probe…）与成功/失败结果打 tag；失败计数按 core 结构化错误的 `ErrorPattern`（`reason`）类别打 tag——类别有限、基数可控，不会指标爆炸，且让失败可按根因归因。额外注册一个 `Gauge` 反映「当前运行中的 ffmpeg 子进程数」，由引擎/门面维护的活跃计数驱动，供容量与背压观测。

### D14: Health 降级——缺 libass/libfreetype 判 DOWN
把构建开关缺失视为「工具链不完整」：`HealthIndicator` 在 `libass` 或 `libfreetype` 任一缺失时报 `DOWN`（`details` 指明缺哪个），而非仅二进制缺失才 `DOWN`。取舍：宁可对不烧字幕/不打字的应用偏保守，也让「环境缺能力」在健康探针即暴露，而非等到调 `burnSubtitles`/`drawText` 才炸。版本 < 4.2 仍仅告警不判 `DOWN`（沿用 core 语义）。

### D15: 进度事件双通道可切
进度既可经 `ApplicationEventPublisher` 广播 `FfmpegProgressEvent`（松耦合、多订阅者），也可直投容器中注入的 `FfmpegProgressListener` bean（聚焦、低开销），由 `ffmpeg4j.async.progress-channel`（`application-event` / `listener` / `both`，默认 `application-event`）切换。两条通道**都经 `TaskExecutor` 派发**，绝不占 pump 线程（呼应 D7）。

### D16: v1 API 面仅 `FfmpegClient`
不提供 `FfmpegTemplate` 别名或 `@Ffmpeg4j` 注解糖。`FfmpegClient` bean 注入已足够直观，多一层 API 面/注解只增维护与认知负担、收益有限。留待后续按真实反馈再定，接缝上不设阻碍。

## Risks / Trade-offs

- [单模块升多模块的发布复杂度] → 从单 artifact 变三 artifact（parent + autoconfigure + starter，core 坐标不变），发布脚本/CI 需相应调整；以 parent 统一 version 与 dependencyManagement 收敛复杂度，core GAV 保持不变以免波及老用户。
- [静态委托的默认实例 vs 配置注入的 env 的边界混淆] → 静态 `Ffmpeg` 永远走 `FfmpegEnvironment.shared()`，不受 Spring properties 影响；被 Spring 管理的行为只经注入的 `FfmpegClient`。文档须明确「配置只作用于注入的 client bean，静态门面保持全局默认」，避免用户误以为设了 `ffmpeg4j.ffmpeg-path` 就改变了 `Ffmpeg.transcode(...)`。
- [Boot 版本锁定 3.x 排除 2.x 用户] → 换取单轨维护与 jakarta 对齐；Boot 2.x 用户可退回 core 手工装配 `FfmpegClient`，非硬阻断。
- [optional 依赖的条件装配易漏配] → 若 `@ConditionalOnClass` 守卫不严，未装 actuator 的应用可能因缺类而启动失败；以 `ApplicationContextRunner` 在「有/无 actuator、micrometer」两组 classpath 下分别断言装配结果来兜住。
- [进度事件经 executor 派发引入乱序/延迟] → 相对「回调阻塞 pump 线程致死锁」这是正确取舍；事件本就是尽力推送的进度快照，消费方不应假设严格时序。

## Open Questions

本轮 4 个开放问题已由维护者拍板，见 **D13–D16**（指标口径 / Health 降级 / 进度双通道 / v1 API 面）。以下为纯实现期细节，不影响架构：

- `HealthIndicator` 是否缓存版本/能力探测结果并设 TTL，以免每次健康探针都 fork 一次 ffmpeg（倾向带短 TTL 缓存）。
- 具体 metric 名与 tag key 命名（如 `ffmpeg4j.facade.duration` timer、`operation`/`result`/`error.pattern` tag、`ffmpeg4j.subprocess.active` gauge）。
- `FfmpegProgressListener` 的确切函数式签名与任务标识（jobId）如何随进度快照一并传递。
