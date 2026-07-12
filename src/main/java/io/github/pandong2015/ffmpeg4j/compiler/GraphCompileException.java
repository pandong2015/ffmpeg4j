package io.github.pandong2015.ffmpeg4j.compiler;

/** 图编译期校验失败（悬空 pad、字幕流扇出、类型不匹配等），在生成 argv 之前抛出。 */
public class GraphCompileException extends RuntimeException {
    public GraphCompileException(String message) {
        super(message);
    }
}
