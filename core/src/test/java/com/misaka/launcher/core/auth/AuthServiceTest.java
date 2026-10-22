package com.misaka.launcher.core.auth;

import com.misaka.launcher.core.config.ConfigStore;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.config.PortablePaths;
import com.misaka.launcher.core.launch.AccountInfo;
import com.misaka.launcher.core.task.CancelledException;
import com.misaka.launcher.core.task.ProgressListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证与账户管理验证。
 *
 * <p>只覆盖<b>微软正版</b>这一条链路能离线验证的部分：Client ID 的取值口径、
 * 轮询可取消、账户增删切换、令牌过期判定，以及「旧配置里的离线 / 第三方账户会被清掉」。
 * 真正取令牌、查档案要联网，不放在单测里。</p>
 */
@Timeout(value = 60, unit = java.util.concurrent.TimeUnit.SECONDS)
class AuthServiceTest {

    @TempDir
    Path tempDir;

    private PortablePaths paths() {
        PortablePaths paths = PortablePaths.resolve(tempDir, false, tempDir.resolve("data").toString());
        paths.prepare();
        return paths;
    }

    private ConfigStore store() {
        return ConfigStore.open(paths());
    }

    /** 造一条微软账户条目；不走网络，只用于账户管理与命令行的判定。 */
    private static LauncherConfig.AccountEntry microsoftEntry(String name, String uuid, String token) {
        LauncherConfig.AccountEntry entry = new LauncherConfig.AccountEntry();
        entry.id = java.util.UUID.randomUUID().toString();
        entry.type = LauncherConfig.AccountEntry.TYPE_MICROSOFT;
        entry.username = name;
        entry.uuid = uuid;
        entry.accessToken = token;
        entry.xuid = "xuid-" + name;
        entry.lastLoginAt = System.currentTimeMillis();
        return entry;
    }

    // ------------------------------------------------------------------
    // 微软前置校验
    // ------------------------------------------------------------------

    @Test
    void clientIdComesOnlyFromCompileTimeInjection() {
        AuthService auth = new AuthService(store());
        String builtIn = LauncherConfig.AuthSettings.BUILT_IN_MICROSOFT_CLIENT_ID;

        // 断言「只有一处来源」而不是「一定为空」：
        // 开源构建不注入 → 空串（登录不可用）；官方构建由 Secrets 注入 → 有值。
        // 把「必须为空」写死会让带凭据的构建跑不过测试，而那种构建是合法的。
        assertEquals(builtIn, auth.effectiveClientId());
        assertEquals(!builtIn.isBlank(), auth.clientIdConfigured(),
                "clientIdConfigured() 必须与编译期常量一致，界面据此决定是否放行登录");
    }

    @Test
    void clientIdMustNotBeOverriddenByConfig() {
        ConfigStore store = store();
        // 任何外部注入都不该改变生效值：否则拿到发行版的人可以换成别人的应用登记
        AuthService auth = new AuthService(store);

        assertEquals(LauncherConfig.AuthSettings.BUILT_IN_MICROSOFT_CLIENT_ID, auth.effectiveClientId(),
                "Client ID 只有编译期一处来源");
    }

    @Test
    void deviceCodePollingShouldBeCancellable() {
        // 本例要验证的是「轮询能被取消」，必须先有 Client ID 才能走到轮询那一步；
        // 开源构建默认为空，所以这里注入一个假值。
        AuthService auth = new AuthService(store(), "test-client-id");
        DeviceCodeFlow flow = new DeviceCodeFlow("device-code", "USERCODE",
                "https://www.microsoft.com/link", 900, 5);

        // 一开始就已取消：必须在**发出任何请求之前**退出。
        // 这条用例锁的是「点了停止、后台还在每 5 秒轮询到过期」那个真实故障。
        ProgressListener cancelled = new ProgressListener() {
            @Override
            public boolean isCancelled() {
                return true;
            }
        };

        assertThrows(CancelledException.class, () -> auth.completeMicrosoftLogin(flow, cancelled));
    }

    @Test
    void legacyOfflineAndSkinSiteEntriesShouldBePurged() throws Exception {
        // v3 时代的配置：一条微软账户 + 一条离线 + 一条皮肤站
        String legacy = "{\"configVersion\":3,\"currentAccountId\":\"offline-1\",\"accounts\":["
                + "{\"id\":\"ms-1\",\"type\":\"microsoft\",\"username\":\"Premium\","
                + "\"uuid\":\"00000000-0000-0000-0000-000000000001\",\"accessToken\":\"t\"},"
                + "{\"id\":\"offline-1\",\"type\":\"offline\",\"username\":\"Player\",\"accessToken\":\"0\"},"
                + "{\"id\":\"ygg-1\",\"type\":\"yggdrasil\",\"username\":\"SkinSite\","
                + "\"serverUrl\":\"https://littleskin.cn\",\"accessToken\":\"y\"}]}";
        PortablePaths paths = paths();
        Files.writeString(paths.configFile(), legacy, StandardCharsets.UTF_8);

        ConfigStore store = ConfigStore.open(paths);

        assertEquals(1, store.get().accounts.size(), "只应剩下微软账户");
        assertEquals("ms-1", store.get().accounts.get(0).id);
        assertEquals(LauncherConfig.CURRENT_VERSION, store.get().configVersion);
        assertEquals("ms-1", store.get().currentAccountId,
                "当前账户指向被删掉的条目时必须回落到剩下的账户");
    }

