package com.misaka.launcher.core.instance;

import com.misaka.launcher.core.config.ConfigStore;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.config.PortablePaths;
import com.misaka.launcher.core.model.LoaderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多实例（多版本多目录）管理验证。
 */
class InstanceManagerTest {

    @TempDir
    Path tempDir;

    private InstanceManager manager;
    private ConfigStore store;

    @BeforeEach
    void setUp() {
        PortablePaths paths = PortablePaths.resolve(false, tempDir.resolve("data").toString());
        paths.prepare();
        store = ConfigStore.open(paths);
        manager = new InstanceManager(store, paths);
    }

    // ==================================================================
    // 版本自愈（安装中途失败后的恢复）
    // ==================================================================

    /** 在实例游戏目录里造一个「已安装」的版本（目录名与 json 同名）。 */
    private void installVersionOnDisk(LauncherConfig.InstanceEntry entry, String versionId)
            throws Exception {
        Path dir = manager.gamePaths(entry).versionDir(versionId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(versionId + ".json"), "{\"id\":\"" + versionId + "\"}");
    }

    /**
     * 造一个游戏目录落在 {@code @TempDir} 下的实例。
     * <p>必须显式指定目录：非便携模式下默认游戏目录在构建目录里，
     * 会跨次运行复用同一路径，导致「磁盘上已有哪些版本」不可控（踩过）。</p>
     */
    private LauncherConfig.InstanceEntry createIsolated(String name) {
        Path dir = tempDir.resolve("game-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        return manager.create(name, dir.toString(), "");
    }

    @Test
    void diskVersionWithoutRecordShouldSelfHeal() throws Exception {
        // 安装因为个别文件下载失败而整体中止时，版本 JSON 其实已经在盘上，
        // 但 updateVersion 没跑到 —— 不恢复的话用户永远卡在「版本未安装」
        LauncherConfig.InstanceEntry entry = createIsolated("Minecraft-1.20.1");
        assertFalse(manager.isLaunchable(entry));
        installVersionOnDisk(entry, "1.20.1");

        String recovered = manager.recoverVersionFromDisk(entry);

        assertEquals("1.20.1", recovered);
        assertEquals("1.20.1", entry.versionId);
        assertEquals("1.20.1", entry.mcVersion);
        assertTrue(manager.isLaunchable(entry), "自愈后应可启动");
    }

    @Test
    void healthyInstanceShouldNotBeTouched() throws Exception {
        LauncherConfig.InstanceEntry entry = createIsolated("Minecraft-1.20.1");
        installVersionOnDisk(entry, "1.20.1");
        manager.updateVersion(entry.id, "1.20.1", LoaderType.FORGE, "47.4.26");

        assertEquals("", manager.recoverVersionFromDisk(entry), "已完好时不应产生任何改动");
        assertEquals(LoaderType.FORGE.id(), entry.loaderType, "已有加载器信息不能被覆盖");
    }

    @Test
    void ambiguousDiskVersionsShouldNotBeGuessed() throws Exception {
        LauncherConfig.InstanceEntry entry = createIsolated("Minecraft-1.20.1");
        installVersionOnDisk(entry, "1.20.1");
        installVersionOnDisk(entry, "1.19.2");

        assertEquals("", manager.recoverVersionFromDisk(entry), "多个版本时无法判断用户想用哪个");
        assertEquals("", entry.versionId);
    }

    @Test
    void selfHealShouldInferLoaderFromVersionId() throws Exception {
        LauncherConfig.InstanceEntry entry = createIsolated("Minecraft-1.20.1-Forge");
        installVersionOnDisk(entry, "1.20.1-forge-47.4.26");

        assertEquals("1.20.1-forge-47.4.26", manager.recoverVersionFromDisk(entry));

        assertEquals("1.20.1", entry.mcVersion);
        assertEquals(LoaderType.FORGE.id(), entry.loaderType);
        assertEquals("47.4.26", entry.loaderVersion);
    }

    @Test
    void selectingInstanceShouldBackfillFromDisk() throws Exception {
        // 老配置 / 手改过的配置里 mcVersion 与加载器字段可能是空的：
        // 界面展示、Java 判定、加载器安装都要用到它们，选中时就得主动检测回填
        LauncherConfig.InstanceEntry entry = createIsolated("Minecraft-1.20.1-Forge");
        installVersionOnDisk(entry, "1.20.1-forge-47.4.26");
        entry.versionId = "1.20.1-forge-47.4.26";
        entry.mcVersion = "";
        entry.loaderType = "";
        entry.loaderVersion = "";

        assertTrue(manager.syncVersionMetadata(entry));

        assertEquals("1.20.1", entry.mcVersion);
        assertEquals(LoaderType.FORGE.id(), entry.loaderType);
        assertEquals("47.4.26", entry.loaderVersion);
    }

    @Test
    void backfillShouldNotInventGameVersion() {
        // NeoForge 1.21+ 的 id（neoforge-21.1.0）里没有游戏版本：
        // 只能补加载器信息，绝不能把 "neoforge-21.1.0" 当成游戏版本写进 mcVersion
        LauncherConfig.InstanceEntry entry = createIsolated("NeoForge 实例");
        entry.versionId = "neoforge-21.1.0";
        entry.mcVersion = "1.21.1";

        manager.syncVersionMetadata(entry);

        assertEquals("1.21.1", entry.mcVersion, "读不出游戏版本时必须保留原值");
        assertEquals(LoaderType.NEOFORGE.id(), entry.loaderType);
        assertEquals("21.1.0", entry.loaderVersion);
    }

    @Test
    void missingLoaderVersionShouldNotBeReplacedByVanilla() throws Exception {
        // 真实故障：Forge 实例安装中断后原版先就位，自愈补记了原版版本，
        // 游戏以 vanilla 启动 —— 实例声明的加载器与 4608 个模组文件形同虚设
        LauncherConfig.InstanceEntry entry = createIsolated("Forge 实例");
        entry.mcVersion = "1.20.1";
        entry.loaderType = LoaderType.FORGE.id();
        entry.loaderVersion = "47.4.26";
        installVersionOnDisk(entry, "1.20.1");

        assertEquals("", manager.recoverVersionFromDisk(entry), "不得拿原版顶替加载器实例");
        assertEquals("", entry.versionId, "versionId 必须保持为空，交由安装流程补装");
    }

    @Test
    void installedLoaderVersionShouldWinOverVanilla() throws Exception {
        LauncherConfig.InstanceEntry entry = createIsolated("Forge 实例");
        entry.mcVersion = "1.20.1";
        entry.loaderType = LoaderType.FORGE.id();
        entry.loaderVersion = "47.4.26";
        installVersionOnDisk(entry, "1.20.1");
        installVersionOnDisk(entry, "1.20.1-forge-47.4.26");

        assertEquals("1.20.1-forge-47.4.26", manager.recoverVersionFromDisk(entry),
                "实例声明了加载器时应优先采用加载器版本");
        assertEquals("1.20.1-forge-47.4.26", entry.versionId);
    }

    @Test
    void mismatchedVersionIdShouldHealToLoaderVersion() throws Exception {
        // 真实故障：历史自愈把 Forge 实例的 versionId 写成了原版 1.20.1，
        // isLaunchable 又放行 → 游戏反复以原版启动（0 秒退出），模组全部失效
        LauncherConfig.InstanceEntry entry = createIsolated("Forge 实例");
        entry.mcVersion = "1.20.1";
        entry.loaderType = LoaderType.FORGE.id();
        entry.loaderVersion = "47.4.26";
        installVersionOnDisk(entry, "1.20.1");
        installVersionOnDisk(entry, "1.20.1-forge-47.4.26");
        entry.versionId = "1.20.1";

        assertFalse(manager.isLaunchable(entry), "原版版本不该让 Forge 实例视为可启动");
        assertEquals("1.20.1-forge-47.4.26", manager.recoverVersionFromDisk(entry),
                "应自愈为实例声明的加载器版本");
        assertTrue(manager.isLaunchable(entry));
    }

    @Test
    void coexistingVanillaAndLoaderShouldHealToLoader() throws Exception {
        // 用户照着启动器日志里的命令手动装完了 Forge：盘上有原版 + Forge 两个版本，
        // 而实例因安装中断没有任何元信息 —— 拒绝选择会让启动器一直显示「未安装」。
        // 加载器版本的 id 自带 mc-loader-ver 完整语义，采用它不是猜
        LauncherConfig.InstanceEntry entry = createIsolated("手动装 Forge");
        installVersionOnDisk(entry, "1.20.1");
        installVersionOnDisk(entry, "1.20.1-forge-47.4.26");

        assertEquals("1.20.1-forge-47.4.26", manager.recoverVersionFromDisk(entry));
        assertEquals("1.20.1", entry.mcVersion);
        assertEquals(LoaderType.FORGE.id(), entry.loaderType);
        assertEquals("47.4.26", entry.loaderVersion);
        assertTrue(manager.isLaunchable(entry));
    }

    @Test
    void deleteInstanceShouldRemoveGameDir() throws Exception {
        LauncherConfig.InstanceEntry entry = createIsolated("待删除");
        installVersionOnDisk(entry, "1.20.1");
        Path gameDir = manager.gamePaths(entry).gameDir();
        assertTrue(Files.isDirectory(gameDir));

        manager.delete(entry.id, true);

        assertFalse(Files.exists(gameDir), "应真实删除游戏目录（用户要求：删除实例即删除 .minecraft 下的实例文件）");
        assertTrue(manager.list().stream().noneMatch(e -> e.id.equals(entry.id)), "记录应被移除");
    }

    @Test
    void sharedGameDirShouldNotBeDeleted() throws Exception {
        // 防护：gameDir 是用户可改的，两个实例指到同一目录时，
        // 删除其中一个绝不能连另一个的存档一起端掉
        LauncherConfig.InstanceEntry first = createIsolated("共享 A");
        installVersionOnDisk(first, "1.20.1");
        Path shared = manager.gamePaths(first).gameDir();
        LauncherConfig.InstanceEntry second = manager.create("共享 B", shared.toString(), "");

        manager.delete(first.id, true);

        assertTrue(Files.exists(shared), "共享目录必须保留");
        assertTrue(manager.list().stream().noneMatch(e -> e.id.equals(first.id)), "记录应被移除");
    }

    @Test
    void createInstanceShouldMakeIsolatedDir() {
        LauncherConfig.InstanceEntry entry = manager.create("整合包 A");

        assertNotNull(entry.id);
        assertTrue(entry.gameDir.contains("整合包") || entry.gameDir.contains("instance"),
                "未指定目录时应自动派生: " + entry.gameDir);
        assertTrue(Files.isDirectory(Path.of(entry.gameDir)), "游戏目录应被创建");
        // 标准子目录应已就绪
        assertTrue(Files.isDirectory(new GamePaths(Path.of(entry.gameDir)).modsDir()));
        assertEquals(1, manager.list().size(), "全新安装不预置实例，新建后应只有 1 个");
    }

    @Test
    void defaultGameDirShouldSitBesideLauncher() {
        LauncherConfig.InstanceEntry entry = manager.create("整合包 A");

        // HMCL 式布局：默认是启动器同级目录下的 .minecraft，便于直接找到存档与模组
        Path expectedBase = store.paths().defaultGameDir().toAbsolutePath().normalize();
        Path actual = Path.of(entry.gameDir).toAbsolutePath().normalize();
        assertTrue(actual.startsWith(expectedBase),
                "默认游戏目录应在 " + expectedBase + " 之下，实际: " + actual);
    }

    @Test
    void sameVersionInstallsShouldHaveIsolatedDirs() {
        LauncherConfig.InstanceEntry first = manager.create("Minecraft-1.20.1-Forge-47.0.1");
        LauncherConfig.InstanceEntry second = manager.create("Minecraft-1.20.1-Forge-47.0.1");

        assertNotEquals(Path.of(first.gameDir).toAbsolutePath().normalize(),
                Path.of(second.gameDir).toAbsolutePath().normalize(),
                "同名实例共用目录会让 mods/ 与 saves/ 串味");
    }

    @Test
    void explicitDirShouldNotCollideWithOthers() {
        Path shared = tempDir.resolve("shared-game");
        LauncherConfig.InstanceEntry first = manager.create("实例一", shared.toString(), "1.20.1");

        assertEquals(shared.toAbsolutePath().normalize().toString(), first.gameDir);
        assertTrue(manager.gameDirInUse(shared, null));
        assertFalse(manager.gameDirInUse(shared, first.id), "除自身外无占用");
        assertFalse(manager.gameDirInUse(tempDir.resolve("other"), null));
    }

    @Test
    void switchAndDeleteShouldMaintainCurrentInstance() {
        LauncherConfig.InstanceEntry created = manager.create("待删除");
        LauncherConfig.InstanceEntry kept = manager.create("保留");

        manager.switchTo(created.id);
        assertEquals(created.id, store.get().currentInstanceId);

        assertTrue(manager.delete(created.id, false));
        assertEquals(kept.id, manager.current().id, "删除当前实例后应回落到剩余实例");
        assertFalse(store.get().currentInstanceId.equals(created.id), "当前实例不应再指向已删除对象");
    }

    @Test
    void deleteInstanceKeepingGameDir() throws Exception {
        LauncherConfig.InstanceEntry entry = manager.create("保留目录");
        Path gameDir = Path.of(entry.gameDir);
        Files.writeString(gameDir.resolve("marker.txt"), "keep me");

        manager.delete(entry.id, false);

        assertTrue(Files.exists(gameDir.resolve("marker.txt")), "未选择删除时游戏目录必须保留");
    }

    @Test
    void deleteInstanceWithGameDirCleanup() throws Exception {
        LauncherConfig.InstanceEntry entry = manager.create("清理目录");
        Path gameDir = Path.of(entry.gameDir);
        Files.writeString(gameDir.resolve("marker.txt"), "delete me");

        manager.delete(entry.id, true);

        assertFalse(Files.exists(gameDir), "选择删除时游戏目录应被移除");
    }

    @Test
    void updateVersionAndLoaderAndRecordLaunch() {
        LauncherConfig.InstanceEntry entry = manager.create("带加载器");

        manager.updateVersion(entry.id, "1.20.1-forge-47.2.0", LoaderType.FORGE, "47.2.0");
        manager.recordLaunch(entry.id, "acc-1", 3600);

        LauncherConfig.InstanceEntry reloaded = manager.byId(entry.id);
        assertEquals("1.20.1-forge-47.2.0", reloaded.versionId);
        assertEquals("forge", reloaded.loaderType);
        assertEquals("47.2.0", reloaded.loaderVersion);
        assertEquals(3600, reloaded.playTimeSeconds);
        assertEquals("acc-1", reloaded.lastAccountId);
        assertTrue(reloaded.lastLaunchedAt > 0);
    }

    @Test
    void instanceWithoutVersionShouldNotLaunch() {
        LauncherConfig.InstanceEntry entry = manager.create("空实例");
        assertFalse(manager.isLaunchable(entry), "没有版本文件时不应判定为可启动");

        entry.versionId = "1.20.1";
        manager.save(entry);
        assertFalse(manager.isLaunchable(entry), "仅有 id 但文件缺失仍不可启动");
    }

    @Test
    void groupsShouldDedupeAndIgnoreBlank() {
        LauncherConfig.InstanceEntry a = manager.create("A");
        a.group = "生存";
        LauncherConfig.InstanceEntry b = manager.create("B");
        b.group = "生存";
        LauncherConfig.InstanceEntry c = manager.create("C");
        c.group = "  ";
        manager.save(a);
        manager.save(b);
        manager.save(c);

        List<String> groups = manager.groups();
        assertEquals(1, groups.size());
        assertEquals("生存", groups.get(0));
    }

    @Test
    void deleteInstanceShouldCleanModIndex() {
        LauncherConfig.InstanceEntry entry = manager.create("带模组标记");
        store.get().mods.installed.put(entry.id + "\u0000mods/x.jar", new LauncherConfig.InstalledModEntry());
        store.get().mods.installed.put("other-id\u0000mods/y.jar", new LauncherConfig.InstalledModEntry());

        manager.delete(entry.id, false);

        assertEquals(1, store.get().mods.installed.size(), "仅应清理目标实例的记录");
    }
}
