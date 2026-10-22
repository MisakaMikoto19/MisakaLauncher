package com.misaka.launcher.app;

import com.misaka.launcher.core.config.ConfigStore;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.config.PortablePaths;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.log.LogCleaner;
import com.misaka.launcher.core.log.LogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动器运行时上下文：全局唯一的配置、路径与后台线程持有者。
 * <p>启动顺序（顺序不可调换）：</p>
 * <ol>
 *   <li>解析路径（两趟解析以支持配置内指定的便携模式与自定义数据目录）；</li>
 *   <li>初始化日志（必须早于其余组件，保证后续日志落盘）；</li>
 *   <li>加载配置、应用语言；</li>
 *   <li>执行一次日志清理并注册周期任务。</li>
 * </ol>
 */
public final class LauncherContext {

    private static final Logger log = LoggerFactory.getLogger(LauncherContext.class);

    /** 便携模式标记文件：置于安装根目录，存在即强制便携模式。 */
    private static final String PORTABLE_MARKER = "misaka.portable";

    private static volatile LauncherContext instance;

    private final PortablePaths paths;
    private final ConfigStore configStore;
    private final ScheduledExecutorService backgroundExecutor;
    private final AtomicBoolean shutDown = new AtomicBoolean(false);

    private LauncherContext(PortablePaths paths, ConfigStore configStore) {
        this.paths = paths;
        this.configStore = configStore;
        this.backgroundExecutor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "misaka-background");
            thread.setDaemon(true);
            return thread;
        });
    }

    // ------------------------------------------------------------------
    // 启动 / 关闭
    // ------------------------------------------------------------------

    /** 完成引导并返回全局上下文；重复调用返回同一实例。 */
    public static LauncherContext boot() {
        LauncherContext existing = instance;
        if (existing != null) {
            return existing;
        }
        synchronized (LauncherContext.class) {
            if (instance != null) {
                return instance;
            }
            Path root = PortablePaths.detectRoot();
            boolean markerPresent = Files.exists(root.resolve(PORTABLE_MARKER));

            // 第一趟：仅用于定位配置文件（此时还不知道配置内的 portable / dataDirectory）
            PortablePaths probePaths = PortablePaths.resolve(markerPresent, null);
            LauncherConfig probe = readQuietly(probePaths.configFile());

            // 第二趟：按配置真实意图解析。
            // probe == null 表示「全新安装，还没有任何配置文件」：此时采用便携模式默认值
            // （见 LauncherConfig.portable）。已有配置一律以配置内的取值为准 ——
            // 擅自把既有用户的数据目录搬走，等于让他的实例与账户凭空消失。
            boolean portable = markerPresent || probe == null || probe.portable;
            String dataOverride = probe == null ? null : probe.dataDirectory;
            PortablePaths paths = PortablePaths.resolve(portable, dataOverride);
            paths.prepare();

            // 便携模式生效但根目录没有标记文件时补写：让「便携」这件事自描述，
            // 之后每次启动都能直接判定，不必再靠「配置文件恰好落在哪个目录」反推。
            if (paths.isPortable() && !markerPresent) {
                writePortableMarker(root);
            }

            ConfigStore store;
            if (paths.configFile().equals(probePaths.configFile()) && probe != null) {
                // 复用第一趟的解析结果，但仍必须走 adopt：它负责补齐默认值并执行结构迁移。
                // 直接裸构造会跳过迁移，使「配置版本升级」形同虚设。
                store = ConfigStore.adopt(paths, probe);
            } else {
                store = ConfigStore.open(paths);
            }
            LauncherConfig config = store.get();

            LogManager.init(paths, config.log);
            log.info("Misaka Launcher引导完成：{}", paths.describe());
            if (!paths.isRootWritable()) {
                log.warn("启动器安装目录不可写，Java 运行时将安装到数据目录：{}", paths.javaRoot());
            }

            // 外部翻译：<数据目录>/i18n/messages_<tag>.properties，可覆盖内置文案或增加新语言
            I18n.init(paths.dataRoot().resolve("i18n"));
            String language = config.language;
            I18n.setLanguage(language == null || language.isBlank()
                    ? I18n.detectSystemLanguage() : language);
            // 首启动时把「猜出来的语言」写回配置，之后行为稳定不再随系统变
            config.language = I18n.languageTag();

            LauncherContext context = new LauncherContext(paths, store);
            context.startLogMaintenance();
            instance = context;
            Runtime.getRuntime().addShutdownHook(new Thread(context::shutdown, "misaka-shutdown"));
            return context;
        }
    }

    private static LauncherConfig readQuietly(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return com.misaka.launcher.core.util.JsonUtils.read(file, LauncherConfig.class);
        } catch (IOException e) {
            return null;
        }
    }

    public static LauncherContext get() {
        LauncherContext current = instance;
        if (current == null) {
            throw new IllegalStateException("LauncherContext 尚未初始化，请先调用 boot()");
        }
        return current;
    }

    /** 启动时清理一次日志，并注册周期清理。 */
    private void startLogMaintenance() {
        LauncherConfig.LogSettings settings = configStore.get().log;
        try {
            LogCleaner.clean(paths.logsDir(), settings, LogManager.launcherLogFile());
        } catch (RuntimeException e) {
            log.warn("启动日志清理失败: {}", e.getMessage());
        }
        backgroundExecutor.scheduleAtFixedRate(() -> {
            try {
                LogCleaner.clean(paths.logsDir(), configStore.get().log, LogManager.launcherLogFile());
            } catch (RuntimeException e) {
                log.warn("周期日志清理失败: {}", e.getMessage());
            }
        }, 6, 6, TimeUnit.HOURS);
    }

    /** 释放资源。幂等。 */
    public void shutdown() {
        if (!shutDown.compareAndSet(false, true)) {
            return;
        }
        log.info("Misaka Launcher正在关闭");
        try {
            configStore.save();
        } catch (RuntimeException e) {
            log.warn("关闭时保存配置失败: {}", e.getMessage());
        }
        backgroundExecutor.shutdownNow();
        LogManager.shutdown();
    }

    // ------------------------------------------------------------------
    // 访问器
    // ------------------------------------------------------------------

    public PortablePaths paths() {
        return paths;
    }

    public ConfigStore configStore() {
        return configStore;
    }

    public LauncherConfig config() {
        return configStore.get();
    }

    /** 保存配置（UI 修改设置后调用）。 */
    public void saveConfig() {
        configStore.save();
    }

    /** 修改语言并持久化。 */
    public void setLanguage(String tag) {
        I18n.setLanguage(tag);
        configStore.get().language = I18n.languageTag();
        configStore.save();
    }

    /** 后台调度器，供定时刷新等低优先级任务使用。 */
    public ScheduledExecutorService backgroundExecutor() {
        return backgroundExecutor;
    }

    /**
     * 切换便携模式。
     * <p>通过创建/删除安装根目录下的标记文件实现；由于数据目录位置随之改变，
     * 需要重启启动器后生效。</p>
     *
     * @return 是否成功写入标记
     */
    public boolean setPortableMode(boolean portable) {
        Path marker = paths.root().resolve(PORTABLE_MARKER);
        try {
            if (portable) {
                Files.writeString(marker, "portable=true");
            } else {
                Files.deleteIfExists(marker);
            }
            configStore.get().portable = portable;
            configStore.save();
            return true;
        } catch (IOException e) {
            log.error("切换便携模式失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 补写便携模式标记文件。
     * <p>写失败不算致命错误（本次运行已经按便携模式解析完毕），
     * 只是下次启动要靠配置内的 {@code portable} 字段来判定，因此仅告警。</p>
     */
    private static void writePortableMarker(Path root) {
        try {
            Files.writeString(root.resolve(PORTABLE_MARKER), "portable=true");
            log.info("已启用便携模式，数据目录：{}", root);
        } catch (IOException e) {
            log.warn("写入便携模式标记失败（不影响本次运行）: {}", e.getMessage());
        }
    }
}
