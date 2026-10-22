package com.misaka.launcher.core.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.misaka.launcher.core.download.DownloadTargetType;
import com.misaka.launcher.core.model.LoaderType;
import com.misaka.launcher.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模组加载器元数据服务。
 * <p>各加载器的接口形态差异很大，这里统一抽象为两类能力：</p>
 * <ol>
 *   <li>{@link #listVersions}：列出某游戏版本可用的加载器版本；</li>
 *   <li>{@link #fetchProfileJson} / {@link #installerUrl}：获取安装所需的素材。</li>
 * </ol>
 * <p>所有地址均以「官方地址」传入 {@link MetaFetcher}，由下载源决定是否改写到镜像；
 * 对于镜像独有的接口（BMCLAPI 的加载器列表），则显式给出多个候选地址依次尝试。</p>
 */
public final class LoaderMetaService {

    private static final Logger log = LoggerFactory.getLogger(LoaderMetaService.class);

    // ---- Fabric（官方域名会被镜像改写到 /fabric-meta） ----
    private static final String FABRIC_LIST = "https://meta.fabricmc.net/v2/versions/loader/%s";
    private static final String FABRIC_PROFILE = "https://meta.fabricmc.net/v2/versions/loader/%s/%s/profile/json";

    // ---- Quilt（镜像改写到 /quilt-meta） ----
    private static final String QUILT_LIST = "https://meta.quiltmc.org/v3/versions/loader/%s";
    private static final String QUILT_PROFILE = "https://meta.quiltmc.org/v3/versions/loader/%s/%s/profile/json";

    // ---- Forge ----
    /** 官方 maven 路径规则，拼出安装器地址。 */
    private static final String FORGE_INSTALLER =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/%s-%s/forge-%s-%s-installer.jar";
    /** 加载器版本列表：BMCLAPI 提供了按游戏版本过滤的便捷接口。 */
    private static final String FORGE_LIST_MIRROR =
            "https://bmclapi2.bangbang93.com/forge/minecraft/%s";
    /** 官方促销信息（推荐 / 最新版本），体积很小，作为镜像不可用时的兜底。 */
    private static final String FORGE_PROMOTIONS =
            "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";

    // ---- NeoForge ----
    private static final String NEOFORGE_INSTALLER =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/%s/neoforge-%s-installer.jar";
    /** 1.20.1 时代的 NeoForge 沿用 Forge 的坐标结构。 */
    private static final String NEOFORGE_LEGACY_INSTALLER =
            "https://maven.neoforged.net/releases/net/neoforged/forge/%s-%s/forge-%s-%s-installer.jar";
    private static final String NEOFORGE_LIST_OFFICIAL =
            "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge";
    private static final String NEOFORGE_LIST_MIRROR =
            "https://bmclapi2.bangbang93.com/neoforge/list/%s";

    private final MetaFetcher fetcher;

    public LoaderMetaService(MetaFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * 可用的加载器版本。
     *
     * @param loaderType 加载器类型
     * @param mcVersion  目标游戏版本
     */
    public List<LoaderVersion> listVersions(LoaderType loaderType, String mcVersion) throws IOException {
        return switch (loaderType) {
            case FABRIC -> parseFabricLike(fetchJsonLenient(FABRIC_LIST.formatted(mcVersion),
                    DownloadTargetType.LOADER_META, Map.of("mcVersion", mcVersion)), mcVersion);
            case QUILT -> parseFabricLike(fetchJsonLenient(QUILT_LIST.formatted(mcVersion),
                    DownloadTargetType.LOADER_META, Map.of("mcVersion", mcVersion)), mcVersion);
            case FORGE -> listForgeVersions(mcVersion);
            case NEOFORGE -> listNeoForgeVersions(mcVersion);
            default -> List.of();
        };
    }

    /** 该加载器是否支持自动安装。 */
    public boolean supportsAutoInstall(LoaderType loaderType) {
        return loaderType != LoaderType.VANILLA && loaderType != LoaderType.OPTIFINE;
    }

    // ------------------------------------------------------------------
    // Fabric / Quilt：profile 合并式安装
    // ------------------------------------------------------------------

    /**
     * 获取 Fabric / Quilt 的 profile JSON（一段完整的版本描述，通过 inheritsFrom 复用原版）。
     */
    public String fetchProfileJson(LoaderType loaderType, String mcVersion, String loaderVersion) throws IOException {
        String url = switch (loaderType) {
            case FABRIC -> FABRIC_PROFILE.formatted(mcVersion, loaderVersion);
            case QUILT -> QUILT_PROFILE.formatted(mcVersion, loaderVersion);
            default -> throw new IOException("该加载器不支持 profile 合并安装: " + loaderType);
        };
        return fetcher.fetchString(url, DownloadTargetType.LOADER_META,
                Map.of("mcVersion", mcVersion, "loaderVersion", loaderVersion), Map.of());
    }

    /**
     * 由 profile JSON 推导安装后的版本 id。
     * <p>Fabric / Quilt 的 profile 自带 {@code id} 字段（形如
     * {@code fabric-loader-0.15.11-1.20.1}），优先使用它以保证与社区惯例一致。</p>
     */
    public String profileVersionId(LoaderType loaderType, String mcVersion, String loaderVersion, String profileJson) {
        if (profileJson != null) {
            try {
                JsonNode node = JsonUtils.read(profileJson);
                String id = JsonUtils.text(node, "id", "");
                if (!id.isBlank()) {
                    return id;
                }
            } catch (IOException ignored) {
                // 解析失败则退回自造 id
            }
        }
        return defaultVersionId(loaderType, mcVersion, loaderVersion);
    }

    /** 与社区惯例一致的版本 id。 */
    public static String defaultVersionId(LoaderType loaderType, String mcVersion, String loaderVersion) {
        return switch (loaderType) {
            case FABRIC -> "fabric-loader-" + loaderVersion + "-" + mcVersion;
            case QUILT -> "quilt-loader-" + loaderVersion + "-" + mcVersion;
            case FORGE -> mcVersion + "-forge-" + loaderVersion;
            case NEOFORGE -> "neoforge-" + loaderVersion;
            case OPTIFINE -> mcVersion + "-OptiFine_" + loaderVersion;
            default -> mcVersion;
        };
    }

    // ------------------------------------------------------------------
    // Forge / NeoForge：安装器式安装
    // ------------------------------------------------------------------

    /**
     * 安装器下载地址。
     * <p>NeoForge 在 1.20.1 时代沿用 {@code net/neoforged/forge} 坐标，
     * 因此对 1.20.1 给出 legacy 地址。</p>
     */
    public String installerUrl(LoaderType loaderType, String mcVersion, String loaderVersion) {
        return switch (loaderType) {
            case FORGE -> FORGE_INSTALLER.formatted(mcVersion, loaderVersion, mcVersion, loaderVersion);
            case NEOFORGE -> isNeoForgeLegacy(mcVersion)
                    ? NEOFORGE_LEGACY_INSTALLER.formatted(mcVersion, loaderVersion, mcVersion, loaderVersion)
                    : NEOFORGE_INSTALLER.formatted(loaderVersion, loaderVersion);
            default -> null;
        };
    }

    /** NeoForge 1.20.1 使用 legacy 坐标（版本号为 47.x）。 */
    private static boolean isNeoForgeLegacy(String mcVersion) {
        return mcVersion != null && mcVersion.startsWith("1.20.1");
    }

    /**
     * 尝试获取安装器的 SHA-1。
     * <p>Maven 仓库会提供 {@code .sha1} 边车文件；取不到时返回空串（不强制校验）。</p>
     */
    public String installerSha1(String installerUrl) {
        if (installerUrl == null) {
            return "";
        }
        String body = fetcher.tryFetchString(installerUrl + ".sha1");
        if (body == null) {
            return "";
        }
        String value = body.trim().split("\\s+")[0];
        return value.matches("[0-9a-fA-F]{40}") ? value.toLowerCase(java.util.Locale.ROOT) : "";
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    private JsonNode fetchJsonLenient(String url, DownloadTargetType type, Map<String, String> context)
            throws IOException {
        String body = fetcher.fetchString(url, type, context, Map.of());
        return JsonUtils.read(body);
    }

    /**
     * 解析 Fabric / Quilt 形态的版本列表。
     * <p>两种实现的结构略有差异（Fabric 为 {@code [{loader:{version,stable}}]}，
     * Quilt 为 {@code [{loader:{version}}]}），这里做容错解析，
     * 同时兼容直接返回字符串数组的情况。</p>
     */
    private List<LoaderVersion> parseFabricLike(JsonNode root, String mcVersion) {
        List<LoaderVersion> result = new ArrayList<>();
        if (root == null || !root.isArray()) {
            return result;
        }
        for (JsonNode entry : root) {
            if (entry.isTextual()) {
                result.add(new LoaderVersion(entry.asText(), mcVersion, true, "", 0L));
                continue;
            }
            JsonNode loader = entry.get("loader");
            JsonNode source = loader != null ? loader : entry;
            String version = JsonUtils.text(source, "version", "");
            if (version.isBlank()) {
                continue;
            }
            boolean stable = JsonUtils.boolValue(source, "stable", true);
            result.add(new LoaderVersion(version, mcVersion, stable, "", 0L));
        }
        return result;
    }

    /**
     * Forge 版本列表。
     * <p>优先使用 BMCLAPI 的按游戏版本过滤接口（体积小、含安装器哈希）；
     * 全部失败时退化为官方促销信息，至少能给出推荐/最新两个版本。</p>
     */
    private List<LoaderVersion> listForgeVersions(String mcVersion) {
        List<String> candidates = List.of(FORGE_LIST_MIRROR.formatted(mcVersion));
        try {
            JsonNode root = JsonUtils.read(fetcher.fetchFirstAvailable(candidates, Map.of()));
            List<LoaderVersion> versions = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode entry : root) {
                    String version = JsonUtils.text(entry, "version", "");
                    if (version.isBlank()) {
                        continue;
                    }
                    String sha1 = "";
                    JsonNode files = entry.get("files");
                    if (files != null && files.isArray()) {
                        for (JsonNode file : files) {
                            if ("installer".equals(JsonUtils.text(file, "category", ""))) {
                                sha1 = JsonUtils.text(file, "hash", "");
                                break;
                            }
                        }
                    }
                    versions.add(new LoaderVersion(version, mcVersion, true, sha1, 0L));
                }
            }
            if (!versions.isEmpty()) {
                return versions;
            }
        } catch (IOException e) {
            log.warn("获取 Forge 版本列表失败（{}），回退到官方促销信息", e.getMessage());
        }
        return forgePromotions(mcVersion);
    }

    /** 官方促销信息兜底：只提供推荐版与最新版。 */
    private List<LoaderVersion> forgePromotions(String mcVersion) {
        List<LoaderVersion> result = new ArrayList<>();
        try {
            JsonNode root = JsonUtils.read(fetcher.fetchString(FORGE_PROMOTIONS,
                    DownloadTargetType.LOADER_META, Map.of(), Map.of()));
            JsonNode promos = root.get("promos");
            if (promos == null) {
                return result;
            }
            Set<String> seen = new LinkedHashSet<>();
            for (String suffix : List.of("-recommended", "-latest")) {
                String value = JsonUtils.text(promos, mcVersion + suffix, "");
                if (!value.isBlank() && seen.add(value)) {
                    result.add(new LoaderVersion(value, mcVersion, "-recommended".equals(suffix), "", 0L));
                }
            }
        } catch (IOException e) {
            log.warn("获取 Forge 促销信息失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * NeoForge 版本列表。
     * <p>优先用官方 Maven REST 接口（覆盖全部历史版本），
     * 其返回的是全量版本号，需要按游戏版本前缀过滤；
     * 镜像接口已按游戏版本过滤，作为对照。</p>
     */
    private List<LoaderVersion> listNeoForgeVersions(String mcVersion) {
        List<LoaderVersion> fromMirror = new ArrayList<>();
        try {
            JsonNode root = JsonUtils.read(fetcher.fetchFirstAvailable(
                    List.of(NEOFORGE_LIST_MIRROR.formatted(mcVersion)), Map.of()));
            if (root.isArray()) {
                for (JsonNode entry : root) {
                    String version = JsonUtils.text(entry, "version", "");
                    if (!version.isBlank()) {
                        fromMirror.add(new LoaderVersion(version, mcVersion, !version.contains("beta"), "", 0L));
                    }
                }
            }
        } catch (IOException e) {
            log.debug("NeoForge 镜像列表不可用，尝试官方接口: {}", e.getMessage());
        }
        if (!fromMirror.isEmpty()) {
            return fromMirror;
        }

        try {
            JsonNode root = JsonUtils.read(fetcher.fetchString(NEOFORGE_LIST_OFFICIAL,
                    DownloadTargetType.LOADER_META, Map.of(), Map.of()));
            JsonNode versions = root.get("versions");
            List<LoaderVersion> result = new ArrayList<>();
            if (versions != null && versions.isArray()) {
                String prefix = neoForgePrefix(mcVersion);
                for (JsonNode entry : versions) {
                    String version = entry.asText();
                    if (prefix != null && !version.startsWith(prefix)) {
                        continue;
                    }
                    result.add(new LoaderVersion(version, mcVersion, !version.contains("beta"), "", 0L));
                }
            }
            return result;
        } catch (IOException e) {
            log.warn("获取 NeoForge 版本列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 由游戏版本推导 NeoForge 版本前缀。
     * <p>NeoForge 自 1.20.2 起改用 {@code <mcMinor>.<mcPatch>.<build>} 编号，
     * 例如 1.20.4 对应 {@code 20.4.}，1.21 对应 {@code 21.0.}。</p>
     */
    private static String neoForgePrefix(String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()) {
            return null;
        }
        String cleaned = mcVersion;
        int dash = cleaned.indexOf('-');
        if (dash > 0) {
            cleaned = cleaned.substring(0, dash);
        }
        String[] parts = cleaned.split("\\.");
        if (parts.length < 2 || !"1".equals(parts[0])) {
            return null;
        }
        String minor = parts[1];
        String patch = parts.length > 2 ? parts[2] : "0";
        return minor + "." + patch + ".";
    }

    /**
     * 加载器版本信息。
     *
     * @param version       加载器版本号
     * @param mcVersion     对应游戏版本
     * @param stable        是否稳定版
     * @param installerSha1 安装器 SHA-1（已知时非空）
     */
    public record LoaderVersion(String version, String mcVersion, boolean stable, String installerSha1, long installerSize) {

        /** 面向界面展示的名称。 */
        public String displayName() {
            return stable ? version : version + " (测试版)";
        }
    }

    /**
     * 从版本列表中挑选「最新」版本。
     *
     * <p>用于「默认选取适合当前游戏版本的最新版本」：优先稳定版，
     * 没有稳定版则退回到任意最新。列表顺序不可靠（Forge 镜像接口不排序），
     * 因此必须用 {@link #compareVersions} 显式比较，而不是直接取第一个。</p>
     */
    public static LoaderVersion latest(List<LoaderVersion> versions) {
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        List<LoaderVersion> candidates = versions.stream().filter(LoaderVersion::stable).toList();
        if (candidates.isEmpty()) {
            candidates = versions;
        }
        LoaderVersion best = candidates.get(0);
        for (LoaderVersion version : candidates) {
            if (compareVersions(version.version(), best.version()) > 0) {
                best = version;
            }
        }
        return best;
    }

    /**
     * 数值感知的版本号比较。
     *
     * <p>把版本拆成「数字段 / 非数字段」逐段比较：数字段按数值（`9` &lt; `10`），
     * 非数字段按字典序（忽略大小写）。这样 `0.15.11`、`47.2.0`、`20.4.235-beta`
     * 这类五花八门的编号都能得到符合直觉的顺序。</p>
     *
     * @return 负数 / 零 / 正数，含义同 {@link java.util.Comparator}
     */
    public static int compareVersions(String a, String b) {
        if (a == null || a.isBlank()) {
            return b == null || b.isBlank() ? 0 : -1;
        }
        if (b == null || b.isBlank()) {
            return 1;
        }
        String left = a.trim();
        String right = b.trim();
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            char lc = left.charAt(i);
            char rc = right.charAt(j);
            if (Character.isDigit(lc) && Character.isDigit(rc)) {
                int ni = i;
                while (ni < left.length() && Character.isDigit(left.charAt(ni))) {
                    ni++;
                }
                int nj = j;
                while (nj < right.length() && Character.isDigit(right.charAt(nj))) {
                    nj++;
                }
                int si = i;
                while (si < ni - 1 && left.charAt(si) == '0') {
                    si++;
                }
                int sj = j;
                while (sj < nj - 1 && right.charAt(sj) == '0') {
                    sj++;
                }
                int li = ni - si;
                int lj = nj - sj;
                if (li != lj) {
                    return Integer.compare(li, lj);
                }
                for (int k = 0; k < li; k++) {
                    char ci = left.charAt(si + k);
                    char cj = right.charAt(sj + k);
                    if (ci != cj) {
                        return Character.compare(ci, cj);
                    }
                }
                i = ni;
                j = nj;
            } else {
                int cmp = Character.toLowerCase(lc) - Character.toLowerCase(rc);
                if (cmp != 0) {
                    return cmp;
                }
                i++;
                j++;
            }
        }
        // 公共前缀耗尽后：若其中一方还有剩余，且剩余以「-」开头（预发布标记），
        // 则较短的那方是正式版、视为更新；否则较短者更小。
        if (i >= left.length() && j >= right.length()) {
            return 0;
        }
        boolean leftShorter = i >= left.length();
        String rest = leftShorter ? right.substring(j) : left.substring(i);
        if (rest.startsWith("-")) {
            return leftShorter ? 1 : -1;
        }
        return leftShorter ? -1 : 1;
    }
}
