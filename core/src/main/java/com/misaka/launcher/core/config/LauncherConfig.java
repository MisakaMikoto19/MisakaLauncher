package com.misaka.launcher.core.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动器全局配置模型。
 * <p>设计取舍：</p>
 * <ul>
 *   <li>字段全部使用具体类型并就地初始化，使 JSON 中缺失的键自动回落到默认值（向前兼容）；</li>
 *   <li>可扩展的枚举语义（主题、背景模式、账户类型、下载源等）以字符串承载并给出常量，
 *       避免旧版本读取新配置时因未知枚举值而反序列化失败。</li>
 * </ul>
 */
public class LauncherConfig {

    /**
     * 配置结构版本，用于必要的迁移。
     * <p>变更记录：</p>
     * <ul>
     *   <li>v1：首个版本；</li>
     *   <li>v2：Java 首选发行版默认值由 Adoptium 改为 Alibaba Dragonwell。</li>
     *   <li>v3：游戏窗口默认尺寸由写死的 854×480 改为可配置的 1600×900。</li>
     *   <li>v4：移除离线账户与第三方皮肤站账户，只保留微软正版账户。</li>
     * </ul>
     */
    public static final int CURRENT_VERSION = 4;

    public int configVersion = CURRENT_VERSION;

    /**
     * 界面语言：{@code zh_CN} / {@code en_US} / {@code fr_FR}，或数据目录 {@code i18n/} 里
     * 自定义语言文件的标签。
     *
     * <p>默认留空 = 「跟随系统」：启动时按系统语言挑一个能匹配的，都没有就用英语。
     * 写死成 {@code zh_CN} 会让非中文用户一开屏就是中文，且找不到切换入口。</p>
     */
    public String language = "";

    /**
     * 便携模式：全部数据落在启动器安装目录内。
     *
     * <p><b>默认开启</b>：本启动器以「绿色免安装」为首要形态 —— 数据跟着程序走，
     * 备份即复制目录、卸载即删除目录，也不会在用户目录留下残留。
     * 安装根目录不可写时（例如装到 Program Files）{@link PortablePaths} 会自动
     * 回落到用户数据目录，不会因此启动失败。</p>
     *
     * <p>仅在<b>首次运行</b>（还没有配置文件）时生效；已有配置一律以配置内的取值为准，
     * 绝不擅自搬迁既有用户的数据目录。</p>
     */
    public boolean portable = true;

    /** 自定义数据目录；为空则按 {@link #portable} 规则推导。 */
    public String dataDirectory = "";

    /** 主题：{@code dark} / {@code light} / {@code custom}。 */
    public String theme = "light";

    /**
     * 自定义主题的强调色（仅 {@code theme = custom} 时生效）。
     * <p>实现方式是覆盖 CSS 的 looked-up color，因此无需重新加载样式表或重建界面。</p>
     */
    public String themeAccent = "#7C5CFF";

    public UiSettings ui = new UiSettings();
    public BackgroundSettings background = new BackgroundSettings();
    public DownloadSettings download = new DownloadSettings();
    public JavaSettings java = new JavaSettings();
    public LaunchSettings launch = new LaunchSettings();
    public LogSettings log = new LogSettings();
    public ModsSettings mods = new ModsSettings();
    public SyncSettings sync = new SyncSettings();
    public AuthSettings auth = new AuthSettings();
    public UpdateSettings update = new UpdateSettings();

    /** 实例（多版本多目录）列表。 */
    public List<InstanceEntry> instances = new ArrayList<>();

    /** 当前选中的实例 id。 */
    public String currentInstanceId = "";

    /** 账户列表；访问令牌等敏感字段不写入日志。 */
    public List<AccountEntry> accounts = new ArrayList<>();

    /** 当前选中的账户 id。 */
    public String currentAccountId = "";

    /** 首启动引导是否已完成。 */
    public boolean firstRunCompleted = false;

    // ==================================================================
    // 子配置
    // ==================================================================

