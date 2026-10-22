package com.misaka.launcher.core.mods;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * MC 百科（mcmod.cn）来源。
 * <p><b>该站点没有公开 API</b>，因此本 Provider 明确定位为「中文资料检索与跳转」：
 * {@link #supportsSearch()} 返回 false，检索能力由内置浏览器视图直接加载其搜索页实现。</p>
 * <p>这样做的好处是：不抓取、不解析其页面，既尊重站点规则，也不会因为页面改版而失效。</p>
 */
public final class McModProvider implements ModProvider {

    public static final String ID = "mcmod";

    private static final String WEB_ROOT = "https://www.mcmod.cn";
    private static final String SEARCH_ROOT = "https://search.mcmod.cn";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "MC 百科";
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    /** 无公开 API，站内检索由内置浏览器承担。 */
    @Override
    public boolean supportsSearch() {
        return false;
    }

    @Override
    public List<ModProject> search(ModSearchQuery query) {
        return List.of();
    }

    @Override
    public Optional<ModProject> project(String projectId) {
        return Optional.empty();
    }

    @Override
    public List<ModFile> versions(String projectId, String gameVersion, String loader) {
        return List.of();
    }

    @Override
    public Optional<String> resolveDownloadUrl(ModFile file) {
        return Optional.empty();
    }

    /** 检索页地址；由内置 WebView 或系统浏览器打开。 */
    public String searchUrl(String keyword) {
        String value = keyword == null ? "" : keyword.trim();
        return SEARCH_ROOT + "/s?key=" + URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 条目详情页地址。 */
    public String classUrl(String classId) {
        return WEB_ROOT + "/class/" + (classId == null ? "" : classId) + ".html";
    }

    /** 面向界面展示的说明。 */
    public String capabilityNote() {
        return "MC 百科未提供公开 API，此处用于中文资料检索与跳转，不参与自动下载与更新";
    }
}