    // ------------------------------------------------------------------
    // 账户管理
    // ------------------------------------------------------------------

    @Test
    void accountAddRemoveSwitchAndDedupe() throws Exception {
        ConfigStore store = store();
        AccountManager manager = new AccountManager(store, new AuthService(store));

        int initial = manager.list().size();
        LauncherConfig.AccountEntry first = manager.add(microsoftEntry(
                "MisakaPlayer", "00000000-0000-0000-0000-000000000001", "token-1"));
        assertEquals(initial + 1, manager.list().size());
        assertEquals(first.id, store.get().currentAccountId);

        // 同一 UUID 重登（哪怕玩家名变了）应更新而非重复添加
        manager.add(microsoftEntry("Renamed", "00000000-0000-0000-0000-000000000001", "token-2"));
        assertEquals(initial + 1, manager.list().size(), "同一账号不应重复添加");
        assertEquals("Renamed", manager.byId(first.id).username, "应就地把玩家名更新为新值");
        assertEquals("token-2", manager.byId(first.id).accessToken);

        assertTrue(manager.remove(first.id));
        assertEquals(initial, manager.list().size());
        assertNull(manager.current(), "移除最后一个账户后应无当前账户");
    }

    @Test
    void updatingAccountShouldNotChangeCurrentAccount() throws Exception {
        ConfigStore store = store();
        AccountManager manager = new AccountManager(store, new AuthService(store));

        LauncherConfig.AccountEntry first = manager.add(microsoftEntry(
                "MisakaPlayer", "00000000-0000-0000-0000-000000000001", "token-1"));
        LauncherConfig.AccountEntry second = manager.add(microsoftEntry(
                "SecondPlayer", "00000000-0000-0000-0000-000000000002", "token-2"));
        assertEquals(second.id, store.get().currentAccountId);

        // 改一个「非当前」账户的皮肤模型：不能顺带把登录身份切过去
        first.skinModel = "slim";
        manager.update(first);

        assertEquals("slim", manager.byId(first.id).skinModel);
        assertEquals(second.id, store.get().currentAccountId,
                "就地更新账户绝不应改变当前账户");
    }

    @Test
    void launchInfoShouldAlwaysBeMsaAndRejectMissingAccount() {
        ConfigStore store = store();
        AccountManager manager = new AccountManager(store, new AuthService(store));

        LauncherConfig.AccountEntry entry = microsoftEntry(
                "Premium", "00000000-0000-0000-0000-000000000001", "token");
        AccountInfo info = manager.toLaunchInfo(entry);
        assertEquals(AccountInfo.TYPE_MSA, info.userType(), "游戏端用户类型必须是 msa");
        assertEquals(32, info.uuidWithoutDashes().length());
        assertEquals(36, info.uuidWithDashes().length());
        assertEquals("xuid-Premium", info.xuid());

        // 没有账户就是没有账户：不允许兜底成一个离线身份
        assertThrows(IllegalArgumentException.class, () -> manager.toLaunchInfo(null));
        // 令牌为空同样不该放行（例如配置被手工改坏）
        LauncherConfig.AccountEntry noToken = microsoftEntry(
                "NoToken", "00000000-0000-0000-0000-000000000003", "");
        assertThrows(IllegalArgumentException.class, () -> manager.toLaunchInfo(noToken));
    }

    @Test
    void tokenExpiryShouldIncludeRefreshMargin() {
        ConfigStore store = store();
        AccountManager manager = new AccountManager(store, new AuthService(store));

        LauncherConfig.AccountEntry microsoft = microsoftEntry(
                "Premium", "00000000-0000-0000-0000-000000000001", "token");
        microsoft.expiresAt = System.currentTimeMillis() + 60_000L;
        assertTrue(manager.isExpired(microsoft), "剩余不足 5 分钟应判定为需要刷新");

        microsoft.expiresAt = System.currentTimeMillis() + 3_600_000L;
        assertFalse(manager.isExpired(microsoft));

        // 未记录过期时间时不做主动刷新
        microsoft.expiresAt = 0L;
        assertFalse(manager.isExpired(microsoft));
    }

    @Test
    void missingRefreshTokenShouldAskForRelogin() {
        ConfigStore store = store();
        AuthService auth = new AuthService(store);

        LauncherConfig.AccountEntry microsoft = microsoftEntry(
                "Premium", "00000000-0000-0000-0000-000000000001", "token");
        microsoft.refreshToken = "";

        AuthException error = assertThrows(AuthException.class, () -> auth.refreshMicrosoft(microsoft));
        assertTrue(error.getMessage().contains("重新登录"), error.getMessage());
    }
}