    public static class UiSettings {
        /** 默认窗口尺寸，同时也是「重置窗口尺寸」的目标值（保持单一来源）。 */
        public static final double DEFAULT_WIDTH = 1280;
        public static final double DEFAULT_HEIGHT = 820;

        public double windowWidth = DEFAULT_WIDTH;
        public double windowHeight = DEFAULT_HEIGHT;
        public boolean maximized = false;
        public boolean animationsEnabled = true;
        public boolean reduceMotion = false;
        public String lastPage = "home";
        /** 内容区最大宽度，宽屏下居中显示。 */
        public double contentMaxWidth = 1280;
        /** 列表渲染的批处理大小，用于长列表虚拟化。 */
        public int listBatchSize = 40;
    }

    public static class BackgroundSettings {

        public static final String MODE_SOLID = "solid";
        public static final String MODE_GRADIENT = "gradient";
        public static final String MODE_IMAGE = "image";
        public static final String MODE_FOLDER = "folder";

        /**
         * 默认背景模式：内置了一张默认壁纸（首次启动时释放到 backgrounds/ 并指向它）。
         * <p>用户改成纯色/渐变/轮播或换图后，这里就不再起作用。</p>
         */
        public String mode = MODE_IMAGE;
        /**
         * 单图背景路径。默认指向内置壁纸的**相对路径**（相对 backgrounds 目录解析），
         * 这样同一份配置在移动整个启动器目录后仍然有效。
         */
        public String imagePath = "中秋主题壁纸19201080.png";
        /** 轮播文件夹路径。 */
        public String folderPath = "";
        /**
         * 模糊半径（px），0–40。
         * <p>默认值刻意偏小：图片上还会叠「压暗」与不透明度上限，
         * 三者叠在一起时重模糊会把图糊成一片纯色 —— 首次选图应当看得出用了图，
         * 想要氛围感再由用户自己往上调。</p>
         */
        public double blur = 6;
        /** 变暗/变亮叠加强度（%），0–80。默认轻微压暗，保证顶部标题与底部状态栏可读。 */
        public double dim = 15;
        /** 背景整体不透明度（%），5–100。 */
        public double opacity = 100;
        /** 轮播间隔（秒），0 表示不轮播。 */
        public int rotateIntervalSeconds = 0;
        public boolean shuffle = true;
        public boolean showOverlayGradient = true;
        /**
         * 渐变压暗层的不透明度（%），0–100；100 = 内置默认强度。
         *
         * <p>压暗层叠在背景图上、用于保证标题栏与状态栏文字可读。
         * 它的强度是写死的（顶部 35%、底部 40%），图片偏亮时会显得「蒙了一层灰」，
         * 用户需要能把它调淡来凸现背景图（2026-10-08 用户要求）。</p>
         */
        public int overlayOpacity = 13;
        /** 纯色/渐变兜底色。 */
        public String baseColor = "#0B0E14";
        public String gradientStart = "#1B1140";
        public String gradientEnd = "#06131F";
    }

    public static class DownloadSettings {

        public static final String SOURCE_AUTO = "auto";
        public static final String SOURCE_OFFICIAL = "official";
        public static final String SOURCE_BMCLAPI = "bmclapi";
        public static final String SOURCE_CUSTOM = "custom";

        /**
         * 版本 / 资源下载源。
         *
         * <p>{@code auto}（默认）= <b>官方源优先</b>，官方地址不可达时降级到镜像；
         * {@code official} = 只用官方、不降级；{@code bmclapi} = 镜像优先、官方兜底；
         * {@code custom} = 自建镜像优先。镜像一律是「用户选了才用」，不再是默认路径。</p>
         */
        public String source = SOURCE_AUTO;
        /** 自定义源前缀，例如 {@code https://my.mirror.example}。 */
        public String customSourceUrl = "";
        /** 并发下载数。 */
        public int concurrency = 8;
        /** 单请求超时（秒）。 */
        public int timeoutSeconds = 30;
        /** 单文件失败重试次数。 */
        public int retries = 3;
        /** 全局速度上限（KB/s），0 表示不限速。 */
        public int speedLimitKbps = 0;
        /** 下载前是否校验已存在文件的哈希。 */
        public boolean verifyExisting = true;
        /** 各源的历史测速缓存（url 前缀 -> 往返毫秒）。 */
        public Map<String, Long> sourceLatency = new LinkedHashMap<>();
    }

