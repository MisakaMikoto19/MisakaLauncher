package com.misaka.launcher.core.install;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.config.PortablePaths;
import com.misaka.launcher.core.download.DownloadManager;
import com.misaka.launcher.core.download.SourceResolver;
import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.meta.MetaFetcher;
import com.misaka.launcher.core.meta.VersionManifestService;
import com.misaka.launcher.core.task.ProgressListener;
import com.misaka.launcher.core.util.HashUtils;
import com.misaka.launcher.core.util.JsonUtils;
import com.misaka.launcher.core.util.OsUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原版安装流水线端到端测试。
 * <p>使用进程内 HTTP 桩提供版本清单、版本 JSON、客户端 jar、运行库、资源索引与资源对象，
 * 完整走通「抓元数据 → 计划 → 下载 → 解压原生库 → 写版本 JSON」全链路，完全离线。</p>
 * <p>镜像改写能力同时被顺带验证：版本清单与资源对象使用官方域名，
 * 由自定义源（反代模式）改写为本地桩地址。</p>
 */
@Timeout(value = 90, unit = java.util.concurrent.TimeUnit.SECONDS)
class VanillaInstallerTest {

    private static final String VERSION_ID = "1.20.1";
    private static final String ASSET_INDEX_ID = "test-assets";

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String base;
    private final Map<String, byte[]> payloads = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void vanillaInstallShouldSkipUnmatchedLibraries() throws Exception {
        Fixture fixture = prepareFixture();

        InstallResult result = fixture.installer.install(VERSION_ID, ProgressListener.NOOP);

        assertTrue(result.success(), "安装应成功，失败项: " + result.downloadReport().failures());
        assertEquals(VERSION_ID, result.versionId());
        assertEquals(ASSET_INDEX_ID, result.assetIndexId());

        GamePaths paths = fixture.paths;
        // 客户端主 jar
        assertTrue(Files.isRegularFile(paths.versionJar(VERSION_ID)), "客户端 jar 应存在");
        // 版本 JSON 落盘
        assertTrue(Files.isRegularFile(paths.versionJson(VERSION_ID)), "版本 JSON 应存在");
        // 运行库
        assertTrue(Files.isRegularFile(paths.libraryFile("com/example/lib/1.0/lib-1.0.jar")),
                "匹配规则的运行库应被下载");
        // 规则不匹配的运行库必须被跳过
        assertFalse(Files.isRegularFile(paths.libraryFile("com/example/other/1.0/other-1.0.jar")),
                "规则不匹配的运行库不应被下载");
        // 资源索引与资源对象
        assertTrue(Files.isRegularFile(paths.assetIndexFile(ASSET_INDEX_ID)), "资源索引应存在");
        assertTrue(Files.isRegularFile(paths.assetObject(fixture.assetHash)), "资源对象应存在");
        // 原生库解压，且 META-INF 被过滤
        Path nativesDir = paths.nativesDir(VERSION_ID);
        assertTrue(Files.isRegularFile(nativesDir.resolve("dummy.dll").resolveSibling("dummy.dll"))
                        || Files.list(nativesDir).findAny().isPresent(),
                "原生库应被解压到版本隔离目录");
        assertFalse(Files.exists(nativesDir.resolve("META-INF")), "META-INF 不应被解压");
        assertTrue(result.extractedNatives() > 0, "应至少解压出一个原生库文件");
    }

    @Test
    void reinstallShouldDetectUpToDate() throws Exception {
        Fixture fixture = prepareFixture();

        InstallResult first = fixture.installer.install(VERSION_ID, ProgressListener.NOOP);
        assertTrue(first.success());

        InstallResult second = fixture.installer.install(VERSION_ID, ProgressListener.NOOP);
        assertTrue(second.success());
        assertEquals(0, second.downloadReport().downloaded(), "第二次安装不应重复下载任何文件");
        assertEquals(0L, second.downloadReport().bytes(), "第二次安装不应产生网络流量");
        assertTrue(second.downloadReport().skipped() > 0);
    }

    @Test
    void integrityCheckShouldReportMissingFiles() throws Exception {
        Fixture fixture = prepareFixture();

        InstallResult.IntegrityReport before = fixture.installer.verify(VERSION_ID, null);
        assertFalse(before.complete(), "全新目录应报告缺失");
        assertTrue(before.missing() > 0);
        assertTrue(before.completeness() < 1.0);

        fixture.installer.install(VERSION_ID, ProgressListener.NOOP);

        InstallResult.IntegrityReport after = fixture.installer.verify(VERSION_ID, null);
        assertTrue(after.complete(), "安装完成后应无缺失，实际缺失: " + after.missingFiles());
        assertEquals(1.0, after.completeness(), 1e-9);
    }

