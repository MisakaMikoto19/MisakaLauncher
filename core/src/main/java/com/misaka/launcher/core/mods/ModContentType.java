package com.misaka.launcher.core.mods;

import java.util.List;

/**
 * 内容类型 → 游戏目录的映射。
 */
public enum ModContentType {

    MOD("mods", List.of("mod")),
    MODPACK("modpacks", List.of("modpack")),
    RESOURCE_PACK("resourcepacks", List.of("resourcepack")),
    SHADER("shaderpacks", List.of("shader")),
    DATAPACK("datapacks", List.of("datapack")),
    OTHER("", List.of());

    private final String directory;
    private final List<String> projectTypes;

    ModContentType(String directory, List<String> projectTypes) {
        this.directory = directory;
        this.projectTypes = projectTypes;
    }

    /** 游戏目录内的子目录名。 */
    public String directory() {
        return directory;
    }

    public List<String> projectTypes() {
        return projectTypes;
    }

    /** 由来源的项目类型标识推断内容类型。 */
    public static ModContentType fromProjectTypes(List<String> types) {
        if (types == null || types.isEmpty()) {
            return MOD;
        }
        for (String type : types) {
            if (type == null) {
                continue;
            }
            String normalized = type.toLowerCase(java.util.Locale.ROOT);
            for (ModContentType candidate : values()) {
                if (candidate.projectTypes.contains(normalized)) {
                    return candidate;
                }
            }
            // CurseForge 使用 classId 语义，这里兼容其常见别名
            switch (normalized) {
                case "resourcepack", "texture-packs", "resource_pack" -> {
                    return RESOURCE_PACK;
                }
                case "shader", "shaders" -> {
                    return SHADER;
                }
                case "modpack", "modpacks" -> {
                    return MODPACK;
                }
                default -> {
                    // 继续尝试下一个
                }
            }
        }
        return MOD;
    }
}
