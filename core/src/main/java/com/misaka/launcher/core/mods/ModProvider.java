package com.misaka.launcher.core.mods;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * 模组内容源。
 * <p>可插拔设计让「Modrinth 免 Key 直连 / CurseForge 需官方 Key / MC 百科仅跳转」
 * 这三种差异极大的来源能被统一使用。</p>
 */
public interface ModProvider {

    /** 稳定标识。 */
    String id();

    /** 面向用户的名称。 */
    String displayName();

    /**
     * 当前是否可用。
     * <p>CurseForge 需要用户自备 API Key，未配置时应返回 false 并由界面给出指引，
     * 而不是发出注定失败的请求。</p>
     */
    boolean isConfigured();

    /** 是否支持站内检索。MC 百科没有公开 API，因此为 false。 */
    default boolean supportsSearch() {
        return true;
    }

    /** 检索项目。 */
    List<ModProject> search(ModSearchQuery query) throws IOException;

    /**
     * 是否支持「从低到高」的排序方向。
     *
     * <p>CurseForge 有独立的 {@code sortOrder} 参数；Modrinth 的搜索接口没有方向参数
     * （{@code index} 自带固定方向：downloads/follows 高→低、newest/updated 新→旧），
     * 所以它的实现返回 {@code false}，界面据此把方向选择器置灰，而不是给一个
     * 点了没有任何变化的开关。</p>
     */
    default boolean supportsSortDirection() {
        return true;
    }

    /**
     * 该类型内容可用的分类列表（不含 {@link ModCategory#ALL}）。
     *
     * <p>默认空实现：只有真正提供分类筛选的来源才需要覆盖它，界面据此决定
     * 分类下拉是「有得选」还是「只有全部分类」。</p>
     */
    default List<ModCategory> categories(ModContentType type) throws IOException {
        return List.of();
    }

    /** 获取单个项目详情。 */
    Optional<ModProject> project(String projectId) throws IOException;

    /**
     * 列出项目在指定游戏版本/加载器下的可用版本，按发布时间倒序（最新在前）。
     *
     * @param gameVersion 为空表示不过滤
     * @param loader      为空表示不过滤
     */
    List<ModFile> versions(String projectId, String gameVersion, String loader) throws IOException;

    /**
     * 由本地文件哈希反查版本。
     * <p>用途：识别「用户手动放入 mods 目录」的模组，从而让它也能参与更新检查。</p>
     * <p>默认实现返回 empty；Modrinth 用 SHA-1、CurseForge 用指纹。</p>
     */
    default Optional<ModFile> fileByHash(String sha1, String fileName, java.nio.file.Path localFile) throws IOException {
        return Optional.empty();
    }

    /**
     * 精确获取某个文件（更新与整合包安装需要按 id 直接取文件）。
     * <p>默认实现退化为「列出全部版本再按 id 匹配」，CurseForge 则走专门接口以避免多余请求。</p>
     */
    default Optional<ModFile> file(String projectId, String fileId) throws IOException {
        if (fileId == null || fileId.isBlank()) {
            return Optional.empty();
        }
        return versions(projectId, null, null).stream()
                .filter(candidate -> fileId.equals(candidate.versionId()))
                .findFirst();
    }

    /**
     * 解析下载直链。
     * <p>Modrinth 的文件自带直链；CurseForge 需要通过专门接口换取，
     * 且官方要求不得缓存该地址。</p>
     */
    Optional<String> resolveDownloadUrl(ModFile file) throws IOException;
}
