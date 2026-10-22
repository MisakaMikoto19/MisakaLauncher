package com.misaka.launcher.core.download;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自定义镜像源。
 * <p>支持两种形态：</p>
 * <ul>
 *   <li><b>反代型</b>：镜像完整复刻官方路径结构，此时任何官方 URL 都可以通过
 *       「替换主机名」映射过去（例如自建 Nginx 反代 Mojang 域名）；</li>
 *   <li><b>前缀型</b>：镜像使用自有路径，此时由用户在配置中给出官方前缀与镜像前缀的映射表。</li>
 * </ul>
 * 未能在配置中找到映射时返回 {@code null}，由下一源接管。
 */
public final class CustomSource implements DownloadSource {

    public static final String ID = "custom";

    private final String base;
    private final boolean hostReplaceMode;
    private final Map<String, String> prefixRules;

    /**
     * @param baseUrl          自定义源根地址，例如 {@code https://mirror.example.com}
     * @param hostReplaceMode  true 表示镜像复刻官方路径，按主机名替换映射
     * @param prefixRules      前缀映射（官方前缀 → 镜像前缀），仅在非反代模式下使用
     */
    public CustomSource(String baseUrl, boolean hostReplaceMode, Map<String, String> prefixRules) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        this.base = normalized.endsWith("/") && normalized.length() > 1
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
        this.hostReplaceMode = hostReplaceMode;
        this.prefixRules = prefixRules == null ? new LinkedHashMap<>() : new LinkedHashMap<>(prefixRules);
    }

    /** 默认构造：反代模式，覆盖常见官方主机。 */
    public static CustomSource reverseProxy(String baseUrl) {
        return new CustomSource(baseUrl, true, null);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "自定义源";
    }

    @Override
    public int priority() {
        return 5;
    }

    @Override
    public String probeUrl() {
        return base.isBlank() ? null : base + "/mc/game/version_manifest_v2.json";
    }

    @Override
    public String rewrite(String officialUrl, DownloadTargetType type, Map<String, String> context) {
        if (base.isBlank() || officialUrl == null || officialUrl.isBlank()) {
            return null;
        }
        if (hostReplaceMode) {
            int schemeEnd = officialUrl.indexOf("://");
            if (schemeEnd < 0) {
                return null;
            }
            int pathStart = officialUrl.indexOf('/', schemeEnd + 3);
            String path = pathStart < 0 ? "/" : officialUrl.substring(pathStart);
            String host = officialUrl.substring(schemeEnd + 3, pathStart < 0 ? officialUrl.length() : pathStart);
            // 仅映射已知的官方主机，避免把 Modrinth 之类的第三方地址误改
            if (!isKnownOfficialHost(host)) {
                return null;
            }
            return base + path;
        }
        for (Map.Entry<String, String> rule : prefixRules.entrySet()) {
            if (officialUrl.startsWith(rule.getKey())) {
                return base + rule.getValue() + officialUrl.substring(rule.getKey().length());
            }
        }
        return null;
    }

    private static boolean isKnownOfficialHost(String host) {
        return switch (host) {
            case "piston-meta.mojang.com", "launchermeta.mojang.com", "launcher.mojang.com",
                 "piston-data.mojang.com", "libraries.minecraft.net",
                 "resources.download.minecraft.net", "maven.minecraftforge.net",
                 "files.minecraftforge.net", "maven.neoforged.net",
                 // 加载器生态：maven 与 meta 两套域名都要覆盖，
                 // 否则自建反代无法为 Fabric/Quilt 的接口加速
                 "maven.fabricmc.net", "meta.fabricmc.net",
                 "maven.quiltmc.org", "meta.quiltmc.org" -> true;
            default -> false;
        };
    }

    public String base() {
        return base;
    }
}
