package com.misaka.launcher.core.log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.Stream;

/**
 * 日志文件遍历辅助：日志清理与日志查看页共用。
 */
public final class LogFiles {

    /** 参与清理的日志扩展名。 */
    private static final List<String> LOG_EXTENSIONS = List.of("log", "txt", "gz", "zip");

    private LogFiles() {
    }

    /** 递归列出目录下所有日志文件。 */
    public static List<Path> listLogFiles(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(LogFiles::isLogFile)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static boolean isLogFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        int idx = name.lastIndexOf('.');
        if (idx < 0) {
            // 无扩展名的文件也视为日志（部分旋转策略会产生）
            return true;
        }
        return LOG_EXTENSIONS.contains(name.substring(idx + 1));
    }

    public static long lastModifiedMillis(Path file) {
        try {
            FileTime time = Files.getLastModifiedTime(file);
            return time.toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    public static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * 删除文件，失败时返回 false（Windows 上被占用时可能失败）。
     */
    public static boolean deleteQuietly(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            return false;
        }
    }
}