    public static class JavaSettings {

        /**
         * 发行版标识。
         * <p>必须与对应 {@code JavaProvider.id()} 保持一致；config 包不反向依赖 java 包，
         * 因此这里以常量形式声明并在此处集中说明映射关系。</p>
         */
        public static final String VENDOR_DRAGONWELL = "dragonwell";
        public static final String VENDOR_ADOPTIUM = "adoptium";
        public static final String VENDOR_ZULU = "zulu";

        /**
         * 优先使用的 Java 发行版。
         * <p>默认 Alibaba Dragonwell：面向国内网络优化，覆盖 Minecraft 会用到的
         * 全部主版本（8 / 11 / 17 / 21）。该发行版不提供 macOS 构建，
         * 在 macOS 上会自动降级到 Adoptium 或 Zulu。</p>
         */
        public String preferredVendor = VENDOR_DRAGONWELL;
        /** 启动前自动安装缺失的 Java 运行时。 */
        public boolean autoInstall = true;
        /** 自动扫描本机已安装的 Java。 */
        public boolean autoDetect = true;
        /** 额外扫描目录。 */
        public List<String> extraScanPaths = new ArrayList<>();
        /** 已安装（或已发现）的运行时清单。 */
        public List<JavaRuntimeEntry> runtimes = new ArrayList<>();
        /** 单个实例允许使用的最大内存比例上限（%），用于自动参数预设。 */
        public int autoMemoryPercent = 50;
    }

    public static class JavaRuntimeEntry {
        /** adoptium / zulu / mojang / manual。 */
        public String vendor = "";
        public int majorVersion = 17;
        public String os = "";
        public String arch = "";
        /** JAVA_HOME 路径。 */
        public String home = "";
        /** 来源：installed（启动器安装）/ discovered（本机扫描）/ manual（用户指定）。 */
        public String source = "installed";
        public long installedAt = 0L;
        public String versionString = "";

        public JavaRuntimeEntry() {
        }

        public JavaRuntimeEntry(String vendor, int majorVersion, String os, String arch, String home, String source) {
            this.vendor = vendor;
            this.majorVersion = majorVersion;
            this.os = os;
            this.arch = arch;
            this.home = home;
            this.source = source;
            this.installedAt = System.currentTimeMillis();
        }
    }

    public static class LaunchSettings {
        /** 自动按机器配置生成 JVM 参数。 */
        public boolean autoJvmArgs = true;
        /** 手动覆盖时的参数（每行一个或空格分隔，由 UI 决定呈现方式）。 */
        public List<String> jvmArgs = new ArrayList<>();
        public List<String> gameArgs = new ArrayList<>();
        /** 启动前校验文件完整性。 */
        public boolean checkIntegrityOnLaunch = true;
        /** 启动前检查模组官方源更新。 */
        public boolean checkModUpdatesOnLaunch = false;
        /**
         * 游戏窗口宽高（全局，默认 1600×900）。
         *
         * <p>由 {@code LaunchCommandBuilder} 经版本 JSON 里的
         * {@code ${resolution_width}} / {@code ${resolution_height}} 占位符带入命令行，
         * 并由 {@code has_custom_resolution} 规则启用 —— 不需要写进「游戏参数」，
         * 写进去反而会和 JSON 自带的那一份重复而使游戏启动失败。</p>
         */
        public int windowWidth = 1600;
        public int windowHeight = 900;
        /** 全屏启动。 */
        public boolean fullscreen = false;
        /** 启动后关闭启动器（部分实例需要，默认保留启动器以便查看日志）。 */
        public boolean closeLauncherOnStart = false;
        /**
         * 启动前把游戏使用的 java 可执行文件登记为「高性能」图形首选项，
         * 让双显卡笔记本用独显（NVIDIA / AMD 通用）渲染。
         * <p>写的是 Windows 公开的 UserGpuPreferences，可随时在系统「图形设置」里改回。</p>
         */
        public boolean preferHighPerformanceGpu = true;

