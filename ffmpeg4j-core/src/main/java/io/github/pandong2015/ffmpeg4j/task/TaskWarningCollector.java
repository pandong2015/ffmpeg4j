package io.github.pandong2015.ffmpeg4j.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 单任务警告收集上下文。
 *
 * <p>门面任务的构建、探测与执行目前位于同一工作线程，故用线程局部 scope 把底层可靠降级点汇聚到
 * {@link TaskReport}，无需把收集器贯穿所有既有公共方法。scope 外调用 {@link #add} 为无操作，
 * 从而保持旧门面 API 行为不变。
 */
public final class TaskWarningCollector implements AutoCloseable {

    private static final ThreadLocal<TaskWarningCollector> CURRENT = new ThreadLocal<>();

    private final TaskWarningCollector previous;
    private final List<FfmpegWarning> warnings = new ArrayList<>();
    private boolean closed;

    private TaskWarningCollector(TaskWarningCollector previous) {
        this.previous = previous;
    }

    /** 打开当前线程的任务警告 scope。 */
    public static TaskWarningCollector open() {
        TaskWarningCollector collector = new TaskWarningCollector(CURRENT.get());
        CURRENT.set(collector);
        return collector;
    }

    /** 若当前处于任务 scope，则按发生顺序加入警告；否则保持旧 API 的无操作语义。 */
    public static void add(FfmpegWarning warning) {
        TaskWarningCollector collector = CURRENT.get();
        if (collector != null) {
            collector.warnings.add(Objects.requireNonNull(warning, "warning"));
        }
    }

    /** 返回当前 scope 快照；没有任务 scope 时返回空列表。 */
    public static List<FfmpegWarning> currentSnapshot() {
        TaskWarningCollector collector = CURRENT.get();
        return collector == null ? List.of() : collector.snapshot();
    }

    /** 返回当前已收集警告的不可变顺序快照。 */
    public List<FfmpegWarning> snapshot() {
        return List.copyOf(warnings);
    }

    /** 恢复嵌套 scope 之前的线程上下文。 */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            CURRENT.set(previous);
            if (previous == null) {
                CURRENT.remove();
            }
        }
    }
}
