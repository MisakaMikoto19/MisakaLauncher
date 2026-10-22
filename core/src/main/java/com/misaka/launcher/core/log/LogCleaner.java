package com.misaka.launcher.core.log;

import com.misaka.launcher.core.config.LauncherConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 日志自动清理。
 * <p>三条独立策略，按「保留天数 → 总体积上限 → 文件数量上限」顺序执行：</p>
 * <ol>
 *   <li>删除修改时间早于保留天数的文件；</li>
 *   <li>若目录总体积超过上限，按时间从旧到新继续删除，直到满足上限；</li>
 *   <li>若文件数量超过上限，同样从旧到新删除。</li>
 * </ol>
 * <p>当前正在写入的活动日志文件会被跳过（Windows 下删除被占用文件会失败）。</p>
 */
public final class LogCleaner {

    private static final Logger log = LoggerFactory.getLogger(LogCleaner.class);

    private static final long MIB = 1024L * 1024L;

    private LogCleaner() {
    }

    /**
     * 清理结果。
     *
     * @param deletedFiles 删除的文件数
     * @param freedBytes   释放的字节数
     */
    public record CleanResult(int deletedFiles, long freedBytes) {
        public static final CleanResult NONE = new CleanResult(0, 0L);
    }

    /**
     * 执行一次清理。
     *
     * @param logsDir    日志根目录
     * @param settings   日志策略
     * @param activeFile 当前活动的日志文件（受保护，不删除），可为 null
     */
    public static CleanResult clean(Path logsDir, LauncherConfig.LogSettings settings, Path activeFile) {
        if (logsDir == null || settings == null) {
            return CleanResult.NONE;
        }
        List<Path> files = new ArrayList<>(LogFiles.listLogFiles(logsDir));
        if (files.isEmpty()) {
            return CleanResult.NONE;
        }
        Path protectedFile = activeFile == null ? null : activeFile.toAbsolutePath().normalize();

        files.sort(Comparator.comparingLong(LogFiles::lastModifiedMillis));
        int deleted = 0;
        long freed = 0L;

        // 1) 保留天数
        if (settings.retainDays > 0) {
            long cutoff = System.currentTimeMillis() - settings.retainDays * 24L * 3600L * 1000L;
            List<Path> remaining = new ArrayList<>();
            for (Path file : files) {
                if (isProtected(file, protectedFile)) {
                    remaining.add(file);
                    continue;
                }
                if (LogFiles.lastModifiedMillis(file) < cutoff) {
                    long size = LogFiles.sizeOf(file);
                    if (LogFiles.deleteQuietly(file)) {
                        deleted++;
                        freed += size;
                        log.debug("日志过期删除: {}", file.getFileName());
                    }
                } else {
                    remaining.add(file);
                }
            }
            files = remaining;
        }

        // 2) 总体积上限
        if (settings.maxTotalMb > 0) {
            long limit = settings.maxTotalMb * MIB;
            long total = files.stream().mapToLong(LogFiles::sizeOf).sum();
            List<Path> remaining = new ArrayList<>(files);
            for (Path file : files) {
                if (total <= limit) {
                    break;
                }
                if (isProtected(file, protectedFile)) {
                    continue;
                }
                long size = LogFiles.sizeOf(file);
                if (LogFiles.deleteQuietly(file)) {
                    total -= size;
                    deleted++;
                    freed += size;
                    remaining.remove(file);
                    log.debug("日志超容删除: {}", file.getFileName());
                }
            }
            files = remaining;
        }

        // 3) 文件数量上限
        if (settings.maxFiles > 0 && files.size() > settings.maxFiles) {
            int excess = files.size() - settings.maxFiles;
            for (Path file : files) {
                if (excess <= 0) {
                    break;
                }
                if (isProtected(file, protectedFile)) {
                    continue;
                }
                long size = LogFiles.sizeOf(file);
                if (LogFiles.deleteQuietly(file)) {
                    excess--;
                    deleted++;
                    freed += size;
                }
            }
        }

        if (deleted > 0) {
            log.info("日志清理完成：删除 {} 个文件，释放 {} 字节", deleted, freed);
        }
        return new CleanResult(deleted, freed);
    }

    private static boolean isProtected(Path file, Path protectedFile) {
        return protectedFile != null && file.toAbsolutePath().normalize().equals(protectedFile);
    }

    /**
     * 注册周期清理任务。
     *
     * @param logsDir       日志目录
     * @param settingsSupplier 动态读取最新策略（用户可能在设置页修改）
     * @param activeFile   活动日志文件
     * @param initialDelayMinutes 首次执行延迟（分钟）
     * @param periodMinutes 执行周期（分钟）
     */
    public static ScheduledFuture<?> schedule(Path logsDir,
                                             Supplier<LauncherConfig.LogSettings> settingsSupplier,
                                             Path activeFile,
                                             long initialDelayMinutes,
                                             long periodMinutes) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "misaka-log-cleaner");
            thread.setDaemon(true);
            return thread;
        });
        return executor.scheduleAtFixedRate(() -> {
            try {
                clean(logsDir, settingsSupplier.get(), activeFile);
            } catch (RuntimeException e) {
                log.warn("日志清理任务异常: {}", e.getMessage());
            }
        }, initialDelayMinutes, periodMinutes, TimeUnit.MINUTES);
    }
}
