package io.github.pandong2015.ffmpeg4j.task;

/** 媒体任务的单调生命周期状态。 */
public enum TaskStatus {
    /** 已提交但尚未开始执行。 */
    SUBMITTED,
    /** 已开始执行。 */
    RUNNING,
    /** 已收到取消请求，正在收尾。 */
    CANCELLING,
    /** 成功完成。 */
    COMPLETED,
    /** 执行失败。 */
    FAILED,
    /** 已取消。 */
    CANCELLED;

    /** 是否为不可再迁移的终态。 */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
