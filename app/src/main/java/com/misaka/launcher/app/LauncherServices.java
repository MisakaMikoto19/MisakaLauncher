package com.misaka.launcher.app;

import com.misaka.launcher.core.auth.AccountManager;
import com.misaka.launcher.core.auth.AuthService;
import com.misaka.launcher.core.auth.SkinService;
import com.misaka.launcher.core.config.ConfigStore;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.download.DownloadManager;
import com.misaka.launcher.core.download.SourceResolver;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.instance.InstanceManager;
import com.misaka.launcher.core.install.LoaderInstaller;
import com.misaka.launcher.core.install.VanillaInstaller;
import com.misaka.launcher.core.java.JavaRuntimeManager;
import com.misaka.launcher.core.launch.LaunchCommandBuilder;
import com.misaka.launcher.core.launch.LaunchPresetFactory;
import com.misaka.launcher.core.download.SourceGate;
import com.misaka.launcher.core.meta.LoaderMetaService;
import com.misaka.launcher.core.meta.MetaFetcher;
import com.misaka.launcher.core.meta.VersionManifestService;
import com.misaka.launcher.core.mods.CurseForgeProvider;
import com.misaka.launcher.core.mods.McModProvider;
import com.misaka.launcher.core.mods.ModInstaller;
import com.misaka.launcher.core.mods.ModProvider;
import com.misaka.launcher.core.mods.ModpackInstaller;
import com.misaka.launcher.core.mods.ModUpdateService;
import com.misaka.launcher.core.mods.ModrinthProvider;
import com.misaka.launcher.core.sync.SyncEngine;
import com.misaka.launcher.core.task.ProgressListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.misaka.launcher.core.config.PortablePaths;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 服务注册中心。
 * <p>存在的意义：让「构造 core 层对象图」这件事只发生一次。
 * 若每个界面各自 new 一遍 DownloadManager / VersionManifestService，
 * 就会出现连接池重复、缓存不共享、配置对象不一致等难以排查的问题。</p>
 * <p>所有依赖都是无状态或共享状态的单例；需要游戏目录的服务（安装器、同步引擎）
 * 则按实例即时构造，因为它们与具体游戏目录绑定。</p>
 */
public final class LauncherServices {

    private static final Logger log = LoggerFactory.getLogger(LauncherServices.class);

    private final LauncherContext context;

    private final SourceResolver sourceResolver;
    private final SourceGate sourceGate;
    private final DownloadManager downloadManager;
    private final MetaFetcher metaFetcher;
    private final VersionManifestService versionManifestService;
    private final LoaderMetaService loaderMetaService;
    private final JavaRuntimeManager javaRuntimeManager;
    private final InstanceManager instanceManager;
    private final AuthService authService;
    private final AccountManager accountManager;
    private final SkinService skinService;
    /** 头像缓存与广播：侧边栏与账户页共用，避免同一张头像加载两次、更新有先后。 */
    private final AvatarCache avatarCache;
    private final List<ModProvider> modProviders;
    private final ModInstaller modInstaller;
    private final ModUpdateService modUpdateService;
    private final com.misaka.launcher.core.pack.ResourcePackService resourcePackService =
            new com.misaka.launcher.core.pack.ResourcePackService();
    private final LaunchCommandBuilder launchCommandBuilder;
    private final McModProvider mcModProvider;