        // ---- 高清贴图压缩（见 textureCompression 的说明）----
        /** 交给显卡判定：不是 NVIDIA 才压。 */
        public static final String COMPRESSION_AUTO = "auto";
        /** 总是压缩，不管什么显卡。 */
        public static final String COMPRESSION_ALWAYS = "always";
        /** 从不压缩。 */
        public static final String COMPRESSION_OFF = "off";

        /**
         * 高清贴图压缩：图集是一张正方形纹理，边长上限就是驱动报告的
         * {@code GL_MAX_TEXTURE_SIZE} —— NVIDIA 通常 32768，AMD / Intel 通常只有 16384，
         * 容量整整差 4 倍。大型整合包的贴图总量超限后，会在贴图拼接阶段直接崩溃
         * （{@code StitcherException: Unable to fit}），且与显存大小、是否独显无关。
         * 开启后启动器把塞不进去的大贴图等比缩小，以覆盖资源包的形式生效
         * —— 不改任何 mod 文件，随时可撤销。
         *
         * <p>取值：{@code auto}（默认，判定为「不是 NVIDIA」才压）/ {@code always} / {@code off}。
         * 刻意做成可选项而不是一律自动：这台机器到底需不需要，用户比启发式更清楚，
         * 而且并非所有平台都有这个问题。</p>
         */
        public String textureCompression = COMPRESSION_AUTO;
        /** 内存预设：留空表示按机器自动计算。 */
        public String memoryPreset = "auto";
        public int minMemoryMb = 0;
        public int maxMemoryMb = 0;
    }

    public static class LogSettings {
        /** 启动器日志保留天数。 */
        public int retainDays = 14;
        /** 日志目录总体积上限（MB）。 */
        public int maxTotalMb = 256;
        /** 单文件大小上限（MB），超出即滚动。 */
        public int maxFileMb = 8;
        /** 保留的历史文件数量上限。 */
        public int maxFiles = 40;
        /** 是否把游戏标准输出落盘。 */
        public boolean logGameOutput = true;
        /** 是否同时输出到控制台。 */
        public boolean consoleOutput = true;
        /** 日志级别：TRACE/DEBUG/INFO/WARN/ERROR。 */
        public String level = "INFO";
        /** 是否在日志中记录完整命令行（含 accessToken 时会被脱敏覆盖）。 */
        public boolean logCommandLine = false;
    }

    public static class ModsSettings {
        /**
         * CurseForge API Key。
         *
         * <p><b>开源版本刻意留空</b>：CurseForge 的 v1 API 必须带 Key，否则一律 403。
         * 但 Key 是「谁在用」的凭据 —— 随公共仓库分发，等于把自己的额度与责任交给所有 fork。
         * 需要 CurseForge 来源的定制版本，请在<b>编译前</b>填自己的 Key
         * （申请地址见 {@code https://console.curseforge.com}，格式形如 {@code $2a$10$…}）。</p>
         *
         * <p><b>刻意不提供配置覆盖</b>：以前允许在 launcher.json 里填，结果是任何人都能把
         * 发行版改成别人的 Key。现在只有编译期一处入口。留空时该来源在界面上显示为
         * 「未配置」，下载中心自动回退到 Modrinth。</p>
         */
        public static final String BUILT_IN_CURSEFORGE_API_KEY = BuildSecrets.CURSEFORGE_API_KEY;
        /** 启用的下载源。 */
        public List<String> enabledSources = new ArrayList<>(List.of("modrinth", "curseforge", "mcmod"));
        /**
         * 下载中心默认选中的来源。
         *
         * <p>默认 CurseForge：它的模组数量最多，且能按「热门度」排序出真实榜单 ——
         * 打开下载中心就该看到"现在大家都在装什么"，而不是一个空列表等用户去搜。</p>
         */
        public String defaultSource = "curseforge";

