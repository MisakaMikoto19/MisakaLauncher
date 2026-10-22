package com.misaka.launcher.core.config;

import com.misaka.launcher.core.util.OsUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 统一路径解析。
 * <p>两套目录概念：</p>
 * <ul>
 *   <li><b>安装根目录</b>（{@code root}）：启动器本体所在目录，游戏用 Java 运行时安装在其下的 {@code java/}，
 *       满足「安装到启动器同级目录」的要求；</li>
 *   <li><b>数据根目录</b>（{@code dataRoot}）：配置、实例、日志、缓存所在目录。便携模式等于安装根目录，
 *       否则落到用户数据目录（安装根目录不可写时也会自动回落到这里）。</li>
 * </ul>
 */
public final class PortablePaths {

    private static final String ROOT_PROPERTY = "misaka.launcher.root";

    /**
     * 单文件外壳 → 应用的环境变量：用户放置外壳可执行文件的目录。
     * <p>外壳自身解压到 {@code %LOCALAPPDATA%} 下运行，若没有这条信息，
     * 「启动器同级目录」就会被错误地解析成解压缓存目录 —— 那里每次版本更新
     * 都会被整体删除，配置、下载缓存、游戏与 Java 运行时会凭空消失。</p>
     */
    public static final String ORIGIN_ENV = "MISAKA_ORIGIN_DIR";

    /**
     * 安装根目录标记文件。
     * <p>打包产物（jpackage app-image）的布局为 {@code <root>/lib/app/*.jar}，
     * 仅凭 jar 所在目录无法推回安装根目录，因此在打包时于根目录写入本标记文件，
     * 由 {@link #detectRoot()} 向上查找定位。</p>
     */
    public static final String ROOT_MARKER = ".misaka-root";

    /** 向上查找标记文件的最大层数，避免异常布局下无限上溯。 */
    private static final int MAX_ROOT_SEARCH_DEPTH = 6;

    private final Path root;
    private final Path dataRoot;
    private final boolean portable;
    private final boolean rootWritable;

    private PortablePaths(Path root, Path dataRoot, boolean portable, boolean rootWritable) {
        this.root = root;
        this.dataRoot = dataRoot;
        this.portable = portable;
        this.rootWritable = rootWritable;
    }

    /**
     * 按配置解析路径。
     *
     * @param portable    是否便携模式（数据与 java 全部落在安装根目录）
     * @param dataOverride 自定义数据目录；为空时按 portable 规则推导
     */
    public static PortablePaths resolve(boolean portable, String dataOverride) {
        return resolve(detectRoot(), portable, dataOverride);
    }

    /**
     * 使用显式安装根目录解析路径。
     * <p>用途：单测中把「安装根」固定到临时目录。否则自动探测会把根指向构建输出目录，
     * 导致多个用例共享同一份 {@code java/} 而互相污染。</p>
     *
     * @param root         安装根目录
     * @param portable     是否便携模式
     * @param dataOverride 自定义数据目录；为空时按 portable 规则推导
     */
    public static PortablePaths resolve(Path root, boolean portable, String dataOverride) {
        Path installRoot = root.toAbsolutePath().normalize();
        boolean writable = OsUtils.isWritable(installRoot);

        Path dataRoot;
        boolean effectivePortable = portable;
        if (dataOverride != null && !dataOverride.isBlank()) {
            dataRoot = Path.of(dataOverride).toAbsolutePath().normalize();
        } else if (portable) {
            dataRoot = installRoot;
        } else {
            dataRoot = defaultUserDataDir();
        }

        // 便携模式但安装目录不可写（例如装在 Program Files）时自动回落，避免启动即失败
        if (effectivePortable && !writable) {
            effectivePortable = false;
            dataRoot = defaultUserDataDir();
        }

        return new PortablePaths(installRoot, dataRoot, effectivePortable, writable);
    }

