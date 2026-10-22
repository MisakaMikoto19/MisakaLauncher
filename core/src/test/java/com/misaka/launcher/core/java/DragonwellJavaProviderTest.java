package com.misaka.launcher.core.java;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.download.CustomSource;
import com.misaka.launcher.core.download.DownloadManager;
import com.misaka.launcher.core.download.SourceResolver;
import com.misaka.launcher.core.meta.MetaFetcher;
import com.misaka.launcher.core.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Alibaba Dragonwell 提供方验证。
 * <p>全部用例离线：GitHub 接口由本地桩服务器通过下载源改写命中，
 * 不产生任何真实网络请求。</p>
 */
@Timeout(value = 60, unit = java.util.concurrent.TimeUnit.SECONDS)
class DragonwellJavaProviderTest {

    private static final String SHA_WINDOWS =
            "19a4f457291c0f8cda71e39931a522c0c062093c8e02f65ca65d47e656ef68c2";
    private static final String SHA_LINUX =
            "656a2aa3334082a35a222ac1cfabc6979d89257993d2ba6034ad2fa65e26fc9b";

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String base;
    /** 路径 → 响应体。 */
    private final Map<String, byte[]> payloads = new ConcurrentHashMap<>();
    /** 路径 → 请求次数，用于验证缓存。 */
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ==================================================================
    // 平台分支
    // ==================================================================

    @Test
    void macOS应判定为不支持并让出给其它发行版() {
        DragonwellJavaProvider provider = providerFor("macos", "x64");

        IOException error = assertThrows(IOException.class, () -> provider.resolve(17));
        assertTrue(error.getMessage().contains("macOS"), error.getMessage());
        // 关键：必须是「可降级」的普通 IOException，而不是运行时异常，
        // 否则 JavaRuntimeManager 的发行版轮换逻辑无法捕获
        assertTrue(error instanceof IOException);
    }

    @Test
    void unsupportedMajorVersionShouldFailClearly() {
        DragonwellJavaProvider provider = providerFor("windows", "x64");

        IOException error = assertThrows(IOException.class, () -> provider.resolve(22));
        assertTrue(error.getMessage().contains("22"), error.getMessage());
    }

    @Test
    void unsupportedArchShouldFailInsteadOfBadUrl() {
        DragonwellJavaProvider provider = providerFor("linux", "");

        IOException error = assertThrows(IOException.class, () -> provider.resolve(17));
        assertTrue(error.getMessage().contains("架构"), error.getMessage());
    }

    // ==================================================================
    // 资产选择
    // ==================================================================

    @Test
    void Windows应选中x64的zip并采用digest作为校验值() throws Exception {
        stubReleaseFor(17, base, SHA_WINDOWS, true);
        DragonwellJavaProvider provider = providerFor("windows", "x64");

        JavaPackage pkg = provider.resolve(17);

        assertEquals("dragonwell", pkg.vendor());
        assertEquals(17, pkg.majorVersion());
        assertEquals("Alibaba_Dragonwell_Standard_17.0.20.0.21.8_x64_windows.zip", pkg.fileName());
        assertEquals("zip", pkg.archiveType());
        assertEquals(SHA_WINDOWS, pkg.sha256(), "应直接采用 GitHub 资产自带的 digest");
        assertEquals(189433115L, pkg.size());
        assertEquals(1, pkg.downloadUrls().size());
        assertTrue(pkg.downloadUrls().get(0).endsWith(".zip"));
    }

    @Test
    void Linux应选中x64的tarGz并识别归档类型() throws Exception {
        stubReleaseFor(17, base, SHA_LINUX, false);
        DragonwellJavaProvider provider = providerFor("linux", "x64");

        JavaPackage pkg = provider.resolve(17);

        assertEquals("Alibaba_Dragonwell_Standard_17.0.20.0.21.8_x64_linux.tar.gz", pkg.fileName());
        assertEquals("tar.gz", pkg.archiveType());
        assertEquals(SHA_LINUX, pkg.sha256());
    }

    @Test
    void githubHostedAssetsShouldIncludeMirrorCandidates() throws Exception {
        // 真实场景的资产地址就是 GitHub Releases：直连国内极慢，必须给出加速候选
        stubReleaseFor(17, "https://github.com/dragonwell-project/dragonwell17/releases/download/tag",
                SHA_WINDOWS, true);
        DragonwellJavaProvider provider = providerFor("windows", "x64");

        JavaPackage pkg = provider.resolve(17);

        assertTrue(pkg.downloadUrls().size() > 1, "应带上加速镜像候选：" + pkg.downloadUrls());
        assertTrue(pkg.primaryUrl().startsWith("https://github.com/"),
                "官方地址必须兜底在最后：" + pkg.primaryUrl());
    }