        /** 下载中心每页条数（默认 20，可选 20 / 50 / 100）。 */
        public int pageSize = 20;

        /** 下载中心默认排序（popularity = 按热门度，见 {@code ModSearchQuery.Sort}）。 */
        public String defaultSort = "popularity";
        /** 安装模组时自动安装必需依赖。 */
        public boolean autoInstallDependencies = true;
        /** 打开下载中心时自动检查已装模组更新。 */
        public boolean autoCheckUpdates = true;
        /** 已装模组的来源标记，key 为实例 id + 相对路径。 */
        public Map<String, InstalledModEntry> installed = new LinkedHashMap<>();
        /** 是否把模组元数据写入 .index 文件（便于迁移与排错）。 */
        public boolean writeIndexFile = true;
    }

    /** 记录已安装模组的来源，用于后续从官方源检查更新。 */
    public static class InstalledModEntry {
        /** 来源：{@code curseforge} / {@code modrinth} / {@code direct}（官方地址直链）。 */
        public String source = "";
        public String projectId = "";
        public String versionId = "";
        public String fileName = "";
        public String sha1 = "";
        public long installedAt = 0L;
        /**
         * 官方直链（仅 {@code source = direct} 时有效）。
         *
         * <p>这类模组不在任何模组库里，没有版本接口可比对；更新时靠 HEAD 这个地址，
         * 用 {@link #size} 与 {@link #lastModified} 判断远端是否换了文件。</p>
         */
        public String url = "";
        /** 安装时的文件字节数（配合 {@link #url} 判断远端是否变化）。 */
        public long size = 0L;
        /** 安装时远端的 {@code Last-Modified}；为空表示服务器没给。 */
        public String lastModified = "";
        /** 依赖它的项目 id 列表，卸载时用于提示。 */
        public List<String> requiredBy = new ArrayList<>();
    }

    public static class SyncSettings {
        /** 模组同步总开关。 */
        public boolean enabled = false;
        /** 是否在启动游戏前自动执行同步（需总开关打开）。 */
        public boolean autoSyncBeforeLaunch = true;
        /** 同步失败是否阻断启动（默认不阻断，保证「能玩」优先）。 */
        public boolean blockLaunchOnFailure = false;

        /**
         * 启动游戏前是否真的会自动同步。
         *
         * <p><b>必须用这个判定，不要各处自己写 {@code enabled && autoSyncBeforeLaunch}</b>：
         * 两者的默认值正好构成「总开关关、自动执行开」的状态 ——
         * 界面只看其中一个就会出现「显示会自动同步、实际每次都跳过」的误导
         * （用户报障原话：勾选了启动前同步模组，为什么没有效果）。</p>
         */
        public boolean autoSyncActive() {
            return enabled && autoSyncBeforeLaunch;
        }

        /**
         * 同步服务器地址。
         * <p>默认指向本机（{@code 127.0.0.1}）：同步服务由用户自建，
         * 默认不应指向任何第三方主机 —— 既避免误连他人服务器，也避免把私人地址写进仓库。</p>
         */
        public String serverAddress = "127.0.0.1";
        /** 同步服务器端口，默认 10086。 */
        public String serverPort = "10086";

        /** 需要同步的客户端目录（形如 {@code ./mods/}）。 */
        public List<String> directories = new ArrayList<>(List.of("./mods/", "./resourcepacks/", "./shaderpacks/", "./tacz/"));

        /** 忽略的文件名。 */
        public List<String> ignoredFiles = new ArrayList<>(List.of("SyncSettings.json"));

        /** 是否执行硬同步删除（关闭后 soft 语义，仅下载不删除）。 */
        public boolean allowDeleteLocal = true;

        /** 并发下载数，与原客户端一致默认 6。 */
        public int concurrency = 6;
        /** 单文件重试次数。 */
        public int retries = 3;
        /** 列表/配置接口超时（秒）。 */
        public int metaTimeoutSeconds = 10;
        /** 下载超时（秒）。 */
        public int downloadTimeoutSeconds = 300;
        /** 完成后是否自动复查一次。 */
        public boolean recheckAfterSync = true;
    }

