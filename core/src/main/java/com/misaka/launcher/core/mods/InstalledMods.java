package com.misaka.launcher.core.mods;

import com.misaka.launcher.core.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 已安装模组的扫描与启停。
 *
 * <p><b>「禁用」用改名表达</b>：{@code xxx.jar} ↔ {@code xxx.jar.disabled}。
 * 这是 HMCL / Forge 生态通行的做法 —— 游戏只加载 {@code .jar}，
 * 改名既能真正让模组失效，又保留了随时恢复的能力：不删文件、不改内容、
 * 也不需要安装额外的模组管理插件。</p>
 *
 * <p>扫描是递归的：不少整合包会把模组放在 {@code mods/<版本>/} 这类子目录里。</p>
 */
public final class InstalledMods {

    /** 启用态后缀。 */
    public static final String JAR_SUFFIX = ".jar";

    /** 禁用态后缀；启用态后缀 + {@code .disabled}。 */
    public static final String DISABLED_SUFFIX = JAR_SUFFIX + ".disabled";

    /**
     * 一个已安装模组。
     *
     * @param path       当前文件路径（改名会改变它）
     * @param name       展示名（去掉启用/禁用后缀）
     * @param relative   相对模组目录的路径，子目录里的模组也能显示来源
     * @param enabled    是否启用
     * @param size       字节数；读取失败为 0
     * @param modifiedAt 最后修改时间（毫秒）；读取失败为 0
     */
    public record Entry(Path path, String name, String relative, boolean enabled,
                        long size, long modifiedAt) {

        /** 展示用文件名（含后缀，便于用户与资源管理器对照）。 */
        public String fileName() {
            return path.getFileName().toString();
        }
    }

    private InstalledMods() {
    }

    /**
     * 扫描模组目录。
     *
     * @param modsDir 模组目录；不存在或不可读时返回空列表
     * @return 已启用在前、同状态按名称排序
     */
    public static List<Entry> scan(Path modsDir) {
        if (modsDir == null || !Files.isDirectory(modsDir)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        for (Path file : FileUtils.listFilesRecursively(modsDir)) {
            String fileName = file.getFileName().toString();
            Boolean enabled = stateOf(fileName);
            if (enabled == null) {
                continue;
            }
            long size = 0L;
            long modified = 0L;
            try {
                size = Files.size(file);
                modified = Files.getLastModifiedTime(file).toMillis();
            } catch (IOException ignored) {
                // 文件被占用或已删除：仍然列出，只是没有大小与时间
            }
            String relative = modsDir.relativize(file).toString();
            entries.add(new Entry(file, displayName(fileName), relative, enabled, size, modified));
        }
        entries.sort(Comparator.comparing(Entry::enabled).reversed()
                .thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT)));
        return List.copyOf(entries);
    }

    /**
     * 启用 / 禁用：改名。
     *
     * @param  entry   目标条目
     * @param  enabled 目标状态
     * @return 改名后的路径；已经处于目标状态时返回原路径
     * @throws IOException 改名失败，或目标文件名已被占用
     */
    public static Path setEnabled(Entry entry, boolean enabled) throws IOException {
        String fileName = entry.path().getFileName().toString();
        String targetName;
        if (enabled) {
            if (!isDisabled(fileName)) {
                return entry.path();
            }
            targetName = fileName.substring(0, fileName.length() - ".disabled".length());
        } else {
            if (!isJar(fileName)) {
                return entry.path();
            }
            targetName = fileName + ".disabled";
        }

        Path target = entry.path().resolveSibling(targetName);
        // 两种状态并存（用户手工复制过一份）时直接改名会覆盖，宁可报错让用户自己决定
        if (Files.exists(target)) {
            throw new IOException("目标文件已存在：" + targetName);
        }
        return Files.move(entry.path(), target);
    }

    /** 文件名对应的启用状态；不是模组文件返回 {@code null}。 */
    public static Boolean stateOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        if (isDisabled(fileName)) {
            return Boolean.FALSE;
        }
        return isJar(fileName) ? Boolean.TRUE : null;
    }

    /** 去掉启用 / 禁用后缀后的展示名。 */
    static String displayName(String fileName) {
        if (isDisabled(fileName)) {
            return fileName.substring(0, fileName.length() - DISABLED_SUFFIX.length());
        }
        if (isJar(fileName)) {
            return fileName.substring(0, fileName.length() - JAR_SUFFIX.length());
        }
        return fileName;
    }

    private static boolean isDisabled(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(DISABLED_SUFFIX);
    }

    private static boolean isJar(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(JAR_SUFFIX);
    }
}