    @Test
    void missingHashShouldNotAddThirdPartyMirrors() throws Exception {
        // digest 与边车文件都没有 → 无法校验 → 只用官方地址
        ObjectNode release = releaseWith(
                "https://github.com/dragonwell-project/dragonwell17/releases/download/tag", null, false);
        payloads.put(stubPath(17), JsonUtils.writePrettyBytes(release));
        DragonwellJavaProvider provider = providerFor("windows", "x64");

        JavaPackage pkg = provider.resolve(17);

        assertEquals(1, pkg.downloadUrls().size(), "不可校验时不应走第三方加速：" + pkg.downloadUrls());
    }

    @Test
    void standardShouldWinOverExtended() throws Exception {
        ObjectNode release = releaseWith(base, SHA_WINDOWS, true);
        ArrayNode assets = (ArrayNode) release.get("assets");
        // 追加一个 Extended 归档：体积更大，且不应被选中
        assets.addObject()
                .put("name", "Alibaba_Dragonwell_Extended_17.0.20.0.21.8_x64_windows.zip")
                .put("size", 300_000_000L)
                .put("digest", "sha256:" + "f".repeat(64))
                .put("browser_download_url", base + "/extended.zip");
        payloads.put(stubPath(17),
                JsonUtils.writePrettyBytes(release));

        DragonwellJavaProvider provider = providerFor("windows", "x64");
        JavaPackage pkg = provider.resolve(17);

        assertTrue(pkg.fileName().contains("_Standard_"), "应优先选择 Standard 版本线：" + pkg.fileName());
    }

    @Test
    void missingDigestShouldFallBackToSidecar() throws Exception {
        // 主资产不带 digest，但 release 中提供 .sha256.txt
        ObjectNode release = releaseWith(base, null, true);
        payloads.put(stubPath(17),
                JsonUtils.writePrettyBytes(release));
        payloads.put("/sha256.txt", (SHA_WINDOWS + "  Alibaba_Dragonwell_Standard.zip")
                .getBytes(StandardCharsets.UTF_8));

        DragonwellJavaProvider provider = providerFor("windows", "x64");
        JavaPackage pkg = provider.resolve(17);

        assertEquals(SHA_WINDOWS, pkg.sha256(), "digest 缺失时必须回退读取 .sha256.txt");
    }

    @Test
    void missingChecksumsShouldStillAllowInstall() throws Exception {
        ObjectNode release = releaseWith(base, null, false);
        payloads.put(stubPath(17),
                JsonUtils.writePrettyBytes(release));

        DragonwellJavaProvider provider = providerFor("windows", "x64");
        JavaPackage pkg = provider.resolve(17);

        assertTrue(pkg.sha256().isEmpty(), "拿不到哈希时应降级为不校验，而不是让安装无法进行");
    }

    @Test
    void noMatchingPlatformAssetShouldFail() throws Exception {
        // release 中只有 aarch64 的资产，而本机按 x64 解析
        ObjectNode release = JsonUtils.newObject();
        release.put("tag_name", "dragonwell-standard-17.0.20.0.21+8_jdk-17.0.20-ga");
        ArrayNode assets = release.putArray("assets");
        assets.addObject()
                .put("name", "Alibaba_Dragonwell_Standard_17.0.20.0.21.8_aarch64_linux.tar.gz")
                .put("size", 1L)
                .put("digest", "sha256:" + SHA_LINUX)
                .put("browser_download_url", base + "/aarch64.tar.gz");
        payloads.put(stubPath(17),
                JsonUtils.writePrettyBytes(release));

        DragonwellJavaProvider provider = providerFor("windows", "x64");
        IOException error = assertThrows(IOException.class, () -> provider.resolve(17));
        assertTrue(error.getMessage().contains("没有") || error.getMessage().contains("归档"),
                error.getMessage());
    }

    // ==================================================================
    // 缓存与解析细节
    // ==================================================================

    @Test
    void repeatedResolutionShouldHitCache() throws Exception {
        stubReleaseFor(17, base, SHA_WINDOWS, true);
        DragonwellJavaProvider provider = providerFor("windows", "x64");

        provider.resolve(17);
        int afterFirst = hits.values().stream().mapToInt(AtomicInteger::get).sum();
        // 首次解析最多两次请求：一次源预检（配置了自定义源时才有）+ 一次真实抓取
        assertTrue(afterFirst <= 2, "首次解析不应发起过多请求；实际请求路径: " + hits.keySet());

        provider.resolve(17);
        assertEquals(afterFirst, hits.values().stream().mapToInt(AtomicInteger::get).sum(),
                "同一主版本不应重复请求 GitHub（匿名配额仅每小时 60 次）；实际请求路径: " + hits.keySet());
    }

