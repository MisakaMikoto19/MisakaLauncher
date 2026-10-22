package com.misaka.launcher.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 文件系统工具：原子写、幂等目录创建、递归删除与体积统计、静默移动。
 */
public final class FileUtils {

    private static final Logger log = LoggerFactory.getLogger(FileUtils.class);

    private FileUtils() {
    }

    /** 确保目录存在（并发安全，已存在时不抛异常）。 */
    public static Path ensureDir(Path dir) throws IOException {
        if (dir == null) {
            throw new IOException("目录路径为 null");
        }
        Files.createDirectories(dir);
        return dir;
    }

    /** 确保文件所在目录存在。 */
    public static Path ensureParent(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return file;
    }

    /**
     * 原子写入：先写同目录临时文件再 ATOMIC_MOVE，避免进程中断导致配置损坏。
     * <p>部分文件系统（如某些网络盘）不支持原子移动，此时退化为普通替换。</p>
     */
    public static void atomicWrite(Path target, byte[] content) throws IOException {
        ensureParent(target);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, content);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void atomicWrite(Path target, String content) throws IOException {
        atomicWrite(target, content.getBytes(StandardCharsets.UTF_8));
    }

    public static String readString(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /**
     * 读取文本文件的末尾若干字节（文件不存在、不可读或为空时返回空串）。
     *
     * <p>游戏日志与崩溃报告可以有几十 MB，整份读进内存既慢又没必要 ——
     * 真正的原因总在接近末尾的位置。用随机访问只读尾部，避免把整个文件吞进堆里。</p>
     *
     * <p>注意这是<b>按字节</b>截断的：起点可能落在一个多字节字符中间，
     * 因此返回内容头部可能出现一个乱码字符。对「给人看的日志尾部」而言可以接受，
     * 但要拿它做精确解析时应改用按行读取。</p>
     *
     * @param file     目标文件
     * @param maxBytes 最多读取的字节数；&lt;= 0 时返回空串
     */
    public static String readTail(Path file, int maxBytes) {
        if (file == null || maxBytes <= 0 || !Files.isReadable(file)) {
            return "";
        }
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size <= 0) {
                return "";
            }
            int window = (int) Math.min(size, maxBytes);
            long start = size - window;
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate(window);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // 继续读满窗口
            }
            return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("读取文件末尾失败（忽略）：{}：{}", file, e.getMessage());
            return "";
        }
    }

    /**
     * 读取文本文件的末尾若干行（不足则返回全部）。
     *
     * @param file     目标文件
     * @param maxBytes 最多扫描的字节数（建议 64KB 起步）
     * @param maxLines 最多返回的行数
     */
    public static String readTailLines(Path file, int maxBytes, int maxLines) {
        String tail = readTail(file, maxBytes);
        if (tail.isEmpty() || maxLines <= 0) {
            return tail;
        }
        String[] lines = tail.split("\\R");
        if (lines.length <= maxLines) {
            return tail.trim();
        }
        return String.join("\n", Arrays.asList(lines).subList(lines.length - maxLines, lines.length)).trim();
    }

    /**
     * 读取文本文件的头部若干字节（文件不存在、不可读或为空时返回空串）。
     *
     * <p>与 {@link #readTail} 相对。崩溃报告正是「关键信息在开头」的典型：
     * {@code Description:} 与首个异常栈就在文件前几十行，而文件本身可能上百 KB 甚至数 MB
     * （加载器会把完整的模组列表与系统信息堆在尾部）。只读尾部会正好错过原因，
     * 但整份读进内存同样没必要 —— 因此必须能只读头部。</p>
     *
     * <p>注意这是<b>按字节</b>截断的：末尾可能落在一个多字节字符中间，
     * 返回内容尾部可能出现一个乱码字符。用于特征串匹配没有影响。</p>
     *
     * @param file     目标文件
     * @param maxBytes 最多读取的字节数；&lt;= 0 时返回空串
     */
    public static String readHead(Path file, int maxBytes) {
        if (file == null || maxBytes <= 0 || !Files.isReadable(file)) {
            return "";
        }
        try (java.io.InputStream in = Files.newInputStream(file, StandardOpenOption.READ)) {
            byte[] buffer = new byte[maxBytes];
            int read = 0;
            while (read < maxBytes) {
                int count = in.read(buffer, read, maxBytes - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("读取文件头部失败（忽略）：{}：{}", file, e.getMessage());
            return "";
        }
    }

    /**
     * 读取文本文件的头部若干行（不足则返回全部）。
     *
     * @param file     目标文件
     * @param maxBytes 最多扫描的字节数（建议 64KB 起步）
     * @param maxLines 最多返回的行数
     */
    public static String readHeadLines(Path file, int maxBytes, int maxLines) {
        String head = readHead(file, maxBytes);
        if (head.isEmpty() || maxLines <= 0) {
            return head;
        }
        String[] lines = head.split("\\R");
        if (lines.length <= maxLines) {
            return head.trim();
        }
        return String.join("\n", Arrays.asList(lines).subList(0, maxLines)).trim();
    }

    /** 递归删除；不存在视为成功。 */
    public static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 删除失败不抛异常，仅返回是否成功（用于缓存清理等非关键路径）。 */
    public static boolean deleteQuietly(Path path) {
        try {
            deleteRecursively(path);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static long sizeOf(Path path) {
        if (path == null || !Files.exists(path)) {
            return 0L;
        }
        if (Files.isRegularFile(path)) {
            try {
                return Files.size(path);
            } catch (IOException e) {
                return 0L;
            }
        }
        try (Stream<Path> stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 列出目录下的普通文件，目录不存在时返回空列表。 */
    public static List<Path> listFiles(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** 递归列出目录下的所有普通文件。 */
    public static List<Path> listFilesRecursively(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 列出目录并按名称排序，支持扩展名过滤。
     *
     * @param extensions 允许的扩展名（不含点，大小写不敏感）；为空则不过滤
     */
    public static List<Path> listFilesByExtension(Path dir, String... extensions) {
        List<Path> files = new ArrayList<>(listFilesRecursively(dir));
        if (extensions == null || extensions.length == 0) {
            files.sort(Comparator.comparing(Path::toString));
            return files;
        }
        List<String> allowed = new ArrayList<>();
        for (String ext : extensions) {
            allowed.add(ext.toLowerCase(Locale.ROOT));
        }
        return files.stream()
                .filter(p -> allowed.contains(extensionOf(p)))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
    }

    /** 取小写扩展名，无扩展名时返回空串。 */
    public static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int idx = name.lastIndexOf('.');
        return idx < 0 || idx == name.length() - 1 ? "" : name.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    /** 去掉扩展名的文件名。 */
    public static String baseName(Path file) {
        String name = file.getFileName().toString();
        int idx = name.lastIndexOf('.');
        return idx <= 0 ? name : name.substring(0, idx);
    }

    /** 移动文件，跨盘时自动退化为复制 + 删除。 */
    public static void move(Path source, Path target) throws IOException {
        ensureParent(target);
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void copy(Path source, Path target) throws IOException {
        ensureParent(target);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 人类可读体积，例如 {@code 128.4 MB}。 */
    public static String humanSize(long bytes) {
        if (bytes < 0) {
            return "0 B";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    /** 把相对路径字符串规范化为 {@code ./} 前缀 + 正斜杠形式（对齐 FileSync 协议）。 */
    public static String toProtocolPath(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return "./" + normalized;
    }

    /** 校验文件名是否合法（不含路径分隔符与 Windows 保留字符）。 */
    public static boolean isSafeFileName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return name.indexOf('/') < 0
                && name.indexOf('\\') < 0
                && name.indexOf(':') < 0
                && name.indexOf('*') < 0
                && name.indexOf('?') < 0
                && name.indexOf('"') < 0
                && name.indexOf('<') < 0
                && name.indexOf('>') < 0
                && name.indexOf('|') < 0
                && !name.equals(".")
                && !name.equals("..");
    }

    /** 在后台线程中执行的删除，异常不外抛。 */
    public static void runQuietly(IoRunnable runnable) {
        try {
            runnable.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 允许抛出 IOException 的 Runnable。 */
    @FunctionalInterface
    public interface IoRunnable {
        void run() throws IOException;
    }
}
