package com.misaka.launcher.core.log;

import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.config.PortablePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日志子系统验证：落盘、内存缓冲、脱敏与清理策略。
 */
class LogManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void sensitiveDataMustBeRedacted() {
        String masked = LogManager.mask("--accessToken eyJhbGciOiJIUzI1NiJ9.abcdefghijklmn.SIGNATURE1234");
        assertFalse(masked.contains("SIGNATURE1234"), "JWT 主体应被掩码: " + masked);

        String kv = LogManager.mask("refreshToken=abc123def456&x-api-key=SECRETKEY");
        assertFalse(kv.contains("abc123def456"), "refreshToken 值应被掩码: " + kv);
        assertFalse(kv.contains("SECRETKEY"), "api key 值应被掩码: " + kv);
        assertTrue(kv.contains("****"));
    }

    @Test
    void logsShouldLandInFileAndBuffer() throws Exception {
        PortablePaths paths = PortablePaths.resolve(false, tempDir.toString());
        LauncherConfig.LogSettings settings = new LauncherConfig.LogSettings();
        settings.consoleOutput = false;
        paths.prepare();

        LogManager.init(paths, settings);
        try {
            org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger("misaka.test");
            logger.info("测试日志条目 {}", 42);

            Path logFile = LogManager.launcherLogFile();
            assertTrue(Files.isRegularFile(logFile), "日志文件应被创建: " + logFile);
            String content = Files.readString(logFile);
            assertTrue(content.contains("测试日志条目 42"), "文件内容应包含写入的日志");

            List<LogEntry> history = LogManager.history();
            assertFalse(history.isEmpty(), "内存缓冲应捕获日志");
            assertTrue(history.stream().anyMatch(e -> e.message().contains("测试日志条目 42")));
        } finally {
            LogManager.shutdown();
        }
    }

    @Test
    void cleanupShouldHonorRetentionAndProtectActiveFile() throws Exception {
        Path logsDir = tempDir.resolve("logs");
        Files.createDirectories(logsDir);

        Path old = logsDir.resolve("old.log");
        Files.writeString(old, "old content");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 40L * 24 * 3600 * 1000));

        Path active = logsDir.resolve("launcher.log");
        Files.writeString(active, "active content");
        Files.setLastModifiedTime(active, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 40L * 24 * 3600 * 1000));

        LauncherConfig.LogSettings settings = new LauncherConfig.LogSettings();
        settings.retainDays = 7;
        settings.maxTotalMb = 0;
        settings.maxFiles = 0;

        LogCleaner.CleanResult result = LogCleaner.clean(logsDir, settings, active);

        assertFalse(Files.exists(old), "过期日志应被删除");
        assertTrue(Files.exists(active), "活动日志文件必须被保护");
        assertEquals(1, result.deletedFiles());
    }
}
