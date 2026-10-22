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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * CurseForge 官方 Core API 内容源。
 * <p>使用前提：<b>必须由用户自备 API Key</b>（官方要求，无法绕过）。未配置时
 * {@link #isConfigured()} 返回 false，界面据此给出申请指引而不发起请求。</p>
 * <p>两处与 Modrinth 的重要差异：</p>
 * <ul>
 *   <li>文件列表里通常没有直链，需要通过 {@code /mods/{id}/files/{fileId}/download-url}
 *       换取（且官方要求不得缓存该地址），部分作者会禁止第三方下载；</li>
 *   <li>没有 SHA-1，用「MurmurHash2 指纹」识别本地文件。</li>
 * </ul>
 */
public final class CurseForgeProvider implements ModProvider {

    public static final String ID = "curseforge";

    private static final Logger log = LoggerFactory.getLogger(CurseForgeProvider.class);

    private static final String API = "https://api.curseforge.com/v1";
    private static final String SITE = "https://www.curseforge.com/minecraft";
    /** Minecraft 在 CurseForge 上的游戏 id。 */
    private static final int GAME_ID = 432;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final Supplier<String> apiKeySupplier;

    public CurseForgeProvider(Supplier<String> apiKeySupplier) {
        this.apiKeySupplier = apiKeySupplier;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "CurseForge";
    }

    @Override
    public boolean isConfigured() {
        String key = apiKeySupplier.get();
        return key != null && !key.isBlank();
    }

    // ------------------------------------------------------------------
    // 检索
    // ------------------------------------------------------------------

    @Override
    public List<ModProject> search(ModSearchQuery query) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("gameId", String.valueOf(GAME_ID));
        params.put("classId", String.valueOf(classId(query.contentType())));
        params.put("searchFilter", query.safeKeyword());
        params.put("index", String.valueOf(query.offset()));
        params.put("pageSize", String.valueOf(query.limit()));
        params.put("sortField", String.valueOf(sortField(query.sort())));
        // CurseForge 支持方向：asc = 从低到高（例如下载量最少的在前）
        params.put("sortOrder", query.descending() ? "desc" : "asc");
        if (query.gameVersion() != null && !query.gameVersion().isBlank()) {
            params.put("gameVersion", query.gameVersion());
        }
        int loaderType = modLoaderType(query.loader());
        if (loaderType > 0) {
            params.put("modLoaderType", String.valueOf(loaderType));
        }
        // 分类是可选的：空串表示「全部分类」，不能传 categoryId=（会被当成 0 而筛出空结果）
        if (query.categoryId() != null && !query.categoryId().isBlank()) {
            params.put("categoryId", query.categoryId());
        }

        JsonNode root = getJson("/mods/search?" + toQuery(params));
        List<ModProject> projects = new ArrayList<>();
        JsonNode data = root.get("data");
        if (data != null && data.isArray()) {
            for (JsonNode mod : data) {
                projects.add(parseMod(mod, query.contentType()));
            }
        }
        return projects;
    }

    @Override
    public Optional<ModProject> project(String projectId) throws IOException {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = getJson("/mods/" + encode(projectId));
            JsonNode data = root.get("data");
            return data == null ? Optional.empty() : Optional.of(parseMod(data, ModContentType.MOD));
        } catch (IOException e) {
            log.debug("获取 CurseForge 项目失败 {}: {}", projectId, e.getMessage());
            return Optional.empty();
        }
    }

    private ModProject parseMod(JsonNode mod, ModContentType fallbackType) {
        String slug = JsonUtils.text(mod, "slug", "");
        int classId = JsonUtils.intValue(mod, "classId", classId(fallbackType));
        ModContentType type = contentTypeOfClassId(classId);

        List<String> gameVersions = new ArrayList<>();
        List<String> loaders = new ArrayList<>();
        JsonNode latestIndexes = mod.get("latestFilesIndexes");
        if (latestIndexes != null && latestIndexes.isArray()) {
            for (JsonNode index : latestIndexes) {
                String gameVersion = JsonUtils.text(index, "gameVersion", "");
                if (!gameVersion.isBlank() && !gameVersions.contains(gameVersion)) {
                    gameVersions.add(gameVersion);
                }
                int loader = JsonUtils.intValue(index, "modLoader", 0);
                String loaderName = loaderName(loader);
                if (!loaderName.isBlank() && !loaders.contains(loaderName)) {
                    loaders.add(loaderName);
                }
            }
        }

        List<String> categories = new ArrayList<>();
        JsonNode categoryNodes = mod.get("categories");
        if (categoryNodes != null && categoryNodes.isArray()) {
            for (JsonNode category : categoryNodes) {
                categories.add(JsonUtils.text(category, "name", ""));
            }
        }

        String author = "";
        JsonNode authors = mod.get("authors");
        if (authors != null && authors.isArray() && !authors.isEmpty()) {
            author = JsonUtils.text(authors.get(0), "name", "");
        }

        return new ModProject(ID,
                String.valueOf(JsonUtils.longValue(mod, "id", 0L)),
                slug,
                JsonUtils.text(mod, "name", ""),
                JsonUtils.text(mod, "summary", ""),
                author,
                readIconUrl(mod),
                JsonUtils.longValue(mod, "downloadCount", 0L),
                JsonUtils.longValue(mod, "thumbsUpCount", 0L),
                categories, gameVersions, loaders,
                type.projectTypes(),
                JsonUtils.text(mod, "dateModified", ""),
                slug.isBlank() ? "" : SITE + "/" + type.directory() + "/" + slug,
                "");
    }

    private static String readIconUrl(JsonNode mod) {
        JsonNode logo = mod.get("logo");
        return logo == null ? "" : JsonUtils.text(logo, "thumbnailUrl", JsonUtils.text(logo, "url", ""));
    }

    // ------------------------------------------------------------------
    // 版本
    // ------------------------------------------------------------------

    @Override
    public List<ModFile> versions(String projectId, String gameVersion, String loader) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pageSize", "50");
        if (gameVersion != null && !gameVersion.isBlank()) {
            params.put("gameVersion", gameVersion);
        }
        int loaderType = modLoaderType(loader);
        if (loaderType > 0) {
            params.put("modLoaderType", String.valueOf(loaderType));
        }

        JsonNode root = getJson("/mods/" + encode(projectId) + "/files?" + toQuery(params));
        List<ModFile> files = new ArrayList<>();
        JsonNode data = root.get("data");
        if (data != null && data.isArray()) {
            for (JsonNode file : data) {
                files.add(parseFile(projectId, file));
            }
        }
        // 官方返回即为时间倒序，这里再显式排序以保证行为稳定
        files.sort((a, b) -> b.datePublished().compareTo(a.datePublished()));
        return files;
    }

    private ModFile parseFile(String projectId, JsonNode file) {
        String downloadUrl = JsonUtils.text(file, "downloadUrl", "");
        int releaseType = JsonUtils.intValue(file, "releaseType", 1);
        return new ModFile(ID, projectId,
                String.valueOf(JsonUtils.longValue(file, "id", 0L)),
                JsonUtils.text(file, "displayName", ""),
                JsonUtils.text(file, "displayName", ""),
                JsonUtils.text(file, "fileName", ""),
                downloadUrl, "", "", JsonUtils.longValue(file, "fileLength", 0L),
                JsonUtils.stringList(file, "gameVersions"),
                parseLoaders(file),
                parseDependencies(file),
                switch (releaseType) {
                    case 2 -> "beta";
                    case 3 -> "alpha";
                    default -> "release";
                },
                JsonUtils.text(file, "fileDate", ""),
                true);
    }

    private static List<String> parseLoaders(JsonNode file) {
        List<String> loaders = new ArrayList<>();
        for (String version : JsonUtils.stringList(file, "gameVersions")) {
            String name = switch (version) {
                case "Forge" -> "forge";
                case "Fabric" -> "fabric";
                case "Quilt" -> "quilt";
                case "NeoForge" -> "neoforge";
                default -> "";
            };
            if (!name.isBlank() && !loaders.contains(name)) {
                loaders.add(name);
            }
        }
        return loaders;
    }

    private static List<ModFile.ModDependency> parseDependencies(JsonNode file) {
        List<ModFile.ModDependency> dependencies = new ArrayList<>();
        JsonNode nodes = file.get("dependencies");
        if (nodes == null || !nodes.isArray()) {
            return dependencies;
        }
        for (JsonNode node : nodes) {
            String projectId = String.valueOf(JsonUtils.longValue(node, "modId", 0L));
            String versionId = String.valueOf(JsonUtils.longValue(node, "fileId", 0L));
            if ("0".equals(versionId)) {
                versionId = "";
            }
            String type = switch (JsonUtils.intValue(node, "relationType", 0)) {
                case 3 -> "required";
                case 2 -> "optional";
                case 5 -> "incompatible";
                case 1 -> "embedded";
                default -> "optional";
            };
            dependencies.add(new ModFile.ModDependency(projectId, versionId, type));
        }
        return dependencies;
    }

    // ------------------------------------------------------------------
    // 指纹反查与直链解析
    // ------------------------------------------------------------------

    @Override
    public Optional<ModFile> fileByHash(String sha1, String fileName, Path localFile) throws IOException {
        if (localFile == null || !java.nio.file.Files.isRegularFile(localFile)) {
            return Optional.empty();
        }
        long fingerprint;
        try {
            fingerprint = MurmurHash2.fingerprint(localFile);
        } catch (IOException e) {
            log.debug("计算指纹失败 {}: {}", localFile, e.getMessage());
            return Optional.empty();
        }

        String body = JsonUtils.write(Map.of("fingerprints", List.of(fingerprint)));
        HttpUtils.Result result = HttpUtils.send("POST", API + "/fingerprints", authHeaders(), body,
                HttpUtils.JSON_CONTENT_TYPE, TIMEOUT);
        if (!result.isSuccess()) {
            log.debug("指纹反查失败：{}", result.summary());
            return Optional.empty();
        }
        JsonNode matches = result.json().path("data").path("exactMatches");
        if (!matches.isArray() || matches.isEmpty()) {
            return Optional.empty();
        }
        JsonNode first = matches.get(0);
        JsonNode file = first.get("file");
        if (file == null) {
            return Optional.empty();
        }
        return Optional.of(parseFile(String.valueOf(JsonUtils.longValue(first, "id", 0L)), file));
    }

    @Override
    public Optional<ModFile> file(String projectId, String fileId) throws IOException {
        if (projectId == null || projectId.isBlank() || fileId == null || fileId.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = getJson("/mods/" + encode(projectId) + "/files/" + encode(fileId));
            JsonNode data = root.get("data");
            return data == null ? Optional.empty() : Optional.of(parseFile(projectId, data));
        } catch (IOException e) {
            log.debug("获取 CurseForge 文件失败 {}/{}: {}", projectId, fileId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> resolveDownloadUrl(ModFile file) throws IOException {
        if (file.downloadUrl() != null && !file.downloadUrl().isBlank()) {
            return Optional.of(file.downloadUrl());
        }
        try {
            JsonNode root = getJson("/mods/" + encode(file.projectId()) + "/files/"
                    + encode(file.versionId()) + "/download-url");
            String url = JsonUtils.text(root, "data", "");
            return url.isBlank() ? Optional.empty() : Optional.of(url);
        } catch (IOException e) {
            // 常见原因：作者禁止第三方下载，或 Key 权限不足
            log.warn("获取 CurseForge 下载地址失败（可能是作者禁止第三方下载）：{}", e.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // 映射与请求
    // ------------------------------------------------------------------

    /** 内容类型 → classId。 */
    public static int classId(ModContentType type) {
        return switch (type) {
            case MODPACK -> 4471;
            case RESOURCE_PACK -> 12;
            case SHADER -> 6552;
            case DATAPACK -> 6945;
            default -> 6;
        };
    }

    private static ModContentType contentTypeOfClassId(int classId) {
        return switch (classId) {
            case 4471 -> ModContentType.MODPACK;
            case 12 -> ModContentType.RESOURCE_PACK;
            case 6552 -> ModContentType.SHADER;
            case 6945 -> ModContentType.DATAPACK;
            default -> ModContentType.MOD;
        };
    }

    /** 加载器 → modLoaderType。 */
    public static int modLoaderType(String loader) {
        if (loader == null || loader.isBlank()) {
            return 0;
        }
        return switch (loader.toLowerCase(java.util.Locale.ROOT)) {
            case "forge" -> 1;
            case "fabric" -> 4;
            case "quilt" -> 5;
            case "neoforge" -> 6;
            default -> 0;
        };
    }

    private static String loaderName(int modLoader) {
        return switch (modLoader) {
            case 1 -> "forge";
            case 4 -> "fabric";
            case 5 -> "quilt";
            case 6 -> "neoforge";
            default -> "";
        };
    }

    private static int sortField(ModSearchQuery.Sort sort) {
        return switch (sort) {
            // CurseForge 的 Popularity 就是它自己的综合热度榜
            case POPULARITY -> 2;
            case DOWNLOADS -> 6;
            case NEWEST -> 11;
            case UPDATED -> 3;
            default -> 2;
        };
    }

    /**
     * 分类列表（{@code GET /v1/categories}）。
     *
     * <p>按 {@code classId} 取：模组、整合包、资源包、光影各有各的一套分类，
     * 混用会出现「选了资源包分类却去筛模组」这种必然空结果的组合。</p>
     */
    @Override
    public List<ModCategory> categories(ModContentType type) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("gameId", String.valueOf(GAME_ID));
        params.put("classId", String.valueOf(classId(type)));
        JsonNode root = getJson("/categories?" + toQuery(params));
        List<ModCategory> categories = new ArrayList<>();
        JsonNode data = root.get("data");
        if (data != null && data.isArray()) {
            for (JsonNode category : data) {
                String name = JsonUtils.text(category, "name", "");
                String id = JsonUtils.text(category, "id", "");
                if (!name.isBlank() && !id.isBlank()) {
                    categories.add(new ModCategory(id, name));
                }
            }
        }
        categories.sort(Comparator.naturalOrder());
        return categories;
    }

    private Map<String, String> authHeaders() {
        String key = apiKeySupplier.get();
        return key == null || key.isBlank() ? Map.of() : Map.of("x-api-key", key.trim());
    }

    private JsonNode getJson(String path) throws IOException {
        if (!isConfigured()) {
            throw new IOException("尚未配置 CurseForge API Key，请在「设置 → 下载」中填写");
        }
        Map<String, String> headers = new LinkedHashMap<>(authHeaders());
        headers.put("Accept", "application/json");
        HttpUtils.Result result = HttpUtils.send("GET", API + path, headers, null, null, TIMEOUT);
        if (result.status() == 403) {
            // 实测规律：同一个 Key 在 /v1/games、/v1/categories、/v1/mods/{id} 上是 200，
            // 只有 /v1/mods/search 返回 403 —— 这几乎总是「Key 未被授权访问该游戏」，
            // 而不是 Key 写错了。把这两种原因分开写，用户才知道该去哪儿改。
            throw new IOException("CurseForge 拒绝了检索请求（HTTP 403）。常见原因：这枚 Key 未被授权访问"
                    + " Minecraft（在 console.curseforge.com 的 API Key 里确认已包含 gameId 432 / Minecraft），"
                    + "或该 Key 仍在审核中。Key 本身有效时，分类与单个模组详情是能正常读取的。");
        }
        if (!result.isSuccess()) {
            throw new IOException("CurseForge 请求失败：" + result.summary());
        }
        try {
            return result.json();
        } catch (IOException e) {
            throw new IOException("CurseForge 响应不是合法 JSON：" + e.getMessage(), e);
        }
    }

    private static String toQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((key, value) -> {
            if (value == null || value.isBlank()) {
                return;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(key).append('=').append(encode(value));
        });
        return sb.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 隐藏敏感信息：仅暴露 Key 的尾 4 位。 */
    public static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String trimmed = key.trim();
        return trimmed.length() <= 4 ? "****" : "****" + trimmed.substring(trimmed.length() - 4);
    }
}
