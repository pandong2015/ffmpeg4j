## ADDED Requirements

### Requirement: 实例化门面 FfmpegClient

core SHALL 新增一个可实例化的门面 `FfmpegClient(FfmpegEnvironment env, RunOptions defaultRunOptions)`，将 8 个高层门面（transcode、remux、clip、extractAudio、thumbnail、concat、burnSubtitles、probe）承载为实例方法，使 Spring 容器可将其作为普通 bean 注入。实例门面 MUST 使用注入的 `FfmpegEnvironment` 执行子进程（经 `FacadeSupport.execute(cmd, env, ro)`），MUST NOT 硬编码 `FfmpegEnvironment.shared()`。`probe` MUST 走该 env 配置的 ffprobe——即以 `MediaProbe.probe(file, env.binaries().ffprobeCommand())` 探测，而非 PATH 默认。每个门面的调用点 `XxxOptions`（若给定）MUST 与构造时的 `defaultRunOptions` 合并，调用点显式设定的字段覆盖默认值。`buildXxx` 纯函数与 `FfmpegExecutor` 行为 MUST NOT 因本次新增而改变。

#### Scenario: 显式配置的二进制路径生效

- **WHEN** 以 `FfmpegClient(env, ro)` 构造，其中 `env` 的 `FfmpegBinaries` 指向显式 ffmpeg/ffprobe 路径
- **THEN** 该实例的每个门面执行时使用注入 env 的 ffmpeg 二进制，而非 PATH 发现的默认二进制
- **AND** 其 `probe(file)` 经 `MediaProbe.probe(file, env.binaries().ffprobeCommand())` 走同一 env 配置的 ffprobe

#### Scenario: 调用点 Options 与默认 RunOptions 合并

- **WHEN** 实例以某 `defaultRunOptions`（如含默认 `timeout`）构造，随后某次门面调用传入仅设定 `overwrite=true` 的调用点 Options
- **THEN** 该次执行的生效 `RunOptions` 由二者合并——调用点显式设定的字段覆盖默认，其余字段沿用 `defaultRunOptions`
- **AND** 未传调用点 Options 的其它调用继续沿用完整的 `defaultRunOptions`

### Requirement: 静态门面向后兼容

静态门面类 `Ffmpeg` 的 8 个 public static 方法 MUST 委托给一个「默认实例」`FfmpegClient`，该默认实例以 `FfmpegEnvironment.shared()` 与 `RunOptions.defaults()` 构造，从而在未显式配置任何环境时保持与现状完全一致的行为。此重构 MUST NOT 改变 `Ffmpeg` 现有方法签名与可观察行为，现有全部单测 MUST 保持全绿。`FacadeSupport.execute` 在新增带 env 形参的重载 `execute(cmd, env, ro)` 后，其去硬编码化 MUST NOT 影响静态路径的产出 argv 与执行语义。

#### Scenario: 未配置时委托默认实例

- **WHEN** 用户直接调用静态 `Ffmpeg.transcode(...)`（未经任何 Spring 或显式实例配置）
- **THEN** 调用被委托给以 `FfmpegEnvironment.shared()` 与 `RunOptions.defaults()` 构造的默认 `FfmpegClient`
- **AND** 其产出的 argv 与执行行为与重构前逐字节一致，`Ffmpeg` 的公共方法签名不变

#### Scenario: 现有单测在重构后保持全绿

- **WHEN** 在完成 FfmpegClient + 静态委托重构后运行既有测试套件
- **THEN** 依赖静态 `Ffmpeg.xxx` 与 `FacadeSupport` 的全部单测（含 argv 精确断言与集成守卫测试）无需修改即全部通过
- **AND** 未观察到任何面向用户的 API 破坏或行为回归
