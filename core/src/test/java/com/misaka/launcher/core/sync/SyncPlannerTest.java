package com.misaka.launcher.core.sync;

import com.misaka.launcher.core.util.HashUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同步计划器黄金用例。
 * <p>用例数据完全取自真实部署的服务端配置（含 {@code client-only-mods → mods} 重定向），
 * 用于保证判定结果与 FileSync 客户端（Python 实现）逐项一致。</p>
 */
class SyncPlannerTest {

    /** 与 SyncSettings.json 一致。 */
    private static final List<String> CLIENT_DIRECTORIES =
            List.of("./mods/", "./resourcepacks/", "./shaderpacks/", "./tacz/");

    @TempDir
    Path tempDir;

    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = tempDir.resolve("game");
        Files.createDirectories(root);
    }

    // ------------------------------------------------------------------
    // 硬同步前缀推导
    // ------------------------------------------------------------------

    @Test
    void hardPrefixShouldResolveAfterRedirection() {
        ServerSyncConfig config = realServerConfig();
        Map<String, String> redirections = SyncPlanner.buildRedirectionMap(config);

        assertEquals(1, redirections.size(), "只应有配置中的那一条重定向");
        assertEquals("./mods", redirections.get("./client-only-mods"));
        assertFalse(redirections.containsKey("./mods"), "未配置重定向的目录不应出现在映射中");

        var prefixes = SyncPlanner.resolveHardPrefixes(CLIENT_DIRECTORIES, config, redirections);

        // ./mods（自身 hard）与 ./client-only-mods（重定向到 ./mods）都应落到客户端 ./mods
        assertTrue(prefixes.contains("./mods"));
        // ./tacz 为 hard
        assertTrue(prefixes.contains("./tacz"));
        // soft 目录不应成为硬同步前缀
        assertFalse(prefixes.contains("./resourcepacks"));
        assertFalse(prefixes.contains("./shaderpacks"));
        assertEquals(2, prefixes.size(), "硬同步前缀应为 " + prefixes);
    }

    // ------------------------------------------------------------------
    // 服务端文件映射与重定向
    // ------------------------------------------------------------------

    @Test
    void serverFilesShouldMapThroughRedirection() {
        ServerSyncConfig config = realServerConfig();
        Map<String, String> redirections = SyncPlanner.buildRedirectionMap(config);

        List<ServerFileInfo> serverFiles = List.of(
                serverFile("mods/forge.jar", "aaa"),
                serverFile("client-only-mods/clientonly.jar", "bbb"),
                serverFile("tacz/gun.zip", "ccc"),
                serverFile("outside/ignored.txt", "ddd"));

        Map<String, ServerFileInfo> mapped = SyncPlanner.mapServerFiles(CLIENT_DIRECTORIES, serverFiles, redirections);

        assertTrue(mapped.containsKey("./mods/forge.jar"));
        assertTrue(mapped.containsKey("./mods/clientonly.jar"), "重定向后应落在 ./mods 下");
        assertTrue(mapped.containsKey("./tacz/gun.zip"));
        assertFalse(mapped.containsKey("./outside/ignored.txt"), "不在客户端目录下的文件应被忽略");
        assertEquals(3, mapped.size());
        // 下载地址必须使用服务端原始 path，而不是重定向后的客户端路径
        assertEquals("client-only-mods/clientonly.jar",
                mapped.get("./mods/clientonly.jar").downloadPath());
    }

    // ------------------------------------------------------------------
    // 状态判定（核心黄金用例）
    // ------------------------------------------------------------------

    @Test
    void statusShouldMatchLegacyClient() throws Exception {
        write("mods/up-to-date.jar", "same-content");
        write("mods/outdated.jar", "old-content");
        write("mods/extra.jar", "should-be-deleted");
        write("resourcepacks/extra-kept.zip", "kept-by-soft-mode");
        write("shaderpacks/ok.zip", "shader-content");
        write("tacz/extra-tacz.json", "hard-mode-extra");

        ServerSyncConfig config = realServerConfig();
        List<ServerFileInfo> serverFiles = List.of(
                serverFile("mods/up-to-date.jar", md5("same-content")),
                serverFile("mods/outdated.jar", md5("new-content")),
                // 重定向：服务端独有目录 → 客户端 ./mods
                serverFile("client-only-mods/clientonly.jar", md5("client-only")),
                serverFile("shaderpacks/ok.zip", md5("shader-content")),
                serverFile("tacz/gun.zip", md5("gun-content")));

        Map<String, LocalFileInfo> localFiles = SyncPlanner.scanLocal(root, CLIENT_DIRECTORIES, List.of());
        SyncPlan plan = SyncPlanner.plan(root, CLIENT_DIRECTORIES, serverFiles, config, localFiles, true);

        assertEquals(SyncStatus.UP_TO_DATE, status(plan, "./mods/up-to-date.jar"));
        assertEquals(SyncStatus.NEED_UPDATE, status(plan, "./mods/outdated.jar"));
        assertEquals(SyncStatus.NEED_DELETE, status(plan, "./mods/extra.jar"));
        assertEquals(SyncStatus.KEPT, status(plan, "./resourcepacks/extra-kept.zip"),
                "软同步目录中的多余文件必须保留");
        assertEquals(SyncStatus.UP_TO_DATE, status(plan, "./shaderpacks/ok.zip"));
        assertEquals(SyncStatus.NEED_DELETE, status(plan, "./tacz/extra-tacz.json"));
        assertEquals(SyncStatus.NEED_DOWNLOAD, status(plan, "./mods/clientonly.jar"),
                "重定向过来的服务端独有文件应判定为需下载");
        assertEquals(SyncStatus.NEED_DOWNLOAD, status(plan, "./tacz/gun.zip"));

        // 动作清单
        assertTrue(plan.toDelete().contains("./mods/extra.jar"));
        assertTrue(plan.toDelete().contains("./tacz/extra-tacz.json"));
        assertEquals(2, plan.toDelete().size(), "只应删除硬同步目录内的多余文件: " + plan.toDelete());

        assertEquals(3, plan.toDownload().size());
        SyncDownload clientOnly = plan.toDownload().stream()
                .filter(task -> task.fileKey().equals("./mods/clientonly.jar"))
                .findFirst().orElseThrow();
        assertEquals(root.resolve("mods").resolve("clientonly.jar"), clientOnly.target());
        assertEquals("client-only-mods/clientonly.jar", clientOnly.serverPath());

        // 已最新 = UP_TO_DATE(2) + KEPT(1)：软同步下保留的多余文件在原客户端中也报为 Up to date
        assertEquals(3, plan.upToDateCount());
        assertEquals(1, plan.needUpdateCount());
        assertEquals(2, plan.needDownloadCount());
        assertEquals(2, plan.needDeleteCount());
        assertTrue(plan.hasChanges());
    }

    @Test
    void deleteDisabledShouldDetectWithoutDeleting() throws Exception {
        write("mods/extra.jar", "extra");

        ServerSyncConfig config = realServerConfig();
        Map<String, LocalFileInfo> localFiles = SyncPlanner.scanLocal(root, CLIENT_DIRECTORIES, List.of());
        SyncPlan plan = SyncPlanner.plan(root, CLIENT_DIRECTORIES, List.of(), config, localFiles, false);

        assertEquals(SyncStatus.NEED_DELETE, status(plan, "./mods/extra.jar"), "状态仍应被识别出来");
        assertTrue(plan.toDelete().isEmpty(), "关闭删除后不应产生删除动作");
        assertFalse(plan.hasChanges());
    }

    @Test
    void identicalTreesShouldBeUpToDate() throws Exception {
        write("mods/a.jar", "content-a");

        ServerSyncConfig config = realServerConfig();
        List<ServerFileInfo> serverFiles = List.of(serverFile("mods/a.jar", md5("content-a")));
        Map<String, LocalFileInfo> localFiles = SyncPlanner.scanLocal(root, CLIENT_DIRECTORIES, List.of());
        SyncPlan plan = SyncPlanner.plan(root, CLIENT_DIRECTORIES, serverFiles, config, localFiles, true);

        assertFalse(plan.hasChanges());
        assertEquals(1, plan.upToDateCount());
    }

    // ------------------------------------------------------------------
    // 本地扫描
    // ------------------------------------------------------------------

    @Test
    void localScanShouldBuildProtocolKeys() throws Exception {
        write("mods/sub/deep.jar", "deep");
        write("mods/IgnoredByConfig.json", "ignored");
        write("outside.txt", "not-managed");

        Map<String, LocalFileInfo> localFiles = SyncPlanner.scanLocal(root,
                List.of("./mods/", "./absent-dir/"), List.of("IgnoredByConfig.json"));

        assertTrue(localFiles.containsKey("./mods/sub/deep.jar"), localFiles.keySet().toString());
        assertTrue(Files.isDirectory(root.resolve("absent-dir")), "缺失目录应被自动创建");
        assertFalse(localFiles.containsKey("./mods/IgnoredByConfig.json"), "忽略清单中的文件不应被扫描");
        assertFalse(localFiles.containsKey("./outside.txt"), "不属于受管目录的文件不应被扫描");
        assertEquals(1, localFiles.size());
    }

    @Test
    void scanShouldComputeSameMd5AsPython() throws Exception {
        write("mods/hash.jar", "hello");
        Map<String, LocalFileInfo> localFiles = SyncPlanner.scanLocal(root, List.of("./mods/"), List.of());

        LocalFileInfo info = localFiles.get("./mods/hash.jar");
        assertEquals(HashUtils.md5("hello".getBytes(StandardCharsets.UTF_8)), info.md5());
        assertEquals(5L, info.size());
    }

    // ------------------------------------------------------------------
    // 安全与工具
    // ------------------------------------------------------------------

    @Test
    void targetResolveShouldRejectTraversal() {
        assertThrows(IOException.class,
                () -> SyncPlanner.resolveTarget(root, "./../../etc/passwd"));
        assertThrows(IOException.class,
                () -> SyncPlanner.resolveTarget(root, "./mods/../../outside"));
    }

    @Test
    void pathNormalizeShouldMatchPythonRstrip() {
        // Python 的 rstrip('/') 只去掉尾部斜杠，"./" 前缀必须保留
        assertEquals("./mods", SyncPlanner.normalize("./mods/"));
        assertEquals("./mods", SyncPlanner.normalize(".\\mods\\\\"));
        assertEquals("", SyncPlanner.normalize("/"));
        assertEquals("tacz", SyncPlanner.normalize("tacz"));
        assertEquals("", SyncPlanner.normalize(null));
    }

    @Test
    void downloadUrlShouldEncodeSpacesAndCjk() {
        assertEquals("mods/a.jar", SyncProtocolClient.encodePath("mods/a.jar"));
        assertEquals("mods/a.jar", SyncProtocolClient.encodePath("./mods/a.jar"));
        assertEquals("mods/my%20mod.jar", SyncProtocolClient.encodePath("mods/my mod.jar"));
        assertEquals("tacz/%E6%AD%A6%E5%99%A8.zip", SyncProtocolClient.encodePath("tacz/武器.zip"));
        assertEquals("mods/a.jar", SyncProtocolClient.encodePath("mods\\a.jar"));
    }

    @Test
    void emptyConfigShouldDegradeToSoftSync() throws Exception {
        write("mods/whatever.jar", "x");

        Map<String, LocalFileInfo> localFiles = SyncPlanner.scanLocal(root, CLIENT_DIRECTORIES, List.of());
        // 服务端未提供任何目录/重定向：所有本地文件都不应被删除
        SyncPlan plan = SyncPlanner.plan(root, CLIENT_DIRECTORIES, List.of(), new ServerSyncConfig(),
                localFiles, true);

        assertEquals(SyncStatus.KEPT, status(plan, "./mods/whatever.jar"));
        assertTrue(plan.toDelete().isEmpty());
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 与真实服务端 SyncSettingsServerSide.json 等价的配置。 */
    private ServerSyncConfig realServerConfig() {
        ServerSyncConfig config = new ServerSyncConfig();

        Map<String, String> modes = new LinkedHashMap<>();
        modes.put("./mods/", "hard");
        modes.put("./resourcepacks/", "soft");
        modes.put("./shaderpacks/", "soft");
        modes.put("./client-only-mods/", "hard");
        modes.put("./tacz/", "hard");
        modes.forEach((path, mode) -> {
            ServerSyncConfig.SyncDirectory directory = new ServerSyncConfig.SyncDirectory();
            directory.path = path;
            directory.mode = mode;
            config.directories.add(directory);
        });

        ServerSyncConfig.Redirection redirection = new ServerSyncConfig.Redirection();
        redirection.from = "./client-only-mods/";
        redirection.to = "./mods/";
        config.redirections.add(redirection);
        return config;
    }

    private static ServerFileInfo serverFile(String relativePath, String hash) {
        ServerFileInfo info = new ServerFileInfo();
        info.path = relativePath;
        info.realPath = relativePath;
        info.hash = hash;
        info.size = 0L;
        info.mode = "hard";
        return info;
    }

    private static SyncStatus status(SyncPlan plan, String fileKey) {
        SyncStatus status = plan.statuses().get(fileKey);
        assertTrue(status != null, "缺少状态记录: " + fileKey + "，实际键: " + plan.statuses().keySet());
        return status;
    }

    private static String md5(String content) {
        return HashUtils.md5(content.getBytes(StandardCharsets.UTF_8));
    }

    private void write(String relativePath, String content) throws IOException {
        Path target = root.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
