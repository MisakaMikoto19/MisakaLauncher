package com.misaka.launcher.core.auth;

import com.misaka.launcher.core.config.ConfigStore;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.launch.AccountInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 账户管理：账户的增删切换、令牌有效期判定与登录信息转换。
 * <p>令牌续期采用「惰性刷新」策略：启动游戏前按需刷新，而不是后台定时刷新 ——
 * 既减少无谓的网络请求，也避免在用户长时间未使用时被微软判定为异常活动。</p>
 */
public final class AccountManager {

    private static final Logger log = LoggerFactory.getLogger(AccountManager.class);

    /** 令牌提前刷新的余量，避免刚刷新完就过期。 */
    private static final long EXPIRY_MARGIN_MILLIS = 5L * 60L * 1000L;

    private final ConfigStore configStore;
    private final AuthService authService;

    public AccountManager(ConfigStore configStore, AuthService authService) {
        this.configStore = configStore;
        this.authService = authService;
    }

    public List<LauncherConfig.AccountEntry> list() {
        if (configStore.get().accounts == null) {
            configStore.get().accounts = new ArrayList<>();
        }
        return configStore.get().accounts;
    }

    public LauncherConfig.AccountEntry current() {
        return configStore.currentAccount();
    }

    public LauncherConfig.AccountEntry byId(String id) {
        if (id == null) {
            return null;
        }
        return list().stream().filter(entry -> id.equals(entry.id)).findFirst().orElse(null);
    }

    /** 添加账户；同类同身份已存在时更新而非重复添加。 */
    public LauncherConfig.AccountEntry add(LauncherConfig.AccountEntry entry) {
        LauncherConfig.AccountEntry existing = findSame(entry);
        if (existing != null) {
            // 保留原 id，避免实例中的「上次使用账户」引用失效
            entry.id = existing.id;
            int index = list().indexOf(existing);
            list().set(index, entry);
        } else {
            list().add(entry);
        }
        configStore.get().currentAccountId = entry.id;
        configStore.save();
        return entry;
    }

    /**
     * 就地更新账户（按 id 匹配），<b>不改变当前账户</b>。
     *
     * <p>不能用 {@link #add} 代替：它会顺带把该账户设为当前账户，
     * 而「修改某个非当前账户的皮肤模型」显然不该触发账户切换 ——
     * 那会让用户莫名其妙地换了登录身份。</p>
     *
     * @throws IllegalArgumentException 账户不存在
     */
    public LauncherConfig.AccountEntry update(LauncherConfig.AccountEntry entry) {
        if (entry == null || entry.id == null || entry.id.isBlank()) {
            throw new IllegalArgumentException("账户 id 不能为空");
        }
        List<LauncherConfig.AccountEntry> list = list();
        for (int i = 0; i < list.size(); i++) {
            if (entry.id.equals(list.get(i).id)) {
                list.set(i, entry);
                configStore.save();
                log.info("已更新账户: {}", entry.username);
                return entry;
            }
        }
        throw new IllegalArgumentException("账户不存在: " + entry.id);
    }

    private LauncherConfig.AccountEntry findSame(LauncherConfig.AccountEntry entry) {
        for (LauncherConfig.AccountEntry existing : list()) {
            if (!existing.type.equals(entry.type)) {
                continue;
            }
            // 微软账户以 UUID 判同：同一个账号换了玩家名重登，应当更新而不是新增一条
            if (entry.uuid != null && !entry.uuid.isBlank()
                    && normalize(entry.uuid).equals(normalize(existing.uuid))) {
                return existing;
            }
        }
        return null;
    }

    private static String normalize(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "").toLowerCase(Locale.ROOT);
    }

    /** 移除账户。 */
    public boolean remove(String id) {
        LauncherConfig.AccountEntry entry = byId(id);
        if (entry == null) {
            return false;
        }
        list().remove(entry);
        if (id.equals(configStore.get().currentAccountId)) {
            configStore.get().currentAccountId = list().isEmpty() ? "" : list().get(0).id;
        }
        configStore.save();
        log.info("已移除账户: {}", entry.username);
        return true;
    }

    /** 切换当前账户。 */
    public void switchTo(String id) {
        if (byId(id) == null) {
            throw new IllegalArgumentException("账户不存在: " + id);
        }
        configStore.get().currentAccountId = id;
        configStore.save();
    }

    /**
     * 转换为启动所需信息。
     *
     * <p><b>没有账户就没有兜底</b>：以前这里会造一个离线身份 {@code Player} 顶上，
     * 结果是「没登录也能进游戏」。现在缺账户直接抛错，由调用方提示用户先登录。</p>
     *
     * @throws IllegalArgumentException 未提供账户
     */
    public AccountInfo toLaunchInfo(LauncherConfig.AccountEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException(I18n.t("account.microsoft.required"));
        }
        if (entry.accessToken == null || entry.accessToken.isBlank()) {
            throw new IllegalArgumentException(I18n.t("account.microsoft.required"));
        }
        return new AccountInfo(entry.username, entry.uuid, entry.accessToken,
                AccountInfo.TYPE_MSA,
                entry.xuid == null ? "" : entry.xuid,
                entry.clientId == null ? "" : entry.clientId);
    }

    /** 令牌是否已过期（含提前刷新余量）。 */
    public boolean isExpired(LauncherConfig.AccountEntry entry) {
        if (entry == null) {
            return true;
        }
        if (entry.expiresAt <= 0) {
            // 未记录过期时间时不做主动刷新，交给服务端在真正用到时判 401
            return false;
        }
        return System.currentTimeMillis() + EXPIRY_MARGIN_MILLIS >= entry.expiresAt;
    }

    /**
     * 确保账户令牌可用；过期时自动续期并写回配置。
     *
     * @return 可用的账户条目
     * @throws IOException 续期失败（调用方应提示用户重新登录）
     */
    public LauncherConfig.AccountEntry ensureValid(LauncherConfig.AccountEntry entry) throws IOException {
        if (entry == null) {
            throw new AuthException(I18n.t("account.microsoft.required"));
        }
        if (!LauncherConfig.AccountEntry.TYPE_MICROSOFT.equals(entry.type)) {
            // 迁移已经在加载配置时清掉了旧条目；走到这里说明配置文件被手工改过
            throw new AuthException(I18n.t("account.microsoft.required"));
        }
        if (!isExpired(entry)) {
            return entry;
        }
        log.info("账户 {} 的令牌已过期，正在续期", entry.username);
        return add(authService.refreshMicrosoft(entry));
    }

    /** 供界面展示的账户类型文案键。 */
    public static String typeI18nKey(String type) {
        return "account.type.microsoft";
    }
}
