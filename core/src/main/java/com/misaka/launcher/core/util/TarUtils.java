package com.misaka.launcher.core.util;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * 极简 TAR / TAR.GZ 解压实现。
 * <p>存在意义：Linux 与 macOS 平台上的 JDK 发行包是 {@code .tar.gz} 而非 zip，
 * 而 JDK 标准库并不提供 tar 支持。为避免为一个解压功能引入第三方依赖
 * （会破坏 jlink 的模块约束与本项目的「零多余运行时依赖」目标），此处自行实现。</p>
 * <p>支持范围覆盖 JDK 发行包的实际用法：普通文件、目录、符号链接、
 * GNU 长文件名（{@code L}）、PAX 扩展头（{@code x}）中的 {@code path} 覆盖，
 * 并保留可执行权限（否则解出来的 {@code bin/java} 无法运行）。</p>
 */
public final class TarUtils {

    private static final int BLOCK_SIZE = 512;
    private static final int NAME_LENGTH = 100;
    private static final int PREFIX_LENGTH = 155;

    private TarUtils() {
    }

    /**
     * 解压 tar.gz 到目标目录（防目录穿越）。
     *
     * @return 实际写出的文件路径列表
     */
    public static List<Path> extractTarGz(Path archive, Path destDir) throws IOException {
        try (InputStream fileIn = Files.newInputStream(archive);
             InputStream buffered = new BufferedInputStream(fileIn, 64 * 1024);
             GZIPInputStream gzip = new GZIPInputStream(buffered, 64 * 1024)) {
            return extractTar(gzip, destDir);
        }
    }

    /** 解压未压缩的 tar。 */
    public static List<Path> extractTar(Path archive, Path destDir) throws IOException {
        try (InputStream in = Files.newInputStream(archive)) {
            return extractTar(in, destDir);
        }
    }

