package com.misaka.launcher.core.log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * 内存日志环形缓冲。
 * <p>为日志查看页提供「打开即见历史」的体验，同时通过监听器支持实时追加。
 * 积压上限固定，避免长时间运行导致内存增长。</p>
 */
public final class MemoryLogBuffer extends Handler {

    private static final int CAPACITY = 3000;

    private final Deque<LogEntry> entries = new ArrayDeque<>(CAPACITY);
    private final List<Consumer<LogEntry>> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void publish(LogRecord record) {
        if (record == null) {
            return;
        }
        LogEntry entry = new LogEntry(
                record.getMillis(),
                LogFormatter.levelName(record),
                record.getLoggerName(),
                record.getMessage(),
                LogFormatter.formatThrown(record));

        synchronized (entries) {
            if (entries.size() >= CAPACITY) {
                entries.pollFirst();
            }
            entries.addLast(entry);
        }

        for (Consumer<LogEntry> listener : listeners) {
            try {
                listener.accept(entry);
            } catch (RuntimeException ignored) {
                // 监听器异常不影响日志链路
            }
        }
    }

    @Override
    public void flush() {
        // 内存缓冲无需 flush
    }

    @Override
    public void close() {
        synchronized (entries) {
            entries.clear();
        }
        listeners.clear();
    }

    /** 当前缓冲快照（按时间升序）。 */
    public List<LogEntry> snapshot() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    /** 清空缓冲（不影响文件日志）。 */
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    /** 注册实时监听；返回句柄用于取消注册。 */
    public AutoCloseable addListener(Consumer<LogEntry> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
