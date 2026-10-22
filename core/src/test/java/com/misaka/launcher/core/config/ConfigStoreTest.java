package com.misaka.launcher.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置存储行为验证：默认值补齐、往返一致性、损坏文件自愈。
 */
class ConfigStoreTest {

    @TempDir
    Path tempDir;

    private PortablePaths paths() {
        return PortablePaths.resolve(false, tempDir.toString());
    }

    @Test
    void autoSyncShouldRequireBothSwitches() {
        // 两者的默认值恰好构成「总开关关、自动执行开」——只看其中一个就会
        // 声称会自动同步而实际每次跳过（用户报障：勾了启动前同步却没效果）
        LauncherConfig.SyncSettings sync = new LauncherConfig.SyncSettings();

        assertFalse(sync.enabled, "总开关默认关闭（同步服务需用户自建）");
        assertTrue(sync.autoSyncBeforeLaunch, "自动执行默认打开");
        assertFalse(sync.autoSyncActive(), "默认状态下不应声称会自动同步");

        sync.enabled = true;
        assertTrue(sync.autoSyncActive(), "总开关打开且自动执行打开时才生效");

        sync.autoSyncBeforeLaunch = false;
        assertFalse(sync.autoSyncActive(), "关掉自动执行后只应手动同步");

        sync.enabled = false;
        sync.autoSyncBeforeLaunch = true;
        assertFalse(sync.autoSyncActive(), "总开关关闭时必须否决自动执行");
    }

    @Test
    void firstRunShouldNotPresetInstancesOrAccounts() {
        ConfigStore store = ConfigStore.open(paths());

        assertTrue(store.get().instances.isEmpty(), "全新安装不应预置任何实例");
        assertTrue(store.get().accounts.isEmpty(), "全新安装不应预置任何账户");
        assertEquals("", store.get().currentInstanceId);
        assertEquals("", store.get().currentAccountId);
        assertNull(store.currentInstance());
        assertNull(store.currentAccount());
        assertEquals(LauncherConfig.CURRENT_VERSION, store.get().configVersion);
    }

    @Test
    void saveAndReloadShouldRoundTrip() {
        PortablePaths paths = paths();
        ConfigStore store = ConfigStore.open(paths);
        store.get().theme = "light";
        store.get().download.concurrency = 12;
        store.get().sync.serverAddress = "example.org";
        store.save();

        ConfigStore reloaded = ConfigStore.open(paths);
        assertEquals("light", reloaded.get().theme);
        assertEquals(12, reloaded.get().download.concurrency);
        assertEquals("example.org", reloaded.get().sync.serverAddress);
    }

    @Test
    void missingFieldsShouldFallBackToDefaults() throws Exception {
        PortablePaths paths = paths();
        Files.createDirectories(paths.configDir());
        // 仅提供极少数键，其余字段必须由字段初始化值补齐
        Files.writeString(paths.configFile(), "{\"theme\":\"light\"}");

        ConfigStore store = ConfigStore.open(paths);
        assertEquals("light", store.get().theme, "已提供的键应被采用");
        // 语言默认留空 = 跟随系统：写死成 zh_CN 会让非中文用户一开屏就是中文
        assertEquals("", store.get().language, "缺失语言时应保持空串（启动时再按系统猜）");
        assertEquals(8, store.get().download.concurrency, "缺失的嵌套字段应回落默认值");
    }

    @Test
    void corruptConfigShouldBeBackedUpAndRebuilt() throws Exception {
        PortablePaths paths = paths();
        Files.createDirectories(paths.configDir());
        Files.writeString(paths.configFile(), "{ this is not json ");

        ConfigStore store = ConfigStore.open(paths);
        assertEquals(LauncherConfig.CURRENT_VERSION, store.get().configVersion);
        assertTrue(Files.exists(paths.configFile().getParent()), "配置目录应存在");

        try (var stream = Files.list(paths.configDir())) {
            boolean hasBackup = stream.anyMatch(p -> p.getFileName().toString().contains(".corrupt-"));
            assertTrue(hasBackup, "损坏文件应被备份");
        }
    }

    @Test
    void legacyDefaultJavaVendorShouldMigrateToDragonwell() throws Exception {
        PortablePaths paths = paths();
        Files.createDirectories(paths.configDir());
        // 模拟 v1 配置：显式写着旧默认值 adoptium
        Files.writeString(paths.configFile(),
                "{\"configVersion\":1,\"java\":{\"preferredVendor\":\"adoptium\"}}");

        ConfigStore store = ConfigStore.open(paths);

        assertEquals(LauncherConfig.JavaSettings.VENDOR_DRAGONWELL, store.get().java.preferredVendor,
                "旧默认值 adoptium 应迁移为新的首选 Dragonwell");
        assertEquals(LauncherConfig.CURRENT_VERSION, store.get().configVersion, "迁移后版本号应更新");

        // 迁移结果必须落盘，否则下次启动又会退回
        ConfigStore reloaded = ConfigStore.open(paths);
        assertEquals(LauncherConfig.JavaSettings.VENDOR_DRAGONWELL, reloaded.get().java.preferredVendor);
    }

    @Test
    void explicitThirdPartyVendorShouldNotBeMigrated() throws Exception {
        PortablePaths paths = paths();
        Files.createDirectories(paths.configDir());
        Files.writeString(paths.configFile(),
                "{\"configVersion\":1,\"java\":{\"preferredVendor\":\"zulu\"}}");

        ConfigStore store = ConfigStore.open(paths);

        assertEquals(LauncherConfig.JavaSettings.VENDOR_ZULU, store.get().java.preferredVendor,
                "非旧默认值的显式选择必须保留");
    }