    /**
     * 探测安装根目录。
     * <p>优先级：</p>
     * <ol>
     *   <li>{@code -Dmisaka.launcher.root} 系统属性（开发运行与自定义部署可由外部注入）；</li>
     *   <li>从代码源位置向上查找 {@link #ROOT_MARKER} 标记文件（jpackage 产物走此路径）；</li>
     *   <li>jar 所在目录；</li>
     *   <li>当前工作目录。</li>
     * </ol>
     */
    /**
     * 探测安装根目录。
     * <p>优先级：</p>
     * <ol>
     *   <li>{@code -Dmisaka.launcher.root} 系统属性（开发运行与自定义部署可由外部注入）；</li>
     *   <li><b>单文件外壳通过环境变量传入的「用户放置外壳的目录」</b>
     *       （见 {@link #ORIGIN_ENV}）；</li>
     *   <li>从代码源位置向上查找 {@link #ROOT_MARKER} 标记文件（jpackage 产物走此路径）；</li>
     *   <li>jar 所在目录；</li>
     *   <li>当前工作目录。</li>
     * </ol>
     */
    public static Path detectRoot() {
        String property = System.getProperty(ROOT_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Path.of(property).toAbsolutePath().normalize();
        }
        // 单文件外壳会把「用户放置外壳的目录」传进来：配置、下载缓存、游戏与 Java
        // 运行时都应落在这里 —— 而不是跟着解压目录进 %LOCALAPPDATA%
        // （那里每次版本更新都会被整体删除，用户数据会凭空消失）。
        Path origin = detectOriginDir();
        if (origin != null && Files.isDirectory(origin)) {
            return origin;
        }
        Path codeSourceDir = locateCodeSourceDir();
        if (codeSourceDir != null) {
            Path marked = searchMarkerUpwards(codeSourceDir);
            if (marked != null) {
                return marked;
            }
            // 未找到标记（例如直接 java -jar 运行构建产物）时，jar 所在目录即视为安装根目录
            return codeSourceDir;
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    /** 代码源所在目录；无法定位时返回 null。 */
    private static Path locateCodeSourceDir() {
        try {
            Path self = Path.of(PortablePaths.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(self) ? self : self.getParent();
            return dir == null ? null : dir.toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    /** 自起始目录向上查找标记文件，找到则返回标记文件所在目录。 */
    private static Path searchMarkerUpwards(Path start) {
        Path cursor = start;
        for (int depth = 0; depth < MAX_ROOT_SEARCH_DEPTH && cursor != null; depth++) {
            if (Files.exists(cursor.resolve(ROOT_MARKER))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    /**
     * 用户放置单文件外壳的目录（由外壳通过 {@link #ORIGIN_ENV} 传入）。
     *
     * @return 目录；非单文件外壳运行（免安装版 / jpackage / 开发）时为 null
     */
    public static Path detectOriginDir() {
        String env = System.getenv(ORIGIN_ENV);
        if (env == null || env.isBlank()) {
            return null;
        }
        return Path.of(env).toAbsolutePath().normalize();
    }

    /** 产品标识：与打包脚本里的产物名 / 安装目录名一致。 */
    private static final String APP_ID = "MisakaLauncher";

    /** 改名前的产品标识；只用于「老数据目录还在就继续用它」。 */
    private static final String LEGACY_APP_ID = "MisakaMinecraftLauncher";

    /**
     * 各平台默认的用户级数据目录。
     *
     * <p><b>旧目录优先</b>：非便携模式（或安装目录不可写而自动回落）时，配置、实例与
     * Java 运行时都落在这里。产品改名之前在用的安装，数据仍在旧名目录下 ——
     * 这里若直接切到新名，升级后用户会看到「配置与实例全没了」，而数据其实还躺在磁盘上。</p>
     *
     * <p>判据是旧目录里<b>确实有配置</b>（{@code config/launcher.json}），而不是「目录存在」：
     * 单文件外壳的解压缓存也曾经放在同名目录下，只看目录会把数据根指进那个每次更新都会被
     * 整体删除的缓存里 —— 那正是数据凭空消失的经典事故。</p>
     */
    public static Path defaultUserDataDir() {
        Path legacy = userDataDir(LEGACY_APP_ID);
        if (Files.isRegularFile(legacy.resolve("config").resolve("launcher.json"))) {
            return legacy;
        }
        return userDataDir(APP_ID);
    }

    /** 按产品标识拼出各平台的用户级数据目录。 */
    private static Path userDataDir(String appId) {
        String home = System.getProperty("user.home", ".");
        if (OsUtils.isWindows()) {
            String appData = System.getenv("APPDATA");
            Path base = appData == null ? Path.of(home, "AppData", "Roaming") : Path.of(appData);
            return base.resolve(appId);
        }
        if (OsUtils.isMacOS()) {
            return Path.of(home, "Library", "Application Support", appId);
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, appId.toLowerCase(Locale.ROOT));
        }
        return Path.of(home, ".local", "share", appId.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------
    // 安装根目录相关
    // ------------------------------------------------------------------

    /** 启动器安装根目录。 */
    public Path root() {
        return root;
    }

    public boolean isPortable() {
        return portable;
    }

    public boolean isRootWritable() {
        return rootWritable;
    }

    /**
     * 游戏用 Java 运行时安装根目录。
     * <p>便携/可写时位于安装根目录下，否则落到数据目录，避免权限问题。</p>
     */
    public Path javaRoot() {
        return rootWritable ? root.resolve("java") : dataRoot.resolve("java");
    }

    // ------------------------------------------------------------------
    // 数据目录相关
    // ------------------------------------------------------------------

    public Path dataRoot() {
        return dataRoot;
    }

    public Path configDir() {
        return dataRoot.resolve("config");
    }

    public Path configFile() {
        return configDir().resolve("launcher.json");
    }

    public Path instancesDir() {
        return dataRoot.resolve("instances");
    }

    public Path defaultGameDir() {
        // 游戏下载/安装的默认位置 = 启动器同级目录下的 .minecraft（HMCL 式布局），
        // 便于用户直接找到存档与模组。安装目录不可写（例如装在 Program Files）时
        // 退回数据目录，避免默认就指向一个写不进去的路径。
        Path base = rootWritable ? root : dataRoot;
        return base.resolve(".minecraft");
    }

    public Path logsDir() {
        return dataRoot.resolve("logs");
    }

    public Path gameLogsDir() {
        return logsDir().resolve("game");
    }

    public Path cacheDir() {
        return dataRoot.resolve("cache");
    }

    public Path iconsCacheDir() {
        return cacheDir().resolve("icons");
    }

    /** 头像本地缓存：裁好的头像要跨次启动复用，不能每次都去查一遍。 */
    public Path avatarsDir() {
        return cacheDir().resolve("avatars");
    }

    public Path skinsCacheDir() {
        return cacheDir().resolve("skins");
    }

    public Path backgroundsDir() {
        return dataRoot.resolve("backgrounds");
    }

    public Path downloadsDir() {
        return dataRoot.resolve("downloads");
    }

    public Path tempDir() {
        return dataRoot.resolve("temp");
    }

    /** 共享的元数据缓存目录（版本清单、版本 JSON、加载器列表）。 */
    public Path metaCacheDir() {
        return cacheDir().resolve("meta");
    }

    /** 创建所有必需目录（幂等）。 */
    public void prepare() {
        for (Path dir : new Path[]{configDir(), instancesDir(), logsDir(), gameLogsDir(),
                cacheDir(), metaCacheDir(), backgroundsDir(), downloadsDir(), tempDir()}) {
            try {
                Files.createDirectories(dir);
            } catch (Exception ignored) {
                // 单个目录创建失败不应阻断启动，后续按需再报错
            }
        }
    }

    /** 面向用户展示的数据目录摘要，用于「关于」页面与日志。 */
    public String describe() {
        return "root=" + root + ", data=" + dataRoot + ", java=" + javaRoot()
                + ", portable=" + portable + ", writable=" + rootWritable;
    }
}
