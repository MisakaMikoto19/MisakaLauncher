package com.misaka.launcher.core.install;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.config.PortablePaths;
import com.misaka.launcher.core.download.DownloadItem;
import com.misaka.launcher.core.download.DownloadManager;
import com.misaka.launcher.core.download.SourceResolver;
import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.meta.LoaderMetaService;
import com.misaka.launcher.core.meta.MetaFetcher;
import com.misaka.launcher.core.meta.VersionManifestService;
import com.misaka.launcher.core.model.LoaderType;
import com.misaka.launcher.core.model.VersionDetail;
import com.misaka.launcher.core.task.ProgressListener;
import com.misaka.launcher.core.util.HashUtils;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 加载器安装（profile 合并式）端到端验证。
 * <p>以本地桩模拟 Fabric meta 接口与 maven 仓库，验证版本 id 推导、profile 落盘、
 * 「库级别 sha1/size」是否被正确采用，以及官方域名到镜像的改写是否生效。</p>
 */
@Timeout(value = 90, unit = java.util.concurrent.TimeUnit.SECONDS)
class LoaderInstallerTest {

    private static final String MC_VERSION = "1.20.1";
    private static final String LOADER_VERSION = "0.15.11";
    private static final String PROFILE_ID = "fabric-loader-" + LOADER_VERSION + "-" + MC_VERSION;
    private static final String ASM_LIBRARY_PATH = "org/ow2/asm/asm/9.6/asm-9.6.jar";

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
    void Fabric安装应落盘Profile并下载运行库() throws Exception {
        Fixture fixture = prepareFixture();

        LoaderInstallResult result = fixture.installer.install(
                LoaderType.FABRIC, MC_VERSION, LOADER_VERSION, ProgressListener.NOOP);

        assertTrue(result.success(), "安装应成功");
        assertEquals(PROFILE_ID, result.versionId(), "应使用 profile 自带的 id");
        assertEquals(LoaderInstallResult.MODE_PROFILE, result.mode());
        assertEquals(LoaderType.FABRIC, result.loaderType());

        Path profileJson = fixture.paths.versionJson(PROFILE_ID);
        assertTrue(Files.isRegularFile(profileJson), "profile 应写入版本目录");

        VersionDetail detail = VersionManifestService.readLocal(profileJson);
        assertEquals(MC_VERSION, detail.inheritsFrom, "inheritsFrom 必须保留，启动时据此叠加原版");
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", detail.mainClass);

        assertTrue(Files.isRegularFile(fixture.paths.libraryFile(ASM_LIBRARY_PATH)),
                "profile 中的运行库应被下载到 libraries 目录");
    }

    @Test
    void libraryLevelChecksumsShouldBeUsed() throws Exception {
        Fixture fixture = prepareFixture();
        fixture.installer.install(LoaderType.FABRIC, MC_VERSION, LOADER_VERSION, ProgressListener.NOOP);

        VersionDetail profile = VersionManifestService.readLocal(fixture.paths.versionJson(PROFILE_ID));
        InstallPlan plan = fixture.vanillaInstaller.plan(profile, null);

        Optional<DownloadItem> asm = plan.coreItems().stream()
                .filter(item -> item.target().toString().replace('\\', '/').endsWith(ASM_LIBRARY_PATH))
                .findFirst();

        assertTrue(asm.isPresent(), "asm 运行库应出现在安装计划中");
        assertFalse(asm.get().checksum().isBlank(), "Fabric 元数据的库级别 sha1 必须被采用");
        assertTrue(asm.get().size() > 0, "Fabric 元数据的库级别 size 必须被采用");
        assertTrue(asm.get().url().startsWith("https://maven.fabricmc.net/"),
                "计划中应保留官方地址，由下载源负责改写: " + asm.get().url());
    }

    @Test
    void installedVanillaShouldNotBeReinstalled() throws Exception {
        Fixture fixture = prepareFixture();
        byte[] before = Files.readAllBytes(fixture.paths.versionJson(MC_VERSION));

        fixture.installer.install(LoaderType.FABRIC, MC_VERSION, LOADER_VERSION, ProgressListener.NOOP);

        assertArrayEqualsQuietly(before, Files.readAllBytes(fixture.paths.versionJson(MC_VERSION)),
                "原版描述文件不应被改写");
    }

    @Test
    void versionListShouldTolerateMirrorShape() throws Exception {
        Fixture fixture = prepareFixture();

        List<LoaderMetaService.LoaderVersion> versions =
                fixture.loaderMeta.listVersions(LoaderType.FABRIC, MC_VERSION);

        assertFalse(versions.isEmpty(), "应能解析出加载器版本");
        assertEquals(LOADER_VERSION, versions.get(0).version());
        assertTrue(versions.get(0).stable(), "stable 标记应被解析");
    }

    @Test
    void defaultVersionNamingShouldMatchCommunityConvention() {
        assertEquals("fabric-loader-0.15.11-1.20.1",
                LoaderMetaService.defaultVersionId(LoaderType.FABRIC, "1.20.1", "0.15.11"));
        assertEquals("quilt-loader-0.23.1-1.20.1",
                LoaderMetaService.defaultVersionId(LoaderType.QUILT, "1.20.1", "0.23.1"));
        assertEquals("1.20.1-forge-47.2.0",
                LoaderMetaService.defaultVersionId(LoaderType.FORGE, "1.20.1", "47.2.0"));
        assertEquals("neoforge-20.4.4",
                LoaderMetaService.defaultVersionId(LoaderType.NEOFORGE, "1.20.4", "20.4.4"));
    }

