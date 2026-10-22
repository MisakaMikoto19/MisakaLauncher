package com.misaka.launcher.core.mods;

/**
 * 检索条件。
 *
 * @param keyword      关键词
 * @param contentType  内容类型
 * @param gameVersion  游戏版本
 * @param loader       加载器
 * @param categoryId   分类标识（CurseForge 的 categoryId、Modrinth 的分类 slug；空串表示全部分类）
 * @param offset       起始偏移
 * @param limit        返回条数
 * @param sort         排序方式
 */
public record ModSearchQuery(String keyword,
                             ModContentType contentType,
                             String gameVersion,
                             String loader,
                             String categoryId,
                             int offset,
                             int limit,
                             Sort sort,
                             boolean descending) {

    /** 单页条数上限：两个站点都不允许一次取太多（CurseForge 上限 50，Modrinth 100）。 */
    public static final int MAX_LIMIT = 50;

    /** 排序方式；各来源会映射到自家的枚举值。 */
    public enum Sort {
        /**
         * 热门度。
         *
         * <p>下载中心的默认排序。CurseForge 的 Popularity 是它自己的综合热度，
         * Modrinth 没有同名指标，用下载量近似 —— 目标是同一件事：让人一打开
         * 就看到"现在大家在装什么"。</p>
         */
        POPULARITY("popularity"),
        RELEVANCE("relevance"),
        DOWNLOADS("downloads"),
        FOLLOWS("follows"),
        NEWEST("newest"),
        UPDATED("updated");

        private final String id;

        Sort(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Sort fromId(String id) {
            if (id != null) {
                for (Sort value : values()) {
                    if (value.id.equalsIgnoreCase(id)) {
                        return value;
                    }
                }
            }
            return POPULARITY;
        }
    }

    public ModSearchQuery {
        offset = Math.max(0, offset);
        // 上限按两个站点里更严的那个来：超过 CurseForge 的 50 会被它直接 400
        limit = limit <= 0 ? 20 : Math.min(limit, MAX_LIMIT);
        sort = sort == null ? Sort.POPULARITY : sort;
        contentType = contentType == null ? ModContentType.MOD : contentType;
        categoryId = categoryId == null ? "" : categoryId.trim();
        gameVersion = gameVersion == null ? "" : gameVersion.trim();
        loader = loader == null ? "" : loader.trim();
    }

    /** 默认查询：热门内容的第一页（降序）。 */
    public static ModSearchQuery of(String keyword, ModContentType type, String gameVersion, String loader) {
        return new ModSearchQuery(keyword, type, gameVersion, loader, "", 0, 20, Sort.POPULARITY, true);
    }

    public ModSearchQuery withOffset(int newOffset) {
        return new ModSearchQuery(keyword, contentType, gameVersion, loader, categoryId,
                newOffset, limit, sort, descending);
    }

    public ModSearchQuery withLimit(int newLimit) {
        return new ModSearchQuery(keyword, contentType, gameVersion, loader, categoryId,
                offset, newLimit, sort, descending);
    }

    public ModSearchQuery withSort(Sort newSort) {
        return new ModSearchQuery(keyword, contentType, gameVersion, loader, categoryId,
                offset, limit, newSort, descending);
    }

    public ModSearchQuery withCategory(String newCategoryId) {
        // 换分类等于换结果集，偏移必须归零，否则会从"新分类的第 3 页"开始，看起来像没结果
        return new ModSearchQuery(keyword, contentType, gameVersion, loader, newCategoryId,
                0, limit, sort, descending);
    }

    /** 升降序（仅 CurseForge 支持；Modrinth 的排序方向由 index 固定，见其文档）。 */
    public ModSearchQuery withDescending(boolean newDescending) {
        return new ModSearchQuery(keyword, contentType, gameVersion, loader, categoryId,
                offset, limit, sort, newDescending);
    }

    /** 当前是第几页（从 1 开始，仅用于界面展示）。 */
    public int pageNumber() {
        return limit <= 0 ? 1 : (offset / limit) + 1;
    }

    public String safeKeyword() {
        return keyword == null ? "" : keyword.trim();
    }
}
