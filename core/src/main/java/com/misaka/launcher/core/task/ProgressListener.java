package com.misaka.launcher.core.task;

/**
 * 长耗时任务的统一进度回调。
 * <p>core 层不依赖任何 UI 框架，全部进度与日志通过本接口上抛；
 * 由 app 层负责切回 JavaFX 线程并节流渲染。</p>
 */
public interface ProgressListener {

    /** 无操作实现，便于测试与「静默执行」。 */
    ProgressListener NOOP = new ProgressListener() {
    };

    /**
     * 进入新阶段。
     *
     * @param stage 人类可读的阶段名，例如「下载运行库」「解压主文件」
     */
    default void onStage(String stage) {
    }

    /**
     * 进度更新。
     *
     * @param completed 已完成量（字节或文件数，与 total 同单位）
     * @param total     总量；未知时为 -1
     */
    default void onProgress(long completed, long total) {
    }

    /**
     * 「共几项、已完成几项」的计数进度，用于界面上的 {@code 12/300} 这类显示。
     *
     * <p>与 {@link #onProgress(long, long)} 是两种不同粒度的信息，不可混用：
     * 后者描述「当前这一步完成了多少」（字节），前者描述「一共有多少项（文件）、处理完了几项」。
     * 只有进度条不够直观 —— 用户需要知道「还剩多少文件」，而把字节数显示成进度
     * 只会在 40MB 的文件上给出 {@code 1048576/41943040} 这种没人看得懂的东西。</p>
     *
     * @param completedItems 已完成项数
     * @param totalItems     总项数；未知或 &lt;= 0 时界面不展示
     */
    default void onItemProgress(int completedItems, int totalItems) {
    }

    /** 明细日志（面向用户展示的简短信息，不是诊断日志）。 */
    default void onMessage(String message) {
    }

    /** 是否已被请求取消；实现应保证线程安全或由单一控制线程写入。 */
    default boolean isCancelled() {
        return false;
    }

    /** 便捷方法：取消时抛出 {@link CancelledException}，由调用方在检查点调用。 */
    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancelledException();
        }
    }

    /** 便捷方法：判定总量是否已知。 */
    default boolean hasTotal(long total) {
        return total > 0;
    }
}
