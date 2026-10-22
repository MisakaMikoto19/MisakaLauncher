package com.misaka.launcher.core.launch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机器自适应内存策略验证。
 *
 * <p>重点锁住「界面滑杆的可调范围」这条不变量：上限必须来自<b>物理内存</b>，
 * 且不得低于自动建议值。旧实现拿 {@code Runtime.maxMemory()}（启动器自身 JVM 的堆上限）
 * 当上限，在 16GB 机器上上限只有 4GB，连默认建议值都选不中。</p>
 */
class LaunchPresetFactoryTest {

    /** 构造固定规格，避免测试结果随运行机器的配置漂移。 */
    private static LaunchPresetFactory.MachineSpec machine(long physicalMemoryMb) {
        return new LaunchPresetFactory.MachineSpec(8, physicalMemoryMb);
    }

    @Test
    void autoHeapShouldBeHalfMemoryCapped() {
        // 16GB：一半 8GB，正好等于收益上限
        assertEquals(8192, LaunchPresetFactory.create(machine(16384L), 0, 0).maxMemoryMb());
        // 32GB：仍收敛到 8GB —— 超过这个量对 Minecraft 收益很小，反而拉长 GC 停顿
        assertEquals(8192, LaunchPresetFactory.create(machine(32768L), 0, 0).maxMemoryMb());
        // 4GB：一半 2GB，但必须给系统留出空间
        assertEquals(2048, LaunchPresetFactory.create(machine(4096L), 0, 0).maxMemoryMb());
    }

    @Test
    void smallMemoryShouldRespectHeapFloor() {
        // 1GB 机器：一半只有 512MB，但低于 1GB 在现代版本（含模组）下几乎必然 OOM
        assertEquals(1024, LaunchPresetFactory.create(machine(1024L), 0, 0).maxMemoryMb());
    }

    @Test
    void memoryCeilingShouldNotBeBelowAutoSuggestion() {
        int ceiling = LaunchPresetFactory.memoryCeilingMb();
        int suggested = LaunchPresetFactory.suggestedMaxMemoryMb();
        assertTrue(ceiling >= suggested,
                "可调上限(" + ceiling + " MB) 不应低于自动建议(" + suggested
                        + " MB)，否则默认值在滑杆上根本选不中");
    }

    @Test
    void memoryCeilingShouldFollowPhysicalMemory() {
        long physical = LaunchPresetFactory.detect().physicalMemoryMb();
        int ceiling = LaunchPresetFactory.memoryCeilingMb();

        assertTrue(ceiling > 0, "上限绝不能为 0，否则滑杆完全不可用");
        if (physical > 0) {
            assertEquals((int) physical, ceiling, "上限应等于本机物理内存");
        } else {
            // 缺少 jdk.management 模块时允许回退，但必须仍然能用
            assertTrue(ceiling >= 8192, "探测失败时应回退到保守上限，实际 " + ceiling);
        }
    }
}
