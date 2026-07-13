package io.github.pandong2015.ffmpeg4j.env;

import io.github.pandong2015.ffmpeg4j.FfmpegException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * ffmpeg 构建能力探测结果：版本号 + 关键构建开关（libass / libfreetype）。
 *
 * <p>能力来源于两路信号并取<em>并集</em>，以提高健壮性：
 * <ol>
 *   <li>{@code ffmpeg -version} 的 {@code configuration:} 行是否含 {@code --enable-libass} /
 *       {@code --enable-libfreetype}；</li>
 *   <li>{@code ffmpeg -filters} 列表中是否存在对应滤镜（{@code subtitles}/{@code ass} → libass；
 *       {@code drawtext} → libfreetype）。</li>
 * </ol>
 *
 * <p>当调用方请求依赖某开关的特性而该开关缺失时，{@link #requireLibass()} /
 * {@link #requireLibfreetype()} 会在<em>下发命令前</em>抛出可诊断异常并点名缺失的构建标志，
 * 而不是让 ffmpeg 在运行期抛出含糊的「No such filter」。
 */
public final class FfmpegCapabilities {

    private static final Logger LOG = Logger.getLogger(FfmpegCapabilities.class.getName());

    private static final String FLAG_LIBASS = "--enable-libass";
    private static final String FLAG_LIBFREETYPE = "--enable-libfreetype";

    private final FfmpegVersion version;
    private final boolean hasLibass;
    private final boolean hasLibfreetype;

    FfmpegCapabilities(FfmpegVersion version, boolean hasLibass, boolean hasLibfreetype) {
        this.version = version;
        this.hasLibass = hasLibass;
        this.hasLibfreetype = hasLibfreetype;
    }

    /**
     * 探测给定二进制的版本与能力。
     *
     * <p>若解析出的版本低于 {@link FfmpegVersion#MIN_FFMPEG_VERSION}，仅记录一条 WARNING 并继续
     * （<b>不</b>硬失败）。二进制缺失属于硬错误，应在 {@link FfmpegBinaries#locate()} 阶段已抛出。
     */
    public static FfmpegCapabilities probe(FfmpegBinaries binaries) {
        ProcRunner.Result versionResult = ProcRunner.run(binaries.ffmpegArgv("-hide_banner", "-version"));
        FfmpegVersion version = FfmpegVersion.parse(versionResult.output());

        if (version.isBelowMinimum()) {
            LOG.warning("检测到 ffmpeg 版本 " + version + " 低于建议的最低版本 "
                    + FfmpegVersion.MIN_FFMPEG_VERSION
                    + "；部分滤镜/特性可能不可用，但仍继续运行（真实功能下限约 2.3）。");
        } else if (!version.isKnown()) {
            LOG.fine("无法从 ffmpeg 输出解析出数字版本（可能为 git 快照），按不低于最低版本处理。");
        }

        String config = versionResult.output();
        Set<String> filterNames = fetchFilterNames(binaries);

        boolean libass = config.contains(FLAG_LIBASS)
                || filterNames.contains("subtitles") || filterNames.contains("ass");
        boolean libfreetype = config.contains(FLAG_LIBFREETYPE)
                || filterNames.contains("drawtext");

        return new FfmpegCapabilities(version, libass, libfreetype);
    }

    private static Set<String> fetchFilterNames(FfmpegBinaries binaries) {
        try {
            ProcRunner.Result filters = ProcRunner.run(binaries.ffmpegArgv("-hide_banner", "-filters"));
            return parseFilterNames(filters.output());
        } catch (FfmpegException e) {
            // -filters 失败不应让整个探测崩溃；退回仅依赖 configuration 行的信号。
            LOG.fine("执行 ffmpeg -filters 失败，能力探测退回仅使用 configuration 行: " + e.getMessage());
            return Set.of();
        }
    }

    /**
     * 从 {@code ffmpeg -filters} 输出中解析出滤镜名集合。
     *
     * <p>数据行形如 {@code " TS aap               AA->A      Apply ..."}：以「输入->输出」token
     * （含 {@code ->}）定位，其前一 token 即滤镜名。图例/表头行不含 {@code ->}，天然被跳过。
     */
    static Set<String> parseFilterNames(String filtersOutput) {
        Set<String> names = new HashSet<>();
        if (filtersOutput == null || filtersOutput.isBlank()) {
            return names;
        }
        for (String line : filtersOutput.split("\\R")) {
            String[] tokens = line.trim().split("\\s+");
            if (tokens.length < 3) {
                continue;
            }
            for (int i = 1; i < tokens.length; i++) {
                if (tokens[i].contains("->")) {
                    names.add(tokens[i - 1]);
                    break;
                }
            }
        }
        return names;
    }

    /** 解析出的 ffmpeg 版本。 */
    public FfmpegVersion version() {
        return version;
    }

    /** 是否具备 libass（{@code subtitles}/{@code ass} 滤镜，用于 burnSubtitles/burnAss）。 */
    public boolean hasLibass() {
        return hasLibass;
    }

    /** 是否具备 libfreetype（{@code drawtext} 滤镜，用于 drawText）。 */
    public boolean hasLibfreetype() {
        return hasLibfreetype;
    }

    /**
     * 前置校验：调用方要用 burnSubtitles/burnAss，但当前构建缺少 libass 时抛出可诊断异常。
     *
     * @throws FfmpegException 当 {@link #hasLibass()} 为 {@code false} 时，消息点名 {@code --enable-libass}。
     */
    public void requireLibass() {
        if (!hasLibass) {
            throw new FfmpegException(
                    "当前 ffmpeg 构建缺少字幕烧录支持：configuration 未包含 " + FLAG_LIBASS
                            + "，且 -filters 列表中不存在 subtitles/ass 滤镜。"
                            + "burnSubtitles / burnAss 不可用。请改用带 " + FLAG_LIBASS + " 的 ffmpeg 构建。",
                    null);
        }
    }

    /**
     * 前置校验：调用方要用 drawText，但当前构建缺少 libfreetype 时抛出可诊断异常。
     *
     * @throws FfmpegException 当 {@link #hasLibfreetype()} 为 {@code false} 时，消息点名 {@code --enable-libfreetype}。
     */
    public void requireLibfreetype() {
        if (!hasLibfreetype) {
            throw new FfmpegException(
                    "当前 ffmpeg 构建缺少文字绘制支持：configuration 未包含 " + FLAG_LIBFREETYPE
                            + "，且 -filters 列表中不存在 drawtext 滤镜。"
                            + "drawText 不可用。请改用带 " + FLAG_LIBFREETYPE + " 的 ffmpeg 构建。",
                    null);
        }
    }

    @Override
    public String toString() {
        return "FfmpegCapabilities{version=" + version
                + ", libass=" + hasLibass
                + ", libfreetype=" + hasLibfreetype + '}';
    }

    /** 供测试/诊断使用：直接由已知信号构造，绕过进程探测。 */
    static FfmpegCapabilities fromSignals(FfmpegVersion version, String configLine, List<String> filterNames) {
        Set<String> names = new HashSet<>(filterNames);
        boolean libass = (configLine != null && configLine.contains(FLAG_LIBASS))
                || names.contains("subtitles") || names.contains("ass");
        boolean libfreetype = (configLine != null && configLine.contains(FLAG_LIBFREETYPE))
                || names.contains("drawtext");
        return new FfmpegCapabilities(version, libass, libfreetype);
    }
}
