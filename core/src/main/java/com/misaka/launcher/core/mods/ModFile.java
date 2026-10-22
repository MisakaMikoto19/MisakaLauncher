package com.misaka.launcher.core.mods;

import java.util.List;

/**
 * 统一的可下载文件（某个项目的一个具体版本）。
 *
 * @param source        来源
 * @param projectId     项目 id
 * @param versionId     版本 id（用于更新比对）
 * @param versionNumber 可读版本号
 * @param name          版本名称
 * @param fileName      落盘文件名
 * @param downloadUrl   直链；CurseForge 需二次解析，可能为空
 * @param sha1          SHA-1（Modrinth 提供，CurseForge 不提供）
 * @param sha512        SHA-512（Modrinth 提供）
 * @param size          字节数
 * @param gameVersions  支持的 Minecraft 版本
 * @param loaders       支持的加载器
 * @param dependencies  依赖声明
 * @param releaseType   发布类型：release / beta / alpha
 * @param datePublished 发布时间（ISO 8601）
 * @param primary       是否为该版本的主文件
 */
public record ModFile(String source,
                      String projectId,
                      String versionId,
                      String versionNumber,
                      String name,
                      String fileName,
                      String downloadUrl,
                      String sha1,
                      String sha512,
                      long size,
                      List<String> gameVersions,
                      List<String> loaders,
                      List<ModDependency> dependencies,
                      String releaseType,
                      String datePublished,
                      boolean primary) {

    public ModFile {
        gameVersions = gameVersions == null ? List.of() : List.copyOf(gameVersions);
        loaders = loaders == null ? List.of() : List.copyOf(loaders);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    /** 是否为稳定版（更新检查时优先选择）。 */
    public boolean isStable() {
        return releaseType == null || "release".equalsIgnoreCase(releaseType);
    }

    /** 是否兼容指定的游戏版本与加载器。 */
    public boolean isCompatible(String gameVersion, String loader) {
        boolean versionOk = gameVersion == null || gameVersion.isBlank()
                || gameVersions.isEmpty() || gameVersions.contains(gameVersion);
        boolean loaderOk = loader == null || loader.isBlank()
                || loaders.isEmpty() || loaders.contains(loader);
        return versionOk && loaderOk;
    }

    /** 必需依赖。 */
    public List<ModDependency> requiredDependencies() {
        return dependencies.stream().filter(ModDependency::isRequired).toList();
    }

    public String displayName() {
        return versionNumber == null || versionNumber.isBlank() ? fileName : versionNumber;
    }

    /**
     * 依赖声明。
     *
     * @param projectId 依赖的项目 id
     * @param versionId 依赖的指定版本 id；为空表示任意版本
     * @param type      依赖类型：{@code required} / {@code optional} / {@code incompatible} / {@code embedded}
     */
    public record ModDependency(String projectId, String versionId, String type) {

        public boolean isRequired() {
            return "required".equalsIgnoreCase(type);
        }

        public boolean isOptional() {
            return "optional".equalsIgnoreCase(type);
        }
    }
}