    @Test
    void freshConfigShouldEnablePortableMode() {
        ConfigStore store = ConfigStore.open(paths());
        assertTrue(store.get().portable, "全新安装应默认便携模式（数据跟着程序走）");
    }

    @Test
    void existingNonPortableConfigShouldNotBeOverridden() throws Exception {
        // 关键的不变量：默认值只在「字段缺失」时生效。
        // 老用户配置里显式写着 portable=false，若被新默认值覆盖，
        // 数据目录就被静默搬走 —— 表现为实例与账户凭空消失。
        PortablePaths paths = paths();
        Files.createDirectories(paths.configDir());
        Files.writeString(paths.configFile(), "{\"configVersion\":"
                + LauncherConfig.CURRENT_VERSION + ",\"portable\":false}");

        ConfigStore store = ConfigStore.open(paths);
        assertFalse(store.get().portable, "显式写入的非便携配置必须保留");
    }

    @Test
    void freshConfigShouldDefaultToDragonwell() {
        ConfigStore store = ConfigStore.open(paths());
        assertEquals(LauncherConfig.JavaSettings.VENDOR_DRAGONWELL, store.get().java.preferredVendor);
    }

    @Test
    void adoptingParsedConfigShouldFillDefaultsAndMigrate() {
        // 复现启动引导的第二趟：配置对象由外部解析好后交给 ConfigStore 接管
        LauncherConfig parsed = new LauncherConfig();
        parsed.configVersion = 1;
        parsed.java.preferredVendor = LauncherConfig.JavaSettings.VENDOR_ADOPTIUM;
        parsed.instances = new java.util.ArrayList<>();
        parsed.accounts = new java.util.ArrayList<>();

        ConfigStore store = ConfigStore.adopt(paths(), parsed);

        assertEquals(LauncherConfig.JavaSettings.VENDOR_DRAGONWELL, store.get().java.preferredVendor,
                "接管路径同样必须执行结构迁移，否则迁移逻辑等于死代码");
        assertEquals(LauncherConfig.CURRENT_VERSION, store.get().configVersion);
        assertEquals(0, store.get().instances.size(), "接管路径同样不应再补齐默认实例");
        assertEquals(0, store.get().accounts.size(), "接管路径同样不应再补齐默认账户");
    }

    @Test
    void freshConfigShouldDefaultWindowSizeTo1600x900() {
        ConfigStore store = ConfigStore.open(paths());
        assertEquals(1600, store.get().launch.windowWidth);
        assertEquals(900, store.get().launch.windowHeight);
    }

    @Test
    void legacyWindowSizeShouldMigrateTo1600x900() throws Exception {
        // 旧配置里显式写着写死的 854×480，只改默认值对老用户不生效
        PortablePaths paths = paths();
        Files.createDirectories(paths.configDir());
        Files.writeString(paths.configFile(),
                "{\"configVersion\":2,\"launch\":{\"windowWidth\":854,\"windowHeight\":480}}");

        ConfigStore store = ConfigStore.open(paths);

        assertEquals(1600, store.get().launch.windowWidth, "旧默认宽度 854 应迁移为 1600");
        assertEquals(900, store.get().launch.windowHeight, "旧默认高度 480 应迁移为 900");
        assertEquals(LauncherConfig.CURRENT_VERSION, store.get().configVersion, "迁移后版本号应更新");

        // 迁移结果必须落盘
        assertEquals(1600, ConfigStore.open(paths).get().launch.windowWidth);
    }

    @Test
    void customWindowSizeShouldNotBeMigrated() throws Exception {
        PortablePaths paths = paths();
        Files.createDirectories(paths.configDir());
        Files.writeString(paths.configFile(),
                "{\"configVersion\":2,\"launch\":{\"windowWidth\":1920,\"windowHeight\":1080}}");

        ConfigStore store = ConfigStore.open(paths);

        assertEquals(1920, store.get().launch.windowWidth, "只应迁移恰好等于旧默认值的配置");
        assertEquals(1080, store.get().launch.windowHeight);
    }

    @Test
    void unsupportedAccountsShouldBeDroppedOnLoad() throws Exception {
        // v3 配置里可能同时存在离线账户与皮肤站账户：加载后只应留下微软账户
        PortablePaths paths = paths();
        paths.prepare();
        Files.writeString(paths.configFile(), "{\"configVersion\":3,\"accounts\":["
                + "{\"id\":\"ms\",\"type\":\"microsoft\",\"username\":\"Premium\",\"uuid\":\"u\",\"accessToken\":\"t\"},"
                + "{\"id\":\"off\",\"type\":\"offline\",\"username\":\"Player\"},"
                + "{\"id\":\"ygg\",\"type\":\"yggdrasil\",\"username\":\"Site\",\"serverUrl\":\"https://littleskin.cn\"}]}",
                java.nio.charset.StandardCharsets.UTF_8);

        ConfigStore store = ConfigStore.open(paths);

        assertEquals(1, store.get().accounts.size());
        assertEquals("ms", store.get().accounts.get(0).id);
        assertEquals(LauncherConfig.CURRENT_VERSION, store.get().configVersion);
    }
}