    /**
     * 启动器自更新（ZooKeeper 更新服务）。
     *
     * <p>检查是<b>后台多线程</b>做的，绝不占用界面线程：启动时先在后台查一次，
     * 有新版本才弹对话框问用户；用户也可以随时在设置页手动检查。</p>
     *
     * <p>服务地址默认指向本机：更新服务由用户自建（同同步服务器），客户端不内置任何第三方地址。</p>
     *
     * <p><b>产品标识与显示名称不在这里</b>：它们是
     * {@code ZooKeeperUpdateClient.PRODUCT} / {@code DISPLAY_NAME}（硬编码在更新代码里）。
     * 这两个值必须与服务端登记完全一致，做成配置项只会让「填错产品键」表现为
     * 「检查更新永远返回产品不存在」而无法自查；也正因如此，本类曾有的 {@code product} 字段已移除
     * （老配置里残留的同名字段由 JsonUtils 的宽松解析忽略）。通道仍可在设置页切换。</p>
     */
    public static class UpdateSettings {
        /** 启动时在后台检查新版本。 */
        public boolean checkOnStartup = true;
        /** ZooKeeper 服务根地址，例如 {@code http://127.0.0.1:8080}。 */
        public String serverUrl = "http://127.0.0.1:8080";
        /** 通道：{@code stable} / {@code beta} / {@code dev} / {@code alpha}。 */
        public String channel = "stable";
        /** 可选 API Key（{@code zk_...}）；为空表示匿名访问。 */
        public String apiKey = "";
        /**
         * 下载账号口令，与 {@code ZooKeeperUpdateClient.DOWNLOAD_USER} 配套。
         *
         * <p><b>可选</b>：启动器总是带着自称用户名下载，留空只是归属在管理台显示为
         * 「未验证」，照样能统计到账号；配了口令才走 HTTP Basic 变成已验证归属。
         * API Key 只记 api_key_id，账号列仍是 {@code (anonymous)}。</p>
         *
         * <p>明文保存在配置里，与内置口令同一安全等级：这是「下载归属」用的低权限账号，
         * 不是能改服务端数据的管理员凭据。</p>
         */
        public String downloadPassword = "";
        /**
         * 用户选择「跳过此版本」后记录的版本号。
         * <p>只跳过这一个版本：服务端再发新版时照常提示，否则「跳过」会变成永久静音。</p>
         */
        public String skippedVersion = "";
        /** 检查更新的超时（秒）。 */
        public int timeoutSeconds = 10;
        /** 下载更新包的并行线程数。 */
        public int downloadThreads = 4;
    }

    public static class AuthSettings {
        /**
         * 微软 OAuth 应用的 Client ID（public client）。
         *
         * <p><b>开源版本刻意留空</b>：它是产品的身份，不是用户的参数。既不该让玩家先去 Azure
         * 注册应用，也不该把某一个人的应用 ID 写进公共仓库 —— 那样所有 fork 都会共用同一个
         * 应用登记，被 Mojang 判定滥用时一起失效。需要正版登录的定制版本，请在<b>编译前</b>
         * 把这行改成自己在 Azure 注册、并已通过 Mojang 白名单审核的 Client ID。</p>
         *
         * <p><b>刻意不提供配置覆盖</b>：以前允许在 launcher.json 里填，结果是任何人拿到发行版
         * 都能把它换成别的应用（复用他人登记、混淆归属）。现在只有编译期一处入口。</p>
         *
         * <p>注册口径（必须逐项一致，否则会在某一跳拿到 AADSTS 错误码）：
         * 受支持的账户类型「仅个人 Microsoft 账户」；重定向地址 {@code http://localhost}
         * （平台选「公共客户端/本机(移动和桌面)」）；「身份验证 → 允许公共客户端流」= 是；
         * 随后按 {@code https://aka.ms/AppRegInfo} 提交 Mojang 审核。</p>
         */
        public static final String BUILT_IN_MICROSOFT_CLIENT_ID = BuildSecrets.MICROSOFT_CLIENT_ID;
        /** 头像尺寸（px）。 */
        public int avatarSize = 128;
    }

