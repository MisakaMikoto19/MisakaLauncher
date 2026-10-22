package com.misaka.launcher.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.misaka.launcher.core.util.FileUtils;
import com.misaka.launcher.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置读写。
 * <p>特点：</p>
 * <ul>
 *   <li>原子写（临时文件 + ATOMIC_MOVE），避免异常退出损坏配置；</li>
 *   <li>读取时对缺失键自动回落默认值，损坏文件自动备份并重建；</li>
 *   <li>内置结构版本迁移入口；</li>
 *   <li>内存中持有唯一配置实例，UI 与 core 共享同一份状态。</li>
 * </ul>
 */
public final class ConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

    /** v3 之前的游戏窗口默认尺寸（写死的旧值，仅用于迁移识别）。 */
    private static final int LEGACY_WINDOW_WIDTH = 854;
    private static final int LEGACY_WINDOW_HEIGHT = 480;

    private final PortablePaths paths;
    private LauncherConfig config;

    private ConfigStore(PortablePaths paths, LauncherConfig config) {
        this.paths = paths;
        this.config = config;
    }

    /** 打开（或首次创建）配置。 */
    public static ConfigStore open(PortablePaths paths) {
        return adopt(paths, loadFile(paths.configFile()));
    }

    /**
     * 接管一份已解析的配置：补齐结构性默认值并执行结构迁移。
     *
     * <p>为什么需要这个入口：启动引导要分两趟读配置（先探测便携标记与数据目录，
     * 再按最终路径解析），第二趟拿到的是「已经解析好的对象」，若直接裸构造，
     * 默认值补齐与版本迁移都会被静默跳过 —— 曾导致配置版本迁移逻辑写了却从不执行。</p>
     */
    public static ConfigStore adopt(PortablePaths paths, LauncherConfig config) {
        ConfigStore store = new ConfigStore(paths, config);
        store.ensureDefaults();
        store.migrate();
        return store;
    }

    /** 仅供测试与「放弃修改」场景使用（不补默认值、不迁移）。 */
    public static ConfigStore of(PortablePaths paths, LauncherConfig config) {
        return new ConfigStore(paths, config);
    }

    public PortablePaths paths() {
        return paths;
    }

    public LauncherConfig get() {
        return config;
    }

    /** 用新配置替换当前配置（例如切换便携模式后重载）。 */
    public void replace(LauncherConfig replacement) {
        this.config = replacement;
    }

    private static LauncherConfig loadFile(Path file) {
        if (!Files.isRegularFile(file)) {
            log.info("未找到配置文件，使用默认配置: {}", file);
            return new LauncherConfig();
        }
        try {
            JsonNode node = JsonUtils.read(file);
            LauncherConfig parsed = JsonUtils.convert(node, LauncherConfig.class);
            if (parsed == null) {
                throw new IOException("配置内容为空");
            }
            log.info("已加载配置: {}", file);
            return parsed;
        } catch (IOException e) {
            Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis() + ".bak");
            log.error("配置文件解析失败，已备份到 {}: {}", backup, e.getMessage());
            try {
                Files.move(file, backup);
            } catch (IOException moveFailure) {
                log.warn("备份损坏配置失败: {}", moveFailure.getMessage());
            }
            return new LauncherConfig();
        }
    }

    /** 保存到磁盘。 */
    public void save() {
        try {
            FileUtils.ensureDir(paths.configDir());
            byte[] bytes = JsonUtils.writePrettyBytes(config);
            FileUtils.atomicWrite(paths.configFile(), bytes);
        } catch (IOException e) {
            log.error("保存配置失败: {}", e.getMessage(), e);
        }
    }

    /** 保存到指定路径（用于导出配置）。 */
    public void exportTo(Path target) throws IOException {
        FileUtils.atomicWrite(target, JsonUtils.writePrettyBytes(config));
    }

    /** 从指定路径导入配置并替换内存实例。 */
    public void importFrom(Path source) throws IOException {
        this.config = JsonUtils.read(source, LauncherConfig.class);
        ensureDefaults();
        save();
    }

    // ------------------------------------------------------------------
    // 默认值与迁移
    // ------------------------------------------------------------------

    /**
     * 补齐结构性默认值（只保证集合非空，不再预置任何实例 / 账户）。
     *
     * <p><b>不创建默认实例或账户</b>：全新安装应以空状态启动，实例在「安装游戏」点击安装时
     * 按命名约定（{@code Minecraft-<版本>-<加载器>-<加载器版本>}）自动生成，账户由用户在账户页自行添加。
     * 各界面均已处理空态，不会再因「没有默认项」而阻塞。</p>
     *
     * <p>当前选中项失效（被删除或配置损坏）时：非空则回落到第一个、为空则置空，
     * 避免「指向不存在的 id」让界面在空态与错误态之间跳变。</p>
     */
    private void ensureDefaults() {
        if (config.instances == null) {
            config.instances = new java.util.ArrayList<>();
        }
        if (config.accounts == null) {
            config.accounts = new java.util.ArrayList<>();
        }

        if (config.instances.isEmpty()) {
            config.currentInstanceId = "";
        } else if (config.instances.stream().noneMatch(i -> i.id.equals(config.currentInstanceId))) {
            config.currentInstanceId = config.instances.get(0).id;
        }

        if (config.accounts.isEmpty()) {
            config.currentAccountId = "";
        } else if (config.accounts.stream().noneMatch(a -> a.id.equals(config.currentAccountId))) {
            config.currentAccountId = config.accounts.get(0).id;
        }
    }

    /** 结构版本迁移。 */
    private void migrate() {
        if (config.configVersion < LauncherConfig.CURRENT_VERSION) {
            log.info("配置结构从 v{} 迁移到 v{}", config.configVersion, LauncherConfig.CURRENT_VERSION);
            if (config.configVersion < 2) {
                migrateToV2();
            }
            if (config.configVersion < 3) {
                migrateToV3();
            }
            if (config.configVersion < 4) {
                migrateToV4();
            }
            config.configVersion = LauncherConfig.CURRENT_VERSION;
            save();
        } else if (config.configVersion > LauncherConfig.CURRENT_VERSION) {
            log.warn("配置由更高版本（v{}）写入，可能存在未知字段被忽略", config.configVersion);
        }
    }

    /**
     * v1 → v2：Java 首选发行版由 Adoptium 改为 Alibaba Dragonwell。
     *
     * <p>为什么必须迁移而不是只改默认值：默认值只在「字段缺失」时生效，
     * 而所有已存在的配置里都显式写着旧默认值 {@code adoptium}，
     * 不迁移的话「首选 Dragonwell」这项改动对老用户完全不生效。</p>
     *
     * <p>代价是无法区分「用户主动选了 Adoptium」与「沿用旧默认值」——两者都迁到 Dragonwell；
     * 用户随时可在设置页切回任意发行版，因此这个取舍可接受。</p>
     */
    private void migrateToV2() {
        if (config.java == null) {
            return;
        }
        if (LauncherConfig.JavaSettings.VENDOR_ADOPTIUM.equals(config.java.preferredVendor)) {
            config.java.preferredVendor = LauncherConfig.JavaSettings.VENDOR_DRAGONWELL;
            log.info("Java 首选发行版已迁移为 {}（原值 adoptium 是旧默认值）",
                    config.java.preferredVendor);
        }
    }

    /**
     * v2 → v3：游戏窗口默认尺寸由 854×480 改为 1600×900。
     *
     * <p>与 v2 同理：默认值只在字段缺失时生效，而老配置里显式写着
     * {@code windowWidth: 854, windowHeight: 480}，不迁移的话这项改动对老用户不生效。
     * 这两个值此前在界面上<b>无处可改</b>（写死在配置里），所以任何等于旧默认值的配置
     * 都只可能来自旧默认值本身，改写是安全的。</p>
     *
     * <p>只在整个尺寸<b>恰好等于旧默认值</b>时改写：万一将来有人手工编辑过配置文件，
     * 也不会被这次迁移覆盖掉。</p>
     */
    private void migrateToV3() {
        if (config.launch == null) {
            return;
        }
        if (config.launch.windowWidth == LEGACY_WINDOW_WIDTH
                && config.launch.windowHeight == LEGACY_WINDOW_HEIGHT) {
            config.launch.windowWidth = 1600;
            config.launch.windowHeight = 900;
            log.info("游戏窗口默认尺寸已由 {}×{} 迁移为 1600×900",
                    LEGACY_WINDOW_WIDTH, LEGACY_WINDOW_HEIGHT);
        }
    }

    /**
     * v3 → v4：移除离线账户与第三方皮肤站（Yggdrasil）账户。
     *
     * <p>这两类账户不再被支持：它们都不经过微软 OAuth，也不校验是否拥有 Java 版，
     * 留着只会在界面上留下一个「点了也启动不了」的入口。这里在加载配置时清掉，
     * 而不是留给界面各自判空 —— 后者意味着每个显示账户的地方都要再写一遍「跳过它」。</p>
     *
     * <p>只清理账户条目本身：实例、存档、Java 运行时与更新设置一概不动。</p>
     */
    private void migrateToV4() {
        if (config.accounts == null) {
            return;
        }
        int before = config.accounts.size();
        config.accounts.removeIf(entry -> entry == null
                || !LauncherConfig.AccountEntry.TYPE_MICROSOFT.equals(entry.type));
        int removed = before - config.accounts.size();
        if (removed == 0) {
            return;
        }
        // 当前账户可能刚被清掉：回落到剩下的第一个，避免界面指向一个不存在的 id
        boolean currentStillExists = config.accounts.stream()
                .anyMatch(entry -> entry.id.equals(config.currentAccountId));
        if (!currentStillExists) {
            config.currentAccountId = config.accounts.isEmpty() ? "" : config.accounts.get(0).id;
        }
        log.info("已移除 {} 个不受支持的账户（离线 / 第三方认证），当前可用账户 {} 个",
                removed, config.accounts.size());
    }

    // ------------------------------------------------------------------
    // 便捷访问
    // ------------------------------------------------------------------

    /** 当前实例；若不存在则返回第一个实例。 */
    public LauncherConfig.InstanceEntry currentInstance() {
        for (LauncherConfig.InstanceEntry entry : config.instances) {
            if (entry.id.equals(config.currentInstanceId)) {
                return entry;
            }
        }
        return config.instances.isEmpty() ? null : config.instances.get(0);
    }

    /** 当前账户；若不存在则返回第一个账户。 */
    public LauncherConfig.AccountEntry currentAccount() {
        for (LauncherConfig.AccountEntry entry : config.accounts) {
            if (entry.id.equals(config.currentAccountId)) {
                return entry;
            }
        }
        return config.accounts.isEmpty() ? null : config.accounts.get(0);
    }

}
