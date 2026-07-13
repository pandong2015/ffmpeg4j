package io.github.pandong2015.ffmpeg4j.model;

/**
 * 音频汇聚归一化目标：采样率、采样格式、声道布局。
 *
 * @param sampleRate    目标采样率（Hz，如 {@code 48000}）
 * @param sampleFormat  目标采样格式（如 {@code fltp}）
 * @param channelLayout 目标声道布局（如 {@code stereo}）
 */
public record AudioNormTarget(int sampleRate, String sampleFormat, String channelLayout) {

    /** 最常用的立体声 48kHz 目标：{@code 48000 / fltp / stereo}。 */
    public static AudioNormTarget stereo48k() {
        return new AudioNormTarget(48000, "fltp", "stereo");
    }
}
