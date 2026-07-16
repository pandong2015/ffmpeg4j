package io.github.pandong2015.ffmpeg4j.facade;

import java.nio.file.Path;
import java.util.List;

/**
 * agroup 共享音频的独立 rendition 结果（<b>专属 record</b>，不复用视频 {@link HlsVariantResult} 的
 * 分辨率/带宽字段）——master 的 {@code #EXT-X-MEDIA:TYPE=AUDIO} 行本就无 {@code RESOLUTION}/{@code BANDWIDTH}。
 *
 * <p>{@code name} 取自 <b>URI 目录名</b>（如 {@code audio}），而非 ffmpeg 会加后缀改写的 {@code NAME} 属性
 * （实测 {@code audio}→{@code audio_2}）。
 *
 * @param name     rendition 目录名（取自 master {@code #EXT-X-MEDIA} 的 {@code URI} 目录段）
 * @param groupId  {@code GROUP-ID}（如 {@code group_aud}）
 * @param playlist rendition media playlist
 * @param segments 有序分段路径清单（源自 m3u8）
 */
public record HlsAudioRendition(String name, String groupId, Path playlist, List<Path> segments) {

    public HlsAudioRendition {
        segments = List.copyOf(segments);
    }
}
