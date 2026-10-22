package com.misaka.launcher.core.task;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进度监听器的组合与包装工具。
 */
public final class ProgressListeners {

    /** 默认节流间隔：与设计方案一致，避免高频刷 UI 导致掉帧。 */
    public static final long DEFAULT_THROTTLE_MILLIS = 100L;

    private ProgressListeners() {
    }

    /** 组合多个监听器，任一监听器抛出的异常都不会影响其他监听器与主流程。 */
    public static ProgressListener composite(ProgressListener... listeners) {
        List<ProgressListener> list = new ArrayList<>();
        for (ProgressListener listener : listeners) {
            if (listener != null) {
                list.add(listener);
            }
        }
        return new ProgressListener() {
            @Override
            public void onStage(String stage) {
                for (ProgressListener l : list) {
                    try {
                        l.onStage(stage);
                    } catch (RuntimeException ignored) {
                        // 监听器自身异常不应中断任务
                    }
                }
            }

            @Override
            public void onProgress(long completed, long total) {
                for (ProgressListener l : list) {
                    try {
                        l.onProgress(completed, total);
                    } catch (RuntimeException ignored) {
                        // 同上
                    }
                }
            }

            @Override
            public void onItemProgress(int completedItems, int totalItems) {
                for (ProgressListener l : list) {
                    try {
                        l.onItemProgress(completedItems, totalItems);
                    } catch (RuntimeException ignored) {
                        // 同上
                    }
                }
            }

            @Override
            public void onMessage(String message) {
                for (ProgressListener l : list) {
                    try {
                        l.onMessage(message);
                    } catch (RuntimeException ignored) {
                        // 同上
                    }
                }
            }

            @Override
            public boolean isCancelled() {
                for (ProgressListener l : list) {
                    try {
                        if (l.isCancelled()) {
                            return true;
                        }
                    } catch (RuntimeException ignored) {
                        // 同上
                    }
                }
                return false;
            }
        };
    }

    /** 丢弃进度事件、仅保留阶段与日志的监听器（用于后台静默校验）。 */
    public static ProgressListener stagesOnly(ProgressListener delegate) {
        return new ProgressListener() {
            @Override
            public void onStage(String stage) {
                delegate.onStage(stage);
            }

            @Override
            public void onMessage(String message) {
                delegate.onMessage(message);
            }

            @Override
            public boolean isCancelled() {
                return delegate.isCancelled();
            }
        };
    }

    /**
     * 节流包装：同一时间窗口内只透传最后一次进度。
     * <p>阶段与日志事件不节流，保证状态切换及时。</p>
     */
    public static ProgressListener throttled(ProgressListener delegate, long intervalMillis) {
        AtomicLong lastEmit = new AtomicLong(0L);
        AtomicLong pending = new AtomicLong(-1L);
        AtomicLong pendingTotal = new AtomicLong(-1L);
        return new ProgressListener() {
            @Override
            public void onStage(String stage) {
                delegate.onStage(stage);
            }

            @Override
            public void onProgress(long completed, long total) {
                pending.set(completed);
                pendingTotal.set(total);
                long now = System.currentTimeMillis();
                long last = lastEmit.get();
                if (now - last >= intervalMillis || (total > 0 && completed >= total)) {
                    if (lastEmit.compareAndSet(last, now)) {
                        delegate.onProgress(pending.get(), pendingTotal.get());
                    }
                }
            }

            @Override
            public void onItemProgress(int completedItems, int totalItems) {
                // 计数进度本身就是低频事件（每个文件一次），无需节流但要透传
                delegate.onItemProgress(completedItems, totalItems);
            }

            @Override
            public void onMessage(String message) {
                delegate.onMessage(message);
            }

            @Override
            public boolean isCancelled() {
                return delegate.isCancelled();
            }
        };
    }

    public static ProgressListener throttled(ProgressListener delegate) {
        return throttled(delegate, DEFAULT_THROTTLE_MILLIS);
    }

    /** 创建一个可被外部取消的监听器，返回句柄便于 UI 侧调用 {@code cancel()}。 */
    public static Cancellable createCancellable(ProgressListener delegate) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ProgressListener listener = new ProgressListener() {
            @Override
            public void onStage(String stage) {
                delegate.onStage(stage);
            }

            @Override
            public void onProgress(long completed, long total) {
                delegate.onProgress(completed, total);
            }

            @Override
            public void onItemProgress(int completedItems, int totalItems) {
                delegate.onItemProgress(completedItems, totalItems);
            }

            @Override
            public void onMessage(String message) {
                delegate.onMessage(message);
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get() || delegate.isCancelled();
            }
        };
        return new Cancellable(listener, cancelled);
    }

    /** {@link #createCancellable(ProgressListener)} 的返回句柄。 */
    public static final class Cancellable {
        private final ProgressListener listener;
        private final AtomicBoolean flag;

        private Cancellable(ProgressListener listener, AtomicBoolean flag) {
            this.listener = listener;
            this.flag = flag;
        }

        public ProgressListener listener() {
            return listener;
        }

        public void cancel() {
            flag.set(true);
        }

        public boolean isCancelled() {
            return flag.get();
        }
    }
}
