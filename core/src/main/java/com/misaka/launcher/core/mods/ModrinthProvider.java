package com.misaka.launcher.core.mods;

import com.fasterxml.jackson.databind.JsonNode;
import com.misaka.launcher.core.util.HttpUtils;
import com.misaka.launcher.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Modrinth v2 内容源。
 * <p>优势：无需 API Key、直链可下载、提供 SHA-1/SHA-512 校验值（可做完整性校验），
 * 因此作为默认首选来源。</p>
 * <p>接口约束：官方要求请求携带可识别的 User-Agent，本项目在 {@link HttpUtils} 中统一注入。</p>
 */
public final class ModrinthProvider implements ModProvider {

    public static final String ID = "modrinth";

    private static final Logger log = LoggerFactory.getLogger(ModrinthProvider.class);

    private static final String OFFICIAL_API = "https://api.modrinth.com/v2";
    private static final String SITE = "https://modrinth.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** 接口根地址；可通过构造器覆盖，便于单测注入本地桩。 */
    private final String apiBase;

    public ModrinthProvider() {
        this(OFFICIAL_API);
    }

    public ModrinthProvider(String apiBase) {
        String normalized = apiBase == null || apiBase.isBlank() ? OFFICIAL_API : apiBase.trim();
        this.apiBase = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Modrinth";
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    // ------------------------------------------------------------------
    // 检索
    // ------------------------------------------------------------------

    @Override
    public List<ModProject> search(ModSearchQuery query) throws IOException {
        List<String> facetGroups = new ArrayList<>();
        // 每个 facet 组内部为 OR，组之间为 AND
        facetGroups.add(facet(query.contentType().projectTypes().get(0), "project_type"));
        if (query.gameVersion() != null && !query.gameVersion().isBlank()) {
            facetGroups.add(facet(query.gameVersion(), "versions"));
        }
        if (query.loader() != null && !query.loader().isBlank()) {
            facetGroups.add(facet(query.loader(), "categories"));
        }
        // 分类与加载器同属 categories 维度，但语义不同：这里要用 OR 单独成组，
        // 否则会和 loader 落进同一个 facet 组，变成「加载器 或 分类」而放宽结果
        if (query.categoryId() != null && !query.categoryId().isBlank()) {
            facetGroups.add(facet(query.categoryId(), "categories"));
        }

        StringBuilder url = new StringBuilder(apiBase).append("/search")
                .append("?query=").append(encode(query.safeKeyword()))
                .append("&limit=").append(query.limit())
                .append("&offset=").append(query.offset())
                // Modrinth 没有 popularity 这个 index，用下载量近似（见 ModSearchQuery.Sort）
                .append("&index=").append("popularity".equals(query.sort().id())
                        ? "downloads" : query.sort().id());
        if (!facetGroups.isEmpty()) {
            url.append("&facets=").append(encode("[" + String.join(",", facetGroups) + "]"));
        }

        JsonNode root = getJson(url.toString());
        List<ModProject> projects = new ArrayList<>();
        JsonNode hits = root.get("hits");
        if (hits != null && hits.isArray()) {
            for (JsonNode hit : hits) {
                projects.add(parseSearchHit(hit));
            }
        }
        return projects;
    }

    /**
     * Modrinth 的检索接口没有排序方向参数（见 {@link ModProvider#supportsSortDirection()}）。
     */
    @Override
    public boolean supportsSortDirection() {
        return false;
    }

    /**
     * 分类列表（{@code GET /tag/category}）。
     *
     * <p>Modrinth 的分类是带 {@code project_type} 的全局列表（同一个 "adventure"
     * 同时存在于模组与整合包里），必须按类型过滤，否则下拉框里会混进
     * 对当前内容没有意义的选项。</p>
     */
    @Override
    public List<ModCategory> categories(ModContentType type) throws IOException {
        String projectType = type == null || type.projectTypes().isEmpty()
                ? "mod" : type.projectTypes().get(0);
        JsonNode root = getJson(apiBase + "/tag/category");
        List<ModCategory> categories = new ArrayList<>();
        if (root != null && root.isArray()) {
            for (JsonNode category : root) {
                if (!projectType.equalsIgnoreCase(JsonUtils.text(category, "project_type", ""))) {
                    continue;
                }
                String name = JsonUtils.text(category, "name", "");
                if (!name.isBlank()) {
                    categories.add(new ModCategory(name, name));
                }
            }
        }
        categories.sort(Comparator.naturalOrder());
        return categories;
    }

    private static String facet(String value, String field) {
        return "[\"" + field + ":" + value + "\"]";
    }

    private static ModProject parseSearchHit(JsonNode hit) {
        String slug = JsonUtils.text(hit, "slug", "");
        String id = JsonUtils.text(hit, "project_id", "");
        return new ModProject(ID, id, slug,
                JsonUtils.text(hit, "title", ""),
                JsonUtils.text(hit, "description", ""),
                JsonUtils.text(hit, "author", ""),
                JsonUtils.text(hit, "icon_url", ""),
                JsonUtils.longValue(hit, "downloads", 0L),
                JsonUtils.longValue(hit, "follows", 0L),
                JsonUtils.stringList(hit, "display_categories"),
                JsonUtils.stringList(hit, "versions"),
                JsonUtils.stringList(hit, "categories"),
                JsonUtils.stringList(hit, "all_project_types"),
                JsonUtils.text(hit, "date_modified", ""),
                slug.isBlank() ? "" : SITE + "/project/" + slug,
                JsonUtils.text(hit, "license", ""));
    }

    // ------------------------------------------------------------------
    // 详情
    // ------------------------------------------------------------------

    @Override
    public Optional<ModProject> project(String projectId) throws IOException {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = getJson(apiBase + "/project/" + encode(projectId));
            String slug = JsonUtils.text(root, "slug", "");
            return Optional.of(new ModProject(ID,
                    JsonUtils.text(root, "id", projectId),
                    slug,
                    JsonUtils.text(root, "title", ""),
                    JsonUtils.text(root, "description", ""),
                    JsonUtils.text(root, "team", ""),
                    JsonUtils.text(root, "icon_url", ""),
                    JsonUtils.longValue(root, "downloads", 0L),
                    JsonUtils.longValue(root, "followers", 0L),
                    JsonUtils.stringList(root, "categories"),
                    JsonUtils.stringList(root, "game_versions"),
                    JsonUtils.stringList(root, "loaders"),
                    JsonUtils.stringList(root, "project_type"),
                    JsonUtils.text(root, "updated", ""),
                    slug.isBlank() ? "" : SITE + "/project/" + slug,
                    readLicense(root)));
        } catch (IOException e) {
            log.debug("获取 Modrinth 项目失败 {}: {}", projectId, e.getMessage());
            return Optional.empty();
        }
    }

