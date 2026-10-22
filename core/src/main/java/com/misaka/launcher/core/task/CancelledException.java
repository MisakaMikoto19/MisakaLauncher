package com.misaka.launcher.core.task;

/**
 * 任务被用户取消时抛出。
 * <p>属于流程控制异常，调用方应静默处理（记录 INFO 级别日志），不应作为错误上报。</p>
 */
public class CancelledException extends RuntimeException {

    public CancelledException() {
        super("任务已取消");
    }

    public CancelledException(String message) {
        super(message);
    }
}
