package com.misaka.launcher.core.launch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * 机器自适应启动参数预设。
 * <p>目标：用户零配置即可得到一套「合理且稳定」的参数。策略有意偏保守 ——
 * 只调整收益明确、风险低的项（堆大小、G1 停顿目标、GC 线程数），
 * 不做激进调优，避免出现「换了启动器反而更容易崩」的观感。</p>
 */
public final class LaunchPresetFactory {

    private static final Logger log = LoggerFactory.getLogger(LaunchPresetFactory.class);

    /** 堆内存上限：超过 8GB 对 Minecraft 收益很小，反而拉长 GC 停顿。 */
    private static final int MAX_HEAP_CEILING_MB = 8192;
    /** 堆内存下限：低于 1GB 在现代版本（含模组）下几乎必然 OOM。 */
    private static final int MIN_HEAP_FLOOR_MB = 1024;
    /** 为操作系统与显存预留的内存。 */
    private static final int OS_RESERVE_MB = 2048;
    /** 堆大小对齐粒度，避免出现 6173m 这类怪异数字。 */
    private static final int ALIGNMENT_MB = 128;
    /** 物理内存探测失败时的可调上限兜底值。 */
    private static final int FALLBACK_CEILING_MB = 16384;

    private LaunchPresetFactory() {
    }

    /**
     * 机器规格。
     *
     * @param cpuCores        逻辑核心数
     * @param physicalMemoryMb 物理内存（MB）；探测失败为 -1
     */
    public record MachineSpec(int cpuCores, long physicalMemoryMb) {
    }

    /**
     * 参数预设。
     *
     * @param minMemoryMb 初始堆
     * @param maxMemoryMb 最大堆
     * @param jvmArgs     附加 JVM 参数（不含 -Xms/-Xmx，由命令构建器统一追加）
     */
    public record Preset(int minMemoryMb, int maxMemoryMb, List<String> jvmArgs) {
    }

    /** 探测本机规格。 */
    public static MachineSpec detect() {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        long memoryMb = -1L;
        try {
            java.lang.management.OperatingSystemMXBean bean =
                    ManagementFactory.getOperatingSystemMXBean();
            // 需要 jdk.management 模块；若运行时被裁剪则跳过，回退到保守默认值
            if (bean instanceof com.sun.management.OperatingSystemMXBean extended) {
                long total = extended.getTotalMemorySize();
                if (total > 0) {
                    memoryMb = total / (1024L * 1024L);
                }
            }
        } catch (Throwable t) {
            log.debug("无法读取物理内存（可能缺少 jdk.management 模块）：{}", t.toString());
        }
        return new MachineSpec(cores, memoryMb);
    }

    /** 按机器规格生成预设。 */
    public static Preset create(MachineSpec spec) {
        return create(spec, 0, 0);
    }

    /**
     * 按机器规格生成预设，允许用户覆盖内存。
     *
     * @param overrideMinMb 覆盖初始堆；&lt;= 0 表示自动
     * @param overrideMaxMb 覆盖最大堆；&lt;= 0 表示自动
     */
    public static Preset create(MachineSpec spec, int overrideMinMb, int overrideMaxMb) {
        long physical = spec.physicalMemoryMb() > 0 ? spec.physicalMemoryMb() : 4096L;
        int cores = Math.max(1, spec.cpuCores());

        int maxMemory = overrideMaxMb > 0 ? overrideMaxMb : autoMaxHeap(physical);
        int minMemory = overrideMinMb > 0 ? overrideMinMb : Math.min(maxMemory, Math.max(512, maxMemory / 2));
        minMemory = Math.min(minMemory, maxMemory);

        List<String> args = new ArrayList<>();
        // G1 是 1.9+ 起的默认收集器，也是当前 Minecraft 社区共识最优选择；
        // 显式指定可避免旧版本 JVM 落到更差的默认收集器
        args.add("-XX:+UseG1GC");
        args.add("-XX:MaxGCPauseMillis=200");
        args.add("-XX:+ParallelRefProcEnabled");
        args.add("-XX:+DisableExplicitGC");
        args.add("-Dfile.encoding=UTF-8");

        // 核心数较少时 JVM 的默认 GC 线程数已经合适，强行指定反而会拖慢并行阶段
        if (cores >= 8) {
            int parallel = Math.min(cores, 16);
            args.add("-XX:ParallelGCThreads=" + parallel);
            args.add("-XX:ConcGCThreads=" + Math.max(1, parallel / 4));
        }

        log.info("自动参数预设：物理内存 {} MB，核心数 {}，堆 {}–{} MB",
                physical, cores, minMemory, maxMemory);
        return new Preset(minMemory, maxMemory, List.copyOf(args));
    }

    /** 自动堆上限：物理内存的一半，并夹在 [1GB, min(8GB, 物理内存-2GB)] 之间。 */
    private static int autoMaxHeap(long physicalMb) {
        long half = physicalMb / 2;
        long ceiling = Math.min(MAX_HEAP_CEILING_MB, Math.max(MIN_HEAP_FLOOR_MB, physicalMb - OS_RESERVE_MB));
        long value = Math.min(half, ceiling);
        value = Math.max(value, MIN_HEAP_FLOOR_MB);
        value = align(value);
        return (int) Math.max(value, MIN_HEAP_FLOOR_MB);
    }

    /** 向下对齐到 128MB 的整数倍。 */
    private static long align(long megabytes) {
        long aligned = (megabytes / ALIGNMENT_MB) * ALIGNMENT_MB;
        return aligned <= 0 ? megabytes : aligned;
    }

    /** 面向界面展示的自动内存建议值。 */
    public static int suggestedMaxMemoryMb() {
        return autoMaxHeap(detect().physicalMemoryMb() > 0 ? detect().physicalMemoryMb() : 4096L);
    }

    /**
     * 内存可调上限（MB）= <b>本机物理内存</b>。
     *
     * <p>界面滑杆的范围必须用它，而不是 {@code Runtime.getRuntime().maxMemory()} ——
     * 后者只是启动器自身 JVM 的堆上限（默认约为物理内存的 1/4），
     * 与「用户能给游戏分配多少」毫无关系。把它当上限会让 16GB 机器的滑杆最高只到 4GB，
     * 甚至连自动建议值都选不中。</p>
     *
     * <p>探测失败时回退到 {@link #FALLBACK_CEILING_MB}，绝不返回 0 或负数
     * （那会让滑杆完全不可用）。</p>
     */
    public static int memoryCeilingMb() {
        long physical = detect().physicalMemoryMb();
        return physical > 0 ? (int) physical : FALLBACK_CEILING_MB;
    }
}
