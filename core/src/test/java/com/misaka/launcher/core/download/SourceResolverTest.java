package com.misaka.launcher.core.download;

import com.misaka.launcher.core.config.LauncherConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下载源改写与择优逻辑验证（离线，不发起网络请求）。
 */
class SourceResolverTest {

    @TempDir
    Path tempDir;

    private LauncherConfig.DownloadSettings settings(String source) {
        LauncherConfig.DownloadSettings s = new LauncherConfig.DownloadSettings();
        s.source = source;
        return s;
    }

    @Test
    void versionJsonShouldMapToMirror() {
        BmclapiSource source = new BmclapiSource();
        String official = "https://piston-meta.mojang.com/v1/packages/"
                + "a".repeat(40) + "/1.20.1.json";
        assertEquals(BmclapiSource.DEFAULT_BASE + "/version/1.20.1/json",
                source.rewrite(official, DownloadTargetType.VERSION_JSON, Map.of()));
    }

    @Test
    void clientJarShouldMapWithVersionContext() {
        BmclapiSource source = new BmclapiSource();
        String official = "https://piston-data.mojang.com/v1/objects/" + "b".repeat(40) + "/client.jar";

        // 缺少版本号上下文时无法映射，必须返回 null 交由其他源处理
        assertEquals(null, source.rewrite(official, DownloadTargetType.GAME_JAR, Map.of()));

        assertEquals(BmclapiSource.DEFAULT_BASE + "/version/1.20.1/client",
                source.rewrite(official, DownloadTargetType.GAME_JAR, Map.of("versionId", "1.20.1")));
    }

    @Test
    void librariesAndAssetsShouldUsePrefixMapping() {
        BmclapiSource source = new BmclapiSource();

        assertEquals(BmclapiSource.DEFAULT_BASE + "/maven/org/ow2/asm/asm/9.5/asm-9.5.jar",
                source.rewrite("https://libraries.minecraft.net/org/ow2/asm/asm/9.5/asm-9.5.jar",
                        DownloadTargetType.LIBRARY, Map.of()));

        assertEquals(BmclapiSource.DEFAULT_BASE + "/assets/ab/abcdef",
                source.rewrite("https://resources.download.minecraft.net/ab/abcdef",
                        DownloadTargetType.ASSET_OBJECT, Map.of()));
    }

    @Test
    void unmappableThirdPartyUrlShouldReturnNull() {
        BmclapiSource source = new BmclapiSource();
        assertEquals(null, source.rewrite("https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse",
                DownloadTargetType.JAVA_RUNTIME, Map.of()));
        assertEquals(null, source.rewrite("https://cdn.modrinth.com/data/abc/versions/1/file.jar",
                DownloadTargetType.MOD_FILE, Map.of()));
    }

    @Test
    void candidateListShouldAlwaysIncludeOfficialFallback() {
        SourceResolver resolver = new SourceResolver(settings(LauncherConfig.DownloadSettings.SOURCE_AUTO));
        DownloadItem item = DownloadItem.of(tempDir.resolve("asm.jar"),
                "https://libraries.minecraft.net/org/ow2/asm/asm/9.5/asm-9.5.jar",
                "00", 100L, DownloadTargetType.LIBRARY);

        List<String> candidates = resolver.candidates(item);
        assertTrue(candidates.contains(item.url()), "候选必须包含原始官方地址");
        assertTrue(candidates.size() >= 2, "自动模式下镜像与官方都应出现: " + candidates);
        assertEquals(candidates.size(), candidates.stream().distinct().count(), "候选不应重复");
    }

    @Test
    void thirdPartyOnlyUrlShouldFallBackToOfficial() {
        SourceResolver resolver = new SourceResolver(settings(LauncherConfig.DownloadSettings.SOURCE_AUTO));
        String url = "https://cdn.modrinth.com/data/abc/versions/1/file.jar";
        List<String> candidates = resolver.candidates(
                DownloadItem.of(tempDir.resolve("mod.jar"), url, "", -1L, DownloadTargetType.MOD_FILE));

        assertEquals(List.of(url), candidates);
    }

    @Test
    void explicitOfficialSourceShouldNotDegrade() {
        SourceResolver resolver = new SourceResolver(settings(LauncherConfig.DownloadSettings.SOURCE_OFFICIAL));
        List<DownloadSource> sources = resolver.orderedSources();
        assertEquals(1, sources.size());
        assertEquals(OfficialSource.ID, sources.get(0).id());
    }

    @Test
    void mirrorSelectionShouldKeepOfficialInFallbackChain() {
        SourceResolver resolver = new SourceResolver(settings(LauncherConfig.DownloadSettings.SOURCE_BMCLAPI));
        List<DownloadSource> sources = resolver.orderedSources();
        assertEquals(BmclapiSource.ID, sources.get(0).id());
        assertEquals(OfficialSource.ID, sources.get(sources.size() - 1).id());
    }

    @Test
    void customSourceShouldJoinCandidates() {
        LauncherConfig.DownloadSettings s = settings(LauncherConfig.DownloadSettings.SOURCE_CUSTOM);
        s.customSourceUrl = "https://mirror.example.com";
        SourceResolver resolver = new SourceResolver(s);

        assertEquals(CustomSource.ID, resolver.orderedSources().get(0).id());

        String official = "https://libraries.minecraft.net/org/ow2/asm/asm/9.5/asm-9.5.jar";
        List<String> candidates = resolver.candidates(
                DownloadItem.of(tempDir.resolve("asm.jar"), official, "", -1L, DownloadTargetType.LIBRARY));

        assertEquals("https://mirror.example.com/org/ow2/asm/asm/9.5/asm-9.5.jar", candidates.get(0));
    }

    @Test
    void autoModeShouldPreferOfficialAndIgnoreLatencyCache() {
        // 默认顺序是确定的：官方在前、镜像兜底，与测速缓存无关。
        // 这里故意让缓存里镜像更快，顺序仍然不能翻转 —— 否则同一份配置在不同网络下
        // 会走不同的下载路径，排查「这台机器下不来」就没有共同前提了。
        LauncherConfig.DownloadSettings s = settings(LauncherConfig.DownloadSettings.SOURCE_AUTO);
        s.sourceLatency.put(BmclapiSource.ID, 20L);
        s.sourceLatency.put(OfficialSource.ID, 900L);
        SourceResolver resolver = new SourceResolver(s);
        resolver.applyCachedOrder();

        List<DownloadSource> ordered = resolver.orderedSources();
        assertNotNull(ordered);
        assertEquals(OfficialSource.ID, ordered.get(0).id(), "自动模式必须官方优先");
        assertEquals(BmclapiSource.ID, ordered.get(ordered.size() - 1).id(), "镜像只作兜底");
        assertFalse(resolver.latencies().isEmpty(), "测速结果仍要展示出来（仅供诊断）");
    }
}