    // ==================================================================
    // 实例与账户
    // ==================================================================

    public static class InstanceEntry {
        public String id = "";
        public String name = "";
        /** 游戏目录（隔离目录）。 */
        public String gameDir = "";
        /** 版本 id，例如 {@code 1.20.1} 或 {@code 1.20.1-forge-47.2.0}。 */
        public String versionId = "";
        /**
         * 该实例的 Minecraft 版本（例如 {@code 1.20.1}）。
         * <p>与 {@link #versionId} 分开存：加载器安装后的 versionId 形如
         * {@code 1.20.1-forge-47.2.0} 或 {@code fabric-loader-0.15.11-1.20.1}，
         * 无法稳定反推出 Minecraft 版本，而界面展示与 Java 判定都需要它。</p>
         */
        public String mcVersion = "";
        /** 加载器类型：vanilla / forge / neoforge / fabric / quilt / optifine。 */
        public String loaderType = "vanilla";
        public String loaderVersion = "";
        /**
         * 附加加载器版本（与主加载器共存，如 OptiFine）。
         * 空串表示未选择附加加载器。
         */
        public String optifineVersion = "";
        public String iconPath = "";
        public String group = "";
        /** 实例级 Java 覆盖（JAVA_HOME 或 java 可执行文件路径）。 */
        public String javaPath = "";
        /**
         * 实例级 Java 主版本；{@code 0} 表示未指定 —— 按 Minecraft 版本自动判定。
         * <p>与 {@link #javaPath} 的关系：两者都未配置时才走自动判定；
         * 指定了主版本则按版本挑已安装的 JDK（缺失时自动安装）。</p>
         */
        public int javaMajorVersion = 0;
        /** 实例级内存覆盖（MB），0 表示跟随全局。 */
        public int maxMemoryMb = 0;
        public int minMemoryMb = 0;
        /** 实例级 JVM 参数覆盖；为空表示跟随全局。 */
        public List<String> jvmArgsOverride = new ArrayList<>();
        /** 实例级游戏参数附加。 */
        public List<String> gameArgs = new ArrayList<>();
        public boolean syncEnabled = false;
        public long lastLaunchedAt = 0L;
        public long playTimeSeconds = 0L;
        public String lastAccountId = "";
        public String notes = "";
    }

    public static class AccountEntry {
        /** 唯一的账户类型：微软正版。离线与第三方认证账户已移除（配置迁移 v4 会清掉旧条目）。 */
        public static final String TYPE_MICROSOFT = "microsoft";

        public String id = "";
        public String type = TYPE_MICROSOFT;
        public String username = "";
        public String uuid = "";
        /** 游戏可识别的用户类型：恒为 {@code msa}。 */
        public String userType = "msa";
        /** 微软账户的 XUID，仅正版账户有值。 */
        public String xuid = "";

        /** 客户端标识（部分官方接口会回显）。 */
        public String clientId = "";
        /** 访问令牌；不写入日志。 */
        public String accessToken = "";
        /** 刷新令牌；不写入日志。 */
        public String refreshToken = "";
        /** 皮肤模型：default（Steve）/ slim（Alex）。 */
        public String skinModel = "default";
        /** 缓存的本地头像文件路径。 */
        public String avatarPath = "";
        /** 令牌过期时间（epoch 毫秒），0 表示未知。 */
        public long expiresAt = 0L;
        public long lastLoginAt = 0L;

        /** 无连字符形式的 UUID（供启动命令行使用）。 */
        public String uuidWithoutDashes() {
            return uuid == null ? "" : uuid.replace("-", "");
        }

        /** 带连字符的标准 UUID 形式（界面展示与部分接口需要）。 */
        public String uuidWithDashes() {
            String raw = uuidWithoutDashes();
            if (raw.length() != 32) {
                return raw;
            }
            return raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12, 16)
                    + "-" + raw.substring(16, 20) + "-" + raw.substring(20);
        }
    }
}