    private LauncherServices(LauncherContext context) {
        this.context = context;
        LauncherConfig config = context.config();
        ensureBundledWallpaper(context.paths());
        applyBundledWallpaperDefaults(context);

        this.sourceResolver = new SourceResolver(config.download);
        // 下载与元数据抓取共用同一个源健康门：一次发现（预检/熔断）全链路受益
        this.sourceGate = new SourceGate();
        this.downloadManager = new DownloadManager(sourceResolver, config.download, sourceGate);
        this.metaFetcher = new MetaFetcher(sourceResolver, sourceGate);
        this.versionManifestService = new VersionManifestService(context.paths(), metaFetcher);
        this.loaderMetaService = new LoaderMetaService(metaFetcher);
        this.javaRuntimeManager = new JavaRuntimeManager(context.paths(), context.configStore(), downloadManager);
        this.instanceManager = new InstanceManager(context.configStore(), context.paths());

        this.authService = new AuthService(context.configStore());
        this.accountManager = new AccountManager(context.configStore(), authService);
        this.skinService = new SkinService(context.paths(), config.auth);
        this.avatarCache = new AvatarCache(context.paths(), skinService);

        this.mcModProvider = new McModProvider();
        // 顺序即界面顺序：CurseForge 是默认来源，所以排第一
        this.modProviders = List.of(
                // Key 只有编译期常量一处来源（开源版本为空）：留空时该来源自动显示为未配置，
                // 下载中心回退到 Modrinth。刻意不支持从 launcher.json 读取。
                new CurseForgeProvider(() -> com.misaka.launcher.core.config.LauncherConfig.ModsSettings
                        .BUILT_IN_CURSEFORGE_API_KEY),
                new ModrinthProvider(),
                mcModProvider);
        this.modInstaller = new ModInstaller(context.configStore(), downloadManager, modProviders);
        this.modUpdateService = new ModUpdateService(context.configStore(), downloadManager, modProviders);

        // 版本号取自构建期（gradle.properties → BuildInfo），不再写死
        this.launchCommandBuilder = new LaunchCommandBuilder("misaka", AppInfo.version());

        // 启动早期用缓存顺序排一下下载源，避免界面首次请求就等待测速
        sourceResolver.applyCachedOrder();
        log.info("服务已就绪：{}", javaRuntimeManager.describe());
    }

    public static LauncherServices create(LauncherContext context) {
        return new LauncherServices(context);
    }

    /** 打包在应用内的默认壁纸（资源名用 ASCII，落盘时用用户可读的中文名）。 */
    private static final String BUNDLED_WALLPAPER_RESOURCE = "/background/mid-autumn-1920x1080.png";
    private static final String BUNDLED_WALLPAPER_NAME = "中秋主题壁纸19201080.png";

    /**
     * 背景模式是「单图」但路径为空时，补上内置壁纸的相对路径。
     *
     * <p>不补的话配置与界面会对不上：实际显示的是内置壁纸（BackgroundManager 的兜底），
     * 设置页却显示一个空路径 —— 用户看到的就是「默认壁纸似乎没内置」
     * （真实故障：dist 配置里 mode=image、imagePath 为空）。</p>
     */
    private static void applyBundledWallpaperDefaults(LauncherContext context) {
        LauncherConfig.BackgroundSettings background = context.config().background;
        if (LauncherConfig.BackgroundSettings.MODE_IMAGE.equals(background.mode)
                && (background.imagePath == null || background.imagePath.isBlank())) {
            background.imagePath = BUNDLED_WALLPAPER_NAME;
            context.configStore().save();
            log.info("背景路径为空，已补上内置默认壁纸：{}", BUNDLED_WALLPAPER_NAME);
        }
    }

