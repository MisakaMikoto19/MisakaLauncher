package com.misaka.launcher.core.download;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BMCLAPI 镜像源（国内加速）。
 *
 * <p>BMCLAPI 的路径与 Mojang 官方并不一一对应，因此以「正则规则表」方式实现改写，
 * 规则按顺序匹配，首个命中者生效；未命中任何规则时返回 {@code null}，交由下一源处理。</p>
 *
 * <p><b>默认不参与下载</b>：优先级排在官方源之后，只有在设置里显式选择「BMCLAPI 镜像」、
 * 或自动模式下官方地址不可达而降级时才会用到。</p>
 */
public final class BmclapiSource implements DownloadSource {

    public static final String ID = "bmclapi";
    public static final String DEFAULT_BASE = "https://bmclapi2.bangbang93.com";

    /** version JSON：{@code /v1/packages/<sha1>/<versionId>.json} → {@code /version/<versionId>/json}。 */
    private static final Pattern PACKAGES_JSON = Pattern.compile(
            "^https?://(?:piston-meta|launchermeta)\\.mojang\\.com/v1/packages/[0-9a-fA-F]{40}/(.+)\\.json$");

    /** 客户端 / 服务端 jar：只能借助上下文中的版本号映射。 */
    private static final Pattern PISTON_DATA_OBJECT = Pattern.compile(
            "^https?://piston-data\\.mojang\\.com/v1/objects/[0-9a-fA-F]{40}/(client|server)\\.jar$");

    /** 纯前缀替换规则：官方前缀 → 镜像路径前缀。 */
    private static final Map<String, String> PREFIX_RULES = new LinkedHashMap<>();

    static {
        PREFIX_RULES.put("https://piston-meta.mojang.com/", "/");
        PREFIX_RULES.put("https://launchermeta.mojang.com/", "/");
        PREFIX_RULES.put("https://launcher.mojang.com/", "/");
        // 运行库：官方 maven 目录 → /maven
        PREFIX_RULES.put("https://libraries.minecraft.net/", "/maven/");
        PREFIX_RULES.put("https://maven.minecraftforge.net/", "/maven/");
        PREFIX_RULES.put("https://files.minecraftforge.net/maven/", "/maven/");
        PREFIX_RULES.put("https://maven.fabricmc.net/", "/maven/");
        PREFIX_RULES.put("https://maven.quiltmc.org/repository/release/", "/maven/");
        PREFIX_RULES.put("https://maven.neoforged.net/releases/", "/neoforge/");
        // 资源对象
        PREFIX_RULES.put("https://resources.download.minecraft.net/", "/assets/");
        // 加载器元数据
        PREFIX_RULES.put("https://meta.fabricmc.net/", "/fabric-meta/");
        PREFIX_RULES.put("https://meta.quiltmc.org/", "/quilt-meta/");
    }

    private final String base;

    public BmclapiSource() {
        this(DEFAULT_BASE);
    }

    public BmclapiSource(String base) {
        String normalized = base == null || base.isBlank() ? DEFAULT_BASE : base.trim();
        this.base = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "BMCLAPI 镜像";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public String probeUrl() {
        return base + "/mc/game/version_manifest_v2.json";
    }

    @Override
    public String rewrite(String officialUrl, DownloadTargetType type, Map<String, String> context) {
        if (officialUrl == null || officialUrl.isBlank()) {
            return null;
        }

        // 1) 版本 JSON：URL 中以版本号作为文件名，可直接提取
        Matcher packages = PACKAGES_JSON.matcher(officialUrl);
        if (packages.matches()) {
            return base + "/version/" + packages.group(1) + "/json";
        }

        // 2) 客户端/服务端 jar：需要调用方提供 versionId 上下文
        Matcher object = PISTON_DATA_OBJECT.matcher(officialUrl);
        if (object.matches()) {
            String versionId = context == null ? null : context.get("versionId");
            if (versionId == null || versionId.isBlank()) {
                return null;
            }
            String kind = object.group(1);
            return base + "/version/" + versionId + "/" + ("client".equals(kind) ? "client" : "server");
        }

        // 3) 前缀替换
        for (Map.Entry<String, String> rule : PREFIX_RULES.entrySet()) {
            if (officialUrl.startsWith(rule.getKey())) {
                return base + rule.getValue() + officialUrl.substring(rule.getKey().length());
            }
        }

        // 4) 无法映射（例如 Modrinth / CurseForge / Adoptium 等自有 CDN）
        return null;
    }

    public String base() {
        return base;
    }
}
