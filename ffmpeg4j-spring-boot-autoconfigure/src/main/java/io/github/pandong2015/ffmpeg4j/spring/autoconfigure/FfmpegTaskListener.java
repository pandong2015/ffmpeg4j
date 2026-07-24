package io.github.pandong2015.ffmpeg4j.spring.autoconfigure;

/**
 * 任务生命周期直投监听器；异常由桥接层隔离，不会改变媒体任务结果。
 */
@FunctionalInterface
public interface FfmpegTaskListener {

    /** 接收一条有序的任务生命周期事件。 */
    void onTaskEvent(FfmpegTaskEvent event);
}
