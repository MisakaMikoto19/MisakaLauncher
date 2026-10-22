package com.misaka.launcher.core.util;

import java.util.Locale;

/**
 * CPU 架构判定，同时提供 Java 发行版下载所需的架构别名与 Mojang rules 所需的名称。
 */
public final class ArchUtils {

    private static final String ID = detectId();

    private ArchUtils() {
    }

    private static String detectId() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "aarch64";
        }
        if (arch.startsWith("arm")) {
            return "arm";
        }
        if (arch.contains("64")) {
            return "x64";
        }
        return "x86";
    }

    /**
     * 规范化架构标识。
     *
     * @return {@code x64} / {@code x86} / {@code aarch64} / {@code arm}
     */
    public static String id() {
        return ID;
    }

    /** Mojang 版本 JSON 的 {@code rules.os.arch} 取值。 */
    public static String mojangArch() {
        return switch (ID) {
            case "x64" -> "x86_64";
            case "x86" -> "x86";
            case "aarch64" -> "arm64";
            default -> "arm32";
        };
    }

    /** Adoptium（Temurin）API 使用的架构名。 */
    public static String adoptiumArch() {
        return switch (ID) {
            case "x64" -> "x64";
            case "x86" -> "x32";
            case "aarch64" -> "aarch64";
            default -> "arm";
        };
    }

    /**
     * Alibaba Dragonwell 使用的架构名。
     * <p>该发行版仅提供 x86-64 与 aarch64 构建，返回值同时用于拼接资产文件名，
     * 因此不存在的架构必须由调用方（Provider）自行判定并报错，而不是在这里兜底成别的值，
     * 否则会拼出一个注定 404 的地址。</p>
     */
    public static String dragonwellArch() {
        return switch (ID) {
            case "x64" -> "x64";
            case "aarch64" -> "aarch64";
            default -> "";
        };
    }

    /** Azul Zulu 使用的架构名。 */
    public static String zuluArch() {
        return switch (ID) {
            case "x64" -> "x64";
            case "x86" -> "i686";
            case "aarch64" -> "aarch64";
            default -> "arm";
        };
    }

    public static boolean is64Bit() {
        return !ID.equals("x86") && !ID.equals("arm");
    }
}