    @Test
    void planShouldEstimateSizeAndSplitRequiredOptional() throws Exception {
        Fixture fixture = prepareFixture();

        InstallPlan plan = fixture.installer.plan(VERSION_ID, null);

        assertTrue(plan.totalBytes() > 0, "计划应能给出预估体积");
        assertFalse(plan.coreItems().isEmpty());
        assertFalse(plan.assetItems().isEmpty());
        assertTrue(plan.allItems().size() >= plan.coreItems().size() + plan.assetItems().size() - 1);
    }

    @Test
    void loaderVersionShouldUseLocalInheritanceChain() throws Exception {
        // 真实故障：Forge 装好、版本 id 也记对了，点启动却在「校验文件」阶段报
        // 「版本清单中不存在该版本: 1.20.1-forge-47.4.26」——
        // 因为那时去远端清单里找只存在于本地磁盘的加载器版本。
        Fixture fixture = prepareFixture();
        fixture.installer.install(VERSION_ID, ProgressListener.NOOP);
        GamePaths paths = fixture.paths;

        // 加载器版本只是增量描述：只有 inheritsFrom 指向原版
        String loaderId = VERSION_ID + "-forge-47.4.26";
        ObjectNode loader = JsonUtils.newObject();
        loader.put("id", loaderId);
        loader.put("inheritsFrom", VERSION_ID);
        loader.put("mainClass", "cpw.mods.bootstraplauncher.BootstrapLauncher");
        // 故意不声明额外库：这样校验结果只取决于继承链有没有被正确合并
        loader.putArray("libraries");
        Files.createDirectories(paths.versionDir(loaderId));
        Files.write(paths.versionJson(loaderId), JsonUtils.writePrettyBytes(loader));

        InstallPlan plan = fixture.installer.plan(loaderId, null);

        assertEquals(loaderId, plan.versionId());
        assertTrue(plan.coreItems().stream()
                        .anyMatch(item -> item.target().equals(paths.versionJar(VERSION_ID))),
                "主 jar 应复用原版目录里已存在的那个，而不是在加载器目录里再下一份");
        assertTrue(plan.coreItems().stream().anyMatch(
                        item -> item.target().equals(paths.libraryFile("com/example/lib/1.0/lib-1.0.jar"))),
                "计划应包含继承链上原版的运行库");

        InstallResult.IntegrityReport report = fixture.installer.verify(loaderId, null);
        assertTrue(report.complete(), "加载器版本校验应基于本地继承链，缺失：" + report.missingFiles());
    }

    // ------------------------------------------------------------------
    // 测试装置
    // ------------------------------------------------------------------

    private record Fixture(VanillaInstaller installer, GamePaths paths, String assetHash) {
    }