    private static String readLicense(JsonNode root) {
        JsonNode license = root.get("license");
        return license == null ? "" : JsonUtils.text(license, "id", "");
    }

    // ------------------------------------------------------------------
    // 版本
    // ------------------------------------------------------------------

    @Override
    public List<ModFile> versions(String projectId, String gameVersion, String loader) throws IOException {
        StringBuilder url = new StringBuilder(apiBase).append("/project/").append(encode(projectId)).append("/version");
        List<String> params = new ArrayList<>();
        if (gameVersion != null && !gameVersion.isBlank()) {
            params.add("game_versions=" + encode("[\"" + gameVersion + "\"]"));
        }
        if (loader != null && !loader.isBlank()) {
            params.add("loaders=" + encode("[\"" + loader + "\"]"));
        }
        if (!params.isEmpty()) {
            url.append('?').append(String.join("&", params));
        }

        JsonNode root = getJson(url.toString());
        List<ModFile> files = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode version : root) {
                files.add(parseVersion(version));
            }
        }
        return files;
    }

    private ModFile parseVersion(JsonNode version) {
        String projectId = JsonUtils.text(version, "project_id", "");
        List<String> gameVersions = JsonUtils.stringList(version, "game_versions");
        List<String> loaders = JsonUtils.stringList(version, "loaders");

        JsonNode files = version.get("files");
        String fileName = "";
        String downloadUrl = "";
        String sha1 = "";
        String sha512 = "";
        long size = 0L;
        boolean primary = false;
        if (files != null && files.isArray() && !files.isEmpty()) {
            JsonNode chosen = null;
            for (JsonNode file : files) {
                if (JsonUtils.boolValue(file, "primary", false)) {
                    chosen = file;
                    break;
                }
            }
            if (chosen == null) {
                chosen = files.get(0);
            }
            fileName = JsonUtils.text(chosen, "filename", "");
            downloadUrl = JsonUtils.text(chosen, "url", "");
            size = JsonUtils.longValue(chosen, "size", 0L);
            primary = JsonUtils.boolValue(chosen, "primary", false);
            JsonNode hashes = chosen.get("hashes");
            if (hashes != null) {
                sha1 = JsonUtils.text(hashes, "sha1", "");
                sha512 = JsonUtils.text(hashes, "sha512", "");
            }
        }

        List<ModFile.ModDependency> dependencies = new ArrayList<>();
        JsonNode deps = version.get("dependencies");
        if (deps != null && deps.isArray()) {
            for (JsonNode dep : deps) {
                dependencies.add(new ModFile.ModDependency(
                        JsonUtils.text(dep, "project_id", ""),
                        JsonUtils.text(dep, "version_id", ""),
                        JsonUtils.text(dep, "dependency_type", "optional")));
            }
        }

        return new ModFile(ID, projectId,
                JsonUtils.text(version, "id", ""),
                JsonUtils.text(version, "version_number", ""),
                JsonUtils.text(version, "name", ""),
                fileName, downloadUrl, sha1, sha512, size,
                gameVersions, loaders, dependencies,
                JsonUtils.text(version, "version_type", "release"),
                JsonUtils.text(version, "date_published", ""),
                primary);
    }

    // ------------------------------------------------------------------
    // 反查与直链
    // ------------------------------------------------------------------

    @Override
    public Optional<ModFile> fileByHash(String sha1, String fileName, Path localFile) throws IOException {
        if (sha1 == null || sha1.isBlank()) {
            return Optional.empty();
        }
        String body = JsonUtils.write(Map.of("hashes", List.of(sha1), "algorithm", "sha1"));
        HttpUtils.Result result = HttpUtils.send("POST", apiBase + "/version_files", Map.of(), body,
                HttpUtils.JSON_CONTENT_TYPE, TIMEOUT);
        if (!result.isSuccess()) {
            return Optional.empty();
        }
        JsonNode root = result.json();
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getValue().isObject()) {
                return Optional.of(parseVersion(entry.getValue()));
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> resolveDownloadUrl(ModFile file) {
        // Modrinth 的文件自带直链，无需二次解析
        return file.downloadUrl() == null || file.downloadUrl().isBlank()
                ? Optional.empty() : Optional.of(file.downloadUrl());
    }

    // ------------------------------------------------------------------

    private JsonNode getJson(String url) throws IOException {
        HttpUtils.Result result = HttpUtils.send("GET", url, Map.of("Accept", "application/json"),
                null, null, TIMEOUT);
        if (!result.isSuccess()) {
            throw new IOException("Modrinth 请求失败：" + result.summary());
        }
        try {
            return result.json();
        } catch (IOException e) {
            throw new IOException("Modrinth 响应不是合法 JSON：" + e.getMessage(), e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 详情页地址，供「在浏览器打开」使用。 */
    public static String siteUrl(String slugOrId) {
        return SITE + "/project/" + slugOrId;
    }
}