    @Test
    void installerUrlShouldDifferForForgeAndNeoForge() throws Exception {
        Fixture fixture = prepareFixture();

        String forge = fixture.loaderMeta.installerUrl(LoaderType.FORGE, "1.20.1", "47.2.0");
        assertTrue(forge.contains("net/minecraftforge/forge/1.20.1-47.2.0"), forge);
        assertTrue(forge.endsWith("-installer.jar"), forge);

        String neoModern = fixture.loaderMeta.installerUrl(LoaderType.NEOFORGE, "1.20.4", "20.4.4");
        assertTrue(neoModern.contains("net/neoforged/neoforge/20.4.4"), neoModern);

        String neoLegacy = fixture.loaderMeta.installerUrl(LoaderType.NEOFORGE, "1.20.1", "47.1.5");
        assertTrue(neoLegacy.contains("net/neoforged/forge/1.20.1-47.1.5"),
                "1.20.1 的 NeoForge 应使用 legacy 坐标: " + neoLegacy);
    }

    // ------------------------------------------------------------------
    // 测试装置
    // ------------------------------------------------------------------

    private record Fixture(LoaderInstaller installer,
                           VanillaInstaller vanillaInstaller,
                           LoaderMetaService loaderMeta,
                           GamePaths paths) {
    }

    private Fixture prepareFixture() throws IOException {
        PortablePaths launcherPaths = PortablePaths.resolve(false, tempDir.resolve("data").toString());
        launcherPaths.prepare();
        GamePaths gamePaths = new GamePaths(tempDir.resolve("game-fabric"));

        // 预置原版描述文件：确保加载器安装流程不会去安装原版。
        // 若缺失，LoaderInstaller 会真实联网下载整个原版（含数百 MB 资源），
        // 单测必须避免这种情况。
        Path baseJson = gamePaths.versionJson(MC_VERSION);
        Files.createDirectories(baseJson.getParent());
        Files.writeString(baseJson, "{\"id\":\"" + MC_VERSION + "\"}");

        // 运行库内容与地址：使用官方 maven.fabricmc.net 域名，验证镜像改写
        byte[] asmLib = "asm-library-binary".getBytes(StandardCharsets.UTF_8);
        payloads.put("/maven/" + ASM_LIBRARY_PATH, asmLib);

        ObjectNode profile = JsonUtils.newObject();
        profile.put("id", PROFILE_ID);
        profile.put("inheritsFrom", MC_VERSION);
        profile.put("type", "release");
        profile.put("mainClass", "net.fabricmc.loader.impl.launch.knot.KnotClient");
        ObjectNode arguments = profile.putObject("arguments");
        arguments.putArray("game").add("--username").add("${auth_player_name}");
        arguments.putArray("jvm").add("-DFabricMcEmu= net.minecraft.client.main.Main ");

        ArrayNode libraries = profile.putArray("libraries");
        // 真实 Fabric 元数据形态：库级别 sha1 / size，且不含 downloads 对象
        libraries.addObject()
                .put("name", "org.ow2.asm:asm:9.6")
                .put("url", "https://maven.fabricmc.net/")
                .put("sha1", HashUtils.sha1(asmLib))
                .put("size", asmLib.length);

        payloads.put("/fabric-meta/v2/versions/loader/" + MC_VERSION + "/" + LOADER_VERSION + "/profile/json",
                JsonUtils.writePrettyBytes(profile));

        ObjectNode loaderEntry = JsonUtils.newObject();
        loaderEntry.putObject("loader")
                .put("version", LOADER_VERSION)
                .put("stable", true)
                .put("maven", "net.fabricmc:fabric-loader:" + LOADER_VERSION);
        ArrayNode list = JsonUtils.newArray();
        list.add(loaderEntry);
        payloads.put("/fabric-meta/v2/versions/loader/" + MC_VERSION, JsonUtils.writePrettyBytes(list));

        LauncherConfig.DownloadSettings settings = new LauncherConfig.DownloadSettings();
        settings.source = LauncherConfig.DownloadSettings.SOURCE_CUSTOM;
        settings.customSourceUrl = base;
        settings.concurrency = 2;
        settings.retries = 2;
        settings.timeoutSeconds = 10;

        // 只注入本地桩源，并采用「前缀映射」模式，
        // 使桩地址与 BMCLAPI 的路径布局一致（/fabric-meta/、/maven/）
        com.misaka.launcher.core.download.CustomSource stub = new com.misaka.launcher.core.download.CustomSource(
                base, false, Map.of(
                "https://meta.fabricmc.net/", "/fabric-meta/",
                "https://maven.fabricmc.net/", "/maven/"));
        SourceResolver resolver = new SourceResolver(settings, List.of(stub));
        DownloadManager downloadManager = new DownloadManager(resolver, settings);
        MetaFetcher metaFetcher = new MetaFetcher(resolver);
        VersionManifestService versionMeta = new VersionManifestService(launcherPaths, metaFetcher);
        LoaderMetaService loaderMeta = new LoaderMetaService(metaFetcher);
        VanillaInstaller vanillaInstaller = new VanillaInstaller(versionMeta, downloadManager, gamePaths);

        LoaderInstaller installer = new LoaderInstaller(versionMeta, loaderMeta, downloadManager,
                vanillaInstaller, gamePaths, majorVersion -> {
                    // profile 合并式安装不应触达安装器执行路径
                    throw new IOException("测试不应请求 Java 可执行文件");
                });

        return new Fixture(installer, vanillaInstaller, loaderMeta, gamePaths);
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

    private static void assertArrayEqualsQuietly(byte[] expected, byte[] actual, String message) {
        assertEquals(new String(expected, StandardCharsets.UTF_8), new String(actual, StandardCharsets.UTF_8),
                message);
    }
}
