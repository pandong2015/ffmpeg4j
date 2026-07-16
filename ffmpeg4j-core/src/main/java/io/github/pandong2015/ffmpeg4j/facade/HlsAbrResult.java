package io.github.pandong2015.ffmpeg4j.facade;

import java.nio.file.Path;
import java.util.List;

import io.github.pandong2015.ffmpeg4j.engine.RunResult;

/**
 * {@link Ffmpeg#hlsAbr} 的结果——一入 N 档产 master.m3u8 + 每档 media playlist + 各档段（+ agroup 时单列
 * 音频 rendition + 可选密钥），故有意偏离全家族的 {@link RunResult} 返回约定。
 *
 * <p>{@code variants} 按 {@code var_stream_map} 顺序；各字段解析自 ffmpeg 写出的 master 与各档 m3u8（非 glob）。
 *
 * @param master         master 播放列表（{@code outDir/<masterPlaylistName>}）
 * @param variants       各视频档结果（按 {@code var_stream_map} 顺序）
 * @param audioRendition agroup 共享音频 rendition；非 agroup（每档独立音频）为 {@code null}
 * @param keyFile        AES 密钥文件（{@code outDir/key/enc.key}）；未加密为 {@code null}
 * @param run            内嵌的执行结果（退出码/进度/命令）
 */
public record HlsAbrResult(Path master, List<HlsVariantResult> variants, HlsAudioRendition audioRendition,
                           Path keyFile, RunResult run) {

    public HlsAbrResult {
        variants = List.copyOf(variants);
    }
}
