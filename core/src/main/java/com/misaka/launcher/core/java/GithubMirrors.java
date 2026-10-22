package com.misaka.launcher.core.java;

import java.util.ArrayList;
import java.util.List;

/**
 * GitHub 下载加速镜像。
 *
 * <p>背景：Dragonwell（以及 Adoptium 等）的归档只发布在 GitHub Releases 上，
 * 国内直连 {@code github.com} / {@code objects.githubusercontent.com} 往往只有几十 KB/s，
 * 甚至中途连接被重置。实测同一构件 2 MB 片段：直连 559 KB/s、加速镜像 1039 KB/s。</p>
 *
 * <p><b>只做「候选地址扩充」，不做替换</b>：返回的列表把加速镜像排在前面、
 * 原始 GitHub 地址放在最后兜底。下载环节会保持这个顺序（只把探测不到的地址降级），
 * 因此「镜像优先」是确定的，不会被一次握手测速翻转。</p>
 *
 * <p><b>安全前提</b>：这些镜像不是官方服务，因此只在拿得到官方 SHA-256 时才启用 ——
 * 有哈希校验才能保证拿到的仍是官方产物；拿不到哈希时宁可不加速，
 * 也不会把未校验的二进制交到用户手里（见 {@link #expand(String, boolean)}）。</p>
 */
public final class GithubMirrors {

    /**
     * 加速前缀（按实测可用性挑的三个不同运营方）。
     *
     * <p>顺序只代表默认偏好：真正生效的顺序由延迟实测决定，
     * 死掉的镜像会被自动降级，不需要在代码里维护可用性。</p>
     */
    private static final List<String> PREFIXES = List.of(
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/");

    private static final String GITHUB_PREFIX = "https://github.com/";

    private GithubMirrors() {
    }

    /** 是否为可加速的 GitHub 地址。 */
    public static boolean isGithubUrl(String url) {
        return url != null && url.startsWith(GITHUB_PREFIX);
    }

    /**
     * 展开为候选地址：国内加速镜像在前，原始 GitHub 地址兜底。
     *
     * @param githubUrl         官方地址
     * @param checksumAvailable 是否拿得到官方哈希；为 {@code false} 时不启用第三方加速
     */
    public static List<String> expand(String githubUrl, boolean checksumAvailable) {
        if (githubUrl == null || githubUrl.isBlank()) {
            return List.of();
        }
        if (!isGithubUrl(githubUrl) || !checksumAvailable) {
            return List.of(githubUrl);
        }
        List<String> urls = new ArrayList<>(PREFIXES.size() + 1);
        for (String prefix : PREFIXES) {
            urls.add(prefix + githubUrl);
        }
        // 官方地址必须保留在最后：前面所有镜像都不可用时仍能直连
        urls.add(githubUrl);
        return List.copyOf(urls);
    }

    /** 启用的加速站点（供日志与界面展示）。 */
    public static List<String> mirrorHosts() {
        return PREFIXES.stream()
                .map(prefix -> prefix.replaceFirst("^https?://", "").replaceAll("/$", ""))
                .toList();
    }
}