    /**
     * 把打包在应用内的默认壁纸释放到 {@code backgrounds/}（幂等）。
     *
     * <p>默认配置的背景就是这张图（相对路径，{@code BackgroundManager} 会解析到
     * backgrounds 目录）。目标文件已存在时**不覆盖**——用户可能替换过同名文件，
     * 那份内容应当被尊重。</p>
     */
    private static void ensureBundledWallpaper(PortablePaths paths) {
        Path target = paths.backgroundsDir().resolve(BUNDLED_WALLPAPER_NAME);
        if (java.nio.file.Files.isRegularFile(target)) {
            return;
        }
        try (var in = LauncherServices.class.getResourceAsStream(BUNDLED_WALLPAPER_RESOURCE)) {
            if (in == null) {
                log.warn("内置默认壁纸缺失（打包遗漏？）：{}", BUNDLED_WALLPAPER_RESOURCE);
                return;
            }
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.copy(in, target);
            log.info("已释放内置默认壁纸：{}", target);
        } catch (IOException e) {
            log.warn("释放内置默认壁纸失败: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 访问器
    // ------------------------------------------------------------------

    public LauncherContext context() {
        return context;
    }

    public ConfigStore configStore() {
        return context.configStore();
    }

    public LauncherConfig config() {
        return context.config();
    }

    public SourceResolver sourceResolver() {
        return sourceResolver;
    }

    public DownloadManager downloads() {
        return downloadManager;
    }

    public VersionManifestService versions() {
        return versionManifestService;
    }

    public LoaderMetaService loaders() {
        return loaderMetaService;
    }

    public JavaRuntimeManager java() {
        return javaRuntimeManager;
    }

    /**
     * 保证某个主版本的 Java 就绪，并返回它的 {@code bin/java} 可执行文件。
     *
     * <p><b>这是「装游戏 / 装加载器」的前置步骤</b>：Forge 的安装器是另一个 JVM 进程，
     * 客户机上没有对应版本的 Java 时，安装器要么以一句看不懂的报错结束，要么直接静默卡住
     * （真实故障：在没装 Java 11 的机器上装 1.16.5 + Forge，安装器卡死不动，
     * 用户只能杀进程）。因此「缺什么先装什么」必须发生在安装器被拉起之前，
     * 而不是等它失败之后再补救。</p>
     *
     * @param required      目标主版本，由 {@code JavaVersionRule.requiredMajorVersion} 得出
     * @param allowDownload 是否允许自动下载（跟设置里的 {@code java.autoInstall} 一致）
     * @param listener      进度与取消
     * @throws IOException 不允许自动安装、或者安装后仍不可用时
     */
    public Path ensureJava(int required, boolean allowDownload, ProgressListener listener)
            throws IOException {
        Path existing = javaRuntimeManager.javaExecutable(required).orElse(null);
        if (existing != null) {
            return existing;
        }
        if (!allowDownload) {
            throw new IOException(I18n.t("error.javaRequired", required));
        }
        listener.onStage(I18n.t("java.installing", required));
        javaRuntimeManager.install(required, listener);
        Path installed = javaRuntimeManager.javaExecutable(required).orElse(null);
        if (installed == null) {
            throw new IOException("Java " + required + " 安装结束但未找到可执行文件，请到「设置 → Java」检查");
        }
        return installed;
    }

    public InstanceManager instances() {
        return instanceManager;
    }

    public AuthService auth() {
        return authService;
    }

    public AccountManager accounts() {
        return accountManager;
    }

    public SkinService skins() {
        return skinService;
    }

    /** 头像缓存（侧边栏 / 账户页共用同一个实例）。 */
    public AvatarCache avatars() {
        return avatarCache;
    }

    public List<ModProvider> modProviders() {
        return modProviders;
    }

    public ModInstaller modInstaller() {
        return modInstaller;
    }

    public ModUpdateService modUpdates() {
        return modUpdateService;
    }

    /**
     * 整合包安装器：按实例的游戏目录构造。
     *
     * <p>一个实例一个实例化（而不是做成全局单例）：它把文件写进
     * {@code paths} 指向的游戏目录，拿错对象就会把整合包装到别的实例里。</p>
     */
    public ModpackInstaller modpackInstaller(GamePaths paths) {
        return new ModpackInstaller(downloadManager, paths, modProviders);
    }

    /** 下载管理器：供界面直接取「整合包归档」这类一次性文件。 */
    public com.misaka.launcher.core.download.DownloadManager downloadManager() {
        return downloadManager;
    }

    /** 资源包 / 光影包管理（无状态，按实例的游戏目录操作）。 */
    public com.misaka.launcher.core.pack.ResourcePackService resourcePacks() {
        return resourcePackService;
    }

    public McModProvider mcMod() {
        return mcModProvider;
    }

    public LaunchCommandBuilder launchBuilder() {
        return launchCommandBuilder;
    }

    /** 下载源测速（阻塞，应在后台线程调用）。 */
    public void refreshDownloadSources() {
        sourceResolver.refreshOrder();
    }

    // ------------------------------------------------------------------
    // 按游戏目录构造的服务
    // ------------------------------------------------------------------

    /** 原版安装器（与具体游戏目录绑定）。 */
    public VanillaInstaller vanillaInstaller(GamePaths paths) {
        return new VanillaInstaller(versionManifestService, downloadManager, paths);
    }

    /**
     * 加载器安装器。
     * <p>Java 运行时通过函数式接口注入，安装器只负责「拿到一个可执行文件」，
     * 不关心它是本机已有的还是刚下载的。</p>
     */
    public LoaderInstaller loaderInstaller(GamePaths paths) {
        VanillaInstaller vanilla = vanillaInstaller(paths);
        return new LoaderInstaller(versionManifestService, loaderMetaService, downloadManager,
                vanilla, paths, javaRuntimeManager.asInstallerJavaProvider());
    }

    /** 同步引擎（与实例绑定，但构造时不依赖游戏目录）。 */
    public SyncEngine syncEngine() {
        return SyncEngine.of(config().sync);
    }

    /** 机器自适应参数预设。 */
    public LaunchPresetFactory.Preset launchPreset(int overrideMinMb, int overrideMaxMb) {
        return LaunchPresetFactory.create(LaunchPresetFactory.detect(), overrideMinMb, overrideMaxMb);
    }
}
