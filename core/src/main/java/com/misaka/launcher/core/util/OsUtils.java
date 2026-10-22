package com.misaka.launcher.core.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 平台差异集中入口：操作系统判定、可执行文件后缀、路径可写性探测。
 * 除本类与 {@link ArchUtils} 外，core 层不应出现任何平台专属判断。
 */
public final class OsUtils {

    /** 受支持的操作系统类别。 */
    public enum Os {
        WINDOWS,
        LINUX,
        MACOS,
        UNKNOWN
    }

    private static final Os CURRENT = detect();
    private static final boolean AARCH64 = ArchUtils.id().equals("aarch64");

    private OsUtils() {
    }

    private static Os detect() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return Os.WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return Os.MACOS;
        }
        if (name.contains("linux") || name.contains("nux")) {
            return Os.LINUX;
        }
        return Os.UNKNOWN;
    }

    public static Os current() {
        return CURRENT;
    }

    public static boolean isWindows() {
        return CURRENT == Os.WINDOWS;
    }

    public static boolean isLinux() {
        return CURRENT == Os.LINUX;
    }

    public static boolean isMacOS() {
        return CURRENT == Os.MACOS;
    }

    public static boolean isAarch64() {
        return AARCH64;
    }

    /**
     * 规范化的系统标识，用于 Java 运行时目录命名与下载源分类。
     *
     * @return {@code windows} / {@code linux} / {@code mac}
     */
    public static String id() {
        return switch (CURRENT) {
            case WINDOWS -> "windows";
            case LINUX -> "linux";
            case MACOS -> "mac";
            case UNKNOWN -> "unknown";
        };
    }

    /**
     * Mojang 版本 JSON 的 {@code rules.os.name} 取值。
     *
     * @return {@code windows} / {@code linux} / {@code osx}
     */
    public static String mojangOsName() {
        return switch (CURRENT) {
            case WINDOWS -> "windows";
            case LINUX -> "linux";
            case MACOS -> "osx";
            case UNKNOWN -> "unknown";
        };
    }

    /** 按平台补全可执行文件后缀，例如 {@code java -> java.exe}。 */
    public static String executableName(String baseName) {
        if (isWindows() && !baseName.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            return baseName + ".exe";
        }
        return baseName;
    }

    /** 判断目录（或其最近存在的父目录）是否可写，用于便携模式回落判定。 */
    public static boolean isWritable(Path dir) {
        Path probe = dir;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe == null) {
            return false;
        }
        return Files.isWritable(probe);
    }

    /** 把路径转成适合展示的字符串（Windows 下反斜杠不会造成困扰，仅做空值保护）。 */
    public static String displayPath(Path path) {
        return path == null ? "" : path.toAbsolutePath().normalize().toString();
    }
}
