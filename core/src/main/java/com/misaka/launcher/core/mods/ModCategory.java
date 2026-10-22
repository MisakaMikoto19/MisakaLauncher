package com.misaka.launcher.core.mods;

/**
 * 一个可筛选的分类（如「魔法」「科技」「冒险」）。
 *
 * @param id   来源侧使用的标识：CurseForge 是数字 categoryId 的字符串形式，
 *             Modrinth 是分类 slug（如 {@code adventure}）
 * @param name 展示名
 */
public record ModCategory(String id, String name) implements Comparable<ModCategory> {

    /** 「全部分类」用的哨兵：id 为空串，界面据此不传筛选参数。 */
    public static final ModCategory ALL = new ModCategory("", "");

    public boolean isAll() {
        return id == null || id.isBlank();
    }

    @Override
    public int compareTo(ModCategory other) {
        return name == null ? -1 : other.name == null ? 1 : name.compareTo(other.name);
    }
}
