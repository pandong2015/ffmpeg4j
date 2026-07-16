package io.github.pandong2015.ffmpeg4j.facade;

import java.util.ArrayList;
import java.util.List;

/**
 * ABR 默认码率梯与「按源高度裁剪」的纯逻辑（无 probe 副作用——源高度由门面注入）。
 *
 * <p>{@link #defaults()} 内置四档 <b>1080p@5M / 720p@3M / 480p@1.5M / 360p@800k</b>
 * （{@code maxrate≈1.07×}、{@code bufsize≈1.5×} 由 {@link HlsVariant} 派生）。
 * {@link #cropToSourceHeight(int)} 剔除高于源的放大档（ABR 恒转码，不放大原则）。
 */
public final class HlsLadder {

    private HlsLadder() {
    }

    /** 默认梯（高→低）：1080p@5M / 720p@3M / 480p@1.5M / 360p@800k。返回不可变副本。 */
    public static List<HlsVariant> defaults() {
        return List.of(
                HlsVariant.of(1080, "5000k"),
                HlsVariant.of(720, "3000k"),
                HlsVariant.of(480, "1500k"),
                HlsVariant.of(360, "800k"));
    }

    /**
     * 按源高度裁剪默认梯（<b>仅用于默认梯</b>；显式梯不裁，见 {@link HlsAbrOptions#variants}）。
     *
     * <p>规则：剔除 {@code height>sourceHeight} 的放大档（不放大）。<b>极小源兜底</b>：若所有默认档都高于源，
     * 则以<em>源高度自身（取偶）</em>生成单档（复用最低档 {@code 360p} 的视频码率 {@code 800k}），而非强留会放大的 360 档。
     *
     * @param sourceHeight 源视频高度（像素，须为正——无视频轨/probe 失败应由门面提前 fail-fast）
     */
    public static List<HlsVariant> cropToSourceHeight(int sourceHeight) {
        if (sourceHeight <= 0) {
            throw new IllegalArgumentException("sourceHeight 须为正数，实际 " + sourceHeight);
        }
        List<HlsVariant> defaults = defaults();
        List<HlsVariant> kept = new ArrayList<>();
        for (HlsVariant v : defaults) {
            if (v.height() <= sourceHeight) {
                kept.add(v);
            }
        }
        if (!kept.isEmpty()) {
            return List.copyOf(kept);
        }
        // 极小源：所有默认档都放大 → 单档源高（取偶）、复用最低档码率，不放大。
        int even = sourceHeight - (sourceHeight % 2);
        String lowestBitrate = defaults.get(defaults.size() - 1).videoBitrate();
        return List.of(HlsVariant.of(even, lowestBitrate));
    }
}