    private Fixture prepareFixture() throws IOException {
        PortablePaths launcherPaths = PortablePaths.resolve(false, tempDir.resolve("data").toString());
        launcherPaths.prepare();
        GamePaths gamePaths = new GamePaths(tempDir.resolve("game-test-1.20.1"));

        // 资源对象内容与哈希
        byte[] assetContent = "asset-binary-content".getBytes(StandardCharsets.UTF_8);
        String assetHash = HashUtils.sha1(assetContent);

        // ---------- 资源索引 ----------
        ObjectNode assetIndex = JsonUtils.newObject();
        ObjectNode objects = assetIndex.putObject("objects");
        objects.putObject("minecraft/sound/test.ogg")
                .put("hash", assetHash)
                .put("size", assetContent.length);
        byte[] assetIndexBytes = JsonUtils.writePrettyBytes(assetIndex);
        payloads.put("/assets/index.json", assetIndexBytes);

        // ---------- 运行库 ----------
        byte[] libContent = "library-jar".getBytes(StandardCharsets.UTF_8);
        payloads.put("/lib.jar", libContent);

        byte[] nativeJar = buildNativeJar();
        payloads.put("/natives.jar", nativeJar);

        // 按平台构造 natives classifier
        String osName = OsUtils.mojangOsName();
        String nativeKey = "natives-" + ("osx".equals(osName) ? "macos" : osName);

        byte[] clientJar = "client-jar-binary".getBytes(StandardCharsets.UTF_8);
        payloads.put("/client.jar", clientJar);

        // ---------- 版本 JSON ----------
        ObjectNode version = JsonUtils.newObject();
        version.put("id", VERSION_ID);
        version.put("type", "release");
        version.put("mainClass", "net.minecraft.client.main.Main");
        version.put("assets", ASSET_INDEX_ID);
        version.put("inheritsFrom", "");

        ObjectNode assetIndexInfo = version.putObject("assetIndex");
        assetIndexInfo.put("id", ASSET_INDEX_ID);
        assetIndexInfo.put("url", base + "/assets/index.json");
        assetIndexInfo.put("sha1", HashUtils.sha1(assetIndexBytes));
        assetIndexInfo.put("size", assetIndexBytes.length);
        assetIndexInfo.put("totalSize", assetContent.length);

        ObjectNode downloads = version.putObject("downloads");
        downloads.putObject("client")
                .put("url", base + "/client.jar")
                .put("sha1", HashUtils.sha1(clientJar))
                .put("size", clientJar.length);

        ArrayNode libraries = version.putArray("libraries");

        // 1) 正常运行库
        ObjectNode lib = libraries.addObject();
        lib.put("name", "com.example:lib:1.0");
        ObjectNode libArtifact = lib.putObject("downloads").putObject("artifact");
        libArtifact.put("path", "com/example/lib/1.0/lib-1.0.jar");
        libArtifact.put("url", base + "/lib.jar");
        libArtifact.put("sha1", HashUtils.sha1(libContent));
        libArtifact.put("size", libContent.length);

        // 2) 规则不匹配的运行库：仅允许一个当前平台之外的系统
        ObjectNode otherLib = libraries.addObject();
        otherLib.put("name", "com.example:other:1.0");
        ArrayNode otherRules = otherLib.putArray("rules");
        otherRules.addObject().put("action", "allow").putObject("os").put("name", "not-this-os");
        ObjectNode otherArtifact = otherLib.putObject("downloads").putObject("artifact");
        otherArtifact.put("path", "com/example/other/1.0/other-1.0.jar");
        otherArtifact.put("url", base + "/lib.jar");
        otherArtifact.put("sha1", HashUtils.sha1(libContent));
        otherArtifact.put("size", libContent.length);

        // 3) 带原生库的运行库
        ObjectNode nativeLib = libraries.addObject();
        nativeLib.put("name", "com.example:natives:1.0");
        nativeLib.putObject("downloads").putObject("artifact"); // 占位，url 为空 -> 应被忽略
        nativeLib.putObject("downloads").putObject("classifiers")
                .putObject(nativeKey)
                .put("path", "com/example/natives/1.0/natives-1.0-" + nativeKey + ".jar")
                .put("url", base + "/natives.jar")
                .put("sha1", HashUtils.sha1(nativeJar))
                .put("size", nativeJar.length);
        nativeLib.putObject("natives").put(osName, nativeKey);
        ArrayNode excludes = nativeLib.putObject("extract").putArray("exclude");
        excludes.add("META-INF/");

        byte[] versionBytes = JsonUtils.writePrettyBytes(version);
        payloads.put("/version.json", versionBytes);

        // ---------- 版本清单（使用官方域名，验证镜像改写） ----------
        ObjectNode manifest = JsonUtils.newObject();
        manifest.putObject("latest").put("release", VERSION_ID).put("snapshot", VERSION_ID);
        ArrayNode versions = manifest.putArray("versions");
        ObjectNode ref = versions.addObject();
        ref.put("id", VERSION_ID);
        ref.put("type", "release");
        ref.put("url", base + "/version.json");
        ref.put("sha1", HashUtils.sha1(versionBytes));
        ref.put("releaseTime", "2023-06-12T00:00:00+00:00");
        payloads.put("/mc/game/version_manifest_v2.json", JsonUtils.writePrettyBytes(manifest));

        // 资源对象：地址由安装器用官方域名拼接，再经自定义源改写为本地桩
        payloads.put("/" + assetHash.substring(0, 2) + "/" + assetHash, assetContent);

        // ---------- 组装组件 ----------
        LauncherConfig.DownloadSettings settings = new LauncherConfig.DownloadSettings();
        settings.source = LauncherConfig.DownloadSettings.SOURCE_CUSTOM;
        settings.customSourceUrl = base;
        settings.concurrency = 4;
        settings.retries = 2;
        settings.timeoutSeconds = 10;

        // 只注入本地桩源：保证测试完全离线，不产生任何真实网络请求
        SourceResolver resolver = new SourceResolver(settings,
                java.util.List.of(new com.misaka.launcher.core.download.CustomSource(base, true, null)));
        DownloadManager downloadManager = new DownloadManager(resolver, settings);
        VersionManifestService meta = new VersionManifestService(launcherPaths, new MetaFetcher(resolver));
        VanillaInstaller installer = new VanillaInstaller(meta, downloadManager, gamePaths);

        return new Fixture(installer, gamePaths, assetHash);
    }

    /** 构造一个包含原生库与 META-INF 的 jar，用于验证解压过滤。 */
    private static byte[] buildNativeJar() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("dummy.dll"));
            zip.write("native-binary".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] data = payloads.get(path);
        try {
            if (data == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
        } finally {
            exchange.close();
        }
    }
}
