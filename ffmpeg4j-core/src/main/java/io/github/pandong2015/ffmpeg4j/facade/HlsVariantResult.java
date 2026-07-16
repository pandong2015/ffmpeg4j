package io.github.pandong2015.ffmpeg4j.facade;

import java.nio.file.Path;
import java.util.List;

/**
 * ABR 单档变体的结果。{@code width}/{@code height}/{@code bandwidth} 解析自 master 的
 * {@code #EXT-X-STREAM-INF}（由 ffmpeg 从实测编码结果自动填），{@code segments} 解析自本档 media playlist 的
 * {@code #EXTINF} 段行（有序、免疫 {@code -y} 孤儿段）。
 *
 * @param name     变体目录名（{@link HlsVariant#name()} 或数字索引 {@code 0/1/2}）
 * @param width    像素宽（解析自 master {@code RESOLUTION}）
 * @param height   像素高（解析自 master {@code RESOLUTION}）
 * @param bandwidth master {@code BANDWIDTH}（bit/s）
 * @param playlist 本档 media playlist（{@code outDir/<name>/index.m3u8}）
 * @param segments 本档有序分段路径清单（源自 m3u8，非 glob）
 */
public record HlsVariantResult(String name, int width, int height, long bandwidth, Path playlist,
                               List<Path> segments) {

    public HlsVariantResult {
        segments = List.copyOf(segments);
    }
}
