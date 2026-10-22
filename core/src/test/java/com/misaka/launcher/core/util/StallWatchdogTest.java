package com.misaka.launcher.core.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 停滞看门狗。
 *
 * <p>时钟是注入的假时钟：这里验证的是「多久没活动算卡死」这条规则本身，
 * 不需要真等 5 分钟 —— 真等的话测试既慢又不稳（定时器精度在 CI 上本来就不可靠）。</p>
 */
class StallWatchdogTest {

    /** 可手动推进的假时钟。 */
    private final AtomicLong now = new AtomicLong(1_000_000L);

    private StallWatchdog watchdog(Duration timeout) {
        return new StallWatchdog(timeout, now::get);
    }

    @Test
    void noStallBeforeThreshold() {
        StallWatchdog watchdog = watchdog(Duration.ofMinutes(5));

        now.addAndGet(Duration.ofMinutes(4).toMillis());

        assertFalse(watchdog.timedOut(), "4 分钟还没到阈值");
        assertFalse(watchdog.check(), "阈值内不该触发");
    }

    @Test
    void thresholdShouldTriggerOnce() {
        StallWatchdog watchdog = watchdog(Duration.ofMinutes(5));

        now.addAndGet(Duration.ofMinutes(5).toMillis());

        assertTrue(watchdog.check(), "恰好到阈值就该判定停滞");
        assertFalse(watchdog.check(), "只该触发一次，否则中止动作会被执行两遍");
        assertFalse(watchdog.timedOut(), "判定过一次之后不再重复");
    }

    @Test
    void activityShouldResetTimer() {
        StallWatchdog watchdog = watchdog(Duration.ofMinutes(5));

        now.addAndGet(Duration.ofMinutes(4).toMillis());
        watchdog.touch(); // 又收到一段数据
        now.addAndGet(Duration.ofMinutes(4).toMillis());

        assertFalse(watchdog.check(), "进度还在走就不算卡死");

        now.addAndGet(Duration.ofMinutes(1).toMillis());
        assertTrue(watchdog.check(), "要按最后一次活动起算满 5 分钟");
    }
}