    private static List<Path> extractTar(InputStream in, Path destDir) throws IOException {
        FileUtils.ensureDir(destDir);
        Path root = destDir.toAbsolutePath().normalize();
        List<Path> written = new ArrayList<>();

        String pendingLongName = null;
        String pendingPaxPath = null;

        byte[] header = new byte[BLOCK_SIZE];
        while (true) {
            int read = readFully(in, header);
            if (read < 0) {
                break;
            }
            if (isZeroBlock(header)) {
                // 归档结束标记：连续两个零块，读到第一个即可停止
                break;
            }

            String rawName = readString(header, 0, NAME_LENGTH);
            long size = parseOctal(header, 124, 12);
            char typeFlag = (char) (header[156] & 0xFF);
            String prefix = readString(header, 345, PREFIX_LENGTH);
            int mode = (int) parseOctal(header, 100, 8);

            String name = pendingPaxPath != null ? pendingPaxPath
                    : (pendingLongName != null ? pendingLongName
                    : (prefix.isEmpty() ? rawName : prefix + "/" + rawName));
            pendingLongName = null;
            pendingPaxPath = null;

            long contentSize = size;
            if (typeFlag == 'x' || typeFlag == 'g') {
                // PAX 扩展头：正文是 "key=value\n" 列表，只需取出 path 覆盖
                byte[] payload = readPayload(in, contentSize);
                String paxPath = parsePaxPath(payload);
                if (paxPath != null) {
                    pendingPaxPath = paxPath;
                }
                continue;
            }
            if (typeFlag == 'L') {
                // GNU 长文件名：正文即名称
                byte[] payload = readPayload(in, contentSize);
                pendingLongName = new String(payload, StandardCharsets.UTF_8).replace("\0", "").trim();
                continue;
            }

            if (name.isEmpty() || !PathSafety.isSafeEntryName(name)) {
                skip(in, contentSize);
                continue;
            }

            Path target = root.resolve(name.replace('\\', '/')).normalize();
            if (!PathSafety.isInside(root, target)) {
                throw new IOException("归档条目越界: " + name);
            }

            switch (typeFlag) {
                case '5' -> Files.createDirectories(target);
                case '2' -> {
                    // 符号链接：JDK 包中偶见（macOS 的 Contents/Home 之类），
                    // 无法创建时退化为跳过，不影响 bin/java 可用性
                    String linkTarget = readString(header, 157, 100);
                    createSymlinkQuietly(target, linkTarget);
                }
                case '0', '\0', ' ' -> {
                    FileUtils.ensureParent(target);
                    try (OutputStream out = Files.newOutputStream(target)) {
                        copyExactly(in, out, contentSize);
                    }
                    applyMode(target, mode);
                    written.add(target);
                }
                default -> {
                    // 其余类型（字符设备、FIFO 等）与 JDK 无关，直接跳过正文
                    skip(in, contentSize);
                }
            }
        }
        return written;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 读取恰好 buffer.length 字节；不足（归档结束）返回 -1。 */
    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read < 0) {
                return total == 0 ? -1 : total;
            }
            total += read;
        }
        return total;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String readString(byte[] block, int offset, int length) {
        int end = offset;
        int limit = Math.min(offset + length, block.length);
        while (end < limit && block[end] != 0) {
            end++;
        }
        return new String(block, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long parseOctal(byte[] block, int offset, int length) {
        String value = readString(block, offset, length);
        if (value.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.replace("\0", "").trim(), 8);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static byte[] readPayload(InputStream in, long size) throws IOException {
        if (size <= 0) {
            return new byte[0];
        }
        byte[] payload = new byte[(int) size];
        int offset = 0;
        while (offset < payload.length) {
            int read = in.read(payload, offset, payload.length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        skipPadding(in, size);
        return payload;
    }

    private static String parsePaxPath(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            int space = line.indexOf(' ');
            if (space <= 0) {
                continue;
            }
            String body = line.substring(space + 1);
            if (body.startsWith("path=")) {
                return body.substring("path=".length()).trim();
            }
        }
        return null;
    }

    private static void copyExactly(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = size;
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int read = in.read(buffer, 0, toRead);
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
        skipPadding(in, size);
    }

    private static void skip(InputStream in, long size) throws IOException {
        skipPadding(in, size);
    }

    /** tar 以 512 字节为块对齐，正文之后需要跳过补齐部分。 */
    private static void skipPadding(InputStream in, long size) throws IOException {
        if (size <= 0) {
            return;
        }
        long padding = (BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE;
        long remaining = padding;
        byte[] discard = new byte[BLOCK_SIZE];
        while (remaining > 0) {
            int read = in.read(discard, 0, (int) Math.min(discard.length, remaining));
            if (read < 0) {
                break;
            }
            remaining -= read;
        }
    }

    /** 按 tar 中的权限位恢复文件属性；不支持 POSIX 的文件系统上静默忽略。 */
    private static void applyMode(Path file, int mode) {
        if (mode <= 0) {
            return;
        }
        try {
            Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
            int owner = (mode >> 6) & 0x7;
            int group = (mode >> 3) & 0x7;
            int others = mode & 0x7;
            addPermissions(permissions, owner, PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
            addPermissions(permissions, group, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE);
            addPermissions(permissions, others, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows 等不支持 POSIX 权限的文件系统：忽略
        }
    }

    private static void addPermissions(Set<PosixFilePermission> target, int bits,
                                       PosixFilePermission read, PosixFilePermission write, PosixFilePermission execute) {
        if ((bits & 0x4) != 0) {
            target.add(read);
        }
        if ((bits & 0x2) != 0) {
            target.add(write);
        }
        if ((bits & 0x1) != 0) {
            target.add(execute);
        }
    }

    private static void createSymlinkQuietly(Path link, String linkTarget) {
        try {
            FileUtils.ensureParent(link);
            Files.deleteIfExists(link);
            Files.createSymbolicLink(link, Path.of(linkTarget));
        } catch (IOException | UnsupportedOperationException ignored) {
            // 无权限创建符号链接时跳过：JDK 包中的链接不承载运行必需内容
        }
    }

    /** 把文件复制为另一份（供需要「先解压到临时目录再就位」的调用方使用）。 */
    public static void moveTree(Path source, Path target) throws IOException {
        FileUtils.ensureParent(target);
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.walkFileTree(source, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs)
                        throws IOException {
                    Files.createDirectories(target.resolve(source.relativize(dir)));
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                        throws IOException {
                    Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
            FileUtils.deleteRecursively(source);
        }
    }
}
