package com.misaka.launcher.core.util;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 停滞看门狗：一段时间内没有任何活动就判定「卡住了」。
 *
 * <p>给「本该在几分钟内结束、却可能静默挂住」的操作兜底。典型场景是自更新下载：
 * 连接已建立、对端却迟迟不回响应头时，请求会一直阻塞到 HttpClient 的请求超时，
 * 而那个超时是按几十分钟计的 —— 用户看到的就是「进度条不动、点什么都没反应」。</p>
 *
 * <p>用法：每次有进展时调 {@link #touch()}（字节进度、阶段切换、提示文案都算），
 * 再周期性调用 {@link #check()}；超时那一刻返回 {@code true}，之后永远返回 {@code false}
 * （判定只触发一次，调用方不必自己防重入）。</p>
 *
 * <p>刻意只做判定、不持有线程、不关心「怎么停」：定时器与中止动作都留给调用方，
 * 时钟也可注入 —— 于是「5 分钟超时」这件事能在单测里用改一个变量来验证，
 * 而不必真的等 5 分钟。</p>
 */
public final class StallWatchdog {

    /** 判定停滞的阈值。 */
    private final long timeoutMillis;

    /** 时间源；测试注入假时钟，生产用系统时钟。 */
    private final LongSupplier clock;

    /** 最近一次活动的时刻。 */
    private final AtomicLong lastActivityAt;

    /** 是否已经判定过停滞。 */
    private final AtomicBoolean fired = new AtomicBoolean();

    /**
     * @param timeout 多久没有活动算停滞；必须为正
     */
    public StallWatchdog(Duration timeout) {
        this(timeout, System::currentTimeMillis);
    }

    /** 可注入时钟的构造器：单测用它把「5 分钟」压缩成一次赋值。 */
    StallWatchdog(Duration timeout, LongSupplier clock) {
        this.timeoutMillis = Math.max(1L, timeout.toMillis());
        this.clock = clock;
        this.lastActivityAt = new AtomicLong(clock.getAsLong());
    }

    /** 记录一次活动：阈值从此刻重新计时。 */
    public void touch() {
        lastActivityAt.set(clock.getAsLong());
    }

    /** 自上次活动以来是否已经超时（且尚未判定过）。 */
    public boolean timedOut() {
        return !fired.get() && clock.getAsLong() - lastActivityAt.get() >= timeoutMillis;
    }

    /**
     * 判定一次。
     *
     * @return 本次是否「刚刚判定为停滞」；只会在超时的那一刻返回一次 {@code true}
     */
    public boolean check() {
        if (!timedOut()) {
            return false;
        }
        // 并发调用下也只放行一个：两个观察者同时超时会让中止动作执行两次
        return fired.compareAndSet(false, true);
    }
}
