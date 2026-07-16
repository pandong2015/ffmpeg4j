> 前置：依赖 `add-hls-segment-facade` 已实现（复用其 `HlsKey`/AES 基座、`FacadeSupport.forceKeyFramesArgs`、buildXxx 纯函数 + `FfmpegClient` 异步 try/finally 骨架、m3u8 段解析、失败清理、`ErrorPatterns` HLS/crypto）。

## 1. 码率梯建模

- [x] 1.1 `HlsVariant` `final class` + `of(int height, String videoBitrate)` + wither：`width`(默认 null→`scale=-2:h`)、`maxrate`/`bufsize`(默认由 videoBitrate 派生≈1.07×/1.5×)、`audioBitrate`、`videoCodec`(libx264)/`audioCodec`(aac)、`crf`/`preset`、`name`(**默认 null→目录回退数字索引 0/1/2**)；wither 即时 IAE:`height<=0`/非法 bitrate/**name 含 `%v` 或 var_stream_map 元字符(空格/逗号/冒号/路径分隔符)**
- [x] 1.2 `HlsLadder.defaults()`：**1080p@5M / 720p@3M / 480p@1.5M / 360p@800k**（maxrate/bufsize 派生）
- [x] 1.3 默认梯 probe 裁剪（在**门面**做、buildHlsAbr 保持纯）：`variants` 内部 **null 哨兵**=未设→`ffprobe` 源 height、剔除 `height>源height` 档；**极小源**(全超)→以源高(取偶)单档、不放大；非 null（显式梯）不裁（仅 Javadoc 告警）；**无视频轨/probe 失败→抛可诊断 FfmpegException**
- [x] 1.4 单元测试：HlsVariant wither 校验（含非法 name IAE）；`HlsLadder.defaults()` 档位/派生值精确；裁剪逻辑（源 720→留 720/480/360；源 240→单档 240 不放大；无视频轨→抛错）

## 2. HlsAbrOptions 与 HlsAbrResult

- [x] 2.1 `HlsAbrOptions` `final class` + `defaults()` + wither：`variants`(**内部 null 哨兵**=未设, List.copyOf)、`hlsTime`(double 默认 6.0, `>0`)、`key(HlsKey)`、`audioBitrate`(默认 128k, agroup 用)、`masterPlaylistName`(master.m3u8)、`segmentTemplate`(seg_%d.ts, 含 `%d` 校验)、`startNumber`、`sharedAudio`(默认 true)、`extraOutputArgs`、`onProgress`、`timeout`；**无** videoCodec/audioCodec/alignKeyframes/segmentUriPrefix
- [x] 2.2 `HlsAbrResult` `record`：`Path master`、`List<HlsVariantResult> variants`、可空 `HlsAudioRendition audioRendition`、可空 `Path keyFile`、`RunResult run`；`HlsVariantResult` record：name/width/height/bandwidth/playlist/segments；**`HlsAudioRendition` 专属 record**：name(取自 URI 目录名)/groupId/playlist/segments（不复用视频 record 的分辨率/带宽字段）；仿 StreamInfo 兼容构造器
- [x] 2.3 `toRunOptions()` 复用 `FacadeSupport.runOptions`；单元测试 wither 不可变

## 3. buildHlsAbr（纯函数产 argv，走编译器路线 A）

- [x] 3.1 `FacadeSupport.buildHlsAbr(File in, File outDir, HlsAbrOptions o, Path keyInfoFile)` 纯函数：`VideoStream v = Input.of(in).video()`；每档 `Filters.setsar(Filters.scale(v, width|−2, height))` → 同一 v 被 N 档消费触发编译器自动 `split=N`；音频 agroup 时映射一次、否则每档一次
- [x] 3.2 输出 argv 经 `Output.to(<outDir>/%v/index.m3u8, 各档流按确定顺序).withArgs(...)`（路径用**字面 `%v`**，ffmpeg 按 name:/索引替换）：逐档 `-c:v:N`/`-b:v:N`/`-maxrate:v:N`/`-bufsize:v:N`、音频(agroup 单路 `-c:a:0`/`-b:a`=opts.audioBitrate；非 agroup 带下标 `-c:a:N`/`-b:a:N`)、`var_stream_map` 字符串、`-master_pl_name`、`-f hls -hls_time <fmt(6.0)> -hls_playlist_type vod -hls_list_size 0 -hls_segment_type mpegts -hls_segment_filename <outDir>/%v/seg_%d.ts`、**恒** `-force_key_frames expr:gte(t,n_forced*<hlsTime>)`（复用 forceKeyFramesArgs）、AES `-hls_key_info_file <传入路径>`；**默认不注入 `-hls_base_url`**；`extraOutputArgs` 在后
- [x] 3.3 `var_stream_map` 生成：`name:` **仅显式 name 时下发**（且为预展开具体串、**绝不含 `%v`**），否则省略让 ffmpeg 用数字索引目录；agroup 模式每视频档 `v:i[,name:X],agroup:<gid>` + 末尾 `a:0,agroup:<gid>,name:audio,default:yes`；非 agroup 模式 `v:i,a:i`（`mapped()` 交错 v0,a0,v1,a1）；**输出类型下标**（v:i/a:i）区别于 `-map` 输入引用；facade 以**单一「已解析目录名」列表**驱动 mapped()/var_stream_map/mkdir/结果解析四处同名
- [x] 3.4 build 期 fail-fast：`segmentTemplate` 含 `%d`；N>1 保证 `%v` 在变体目录名或段模板；masterName/变体 name 路径合法；`hlsTime>0`
- [x] 3.5 单元测试（脱进程 argv）：split=N + 逐档 scale+setsar；N×2 `-map` **精确顺序** 与 var_stream_map/`-b:v:N` 下标一一对应；master_pl_name；恒 force_key_frames 一条；VOD 双标签；**无 base_url**；AES key_info_file；agroup vs 每档音频两形态

## 4. 门面接线（写盘/mkdir/解析/清理隔离在 FfmpegClient）

- [x] 4.1 `Ffmpeg.hlsAbr(File in, File outDir)` + `(…, HlsAbrOptions)` 静态两档
- [x] 4.2 `FfmpegClient.hlsAbr` 两档 + `hlsAbrAsync` 两档 → `CompletableFuture<HlsAbrResult>`，复用单码率 D10 带 `try/finally` 异步骨架（不复用 executeAsync）
- [x] 4.3 副作用隔离（门面层）：先 `probeWith(in)` 得源高度→裁剪 variants→算定「已解析目录名」列表；`Files.createDirectories(outDir/<各解析目录名> + outDir/key)`（**与 ffmpeg 实际写入目录同名**，不依赖 ffmpeg %v 自动建）；AES 复用单码率 enc.key/key_info_file 0600 原子创建、唯一命名、finally 删、失败清孤儿 enc.key（覆盖多档段+master 终局态）
- [x] 4.4 组装 `HlsAbrResult`：解析 `master.m3u8` 取各档 `EXT-X-STREAM-INF`（bandwidth/resolution，**引号感知**或定向正则——CODECS 值内含逗号）+ 变体 URI；逐档解析 media playlist 的 `#EXTINF` 段行（有序、非 glob）；agroup 时单列 `HlsAudioRendition`（name 取自 URI 目录、含 groupId）；`run.exitCode≠0` 或任一档 0 段抛 `FfmpegException`
- [x] 4.5 `-map 顺序契约`：加脱进程 argv 单测断言 `GraphCompiler` 按 `mapped()` 顺序发 `-map`、ABR 下标对齐（钉死不变量）

## 5. 端到端与收尾

- [x] 5.1 集成测试（`assumeTrue(commandExists("ffmpeg"))`，`-f lavfi -i testsrc` + sine → `@TempDir`）：默认梯（probe 裁剪后）产 `master.m3u8` + 各档 `<name>/index.m3u8`+段；master 含 N 行 `EXT-X-STREAM-INF`（RESOLUTION 对）
- [x] 5.2 集成测试段 URI：各档 media playlist 段 URI=basename（`seg_0.ts`）、段实存于该档子目录；**无 base_url**
- [x] 5.3 集成测试对齐：各**视频**档 `#EXTINF` 序列逐档一致（force_key_frames 生效；audioRendition 因 aac 帧边界不参与）；另测 N=1 仍产 master、非法 name 抛错、agroup 音频段被加密
- [x] 5.4 集成测试 agroup：只一个音频 rendition 目录、master 含 `#EXT-X-MEDIA:TYPE=AUDIO` + STREAM-INF `AUDIO=`
- [x] 5.5 集成测试 AES：单 `enc.key`、每档含 `#EXT-X-KEY`、master 无 KEY；`.ts` 段被加密
- [x] 5.6 更新 `USAGE.md`/`README.md`：新增 `hlsAbr` 门面（默认梯 + 自定义梯 + AES 示例）；说明与单码率 `hlsSegment` 的分派（N=1 copy vs ABR 转码）；IV=0 单密钥标注
- [x] 5.7 更新 `CHANGELOG.md`（1.4.0 additive：HLS ABR 多码率梯门面）
- [x] 5.8 `mvn -o test` 全 reactor 全绿（无 ffmpeg 经 assumeTrue 跳过集成仍全绿）
- [x] 5.9 `openspec validate add-hls-abr-ladder --strict` 通过

## 6. 备选（按需，不阻断）

- [x] 6.1 `HlsAbrOptions.sharedAudio(false)` 每档独立音频复制模式（默认 agroup 共享）
- [x] 6.2 版本口径：同单码率，文档声明「仅 ffmpeg 8.0.1 实测；4.2 未单独验证」
