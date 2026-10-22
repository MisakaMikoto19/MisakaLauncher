package com.misaka.launcher.core.download;

import java.util.Map;

/**
 * 官方源：恒等改写，永远可用。
 *
 * <p><b>默认首选</b>：游戏文件本应由 Mojang 自己的 CDN 分发，第三方镜像只在用户显式选择、
 * 或官方地址不可达时作为兜底参与（见 {@link SourceResolver}）。</p>
 */
public final class OfficialSource implements DownloadSource {

    public static final String ID = "official";

    private static final String PROBE_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "官方源";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public String probeUrl() {
        return PROBE_URL;
    }

    @Override
    public String rewrite(String officialUrl, DownloadTargetType type, Map<String, String> context) {
        // 官方源对任何地址都是恒等映射
        return officialUrl;
    }
}
