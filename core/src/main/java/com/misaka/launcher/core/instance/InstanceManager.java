package com.misaka.launcher.core.instance;

import com.misaka.launcher.core.config.ConfigStore;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.config.PortablePaths;
import com.misaka.launcher.core.model.LoaderType;
import com.misaka.launcher.core.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 实例（多版本、多游戏目录）管理。
 * <p>实例是启动器的一等公民：它把「游戏目录 + 版本 + 加载器 + 实例级设置」绑定在一起，
 * 使同一台机器上的不同整合包/不同版本互不干扰。</p>
 */
public final class InstanceManager {

    private static final Logger log = LoggerFactory.getLogger(InstanceManager.class);

    /** 加载器版本 id 的识别模式：{@code <mc>-<loader>-<ver>} 或 {@code fabric-loader-<ver>-<mc>}。 */
    private static final java.util.regex.Pattern LOADER_VERSION_MARKER =
            java.util.regex.Pattern.compile("-(forge|neoforge|fabric|quilt)-|^fabric-loader-");

    private final ConfigStore configStore;
    private final PortablePaths paths;

    public InstanceManager(ConfigStore configStore, PortablePaths paths) {
        this.configStore = configStore;
        this.paths = paths;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public List<LauncherConfig.InstanceEntry> list() {
        return configStore.get().instances;
    }

    public LauncherConfig.InstanceEntry current() {
        return configStore.currentInstance();
    }

    public LauncherConfig.InstanceEntry byId(String id) {
        if (id == null) {
            return null;
        }
        return list().stream().filter(entry -> id.equals(entry.id)).findFirst().orElse(null);
    }

    /** 实例的游戏目录路径解析器，定义文件名以外的所有目录布局。 */
    public GamePaths gamePaths(LauncherConfig.InstanceEntry entry) {
        if (entry == null) {
            return GamePaths.defaultFor(paths);
        }
        String dir = entry.gameDir;
        if (dir == null || dir.isBlank()) {
            return GamePaths.defaultFor(paths);
        }
        return new GamePaths(Path.of(dir));
    }

    public GamePaths gamePaths(String instanceId) {
        return gamePaths(byId(instanceId));
    }

    public GamePaths currentGamePaths() {
        return gamePaths(current());
    }

    /** 已使用的分组名（用于界面上的分组筛选）。 */
    public List<String> groups() {
        Set<String> groups = new LinkedHashSet<>();
        for (LauncherConfig.InstanceEntry entry : list()) {
            if (entry.group != null && !entry.group.isBlank()) {
                groups.add(entry.group);
            }
        }
        return new ArrayList<>(groups);
    }

    /** 判断某个游戏目录是否已被其他实例占用（防止两个实例互相覆盖文件）。 */
    public boolean gameDirInUse(Path gameDir, String exceptInstanceId) {
        if (gameDir == null) {
            return false;
        }
        Path normalized = gameDir.toAbsolutePath().normalize();
        for (LauncherConfig.InstanceEntry entry : list()) {
            if (exceptInstanceId != null && exceptInstanceId.equals(entry.id)) {
                continue;
            }
            if (entry.gameDir == null || entry.gameDir.isBlank()) {
                continue;
            }
            if (Path.of(entry.gameDir).toAbsolutePath().normalize().equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 变更
    // ------------------------------------------------------------------

    /**
     * 创建实例。
     *
     * @param name      显示名
     * @param gameDir   游戏目录；为空时自动在 {@code instances/} 下创建独立目录
     * @param versionId 初始版本 id，可为空（表示尚未安装）
     */
    public LauncherConfig.InstanceEntry create(String name, String gameDir, String versionId) {
        LauncherConfig.InstanceEntry entry = new LauncherConfig.InstanceEntry();
        entry.id = UUID.randomUUID().toString();
        entry.name = (name == null || name.isBlank()) ? "新实例" : name.trim();
        if (gameDir == null || gameDir.isBlank()) {
            // 默认落在启动器同级目录的 .minecraft 之下（HMCL 式布局），
            // 并按实例名占一个子目录：多版本共用同一个游戏目录会让 mods/ 与 saves/ 串味。
            Path named = paths.defaultGameDir().resolve(sanitize(entry.name));
            // 同名目录已被其它实例占用（例如重复安装同一版本）时补一段 id，保证隔离
            Path target = gameDirInUse(named, null)
                    ? named.resolveSibling(sanitize(entry.name) + "-" + shortId(entry.id))
                    : named;
            entry.gameDir = target.toAbsolutePath().normalize().toString();
        } else {
            entry.gameDir = Path.of(gameDir).toAbsolutePath().normalize().toString();
        }
        entry.versionId = versionId == null ? "" : versionId;
        entry.loaderType = LoaderType.VANILLA.id();
        entry.syncEnabled = configStore.get().sync.enabled;

        list().add(entry);
        try {
            new GamePaths(Path.of(entry.gameDir)).prepare();
        } catch (IOException e) {
            log.warn("创建实例目录失败（后续安装时会重试）: {}", e.getMessage());
        }
        configStore.save();
        log.info("已创建实例：{} -> {}", entry.name, entry.gameDir);
        return entry;
    }

    public LauncherConfig.InstanceEntry create(String name) {
        return create(name, null, "");
    }

    /** 切换到指定实例。 */
    public void switchTo(String id) {
        if (byId(id) == null) {
            throw new IllegalArgumentException("实例不存在: " + id);
        }
        configStore.get().currentInstanceId = id;
        configStore.save();
    }

    /**
     * 删除实例。
     *
     * @param deleteGameDir 是否同时删除游戏目录内的文件（默认保留，避免误删存档）
     */
    public boolean delete(String id, boolean deleteGameDir) {
        LauncherConfig.InstanceEntry entry = byId(id);
        if (entry == null) {
            return false;
        }
        list().remove(entry);

        // 清理该实例的模组来源标记，避免配置无限膨胀
        String prefix = id + "\u0000";
        configStore.get().mods.installed.keySet().removeIf(key -> key.startsWith(prefix));

        if (deleteGameDir && entry.gameDir != null && !entry.gameDir.isBlank()) {
            Path gameDir = Path.of(entry.gameDir).toAbsolutePath().normalize();
            // 防护一：目录与其他实例共享时不删文件 —— 否则会连别人的存档一起端掉
            boolean sharedWithOther = configStore.get().instances.stream()
                    .anyMatch(other -> !other.id.equals(id)
                            && other.gameDir != null && !other.gameDir.isBlank()
                            && Path.of(other.gameDir).toAbsolutePath().normalize().equals(gameDir));
            // 防护二：目录必须「长得像」Minecraft 实例（有 versions/saves/options.txt 之一）。
            // gameDir 是用户可改的，万一指到了启动器根目录或无关文件夹，
            // 递归删除就是灾难 —— 拿不准就只移除记录
            boolean looksLikeInstance = Files.isDirectory(gameDir.resolve("versions"))
                    || Files.isDirectory(gameDir.resolve("saves"))
                    || Files.isRegularFile(gameDir.resolve("options.txt"));
            if (sharedWithOther || !looksLikeInstance) {
                log.warn("游戏目录{}，仅移除实例记录、保留文件: {}",
                        sharedWithOther ? "与其他实例共享" : "不像 Minecraft 实例目录", gameDir);
            } else {
                try {
                    FileUtils.deleteRecursively(gameDir);
                    log.info("已删除实例游戏目录: {}", gameDir);
                } catch (IOException e) {
                    log.warn("删除游戏目录失败: {}", e.getMessage());
                }
            }
        }

        if (id.equals(configStore.get().currentInstanceId)) {
            configStore.get().currentInstanceId = list().isEmpty() ? "" : list().get(0).id;
        }
        configStore.save();
        log.info("已删除实例: {}", entry.name);
        return true;
    }

    /** 更新实例的版本与加载器信息。 */
    public void updateVersion(String instanceId, String versionId, LoaderType loaderType, String loaderVersion) {
        LauncherConfig.InstanceEntry entry = byId(instanceId);
        if (entry == null) {
            return;
        }
        entry.versionId = versionId;
        if (loaderType != null) {
            entry.loaderType = loaderType.id();
        }
        entry.loaderVersion = loaderVersion == null ? "" : loaderVersion;
        configStore.save();
    }

    /**
     * 更新实例的版本与加载器信息，并记录附加加载器（OptiFine）。
     * <p>附加加载器可与主加载器共存（如 Forge + OptiFine），因此单独一个字段承载。</p>
     */
    public void updateVersion(String instanceId, String versionId, LoaderType loaderType,
                              String loaderVersion, String optifineVersion) {
        updateVersion(instanceId, versionId, loaderType, loaderVersion);
        LauncherConfig.InstanceEntry entry = byId(instanceId);
        if (entry != null) {
            entry.optifineVersion = optifineVersion == null ? "" : optifineVersion;
            configStore.save();
        }
    }

    /** 记录一次启动：更新上次启动时间与累计游玩时长。 */
    public void recordLaunch(String instanceId, String accountId, long previousPlaySeconds) {
        LauncherConfig.InstanceEntry entry = byId(instanceId);
        if (entry == null) {
            return;
        }
        entry.lastLaunchedAt = System.currentTimeMillis();
        if (accountId != null && !accountId.isBlank()) {
            entry.lastAccountId = accountId;
        }
        if (previousPlaySeconds > 0) {
            entry.playTimeSeconds += previousPlaySeconds;
        }
        configStore.save();
    }

    /** 直接保存实例对象（界面编辑后调用）。 */
    public void save(LauncherConfig.InstanceEntry entry) {
        configStore.save();
    }

    /** 确保游戏目录存在并具备标准子目录；返回是否成功。 */
    public boolean ensurePrepared(LauncherConfig.InstanceEntry entry) {
        try {
            gamePaths(entry).prepare();
            return true;
        } catch (IOException e) {
            log.error("准备游戏目录失败 {}: {}", entry == null ? "?" : entry.gameDir, e.getMessage());
            return false;
        }
    }

    /** 实例是否已具备可启动的版本。 */
    public boolean isLaunchable(LauncherConfig.InstanceEntry entry) {
        if (entry == null || entry.versionId == null || entry.versionId.isBlank()) {
            return false;
        }
        if (!Files.isRegularFile(gamePaths(entry).versionJson(entry.versionId))) {
            return false;
        }
        // 声明了加载器的实例，装的必须是加载器版本本身：
        // 「versionId=1.20.1 + loader=forge」是历史遗留的不一致状态，
        // 若按可启动放行，游戏会以原版启动、模组全部失效（真实故障，且每次启动都复现）
        return versionMatchesDeclaredLoader(entry, entry.versionId);
    }

    /** 版本 id 是否与实例声明的加载器相符（原版实例恒为相符）。 */
    private static boolean versionMatchesDeclaredLoader(LauncherConfig.InstanceEntry entry, String versionId) {
        LoaderType loader = LoaderType.fromId(entry.loaderType);
        if (loader.isVanilla()) {
            return true;
        }
        if (loader == LoaderType.OPTIFINE) {
            return versionId.contains("OptiFine");
        }
        return versionId.contains("-" + loader.id() + "-");
    }

    /**
     * 版本自愈：从游戏目录的 {@code versions/} 反查已安装的版本并补记到实例上。
     *
     * <p><b>为什么需要</b>：安装可能因为少数文件下载失败（国内到官方库的连接会随机重置）
     * 而整体判定失败，但版本 JSON 与客户端此时往往已经落盘。若不补记，
     * 实例的 {@code versionId} 会一直是空的 —— 用户永远看到「版本未安装」，
     * 除了重新下载几百 MB 别无出路。这里按磁盘事实恢复，之后启动流水线
     * 的完整性校验会把真正缺失的文件补齐。</p>
     *
     * <p>判定规则（宁缺勿错）：</p>
     * <ol>
     *   <li>当前 versionId 已指向存在的版本 JSON → 无需处理；</li>
     *   <li>{@code mcVersion} 与某个已安装版本同名 → 采用它（最可靠）；</li>
     *   <li>否则只有<b>恰好一个</b>已安装版本时才采用 —— 多个时无法判断用户想用哪个；</li>
     *   <li>采用后顺带补齐 mcVersion 与加载器信息（从版本 id 的命名规律推断）。</li>
     * </ol>
     *
     * @return 补记的版本 id；无需或无法补记时返回空串
     */
    public String recoverVersionFromDisk(LauncherConfig.InstanceEntry entry) {
        if (entry == null) {
            return "";
        }
        GamePaths paths = gamePaths(entry);
        String current = entry.versionId == null ? "" : entry.versionId;
        boolean currentUsable = !current.isBlank() && Files.isRegularFile(paths.versionJson(current))
                && versionMatchesDeclaredLoader(entry, current);
        if (currentUsable) {
            return "";
        }

        List<String> installed = installedVersions(paths);
        if (installed.isEmpty()) {
            return "";
        }
        String mcVersion = entry.mcVersion == null ? "" : entry.mcVersion;
        LoaderType loader = LoaderType.fromId(entry.loaderType);
        boolean hasLoader = !loader.isVanilla()
                && entry.loaderVersion != null && !entry.loaderVersion.isBlank();

        String adopted = null;
        if (hasLoader) {
            // 实例声明了加载器：只有找到「匹配的加载器版本」才自愈，绝不拿原版顶替 ——
            // 那会把 Forge 实例悄悄降级成原版，模组全部失效（真实故障：游戏以原版启动，
            // 4608 个模组文件形同虚设）。找不到就交回安装流程：原版文件已就位，
            // 补装加载器只需几十秒
            String preferred = mcVersion.isBlank()
                    ? "" : mcVersion + "-" + loader.id() + "-" + entry.loaderVersion;
            adopted = installed.stream()
                    .filter(id -> id.equals(preferred)
                            || (id.contains("-" + loader.id() + "-")
                                && (mcVersion.isBlank() || id.startsWith(mcVersion + "-"))))
                    .findFirst()
                    .orElse(null);
            if (adopted == null) {
                log.info("实例 {} 声明了 {} {}，但磁盘上只有 {}；不拿原版顶替，交由安装流程补装",
                        entry.name, loader.displayName(), entry.loaderVersion, installed);
                return "";
            }
        }
        if (adopted == null && !mcVersion.isBlank() && installed.contains(mcVersion)) {
            adopted = mcVersion;
        }
        if (adopted == null && installed.size() == 1) {
            adopted = installed.get(0);
        }
        if (adopted == null) {
            // 原版 + 加载器版本并存、实例又没有任何元信息（安装中断 → updateVersion 没跑过、
            // 用户又照着日志里的命令手动装完了 Forge）：优先采用**唯一的加载器版本**。
            // 加载器版本的 id 自带完整语义（mc-loader-版本），据此补齐的元信息是确定的；
            // 而拒绝选择会让实例一直显示「未安装」，用户明明已经把 Forge 装好了
            List<String> loaderVersions = installed.stream()
                    .filter(id -> LOADER_VERSION_MARKER.matcher(id).find())
                    .toList();
            if (loaderVersions.size() == 1) {
                adopted = loaderVersions.get(0);
            }
        }
        if (adopted == null) {
            log.info("游戏目录里有 {} 个已安装版本，无法自动判定，交由用户选择：{}",
                    installed.size(), installed);
            return "";
        }

        entry.versionId = adopted;
        applyNamingConventions(entry, adopted);
        configStore.save();
        log.info("已从游戏目录恢复实例版本：{} -> {}（{}）", entry.name, adopted, entry.gameDir);
        return adopted;
    }

    /**
     * 按磁盘事实补齐实例的版本元信息（界面选中实例时主动调用）。
     *
     * <p>与 {@link #recoverVersionFromDisk} 的区别：本方法即使 {@code versionId} 完好也会跑 ——
     * 从旧版本升级上来、或用户手改过配置时，{@code mcVersion} / 加载器字段可能是空的，
     * 而界面展示、Java 版本判定、加载器安装都要用到它们。</p>
     *
     * <p>推导遵循「宁缺勿错」：版本 id 里读不出游戏版本时（例如 NeoForge 的
     * {@code neoforge-21.1.0}）**保留原值**，绝不写入猜出来的垃圾。</p>
     *
     * @return 是否有字段被补上（调用方据此刷新界面）
     */
    public boolean syncVersionMetadata(LauncherConfig.InstanceEntry entry) {
        if (entry == null) {
            return false;
        }
        boolean changed = !recoverVersionFromDisk(entry).isBlank();

        String versionId = entry.versionId == null ? "" : entry.versionId;
        if (versionId.isBlank()) {
            return changed;
        }
        String beforeMc = nullToEmpty(entry.mcVersion);
        String beforeLoader = nullToEmpty(entry.loaderType);
        String beforeLoaderVersion = nullToEmpty(entry.loaderVersion);
        String beforeOptifine = nullToEmpty(entry.optifineVersion);

        applyNamingConventions(entry, versionId);

        if (!beforeMc.equals(nullToEmpty(entry.mcVersion))
                || !beforeLoader.equals(nullToEmpty(entry.loaderType))
                || !beforeLoaderVersion.equals(nullToEmpty(entry.loaderVersion))
                || !beforeOptifine.equals(nullToEmpty(entry.optifineVersion))) {
            changed = true;
        }
        if (changed) {
            configStore.save();
            log.info("已按磁盘事实补齐实例元信息：{} -> versionId={} mcVersion={} 加载器={} {}",
                    entry.name, entry.versionId, entry.mcVersion, entry.loaderType, entry.loaderVersion);
        }
        return changed;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 扫描 {@code versions/} 下「目录名与 json 同名」的完整版本。 */
    private static List<String> installedVersions(GamePaths paths) {
        Path versionsDir = paths.versionsDir();
        if (!Files.isDirectory(versionsDir)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        try (var stream = Files.list(versionsDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                String id = dir.getFileName().toString();
                if (Files.isRegularFile(paths.versionJson(id))) {
                    ids.add(id);
                }
            }
        } catch (IOException e) {
            log.debug("列出版本目录失败: {}", e.getMessage());
        }
        return ids;
    }

    /**
     * 按版本 id 的命名规律补齐 mcVersion 与加载器字段。
     *
     * <p>覆盖实际会出现的形态：原版 {@code 1.20.1}、{@code 1.20.1-forge-47.4.26}、
     * {@code 1.20.1-neoforge-21.1.0}、{@code fabric-loader-0.15.11-1.20.1}、
     * {@code 1.20.1-quilt-0.23.1}、{@code 1.20.1-OptiFine_HD_U_I6}。</p>
     *
     * <p><b>宁缺勿错</b>：读不出游戏版本时（例如 NeoForge 1.21+ 的
     * {@code neoforge-21.1.0} 整个 id 里就没有游戏版本）只补加载器信息，
     * {@code mcVersion} 保持原样 —— 写进去一个猜错的版本号，
     * 会让 Java 判定与界面展示都跟着错。</p>
     */
    private static void applyNamingConventions(LauncherConfig.InstanceEntry entry, String versionId) {
        for (LoaderType type : List.of(LoaderType.FORGE, LoaderType.NEOFORGE, LoaderType.QUILT)) {
            String marker = "-" + type.id() + "-";
            int index = versionId.indexOf(marker);
            if (index > 0) {
                entry.mcVersion = versionId.substring(0, index);
                entry.loaderType = type.id();
                entry.loaderVersion = versionId.substring(index + marker.length());
                return;
            }
        }

        String fabric = "fabric-loader-";
        if (versionId.startsWith(fabric)) {
            String rest = versionId.substring(fabric.length());
            int dash = rest.lastIndexOf('-');
            if (dash > 0) {
                entry.loaderVersion = rest.substring(0, dash);
                entry.mcVersion = rest.substring(dash + 1);
                entry.loaderType = LoaderType.FABRIC.id();
                return;
            }
        }

        String optifine = "-OptiFine_";
        if (versionId.indexOf(optifine) > 0) {
            int index = versionId.indexOf(optifine);
            entry.mcVersion = versionId.substring(0, index);
            entry.optifineVersion = versionId.substring(index + 1);
            return;
        }

        String neoforge = LoaderType.NEOFORGE.id() + "-";
        if (versionId.startsWith(neoforge)) {
            // id 里没有游戏版本，只能补加载器信息
            entry.loaderType = LoaderType.NEOFORGE.id();
            entry.loaderVersion = versionId.substring(neoforge.length());
            return;
        }

        // 形如 1.20.1 的原版 id：它本身就是游戏版本
        if (Character.isDigit(versionId.charAt(0))) {
            entry.mcVersion = versionId;
        }
        // 其余形态（自定义整合包版本号等）不做推测
    }

    /** 生成实例名到目录名的安全映射。 */
    private static String sanitize(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        cleaned = cleaned.replaceAll("_+", "_");
        if (cleaned.isEmpty()) {
            cleaned = "instance";
        }
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    private static String shortId(String uuid) {
        return uuid.length() >= 8 ? uuid.substring(0, 8) : uuid;
    }
}
