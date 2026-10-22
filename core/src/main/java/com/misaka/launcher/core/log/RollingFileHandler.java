package com.misaka.launcher.core.log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * 文件日志处理器：按「日期 + 体积」双维度滚动，并限制归档文件数量。
 * <p>活动文件固定为 {@code <logsDir>/<baseName>.log}，滚动后的历史文件进入 {@code archive/} 子目录，
 * 命名为 {@code <baseName>-yyyyMMdd-HHmmss[-n].log}。</p>
 */
public final class RollingFileHandler extends Handler {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path logsDir;
    private final Path archiveDir;
    private final String baseName;
    private final long maxFileBytes;
    private final int maxArchiveFiles;

    private final Object lock = new Object();

    private Writer writer;
    private Path currentFile;
    private LocalDate currentDay;
    private long currentSize;
    private int rotationSequence;

    /**
     * @param logsDir        日志目录
     * @param baseName       活动文件名（不含扩展名）
     * @param maxFileBytes   单文件体积上限，超出即滚动；小于等于 0 表示不按体积滚动
     * @param maxArchiveFiles 归档文件数量上限；超出时删除最旧的
     */
    public RollingFileHandler(Path logsDir, String baseName, long maxFileBytes, int maxArchiveFiles) {
        this.logsDir = logsDir;
        this.archiveDir = logsDir.resolve("archive");
        this.baseName = baseName;
        this.maxFileBytes = maxFileBytes;
        this.maxArchiveFiles = maxArchiveFiles;
    }

    /** 当前活动日志文件路径。 */
    public Path currentFile() {
        return logsDir.resolve(baseName + ".log");
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null) {
            return;
        }
        String line = LogFormatter.formatLine(record);
        synchronized (lock) {
            try {
                ensureOpen();
                rotateIfNeeded(line);
                writer.write(line);
                writer.write(System.lineSeparator());
                String thrown = LogFormatter.formatThrown(record);
                if (thrown != null) {
                    writer.write(thrown);
                    writer.write(System.lineSeparator());
                }
                currentSize += line.length() + 1L;
                writer.flush();
            } catch (IOException e) {
                // 日志自身失败不应影响业务，输出到 stderr 便于排查
                System.err.println("[MisakaLog] 写入日志失败: " + e.getMessage());
            }
        }
    }

    private void ensureOpen() throws IOException {
        LocalDate today = LocalDate.now();
        if (writer == null) {
            Files.createDirectories(logsDir);
            currentFile = currentFile();
            // 恢复当天已有内容，避免重启后覆盖并让体积统计准确
            currentSize = Files.isRegularFile(currentFile) ? Files.size(currentFile) : 0L;
            currentDay = today;
            writer = openWriter(currentFile);
            return;
        }
        if (!today.equals(currentDay)) {
            rollover("day-change");
            currentDay = today;
        }
    }

    private Writer openWriter(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND), 16 * 1024);
    }

    private void rotateIfNeeded(String nextLine) throws IOException {
        if (maxFileBytes <= 0) {
            return;
        }
        if (currentSize + nextLine.length() + 1L <= maxFileBytes) {
            return;
        }
        rollover("size-limit");
    }

    private void rollover(String reason) throws IOException {
        closeQuietly();
        Files.createDirectories(archiveDir);

        Path active = currentFile();
        if (Files.isRegularFile(active) && Files.size(active) > 0) {
            String stamp = LocalDateTime.now().format(STAMP);
            Path target = archiveDir.resolve(baseName + "-" + stamp + ".log");
            int suffix = 1;
            while (Files.exists(target)) {
                target = archiveDir.resolve(baseName + "-" + stamp + "-" + (suffix++) + ".log");
            }
            try {
                Files.move(active, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // 移动失败时退化为复制 + 截断，保证日志不丢
                Files.copy(active, target, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(active);
            }
        }
        currentFile = active;
        currentSize = 0L;
        writer = openWriter(active);
        rotationSequence++;
        purgeArchives();
        writer.write(LogFormatter.formatRawLine("滚动日志文件（原因：" + reason + "）"));
        writer.write(System.lineSeparator());
        writer.flush();
    }

    /** 归档数量超限时删除最旧文件。 */
    private void purgeArchives() {
        if (maxArchiveFiles <= 0) {
            return;
        }
        try {
            List<Path> archives = new ArrayList<>(LogFiles.listLogFiles(archiveDir));
            if (archives.size() <= maxArchiveFiles) {
                return;
            }
            archives.sort(Comparator.comparingLong(LogFiles::lastModifiedMillis));
            int toDelete = archives.size() - maxArchiveFiles;
            for (int i = 0; i < toDelete; i++) {
                Files.deleteIfExists(archives.get(i));
            }
        } catch (IOException ignored) {
            // 清理失败不影响写日志
        }
    }

    /** 已产生的滚动次数（诊断用）。 */
    public int rotationCount() {
        return rotationSequence;
    }

    @Override
    public void flush() {
        synchronized (lock) {
            if (writer != null) {
                try {
                    writer.flush();
                } catch (IOException ignored) {
                    // 忽略
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
                // 忽略
            } finally {
                writer = null;
            }
        }
    }
}
