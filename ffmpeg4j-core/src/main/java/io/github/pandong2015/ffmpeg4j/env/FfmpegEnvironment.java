package io.github.pandong2015.ffmpeg4j.env;

/**
 * L0 环境层门面：一次性打包「已解析的二进制路径」与「已探测的构建能力」。
 *
 * <p>L1 引擎与模型/门面通常只需 {@link #detect()} 或缓存版的 {@link #shared()} 即可拿到所需的
 * 全部环境信息，并在下发依赖特定构建开关的命令前调用 {@link #requireLibass()} /
 * {@link #requireLibfreetype()} 做前置校验。
 */
public record FfmpegEnvironment(FfmpegBinaries binaries, FfmpegCapabilities capabilities) {

    private static volatile FfmpegEnvironment cached;

    /**
     * 定位二进制并探测能力，返回一份全新的环境快照。
     *
     * @throws io.github.pandong2015.ffmpeg4j.FfmpegException 二进制缺失/不可用时抛出。
     */
    public static FfmpegEnvironment detect() {
        FfmpegBinaries binaries = FfmpegBinaries.locate();
        FfmpegCapabilities capabilities = FfmpegCapabilities.probe(binaries);
        return new FfmpegEnvironment(binaries, capabilities);
    }

    /**
     * 返回进程级缓存的环境（首次调用触发一次 {@link #detect()}）。
     *
     * <p>探测涉及子进程调用，缓存可避免重复开销；如需强制刷新，直接使用 {@link #detect()}。
     */
    public static FfmpegEnvironment shared() {
        FfmpegEnvironment local = cached;
        if (local == null) {
            synchronized (FfmpegEnvironment.class) {
                local = cached;
                if (local == null) {
                    local = detect();
                    cached = local;
                }
            }
        }
        return local;
    }

    /** 解析出的 ffmpeg 版本（{@code capabilities().version()} 的快捷方式）。 */
    public FfmpegVersion version() {
        return capabilities.version();
    }

    /** 前置校验 libass；缺失时抛出可诊断异常。 */
    public void requireLibass() {
        capabilities.requireLibass();
    }

    /** 前置校验 libfreetype；缺失时抛出可诊断异常。 */
    public void requireLibfreetype() {
        capabilities.requireLibfreetype();
    }
}
