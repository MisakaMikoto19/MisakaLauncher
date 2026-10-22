package com.misaka.launcher.core.model;

import java.util.Locale;

/**
 * 模组加载器类型。
 * <p>不同加载器的安装机制差异很大，因此类型本身也承载了「安装策略」的语义：</p>
 * <ul>
 *   <li>{@link #FABRIC} / {@link #QUILT}：直接获取 profile JSON 并合并到版本描述，无需执行安装器；</li>
 *   <li>{@link #FORGE} / {@link #NEOFORGE}：需要解析安装器内的 {@code install_profile.json}，
 *       部分版本还要执行 processors；</li>
 *   <li>{@link #OPTIFINE}：官方 installer 为交互式 GUI，需要特殊处理（下载后引导用户或降级为库合并）。</li>
 * </ul>
 */
public enum LoaderType {

    VANILLA("vanilla", "原版"),
    FORGE("forge", "Forge"),
    NEOFORGE("neoforge", "NeoForge"),
    FABRIC("fabric", "Fabric"),
    QUILT("quilt", "Quilt"),
    OPTIFINE("optifine", "OptiFine");

    private final String id;
    private final String displayName;

    LoaderType(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** 是否为原版（不叠加任何加载器）。 */
    public boolean isVanilla() {
        return this == VANILLA;
    }

    /** 是否通过合并 profile JSON 安装（无需运行安装器）。 */
    public boolean isProfileBased() {
        return this == FABRIC || this == QUILT;
    }

    /** 是否为核心加载器（Forge / NeoForge / Fabric / Quilt）。核心加载器彼此互斥。 */
    public boolean isCore() {
        return this == FORGE || this == NEOFORGE || this == FABRIC || this == QUILT;
    }

    /** 是否为附加项（OptiFine 这类可与核心加载器叠加的）。 */
    public boolean isAddon() {
        return this == OPTIFINE;
    }

    /**
     * 与另一个加载器是否<b>不可共存</b>（用于卡片多选的冲突判定）。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>两个核心加载器互斥（Forge 与 Fabric 不能共存）；</li>
     *   <li>OptiFine 只与 Forge（或原版）共存，与 Fabric / Quilt / NeoForge 冲突。</li>
     * </ul>
     */
    public boolean conflictsWith(LoaderType other) {
        if (other == null || this == other) {
            return false;
        }
        if (isCore() && other.isCore()) {
            return true;
        }
        if (this == OPTIFINE) {
            return other != FORGE;
        }
        if (other == OPTIFINE) {
            return this != FORGE;
        }
        return false;
    }

    /** 面向界面的 i18n 键（{@code install.loader.<id>}）。 */
    public String i18nKey() {
        return "install.loader." + id;
    }

    public static LoaderType fromId(String id) {
        if (id == null || id.isBlank()) {
            return VANILLA;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (LoaderType type : values()) {
            if (type.id.equals(normalized)) {
                return type;
            }
        }
        // 常见别名
        return switch (normalized) {
            case "neoforge", "neo-forge" -> NEOFORGE;
            case "fabricloader", "fabric-loader" -> FABRIC;
            case "optifine_install" -> OPTIFINE;
            default -> VANILLA;
        };
    }
}
