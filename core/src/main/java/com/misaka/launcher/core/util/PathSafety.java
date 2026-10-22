package com.misaka.launcher.core.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 路径安全工具，语义对齐 FileSync 服务端的 {@code get_safe_file_path}：
 * 解析后的真实路径必须严格位于允许根目录之内，防止 {@code ../} 目录穿越。
 */
public final class PathSafety {

    private PathSafety() {
    }

    /** 判断 {@code child} 是否位于 {@code root} 之内（含 root 自身）。 */
    public static boolean isInside(Path root, Path child) {
        if (root == null || child == null) {
            return false;
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedChild = child.toAbsolutePath().normalize();
        return normalizedChild.equals(normalizedRoot) || normalizedChild.startsWith(normalizedRoot);
    }

    /**
     * 将相对路径安全地解析到根目录下。
     *
     * @param root           允许的根目录
     * @param relativePath   相对路径，允许使用 {@code /} 或 {@code \}，可带 {@code ./} 前缀
     * @param allowAbsolute  是否允许传入绝对路径（仅同步场景需为 true，用于已解析的本地路径）
     * @throws IOException 路径越界时抛出
     */
    public static Path resolveInside(Path root, String relativePath, boolean allowAbsolute) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IOException("路径为空");
        }
        String cleaned = relativePath.replace('\\', '/');
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        Path candidate;
        Path path = Path.of(cleaned.replace('/', java.io.File.separatorChar));
        if (path.isAbsolute()) {
            if (!allowAbsolute) {
                throw new IOException("不允许使用绝对路径: " + relativePath);
            }
            candidate = path;
        } else {
            candidate = root.resolve(path);
        }
        Path resolved = candidate.toAbsolutePath().normalize();
        if (!isInside(root, resolved)) {
            throw new IOException("路径越界（疑似目录穿越）: " + relativePath);
        }
        return resolved;
    }

    /** 校验压缩包条目名是否安全（拒绝绝对路径、盘符与上跳）。 */
    public static boolean isSafeEntryName(String entryName) {
        if (entryName == null || entryName.isBlank()) {
            return false;
        }
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/")) {
            return false;
        }
        if (normalized.length() > 1 && normalized.charAt(1) == ':') {
            return false;
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                return false;
            }
        }
        return !normalized.toLowerCase(Locale.ROOT).contains("\u0000");
    }
}
