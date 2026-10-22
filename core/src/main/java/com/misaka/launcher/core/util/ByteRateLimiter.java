package com.misaka.launcher.core.util;

import java.util.concurrent.locks.LockSupport;

/**
 * 平均速率限流器（令牌桶的简化形式）。
 * <p>按「已消费字节数对应的理论耗时」与「实际耗时」之差进行补偿式休眠，
 * 因此长时间下载的平均速率会稳定收敛到设定值，且不会因突发读取造成尖峰。</p>
 * <p>线程安全；期望被多个下载线程共享，以限制总体带宽而非单连接带宽。</p>
 */
public final class ByteRateLimiter {

    /** 不做任何限制的实例。 */
    public static final ByteRateLimiter UNLIMITED = new ByteRateLimiter(0);

    private final long bytesPerSecond;

    private long startNanos = 0L;
    private long consumedBytes = 0L;

    /**
     * @param bytesPerSecond 每秒允许的字节数；小于等于 0 表示不限速
     */
    public ByteRateLimiter(long bytesPerSecond) {
        this.bytesPerSecond = Math.max(0L, bytesPerSecond);
    }

    /** 由 KB/s 构造。 */
    public static ByteRateLimiter ofKiloBytesPerSecond(int kbps) {
        return kbps <= 0 ? UNLIMITED : new ByteRateLimiter(kbps * 1024L);
    }

    public boolean isUnlimited() {
        return bytesPerSecond <= 0;
    }

    /**
     * 登记本次读取的字节数，必要时休眠以维持平均速率。
     *
     * @param bytes 本次新增的字节数
     */
    public void consume(int bytes) {
        if (bytesPerSecond <= 0 || bytes <= 0) {
            return;
        }
        long sleepNanos;
        synchronized (this) {
            long now = System.nanoTime();
            if (startNanos == 0L) {
                startNanos = now;
            }
            consumedBytes += bytes;
            long expectedNanos = consumedBytes * 1_000_000_000L / bytesPerSecond;
            long elapsedNanos = now - startNanos;
            sleepNanos = expectedNanos - elapsedNanos;
        }
        // 小于 1ms 的补偿直接忽略，避免高频 park/unpark 反而拖慢吞吐
        if (sleepNanos > 1_000_000L) {
            LockSupport.parkNanos(sleepNanos);
        }
    }

    /** 重置统计，用于复用实例开始新一批下载。 */
    public synchronized void reset() {
        startNanos = 0L;
        consumedBytes = 0L;
    }
}
