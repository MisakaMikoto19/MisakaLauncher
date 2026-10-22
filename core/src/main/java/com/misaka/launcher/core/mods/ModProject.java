package com.misaka.launcher.core.mods;

import java.util.List;

/**
 * 统一的项目模型（模组 / 整合包 / 资源包 / 光影）。
 * <p>不同来源的字段名差异很大，因此各 Provider 负责把自家响应归一化到本模型，
 * 上层界面与安装逻辑只面向这一种结构。</p>
 *
 * @param source       来源：{@code modrinth} / {@code curseforge} / {@code mcmod}
 * @param id           来源内的项目 id
 * @param slug         可读标识（Modrinth 有；CurseForge 用 slug 字段）
 * @param title        名称
 * @param description  简介
 * @param author       作者
 * @param iconUrl      图标地址
 * @param downloads    下载量
 * @param follows      关注量
 * @param categories   分类标签
 * @param gameVersions 支持的 Minecraft 版本
 * @param loaders      支持的加载器
 * @param projectTypes 项目类型（mod / modpack / resourcepack / shader）
 * @param updatedAt    最近更新时间（ISO 8601 字符串）
 * @param pageUrl      详情页地址
 * @param license      许可证
 */
public record ModProject(String source,
                         String id,
                         String slug,
                         String title,
                         String description,
                         String author,
                         String iconUrl,
                         long downloads,
                         long follows,
                         List<String> categories,
                         List<String> gameVersions,
                         List<String> loaders,
                         List<String> projectTypes,
                         String updatedAt,
                         String pageUrl,
                         String license) {

    public ModProject {
        categories = categories == null ? List.of() : List.copyOf(categories);
        gameVersions = gameVersions == null ? List.of() : List.copyOf(gameVersions);
        loaders = loaders == null ? List.of() : List.copyOf(loaders);
        projectTypes = projectTypes == null ? List.of() : List.copyOf(projectTypes);
    }

    /** 归属的游戏内容目录类型。 */
    public ModContentType contentType() {
        return ModContentType.fromProjectTypes(projectTypes);
    }

    /** 是否支持指定游戏版本与加载器。 */
    public boolean supports(String gameVersion, String loader) {
        boolean versionOk = gameVersion == null || gameVersion.isBlank() || gameVersions.contains(gameVersion);
        boolean loaderOk = loader == null || loader.isBlank() || loaders.contains(loader);
        return versionOk && loaderOk;
    }

    /** 展示用副标题：作者 + 下载量。 */
    public String subtitle() {
        return (author == null || author.isBlank() ? "" : author + " · ") + downloads + " 次下载";
    }
}
