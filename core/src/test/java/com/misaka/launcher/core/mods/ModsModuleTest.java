package com.misaka.launcher.core.mods;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.misaka.launcher.core.config.ConfigStore;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.config.PortablePaths;
import com.misaka.launcher.core.download.DownloadManager;
import com.misaka.launcher.core.download.OfficialSource;
import com.misaka.launcher.core.download.SourceResolver;
import com.misaka.launcher.core.instance.GamePaths;
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

import java.io.ByteArrayOutputStream;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模组模块验证：Modrinth 接口解析、内容类型映射、安装与来源标记、
 * 更新检查、指纹算法、整合包安装。
 */
@Timeout(value = 90, unit = java.util.concurrent.TimeUnit.SECONDS)
class ModsModuleTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String base;
    private final Map<String, byte[]> payloads = new ConcurrentHashMap<>();
    private final Map<String, String> lastRequest = new ConcurrentHashMap<>();

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

    // ==================================================================
    // 内容类型与文件名
    // ==================================================================

    @Test
    void contentTypeShouldMapToGameDir() {
        assertEquals(ModContentType.MOD, ModContentType.fromProjectTypes(List.of("mod")));
        assertEquals(ModContentType.MODPACK, ModContentType.fromProjectTypes(List.of("modpack")));
        assertEquals(ModContentType.RESOURCE_PACK, ModContentType.fromProjectTypes(List.of("resourcepack")));
        assertEquals(ModContentType.SHADER, ModContentType.fromProjectTypes(List.of("shader")));
        // 缺省按模组处理，避免误落到空目录
        assertEquals(ModContentType.MOD, ModContentType.fromProjectTypes(List.of()));

        GamePaths paths = new GamePaths(tempDir.resolve("game"));
        assertEquals(paths.modsDir(), ModInstaller.targetDir(paths, ModContentType.MOD));
        assertEquals(paths.resourcePacksDir(), ModInstaller.targetDir(paths, ModContentType.RESOURCE_PACK));
        assertEquals(paths.shaderPacksDir(), ModInstaller.targetDir(paths, ModContentType.SHADER));
    }

    @Test
    void fileNameSanitizeShouldResolvePathAndRejectBad() {
        assertEquals("a.jar", ModInstaller.sanitizeFileName("a.jar"));
        assertEquals("a.jar", ModInstaller.sanitizeFileName("mods/a.jar"));
        assertEquals("a.jar", ModInstaller.sanitizeFileName("C:\\mods\\a.jar"));
        // 穿越路径被剥离为纯文件名，无法逃出目标目录
        assertEquals("evil.jar", ModInstaller.sanitizeFileName("../../evil.jar"));
        // 非法字符与空值必须拒绝
        assertNull(ModInstaller.sanitizeFileName("bad:name.jar"));
        assertNull(ModInstaller.sanitizeFileName(""));
        assertNull(ModInstaller.sanitizeFileName(null));

        // 索引键的解析必须可逆
        String key = ModInstaller.indexKey("inst-1", "mods/a.jar");
        assertEquals("inst-1", ModInstaller.instanceIdOf(key));
        assertEquals("mods/a.jar", ModInstaller.relativePathOf(key));
    }

    // ==================================================================
    // Modrinth
    // ==================================================================

    @Test
    void Modrinth检索应构造正确的分面并解析结果() throws Exception {
        ObjectNode hit = JsonUtils.newObject();
        hit.put("project_id", "AANobbMI").put("slug", "sodium").put("title", "Sodium")
                .put("description", "perf").put("author", "jellysquid3")
                .put("downloads", 1234).put("follows", 55).put("project_type", "mod");
        hit.putArray("categories").add("optimization");
        hit.putArray("display_categories").add("optimization");
        hit.putArray("versions").add("1.20.1");
        hit.putArray("all_project_types").add("mod");
        ObjectNode searchRoot = JsonUtils.newObject();
        ArrayNode hits = searchRoot.putArray("hits");
        hits.add(hit);
        payloads.put("/v2/search", JsonUtils.writePrettyBytes(searchRoot));

        ModrinthProvider provider = new ModrinthProvider(base + "/v2");
        List<ModProject> projects = provider.search(new ModSearchQuery("sodium",
                ModContentType.MOD, "1.20.1", "fabric", "", 0, 10,
                ModSearchQuery.Sort.DOWNLOADS, true));

        assertEquals(1, projects.size());
        ModProject project = projects.get(0);
        assertEquals("AANobbMI", project.id());
        assertEquals("Sodium", project.title());
        assertEquals("modrinth", project.source());
        assertEquals(ModContentType.MOD, project.contentType());
        assertTrue(project.supports("1.20.1", "optimization"));

        // 请求参数必须包含分面与排序
        String query = lastRequest.get("/v2/search");
        assertNotNull(query);
        assertTrue(query.contains("index=downloads"), query);
        assertTrue(query.contains("facets="), query);
        assertTrue(query.contains("project_type"), query);
    }

    @Test
    void Modrinth版本解析应取主文件并保留依赖与哈希() throws Exception {
        ObjectNode file = JsonUtils.newObject();
        file.put("filename", "sodium-0.5.13.jar").put("url", base + "/sodium.jar")
                .put("size", 100L).put("primary", true);
        file.putObject("hashes").put("sha1", "aaaaaaaa").put("sha512", "bbbbbbbb");

        ObjectNode dep = JsonUtils.newObject();
        dep.put("project_id", "P7dR8mSH").put("dependency_type", "required");

        ObjectNode version = JsonUtils.newObject();
        version.put("id", "OihdIimA").put("project_id", "AANobbMI")
                .put("name", "Sodium 0.5.13").put("version_number", "mc1.20.1-0.5.13")
                .put("version_type", "release").put("date_published", "2024-01-01T00:00:00Z");
        version.putArray("game_versions").add("1.20.1");
        version.putArray("loaders").add("fabric");
        version.putArray("files").add(file);
        version.putArray("dependencies").add(dep);

        ArrayNode versions = JsonUtils.newArray();
        versions.add(version);
        payloads.put("/v2/project/AANobbMI/version", JsonUtils.writePrettyBytes(versions));

        ModrinthProvider provider = new ModrinthProvider(base + "/v2");
        List<ModFile> files = provider.versions("AANobbMI", "1.20.1", "fabric");

        assertEquals(1, files.size());
        ModFile parsed = files.get(0);
        assertEquals("OihdIimA", parsed.versionId());
        assertEquals("sodium-0.5.13.jar", parsed.fileName());
        assertEquals("aaaaaaaa", parsed.sha1());
        assertTrue(parsed.isStable());
        assertEquals(1, parsed.requiredDependencies().size());
        assertEquals("P7dR8mSH", parsed.requiredDependencies().get(0).projectId());
        // Modrinth 自带直链，无需二次解析
        assertEquals(Optional.of(base + "/sodium.jar"), provider.resolveDownloadUrl(parsed));
    }

    @Test
    void filePickShouldPreferStableAndHonorExplicit() {
        ModFile beta = file("v-beta", "2.0", "beta");
        ModFile release = file("v-release", "1.0", "release");

        assertEquals("v-release", ModInstaller.selectFile(List.of(beta, release), null).versionId());
        assertEquals("v-beta", ModInstaller.selectFile(List.of(beta, release), "v-beta").versionId());
        assertNull(ModInstaller.selectFile(List.of(beta), "missing"));
    }

    // ==================================================================
    // 安装与更新
    // ==================================================================

    @Test
    void installShouldWriteFileAndIndex() throws Exception {
        Fixture fixture = prepareFixture();

        ModInstaller.InstallOutcome outcome = fixture.installer.install("inst-1", fixture.gamePaths,
                "stub", "proj-1", null, "1.20.1", "fabric", ProgressListener.NOOP);

        assertTrue(outcome.success(), outcome.summary() + " " + outcome.messages());
        assertTrue(Files.isRegularFile(fixture.gamePaths.modsDir().resolve("stub-lib-1.0.jar")));

        String key = ModInstaller.indexKey("inst-1", "mods/stub-lib-1.0.jar");
        LauncherConfig.InstalledModEntry entry = fixture.store.get().mods.installed.get(key);
        assertNotNull(entry, "必须写入来源标记，否则无法检查更新");
        assertEquals("stub", entry.source);
        assertEquals("proj-1", entry.projectId);
        assertEquals("v1", entry.versionId);
    }

    @Test
    void installShouldPullRequiredDependencies() throws Exception {
        Fixture fixture = prepareFixture();

        // 让主文件声明一个必需依赖，依赖项目由同一个桩提供
        fixture.provider.dependencyProjectId = "proj-dep";

        ModInstaller.InstallOutcome outcome = fixture.installer.install("inst-1", fixture.gamePaths,
                "stub", "proj-1", null, "1.20.1", "fabric", ProgressListener.NOOP);

        assertTrue(outcome.success(), outcome.summary() + " " + outcome.messages());
        assertTrue(Files.isRegularFile(fixture.gamePaths.modsDir().resolve("stub-lib-1.0.jar")));
        assertTrue(Files.isRegularFile(fixture.gamePaths.modsDir().resolve("stub-dep-1.0.jar")),
                "必需依赖应被自动安装，消息：" + outcome.messages());
    }

    @Test
    void updateCheckShouldDetectAndDedupe() throws Exception {
        Fixture fixture = prepareFixture();
        fixture.installer.install("inst-1", fixture.gamePaths, "stub", "proj-1", "v1",
                "1.20.1", "fabric", ProgressListener.NOOP);

        // 服务端出现新版本
        fixture.provider.latestVersionId = "v2";
        fixture.provider.latestVersionNumber = "1.1";

        ModUpdateService updates = new ModUpdateService(fixture.store, fixture.downloads, List.of(fixture.provider));
        List<ModUpdateService.ModUpdate> found = updates.check("inst-1", fixture.gamePaths,
                "1.20.1", "fabric", ProgressListener.NOOP);

        assertEquals(1, found.size(), "应发现 1 项更新");
        assertEquals("v2", found.get(0).latest().versionId());

        ModUpdateService.UpdateOutcome outcome = updates.apply(found, ProgressListener.NOOP);
        assertEquals(1, outcome.updated(), outcome.summary() + " " + outcome.messages());
        assertTrue(Files.isRegularFile(fixture.gamePaths.modsDir().resolve("stub-lib-1.1.jar")),
                "新版本文件应落盘");
    }

    @Test
    void upToDateEntriesShouldBeIgnored() throws Exception {
        Fixture fixture = prepareFixture();
        fixture.installer.install("inst-1", fixture.gamePaths, "stub", "proj-1", "v1",
                "1.20.1", "fabric", ProgressListener.NOOP);

        ModUpdateService updates = new ModUpdateService(fixture.store, fixture.downloads, List.of(fixture.provider));
        List<ModUpdateService.ModUpdate> found = updates.check("inst-1", fixture.gamePaths,
                "1.20.1", "fabric", ProgressListener.NOOP);

        assertTrue(found.isEmpty(), "版本一致时不应报告更新");
    }

    @Test
    void latestShouldPreferStable() {
        ModFile beta = file("v9-beta", "9.0-beta", "beta");
        ModFile stable = file("v8", "8.0", "release");
        ModFile picked = ModUpdateService.pickLatest(List.of(beta, stable), "1.20.1", "fabric");
        assertEquals("v8", picked.versionId(), "不应把用户从稳定版带到测试版");

        // 只有测试版可选时仍应返回，而不是「无更新」
        assertEquals("v9-beta", ModUpdateService.pickLatest(List.of(beta), "1.20.1", "fabric").versionId());
    }

    @Test
    void modpackInstallShouldDownloadAndExtract() throws Exception {
        Fixture fixture = prepareFixture();

        byte[] modBytes = "pack-mod".getBytes(StandardCharsets.UTF_8);
        payloads.put("/pack/mod.jar", modBytes);

        ObjectNode index = JsonUtils.newObject();
        index.put("formatVersion", 1).put("name", "测试整合包").put("versionId", "1.0.0");
        index.putObject("dependencies").put("minecraft", "1.20.1").put("fabric-loader", "0.15.11");
        ObjectNode fileEntry = JsonUtils.newObject();
        fileEntry.put("path", "mods/pack-mod.jar");
        fileEntry.put("fileSize", modBytes.length);
        fileEntry.put("downloads", JsonUtils.newArray().add(base + "/pack/mod.jar"));
        fileEntry.putObject("hashes").put("sha1", HashUtils.sha1(modBytes));
        fileEntry.putObject("env").put("client", "required");
        index.putArray("files").add(fileEntry);

        Path archive = tempDir.resolve("pack.mrpack");
        buildMrpack(archive, JsonUtils.writePretty(index));

        ModpackInstaller installer = new ModpackInstaller(fixture.downloads, fixture.gamePaths,
                List.of(fixture.provider));
        ModpackInstaller.InstallResult result = installer.install(archive, ProgressListener.NOOP);

        assertEquals("测试整合包", result.name());
        assertEquals("1.20.1", result.gameVersion());
        assertEquals("fabric", result.loader());
        assertEquals("0.15.11", result.loaderVersion());
        assertEquals(1, result.downloaded());
        assertTrue(result.failed() == 0, result.summary());
        assertTrue(Files.isRegularFile(fixture.gamePaths.modsDir().resolve("pack-mod.jar")));
        assertTrue(Files.isRegularFile(fixture.gamePaths.gameDir().resolve("config/pack.txt")),
                "覆盖文件应被展开到游戏目录");
    }

    @Test
    void unknownModpackFormatShouldFailClearly() throws Exception {
        Fixture fixture = prepareFixture();
        Path archive = tempDir.resolve("bad.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("nothing here".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        ModpackInstaller installer = new ModpackInstaller(fixture.downloads, fixture.gamePaths,
                List.of(fixture.provider));
        IOException error = assertThrows(IOException.class, () -> installer.install(archive, ProgressListener.NOOP));
        assertTrue(error.getMessage().contains("无法识别的整合包格式"), error.getMessage());
    }

    // ==================================================================
    // CurseForge 映射与指纹
    // ==================================================================

    @Test
    void CurseForge映射表应正确() {
        assertEquals(6, CurseForgeProvider.classId(ModContentType.MOD));
        assertEquals(4471, CurseForgeProvider.classId(ModContentType.MODPACK));
        assertEquals(12, CurseForgeProvider.classId(ModContentType.RESOURCE_PACK));
        assertEquals(6552, CurseForgeProvider.classId(ModContentType.SHADER));

        assertEquals(1, CurseForgeProvider.modLoaderType("forge"));
        assertEquals(4, CurseForgeProvider.modLoaderType("fabric"));
        assertEquals(5, CurseForgeProvider.modLoaderType("quilt"));
        assertEquals(6, CurseForgeProvider.modLoaderType("neoforge"));
        assertEquals(0, CurseForgeProvider.modLoaderType(""));
    }

    @Test
    void curseForgeWithoutKeyShouldReportUnavailable() {
        CurseForgeProvider provider = new CurseForgeProvider(() -> "");
        assertFalse(provider.isConfigured());
        assertThrows(IOException.class, () -> provider.search(
                ModSearchQuery.of("x", ModContentType.MOD, "1.20.1", "forge")));

        assertTrue(new CurseForgeProvider(() -> "abc123").isConfigured());
        assertEquals("****c123", CurseForgeProvider.maskKey("abc123"));
        assertEquals("****", CurseForgeProvider.maskKey("ab"));
    }

    @Test
    void fingerprintShouldIgnoreWhitespaceAndBeStable() {
        byte[] plain = "abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] withWhitespace = " ab\tc\r\nd\nef ".getBytes(StandardCharsets.UTF_8);

        assertEquals(MurmurHash2.fingerprint(plain), MurmurHash2.fingerprint(withWhitespace),
                "CurseForge 指纹要求忽略空格/制表/换行/回车");
        assertEquals(MurmurHash2.fingerprint(plain), MurmurHash2.fingerprint(plain.clone()));

        assertTrue(MurmurHash2.fingerprint(plain) != MurmurHash2.fingerprint("abcdefg".getBytes(StandardCharsets.UTF_8)));
        // 结果必须落在无符号 32 位范围内
        assertTrue(MurmurHash2.fingerprint(plain) >= 0 && MurmurHash2.fingerprint(plain) <= 0xFFFFFFFFL);
        // 空输入的 MurmurHash2（种子 0）恒为 0，可用于自检实现正确性
        assertEquals(0, MurmurHash2.hash32(new byte[0], 0));
    }

    @Test
    void MC百科应仅提供跳转能力() {
        McModProvider provider = new McModProvider();
        assertFalse(provider.supportsSearch(), "MC 百科没有公开 API，不应声称支持站内检索");
        assertTrue(provider.isConfigured());
        assertTrue(provider.searchUrl("通用机械").contains("search.mcmod.cn"));
        assertTrue(provider.searchUrl("a b").contains("a%20b"));
        assertTrue(provider.classUrl("1234").endsWith("/class/1234.html"));
        assertTrue(provider.versions("1", "1.20.1", "fabric").isEmpty());
    }

    // ==================================================================
    // 测试装置
    // ==================================================================

    private ModFile file(String versionId, String versionNumber, String releaseType) {
        return new ModFile("stub", "proj-1", versionId, versionNumber, "name",
                "stub-lib-" + versionNumber + ".jar", base + "/stub.jar", "", "", 10L,
                List.of("1.20.1"), List.of("fabric"), List.of(), releaseType, "2024-01-01T00:00:00Z", true);
    }

    private static final String STUB_MOD_CONTENT = "stub-mod-content";
    private static final String STUB_DEP_CONTENT = "stub-dep-content";

    /** 桩来源：文件内容由本地 HTTP 桩提供，便于走通真实下载路径。 */
    private final class StubProvider implements ModProvider {

        private String latestVersionId = "v1";
        private String latestVersionNumber = "1.0";
        private String dependencyProjectId = "";

        @Override
        public String id() {
            return "stub";
        }

        @Override
        public String displayName() {
            return "Stub";
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public List<ModProject> search(ModSearchQuery query) {
            return List.of(project("proj-1", "Stub 模组"));
        }

        @Override
        public Optional<ModProject> project(String projectId) {
            if ("proj-dep".equals(projectId)) {
                return Optional.of(project(projectId, "Stub 前置"));
            }
            return "proj-1".equals(projectId) ? Optional.of(project(projectId, "Stub 模组")) : Optional.empty();
        }

        @Override
        public List<ModFile> versions(String projectId, String gameVersion, String loader) {
            if ("proj-dep".equals(projectId)) {
                return List.of(new ModFile("stub", projectId, "dep-v1", "1.0", "dep",
                        "stub-dep-1.0.jar", base + "/stub-dep.jar", "", "", STUB_DEP_CONTENT.length(),
                        List.of("1.20.1"), List.of("fabric"), List.of(), "release", "2024-01-01T00:00:00Z", true));
            }
            String fileName = "stub-lib-" + latestVersionNumber + ".jar";
            List<ModFile.ModDependency> dependencies = dependencyProjectId.isBlank()
                    ? List.of()
                    : List.of(new ModFile.ModDependency(dependencyProjectId, "", "required"));
            return List.of(new ModFile("stub", projectId, latestVersionId, latestVersionNumber, "name",
                    fileName, base + "/stub.jar", "", "", STUB_MOD_CONTENT.length(),
                    List.of("1.20.1"), List.of("fabric"), dependencies,
                    "release", "2024-01-01T00:00:00Z", true));
        }

        @Override
        public Optional<String> resolveDownloadUrl(ModFile file) {
            return Optional.of(file.downloadUrl());
        }

        private ModProject project(String id, String title) {
            return new ModProject("stub", id, id, title, "desc", "tester", "",
                    1L, 1L, List.of("optimization"), List.of("1.20.1"), List.of("fabric"),
                    List.of("mod"), "2024-01-01T00:00:00Z", "", "");
        }
    }

    private record Fixture(ConfigStore store, GamePaths gamePaths, DownloadManager downloads,
                           StubProvider provider, ModInstaller installer) {
    }

    private Fixture prepareFixture() throws IOException {
        PortablePaths paths = PortablePaths.resolve(tempDir, false, tempDir.resolve("data").toString());
        paths.prepare();
        ConfigStore store = ConfigStore.open(paths);
        GamePaths gamePaths = new GamePaths(tempDir.resolve("game"));
        gamePaths.prepare();

        payloads.put("/stub.jar", STUB_MOD_CONTENT.getBytes(StandardCharsets.UTF_8));
        payloads.put("/stub-dep.jar", STUB_DEP_CONTENT.getBytes(StandardCharsets.UTF_8));

        LauncherConfig.DownloadSettings settings = new LauncherConfig.DownloadSettings();
        settings.source = LauncherConfig.DownloadSettings.SOURCE_OFFICIAL;
        settings.concurrency = 2;
        settings.timeoutSeconds = 10;
        SourceResolver resolver = new SourceResolver(settings, List.of(new OfficialSource()));
        DownloadManager downloadManager = new DownloadManager(resolver, settings);

        StubProvider provider = new StubProvider();
        ModInstaller installer = new ModInstaller(store, downloadManager, List.of(provider));
        return new Fixture(store, gamePaths, downloadManager, provider, installer);
    }

    private void buildMrpack(Path archive, String indexJson) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("modrinth.index.json"));
            zip.write(indexJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("overrides/config/pack.txt"));
            zip.write("override".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.write(archive, buffer.toByteArray());
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (exchange.getRequestURI().getQuery() != null) {
            lastRequest.put(path, exchange.getRequestURI().getQuery());
        }
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
