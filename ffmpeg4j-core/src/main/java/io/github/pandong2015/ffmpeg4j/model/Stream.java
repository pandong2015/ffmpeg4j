package io.github.pandong2015.ffmpeg4j.model;

/**
 * 不可变「流即值」：一路媒体流的值语义句柄。
 *
 * <p>滤镜是纯函数 {@code Stream -> Stream}：接收流、返回新流，绝不修改既有流。用户永不书写
 * ffmpeg 的 pad 名（如 {@code [0:v]}/{@code [out]}）。同一个 {@code Stream} 可被消费任意次，
 * 底层 pad 的一次性约束由编译器自动插入 {@code split}/{@code asplit} 处理（见 command-compiler）。
 *
 * <p>为在 <em>javac 编译期</em>拦截类型错配，密封为 {@link VideoStream}/{@link AudioStream}/
 * {@link SubtitleStream} 三个子类型，curated 滤镜签名按子类型收窄；{@link #mediaType()} 枚举
 * 退居 {@code rawFilter} 产物的运行时兜底。
 */
public sealed interface Stream permits VideoStream, AudioStream, SubtitleStream {

    /** 该流的媒体类型。 */
    MediaType mediaType();

    /** 该流的来源（内部 API）：输入文件的流或滤镜输出。 */
    Origin origin();
}