    @Test
    void versionShouldComeFromJdkTag() throws Exception {
        stubReleaseFor(17, base, SHA_WINDOWS, true);
        DragonwellJavaProvider provider = providerFor("windows", "x64");

        assertEquals("17.0.20", provider.resolve(17).versionString());
    }

    @Test
    void hexExtractionShouldTolerateDigestAndSidecar() {
        assertEquals(SHA_WINDOWS, DragonwellJavaProvider.extractHex("sha256:" + SHA_WINDOWS));
        assertEquals(SHA_WINDOWS, DragonwellJavaProvider.extractHex(SHA_WINDOWS + "  file.zip"));
        assertEquals(SHA_WINDOWS, DragonwellJavaProvider.extractHex(SHA_WINDOWS.toUpperCase()));
        assertEquals("", DragonwellJavaProvider.extractHex(null));
        assertEquals("", DragonwellJavaProvider.extractHex("no hash here"));
    }

    @Test
    void dragonwellShouldOutrankOtherVendors() {
        DragonwellJavaProvider provider = providerFor("windows", "x64");
        assertEquals("dragonwell", provider.id());
        assertTrue(provider.priority() < 10, "优先级数值需小于 Adoptium(10) 才能成为首选");
        assertEquals(List.of(8, 11, 17, 21), DragonwellJavaProvider.supportedMajorVersions());
    }

    @Test
    void defaultVendorShouldBeDragonwell() {
        LauncherConfig.JavaSettings settings = new LauncherConfig.JavaSettings();
        assertEquals(DragonwellJavaProvider.ID, settings.preferredVendor);
        assertEquals(DragonwellJavaProvider.ID, LauncherConfig.JavaSettings.VENDOR_DRAGONWELL);
    }

    // ==================================================================
    // 装置
    // ==================================================================

    /**
     * 构造覆盖了平台的 Provider。
     * <p>用<b>前缀映射</b>而非反代模式把 GitHub 接口导向本地桩：
     * 反代模式只改写已登记为「官方」的域名，而 {@code api.github.com} 并不在列，
     * 一旦语义变化就会让测试静默地跑到真实网络上（曾经真的发生了）。</p>
     */
    private DragonwellJavaProvider providerFor(String os, String arch) {
        LauncherConfig.DownloadSettings settings = new LauncherConfig.DownloadSettings();
        settings.source = LauncherConfig.DownloadSettings.SOURCE_OFFICIAL;
        settings.timeoutSeconds = 10;
        CustomSource stubSource = new CustomSource(base, false,
                Map.of("https://api.github.com/", GITHUB_API_PREFIX));
        SourceResolver resolver = new SourceResolver(settings, List.of(stubSource));
        MetaFetcher fetcher = new MetaFetcher(resolver);
        return new DragonwellJavaProvider(fetcher, os, arch);
    }

    /** 桩服务器上承载 GitHub 接口的路径前缀。 */
    private static final String GITHUB_API_PREFIX = "/gh-api/";

    private static String stubPath(int major) {
        return GITHUB_API_PREFIX + "repos/dragonwell-project/dragonwell" + major + "/releases/latest";
    }

    /** 生成一份含 Specify 平台资产的 release JSON。 */
    private ObjectNode releaseWith(String assetBase, String digest, boolean withSidecar) {
        ObjectNode release = JsonUtils.newObject();
        release.put("tag_name", "dragonwell-standard-17.0.20.0.21+8_jdk-17.0.20-ga");
        ArrayNode assets = release.putArray("assets");

        ObjectNode windows = assets.addObject();
        windows.put("name", "Alibaba_Dragonwell_Standard_17.0.20.0.21.8_x64_windows.zip");
        windows.put("size", 189433115L);
        if (digest != null) {
            windows.put("digest", "sha256:" + digest);
        }
        windows.put("browser_download_url", assetBase + "/dragonwell17.zip");

        ObjectNode linux = assets.addObject();
        linux.put("name", "Alibaba_Dragonwell_Standard_17.0.20.0.21.8_x64_linux.tar.gz");
        linux.put("size", 192284324L);
        linux.put("digest", "sha256:" + SHA_LINUX);
        linux.put("browser_download_url", assetBase + "/dragonwell17.tar.gz");

        if (withSidecar) {
            assets.addObject()
                    .put("name", "Alibaba_Dragonwell_Standard_17.0.20.0.21.8_x64_windows.zip.sha256.txt")
                    .put("size", 155L)
                    .put("digest", "sha256:" + "a".repeat(64))
                    .put("browser_download_url", assetBase + "/sha256.txt");
        }
        return release;
    }

    private void stubReleaseFor(int major, String assetBase, String digest, boolean withSidecar)
            throws IOException {
        payloads.put(stubPath(major), JsonUtils.writePrettyBytes(releaseWith(assetBase, digest, withSidecar)));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
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
