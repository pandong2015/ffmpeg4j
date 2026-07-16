package io.github.pandong2015.ffmpeg4j.facade;

import java.nio.file.Path;
import java.util.List;

import io.github.pandong2015.ffmpeg4j.engine.RunResult;

/**
 * {@link Ffmpeg#hlsSegment} 的结果——单码率 VOD HLS 一次产出多文件（playlist + N 段 + 可选密钥文件），
 * 故有意偏离全家族的 {@link RunResult} 返回约定。
 *
 * <p>{@code segments} 由<b>解析写出的 m3u8</b>（{@code #EXTINF} 后的段 URI 行按出现顺序）得到——天然有序、
 * 免疫 {@code -y} 遗留的孤儿段与词典序错序。段数取 {@code segments().size()}，不设冗余计数字段。
 *
 * @param playlist 播放列表（{@code outDir/<playlistName>}）
 * @param segments 按段序号递增有序的分段路径清单（源自 m3u8，非 glob）
 * @param keyFile  AES 密钥文件（{@code outDir/<keyDir>/<keyFileName>}）；未加密为 {@code null}
 * @param run      内嵌的执行结果（退出码/进度/命令）
 */
public record HlsResult(Path playlist, List<Path> segments, Path keyFile, RunResult run) {

    public HlsResult {
        segments = List.copyOf(segments);
    }
}
