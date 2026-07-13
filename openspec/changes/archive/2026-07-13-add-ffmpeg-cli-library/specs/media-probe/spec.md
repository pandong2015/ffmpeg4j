## ADDED Requirements

### Requirement: 基于 ffprobe 的媒体元数据读取
库 SHALL 提供通过 `ffprobe` 读取媒体元数据的能力，至少暴露容器级信息（格式、时长、总码率）与每条流的信息（流类型 video/audio/subtitle、编解码器、分辨率、帧率、采样率、声道等）。

#### Scenario: 读取含音视频的文件元数据
- **WHEN** 用户对一个含视频轨与音频轨的文件调用 probe
- **THEN** 返回结构化结果，包含时长、各流的类型与编解码器等字段

#### Scenario: 探测识别字幕流
- **WHEN** 用户 probe 一个内嵌字幕轨的容器
- **THEN** 结果中该轨的流类型被标识为 subtitle

### Requirement: 轻量 JSON 解析与依赖约束
`ffprobe` 输出的 JSON MUST 使用极轻量第三方依赖（如 minimal-json/org.json）或自研微型 recursive-descent 解析器解析；`ffmpeg4j-core` MUST NOT 引入重型 JSON 库（如 Jackson）。JDK 不含公开受支持的 JSON 解析 API，故不作为选项。

#### Scenario: core 不含重型 JSON 依赖
- **WHEN** 检视 `ffmpeg4j-core` 的依赖树
- **THEN** 不存在 Jackson 等重型 JSON 库

### Requirement: probe 失败的结构化错误
当 `ffprobe` 无法解析目标（文件不存在或非法媒体）时，probe MUST 抛出可诊断错误，携带 `ffprobe` 的失败信息。

#### Scenario: 对不存在的文件 probe
- **WHEN** 用户对一个不存在的路径调用 probe
- **THEN** 抛出清晰错误说明文件不可读/不存在，而非返回空结果

#### Scenario: 对非法媒体 probe
- **WHEN** 用户对一个内容损坏/非媒体文件调用 probe
- **THEN** 抛出携带 `ffprobe` 失败信息的可诊断错误
