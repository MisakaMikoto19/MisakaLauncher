package com.misaka.launcher.core.meta;

import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.config.PortablePaths;
import com.misaka.launcher.core.download.DownloadSource;
import com.misaka.launcher.core.download.DownloadTargetType;
import com.misaka.launcher.core.download.SourceGate;
import com.misaka.launcher.core.download.SourceResolver;
import com.misaka.launcher.core.model.VersionManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本清单的离线容错验证。
 *
 * <p>真实故障：晚间 BMCLAPI 与官方源同时不可达，用户连续两次「校验文件」失败 ——
 * 而磁盘上明明躺着几小时前的可用清单。清单只决定「有哪些版本、版本 JSON 从哪下载」，
 * 用旧清单安装既有版本与用刚抓的毫无区别，因此抓取失败时必须退回旧缓存。</p>
 */
class VersionManifestServiceTest {

    @TempDir
    Path tempDir;

    /** 必然拒绝连接的端口（1~1024 里未监听的），连接立刻失败，测试完全离线。 */
    private static final String DEAD_BASE = "http://127.0.0.1:9";

    private static final String MANIFEST_JSON = """
            {
              "latest": {"release": "1.20.1", "snapshot": "1.20.2"},
              "versions": [
                {"id": "1.20.1", "type": "release",
                 "url": "https://piston-meta.mojang.com/v1/packages/ab/1.20.1.json",
                 "time": "2023-06-12T13:25:51+00:00", "releaseTime": "2023-06-12T10:12:28+00:00",
                 "sha1": "ab", "complianceLevel": 1}
              ]
            }
            """;

    /** 指向必然拒绝连接端口的源：让抓取确定性地失败，不依赖真实网络。 */
    private MetaFetcher deadFetcher() {
        DownloadSource dead = new DownloadSource() {
            @Override
            public String id() {
                return "dead";
            }

            @Override
            public String name() {
                return "dead";
            }

            @Override
            public int priority() {
                return 5;
            }

            @Override
            public String probeUrl() {
                return "http://127.0.0.1:9/probe";
            }

            @Override
            public String rewrite(String url, DownloadTargetType type, Map<String, String> context) {
                return "http://127.0.0.1:9" + pathOf(url);
            }
        };
        LauncherConfig.DownloadSettings settings = new LauncherConfig.DownloadSettings();
        settings.source = LauncherConfig.DownloadSettings.SOURCE_AUTO;
        return new MetaFetcher(new SourceResolver(settings, List.of(dead)),
                Duration.ofSeconds(2), new SourceGate());
    }

    /** 去掉 scheme+host，保留路径与查询。 */
    private static String pathOf(String url) {
        int pathStart = url.indexOf('/', url.indexOf("://") + 3);
        return pathStart < 0 ? "/" : url.substring(pathStart);
    }

    private PortablePaths paths() {
        return PortablePaths.resolve(false, tempDir.toString());
    }

    private void writeCache(boolean expired) throws IOException {
        Path cache = paths().metaCacheDir().resolve("version_manifest_v2.json");
        Files.createDirectories(cache.getParent());
        Files.write(cache, MANIFEST_JSON.getBytes(StandardCharsets.UTF_8));
        if (expired) {
            Files.setLastModifiedTime(cache, FileTime.fromMillis(
                    System.currentTimeMillis() - 2L * 3600L * 1000L));
        }
    }

    /** 清单地址也指向死端口：否则 fetchString 会拿官方地址做最后兜底，测试就不离线了。 */
    private VersionManifestService service() {
        return new VersionManifestService(paths(), deadFetcher(),
                DEAD_BASE + "/mc/game/version_manifest_v2.json");
    }

    @Test
    void fetchFailureShouldFallBackToStaleManifest() throws Exception {
        writeCache(true);
        VersionManifestService service = service();

        VersionManifest manifest = service.manifest(false);

        assertEquals(1, manifest.versions.size(), "应使用本地旧清单而不是抛错");
        assertEquals("1.20.1", manifest.versions.get(0).id);
        assertEquals("1.20.1", service.manifest().latest.release, "内存缓存应同步更新");
    }

    @Test
    void freshCacheShouldAvoidRequest() throws Exception {
        writeCache(false);
        VersionManifestService service = service();

        assertEquals("1.20.1", service.manifest(false).latest.release);
    }

    @Test
    void noCacheAndFetchFailureShouldThrow() {
        VersionManifestService service = service();

        IOException error = assertThrows(IOException.class, () -> service.manifest(false));
        assertTrue(error.getMessage().contains("已尝试"), "应保留原有报错信息：" + error.getMessage());
    }
}
