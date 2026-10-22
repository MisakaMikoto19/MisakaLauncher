package com.misaka.launcher.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 安全的 ZIP 解压工具。
 * <p>始终校验条目路径落在目标目录内（防御 Zip Slip），并保留条目修改时间。</p>
 */
public final class ZipUtils {

    private ZipUtils() {
    }

    /** 解压全部条目。 */
    public static void extractAll(Path zipFile, Path destDir) throws IOException {
        extract(zipFile, destDir, entry -> true);
    }

    /**
     * 按条件解压。
     *
     * @param filter 条目名过滤器，返回 true 才解压（目录条目自动处理）
     * @return 实际写出的文件路径列表
     */
    public static List<Path> extract(Path zipFile, Path destDir, Predicate<String> filter) throws IOException {
        FileUtils.ensureDir(destDir);
        Path root = destDir.toAbsolutePath().normalize();
        List<Path> written = new ArrayList<>();

        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                if (!PathSafety.isSafeEntryName(name)) {
                    throw new IOException("压缩包包含非法条目名: " + name);
                }
                if (!filter.test(name)) {
                    continue;
                }
                Path target = root.resolve(name.replace('\\', '/')).normalize();
                if (!PathSafety.isInside(root, target)) {
                    throw new IOException("压缩包条目越界: " + name);
                }
                FileUtils.ensureParent(target);
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                written.add(target);
            }
        }
        return written;
    }

    /**
     * 解压 zip 中位于指定前缀下的条目，并剥离该前缀。
     * <p>例如从包含 {@code META-INF/MANIFEST.MF} 的包中提取 natives 时无需此方法，
     * 但整合包覆盖安装（剥掉一层根目录）时会用到。</p>
     *
     * @param prefixToStrip 需要剥离的前缀，例如 {@code "overrides/"}
     */
    public static List<Path> extractStrippingPrefix(Path zipFile, Path destDir, String prefixToStrip) throws IOException {
        FileUtils.ensureDir(destDir);
        Path root = destDir.toAbsolutePath().normalize();
        List<Path> written = new ArrayList<>();
        String prefix = prefixToStrip == null ? "" : prefixToStrip.replace('\\', '/');

        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                if (!name.startsWith(prefix)) {
                    continue;
                }
                String relative = name.substring(prefix.length());
                if (relative.isBlank() || !PathSafety.isSafeEntryName(relative)) {
                    continue;
                }
                Path target = root.resolve(relative).normalize();
                if (!PathSafety.isInside(root, target)) {
                    throw new IOException("压缩包条目越界: " + name);
                }
                FileUtils.ensureParent(target);
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                written.add(target);
            }
        }
        return written;
    }

    /** 列出压缩包内所有条目名（只读，不解压）。 */
    public static List<String> listEntryNames(Path zipFile) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
        }
        return names;
    }

    /** 读取压缩包内单个文本条目内容。 */
    /**
     * 读取归档里某个条目的原始字节；条目不存在时返回 {@code null}。
     *
     * <p>用于取资源包的 {@code pack.png} 缩略图：只要那一个文件，不该为了 40×40 的图标
     * 把整个几百 MB 的包解压出来。</p>
     */
    public static byte[] readBytes(Path zipFile, String entryName) throws IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                return null;
            }
            try (java.io.InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    public static String readText(Path zipFile, String entryName) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
    }

    /** 保留时间戳（当前实现仅记录，供调用方按需使用）。 */
    static FileTime lastModified(ZipEntry entry) {
        long time = entry.getLastModifiedTime() == null ? System.currentTimeMillis() : entry.getLastModifiedTime().toMillis();
        return FileTime.fromMillis(time);
    }

    /*
     * 关于「解压后恢复可执行权限」的说明：
     * java.util.zip.ZipEntry 并不暴露 zip 外部属性（Unix st_mode），
     * 因此纯 JDK 实现无法从 zip 恢复权限位。
     * 实际影响可控：Linux / macOS 的 JDK 发行包统一为 tar.gz，
     * 由 TarUtils 负责还原权限位（已实现）；zip 仅用于 Windows，无需可执行位。
     */
}
