package com.misaka.launcher.core.install;

import com.misaka.launcher.core.download.DownloadItem;
import com.misaka.launcher.core.download.DownloadManager;
import com.misaka.launcher.core.download.DownloadReport;
import com.misaka.launcher.core.download.DownloadTargetType;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.instance.GameOptionsPreset;
import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.meta.JavaVersionRule;
import com.misaka.launcher.core.meta.LoaderMetaService;
import com.misaka.launcher.core.meta.RuleEvaluator;
import com.misaka.launcher.core.meta.VersionManifestService;
import com.misaka.launcher.core.model.LoaderType;
import com.misaka.launcher.core.model.VersionDetail;
import com.misaka.launcher.core.task.ProgressListener;
import com.misaka.launcher.core.util.FileUtils;
import com.misaka.launcher.core.util.HttpUtils;
import com.misaka.launcher.core.util.JsonUtils;
import com.misaka.launcher.core.util.OsUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模组加载器安装。
 * <p>按加载器机制分两类实现：</p>
 * <ul>
 *   <li><b>Profile 合并式</b>（Fabric / Quilt）：直接获取 profile JSON 落到版本目录，
 *       用本地下载器补齐其运行库。全程无需 Java，速度快、可完全走镜像。</li>
 *   <li><b>安装器式</b>（Forge / NeoForge）：下载官方安装器并以
 *       {@code --installClient <gameDir>} 无头执行。这一步必须借助安装器本身，
 *       因为其中包含二进制补丁（binpatch）与 processors 流程，自行复刻风险极高。</li>
 * </ul>
 * <p>无论哪种方式，都会先确保原版版本已安装 —— 既保证安装器能取到原版客户端 jar，
 * 也让整个流程对镜像源更友好。</p>
 *
 * <p><b>全自动是硬要求</b>：加载器安装过程中不允许出现「请用户自己再跑一条命令」这种收尾。
 * 安装器式加载器因此额外做了两件事 —— 预置其运行库（{@link #preseedInstallerLibraries}）
 * 与预置后处理所需的 Mojang 映射并让它跳过下载（{@link #preseedMojmaps}），
 * 把安装器内部所有不受我们控制的联网步骤逐一消掉。</p>
 */
public final class LoaderInstaller {

    private static final Logger log = LoggerFactory.getLogger(LoaderInstaller.class);

    /** 安装器执行超时（分钟）。安装器需要下载若干运行库并执行补丁，给足余量。 */
    private static final long INSTALLER_TIMEOUT_MINUTES = 20L;

    /**
     * 安装器「完全静默」多久判定为卡死（分钟）。
     * <p>这才是卡死的主判据：慢速网络 / 慢盘上安装器可以在毫无输出的情况下干活很久，
     * 一刀切的总时长会误杀；而真正的卡死特征是「一个字节的输出都没有了」。
     * 取 10 分钟是刻意的保守值 —— 宁可让用户多等几分钟，也不要误判一次正常安装；
     * 静默 2 分钟后界面就会开始提示「仍在等待」，用户不会以为程序死了。</p>
     */
    private static final long INSTALLER_IDLE_TIMEOUT_MINUTES = 10L;

    /** 等待安装器的轮询间隔，决定「停止」按钮的响应粒度。 */
    private static final long INSTALLER_POLL_MILLIS = 250L;

    /** 静默多久开始在界面上提示「仍在等待」（毫秒）。 */
    private static final long INSTALLER_SILENT_NOTICE_MILLIS = 120_000L;

    /** 安装器工作目录下存放「改过的 install_profile.json」的子目录。 */
    private static final String MOJMAPS_OVERRIDE_DIR = "profile-override";

    /**
     * 类路径分隔符（Windows {@code ;}、类 Unix {@code :}）。
     * <p>必须提到类级别：{@link #runInstaller} 的形参名就叫 {@code java}，
     * 方法体内再写 {@code java.io.File} 会被解析成「对 Path 取字段 io」。</p>
     */
    private static final String CLASSPATH_SEPARATOR = java.io.File.pathSeparator;

    /**
     * 安装器取运行库用的国内镜像，<b>顺序即优先级</b>。
     *
     * <p><b>为什么把「直连镜像」排在 BMCLAPI 之前</b>（实测数据，8 个典型构件）：</p>
     * <table>
     *   <tr><td>{@code bmclapi2.bangbang93.com/maven/}</td><td>6/8 可用，合计 <b>66.96 秒</b></td></tr>
     *   <tr><td>{@code mirrors.cernet.edu.cn/bmclapi/}</td><td>7/8，合计 2.13 秒</td></tr>
     *   <tr><td>{@code unicom.mirrors.ustc.edu.cn/bmclapi/}</td><td>7/8，合计 <b>0.85 秒</b></td></tr>
     *   <tr><td>{@code maven.aliyun.com/repository/public/}</td><td>3/8（仅 Central）</td></tr>
     * </table>
     *
     * <p>BMCLAPI 主站对这些构件会 302 到一个**挂住的节点**，每个卡满 20 秒才失败
     * （mixin / nashorn / mcp_config / asm-tree 实测全是 20.0x 秒）——
     * 把它排在第一位，几十个构件就是几分钟的纯等待，这正是「安装 Forge 过久」的直接原因。
     * 教育网与中科大镜像就是它 302 背后的真实存储，直接访问反而快几十倍。</p>
     *
     * <p><b>为什么还要自己下</b>：官方 {@code maven.minecraftforge.net} 在国内经常完全不可达
     * （实测 42 秒超时、0 字节），而安装器只会去它那里取运行库，且<b>不会换仓库重试</b> ——
     * 一个构件取不到，整个安装就以「These libraries failed to download」告终。</p>
     */
    private static final List<String> MAVEN_MIRRORS = List.of(
            "https://unicom.mirrors.ustc.edu.cn/bmclapi/",
            "https://mirrors.cernet.edu.cn/bmclapi/",
            "https://maven.aliyun.com/repository/public/",
            "https://bmclapi2.bangbang93.com/maven/");

    /**
     * 传给安装器 {@code --mirror} 的地址。
     *
     * <p>与预置用的镜像<b>刻意不同</b>：预置是我们拿着明确的路径清单批量取，
     * 用直连镜像最快（实测 63 个构件 5 秒），也避开 BMCLAPI 主站对缺失文件的 20 秒挂起；
     * 而安装器内部的请求（processors 取 mojmaps 等）路径由它自己决定，
     * 需要 BMCLAPI 主站那种「按文件转发到合适存储节点」的覆盖能力
     * —— 实测用主站参数安装器 36 秒跑完，换成直连镜像则会反复重试。</p>
     */
    private static final String INSTALLER_MIRROR = "https://bmclapi2.bangbang93.com/maven/";

    /**
     * 提供可用的 Java 可执行文件。
     * <p>刻意以函数式接口注入，避免本类反向依赖 Java 运行时管理模块，
     * 同时让测试可以传入任意桩实现。</p>
     */
    @FunctionalInterface
    public interface JavaProvider {
        /**
         * @param requiredMajorVersion 期望的主版本（安装器自身所需）
         * @return java 可执行文件路径
         */
        Path javaExecutable(int requiredMajorVersion) throws IOException;
    }

    private final VersionManifestService versionMeta;
    private final LoaderMetaService loaderMeta;
    private final DownloadManager downloads;
    private final VanillaInstaller vanillaInstaller;
    private final GamePaths paths;
    private final JavaProvider javaProvider;

    public LoaderInstaller(VersionManifestService versionMeta,
                           LoaderMetaService loaderMeta,
                           DownloadManager downloads,
                           VanillaInstaller vanillaInstaller,
                           GamePaths paths,
                           JavaProvider javaProvider) {
        this.versionMeta = versionMeta;
        this.loaderMeta = loaderMeta;
        this.downloads = downloads;
        this.vanillaInstaller = vanillaInstaller;
        this.paths = paths;
        this.javaProvider = javaProvider;
    }

    public GamePaths paths() {
        return paths;
    }

    // ==================================================================
    // 入口
    // ==================================================================

    /**
     * 安装加载器。
     *
     * @param loaderType    加载器类型（{@link LoaderType#VANILLA} 直接返回原版安装结果）
     * @param mcVersion     目标游戏版本
     * @param loaderVersion 加载器版本
     * @param listener      进度回调
     */
    public LoaderInstallResult install(LoaderType loaderType,
                                       String mcVersion,
                                       String loaderVersion,
                                       ProgressListener listener) throws IOException {
        long start = System.currentTimeMillis();

        if (loaderType == null || loaderType.isVanilla()) {
            InstallResult base = vanillaInstaller.install(mcVersion, listener);
            return new LoaderInstallResult(mcVersion, LoaderType.VANILLA, "", LoaderInstallResult.MODE_PROFILE,
                    base.downloadReport(), 0, System.currentTimeMillis() - start);
        }

        ensureBaseVersion(mcVersion, listener);

        LoaderInstallResult result = switch (loaderType) {
            case FABRIC, QUILT -> installProfileBased(loaderType, mcVersion, loaderVersion, listener, start);
            case FORGE, NEOFORGE -> installViaInstaller(loaderType, mcVersion, loaderVersion, listener, start);
            case OPTIFINE -> downloadOptiFineInstaller(mcVersion, loaderVersion, listener, start);
            default -> throw new IOException("不支持的加载器类型: " + loaderType);
        };

        // 加载器装完才算这一次「安装游戏」结束，因此这里必须再铺一次：
        // ensureBaseVersion 在原版已装时会直接短路（不再经过 VanillaInstaller.install），
        // 「给已装原版的实例补加载器」这条路径就只走得到这里
        if (GameOptionsPreset.deployIfAbsent(paths)) {
            listener.onMessage(I18n.t("instance.options.applied"));
        }
        return result;
    }

    // ==================================================================
    // Profile 合并式（Fabric / Quilt）
    // ==================================================================

    private LoaderInstallResult installProfileBased(LoaderType loaderType,
                                                    String mcVersion,
                                                    String loaderVersion,
                                                    ProgressListener listener,
                                                    long start) throws IOException {
        listener.onStage(I18n.t("install.stage.loader"));
        listener.onMessage("获取 " + loaderType.displayName() + " " + loaderVersion + " 的 profile");

        String profileJson = loaderMeta.fetchProfileJson(loaderType, mcVersion, loaderVersion);
        String versionId = loaderMeta.profileVersionId(loaderType, mcVersion, loaderVersion, profileJson);

        Path versionJson = paths.versionJson(versionId);
        FileUtils.ensureParent(versionJson);
        FileUtils.atomicWrite(versionJson, profileJson);
        log.info("已写入加载器 profile: {}", versionJson);

        VersionDetail profile = VersionManifestService.readLocal(versionJson);
        if (profile.inheritsFrom == null || profile.inheritsFrom.isBlank()) {
            profile.inheritsFrom = mcVersion;
        }

        InstallPlan plan = vanillaInstaller.plan(profile, RuleEvaluator.defaultFeatures());
        DownloadReport report = DownloadReportWrapper.empty();
        if (!plan.coreItems().isEmpty()) {
            listener.onStage(I18n.t("install.stage.libraries"));
            report = downloads.downloadAll(plan.coreItems(), listener);
        }

        listener.onStage(I18n.t("install.stage.finish"));
        LoaderInstallResult result = new LoaderInstallResult(versionId, loaderType, loaderVersion,
                LoaderInstallResult.MODE_PROFILE, report, 0, System.currentTimeMillis() - start);
        log.info("{} {} 安装完成，版本 id = {}", loaderType.displayName(), loaderVersion, versionId);
        return result;
    }

    // ==================================================================
    // 安装器式（Forge / NeoForge）
    // ==================================================================

    private LoaderInstallResult installViaInstaller(LoaderType loaderType,
                                                    String mcVersion,
                                                    String loaderVersion,
                                                    ProgressListener listener,
                                                    long start) throws IOException {
        listener.onStage(I18n.t("install.stage.loader"));

        String installerUrl = loaderMeta.installerUrl(loaderType, mcVersion, loaderVersion);
        if (installerUrl == null) {
            throw new IOException("无法确定安装器地址: " + loaderType + " " + loaderVersion);
        }
        // SHA-1 直接问镜像（<mirror><path>.sha1）：官方 maven 在国内常常不可达，
        // 而 maven 边车文件在镜像上都有 —— 走官方会白等一个超时
        String sha1 = installerSha1(installerUrl);

        Path workDir = paths.versionWorkDir(LoaderMetaService.defaultVersionId(loaderType, mcVersion, loaderVersion));
        FileUtils.ensureDir(workDir);
        String fileName = installerUrl.substring(installerUrl.lastIndexOf('/') + 1);
        Path installerJar = workDir.resolve(fileName);

        listener.onMessage("下载 " + loaderType.displayName() + " 安装器：" + fileName);
        long installerStarted = System.currentTimeMillis();
        DownloadReport installerReport = downloads.downloadAll(List.of(
                DownloadItem.of(installerJar, installerUrl, sha1, -1L, DownloadTargetType.LOADER_INSTALLER)), listener);
        log.info("安装器下载耗时 {} ms（{}）", System.currentTimeMillis() - installerStarted, fileName);

        // 安装器本身所需的 Java 版本按目标游戏版本估算（Forge 安装器要求不低于游戏所需）
        int requiredJava = JavaVersionRule.requiredMajorVersion(mcVersion);
        Path java = javaProvider.javaExecutable(requiredJava);
        if (java == null || !Files.isExecutable(java)) {
            // 这条消息是「装不了加载器」最常见的根因，必须给出可执行的下一步，
            // 而不是让用户对着一个安装器卡住的界面猜
            throw new IOException("执行 " + loaderType.displayName() + " 安装器需要 "
                    + JavaVersionRule.describe(requiredJava) + "（游戏版本 " + mcVersion
                    + "），但本机没有可用的该版本 Java。请先在「设置 → Java」中安装 "
                    + requiredJava + "，再重试安装。");
        }
        log.info("加载器安装器将使用 Java {}：{}", requiredJava, java);

        // 先把安装器要用的运行库铺好（走国内镜像）：安装器自己只会连官方 maven，
        // 那一步在国内常常直接卡死
        DownloadReport preseeded = preseedInstallerLibraries(installerJar, listener);

        // 后处理里只有 DownloadMojmaps 需要联网，而它把官方地址写死在类里、
        // 既不认 --mirror 也不认 --offline（实测 SSL 握手被重置，安装器退出码 1）。
        // 这里先把映射下好并让安装器跳过该步，整段后处理因此不再产生任何网络请求。
        MojmapsProvision mojmaps = preseedMojmaps(workDir, installerJar, mcVersion, listener);

        Set<String> before = listVersionIds();
        // 带国内镜像参数执行：安装器仍要自己取少量运行库与补丁数据，**不能只靠 --offline**
        // （processors 是另起的进程，--offline 传不到它们那里）。运行库已被我们预置、
        // 后处理里的联网步骤已被上面的档案覆盖消掉，这一步基本只剩本地计算。
        long runStarted = System.currentTimeMillis();
        int exitCode = runInstaller(java, installerJar, mojmaps, mcVersion, listener, true);
        if (exitCode != 0) {
            // 后处理虽然已离线化，但安装器仍会自己取运行库与补丁数据，网络抖动一次就整体失败。
            // 原版文件与运行库都已就位，重试只补失败的那几步，成本远低于让整个安装作废
            // （真实故障：DownloadMojmaps Connection reset 让安装直接以退出码 1 结束）
            listener.onMessage(loaderType.displayName() + " 安装器执行失败（退出码 " + exitCode + "），重试一次…");
            log.warn("加载器安装器首次执行失败（退出码 {}），带镜像自动重试", exitCode);
            exitCode = runInstaller(java, installerJar, mojmaps, mcVersion, listener, true);
        }
        if (exitCode != 0) {
            // 最后一级：撤掉档案覆盖、也不带 --mirror，完全按安装器的默认行为跑一遍。
            // 覆盖档案被老版 installertools 拒绝、或镜像本来正挂在坏节点上时，
            // 这条「最朴素」的路径反而是能成功的那条
            listener.onMessage("仍失败，撤掉本地覆盖改走官方源再试一次…");
            log.warn("加载器安装器镜像模式两次失败，降级为官方源重试");
            exitCode = runInstaller(java, installerJar, MojmapsProvision.none(), mcVersion, listener, false);
        }
        log.info("安装器执行耗时 {} ms（退出码 {}）", System.currentTimeMillis() - runStarted, exitCode);
        Set<String> after = listVersionIds();

        String versionId = detectProducedVersion(after, before, loaderType, mcVersion, loaderVersion);
        if (exitCode != 0 || versionId == null) {
            // 安装器保留在现场（排查需要它），但**不再**把「请手动执行这条命令」当成给用户的解法：
            // 加载器安装要么全自动完成，要么以明确的失败原因结束。
            log.warn("{} {} 安装未完成（退出码 {}）。安装器保留在 {}；手工复现命令："
                            + "java -jar \"{}\" --installClient \"{}\"",
                    loaderType.displayName(), loaderVersion, exitCode, installerJar, installerJar,
                    paths.gameDir());
            throw new IOException(loaderType.displayName() + " " + loaderVersion + " 安装未完成"
                    + "（安装器退出码 " + exitCode + "）。已依次自动重试镜像源与官方源；"
                    + "仍失败通常是网络在安装器所需文件上被中断，请检查网络后重试安装（详见日志）。");
        }

        // 把安装器产出的「打过补丁的客户端 jar」搬到版本目录。
        // Forge 的启动参数里有 -DignoreList=...,forge-,${version_name}.jar —— 它期望补丁 jar
        // 位于 versions/<id>/<id>.jar；而安装器实际把它放在 libraries 下。
        // 不搬过去的话，类路径里会同时出现原版 jar 与补丁 jar，两者都导出 net.minecraft.*，
        // 模块系统直接报 ResolutionException（实测「进程启动后 0 秒退出、退出码 1」）——
        // 这也是 HMCL / PCL 等启动器都会做的最后一步。
        adoptPatchedClientJar(paths, versionId);

        // 安装器受限于其内置仓库地址，在部分网络下会漏下载运行库；
        // 此处用本地下载器（支持镜像与校验）做一次补齐
        long repairStarted = System.currentTimeMillis();
        DownloadReport repair = repairMissingLibraries(versionId, listener);
        log.info("安装后补齐运行库耗时 {} ms", System.currentTimeMillis() - repairStarted);

        listener.onStage(I18n.t("install.stage.finish"));
        log.info("{} {} 安装完成，版本 id = {}（安装器退出码 {}）",
                loaderType.displayName(), loaderVersion, versionId, exitCode);
        return new LoaderInstallResult(versionId, loaderType, loaderVersion,
                LoaderInstallResult.MODE_INSTALLER,
                DownloadReportWrapper.merge(List.of(installerReport, preseeded, mojmaps.report(), repair),
                        System.currentTimeMillis() - start),
                exitCode, System.currentTimeMillis() - start);
    }

    /**
     * 预置安装器所需的运行库。
     *
     * <p>把 {@code install_profile.json} / {@code version.json} 里声明的构件用本地下载器
     * （多镜像候选 + SHA-1 校验）铺到 {@code <gameDir>/libraries}，安装器随后命中本地文件
     * 便不再联网 —— 这是「Forge 安装卡很久」的根治手段。</p>
     *
     * @return 预置过程的下载汇总
     */
    private DownloadReport preseedInstallerLibraries(Path installerJar, ProgressListener listener)
            throws IOException {
        long startedAt = System.currentTimeMillis();
        List<InstallerProfile.Artifact> artifacts = InstallerProfile.artifacts(installerJar);
        if (artifacts.isEmpty()) {
            return DownloadReportWrapper.empty();
        }

        listener.onStage(I18n.t("install.stage.libraries"));
        listener.onMessage("预置 " + artifacts.size() + " 个运行库（走国内镜像，避免安装器直连官方 maven）");

        List<InstallerProfile.Artifact> pending = new ArrayList<>(artifacts);
        DownloadReport accumulated = DownloadReportWrapper.empty();
        for (String mirror : MAVEN_MIRRORS) {
            if (pending.isEmpty()) {
                break;
            }
            List<DownloadItem> items = new ArrayList<>(pending.size());
            for (InstallerProfile.Artifact artifact : pending) {
                items.add(new DownloadItem(paths.librariesDir().resolve(artifact.path()),
                        mirror + artifact.path(), artifact.sha1(), artifact.size(),
                        DownloadTargetType.LOADER_INSTALLER, Map.of(), Map.of(), true));
            }
            long roundStarted = System.currentTimeMillis();
            // 快速失败策略：每个候选只试一次、6 秒超时、不退避、不补第二轮。
            // 这些构件是「镜像上有就秒下，没有就换个镜像」的典型场景，
            // 默认的「多候选 × 3 次 + 退避」对不存在文件纯属白等（实测白花 139 秒）
            DownloadReport report = downloads.downloadAllFast(items, listener);
            accumulated = DownloadReportWrapper.merge(List.of(accumulated, report), 0L);
            int before = pending.size();
            pending = retryTargets(report, pending);
            // 逐轮记录：镜像之间的速度差可达几十倍（实测 0.85s vs 66.96s / 8 个构件），
            // 没有这条日志就无法判断慢在哪个镜像上
            log.info("预置轮次 {}：下载 {} 个，剩余 {} 个，耗时 {} ms",
                    mirror, report.downloaded(), pending.size(),
                    System.currentTimeMillis() - roundStarted);
            if (pending.size() == before) {
                // 这一轮一个都没取到：剩下的构件在镜像生态里就是没有
                // （实测 14 个 Forge 中间产物在 4 个镜像上全部失败），
                // 继续换源只是把同样的失败重复三遍
                log.info("镜像 {} 未能取到任何剩余构件（{} 个），停止换源；"
                                + "这些构件由安装器自行生成或从官方地址获取",
                        mirror, pending.size());
                break;
            }
        }

        // 这里**故意不去试构件自带的官方地址**：那个域名（maven.minecraftforge.net）在国内
        // 常常完全不可达，逐个试会各赔一个 15 秒超时；而「所有镜像都 404」的构件通常是
        // 安装器自己会生成（例如打过补丁的客户端 jar），根本不需要我们准备。
        // 真正缺的会在安装器带 --mirror 执行时报出来，那时错误信息也更准确。
        if (pending.isEmpty()) {
            log.info("安装器运行库已全部预置（{} 个，走国内镜像，耗时 {} ms）",
                    artifacts.size(), System.currentTimeMillis() - startedAt);
        } else {
            log.warn("有 {} 个运行库未预置，交由安装器自行处理：{}",
                    pending.size(), InstallerProfile.describe(pending));
        }
        return accumulated;
    }

    // ------------------------------------------------------------------
    // 后处理离线化：Mojang 官方映射（installertools 的 DownloadMojmaps）
    // ------------------------------------------------------------------

    /**
     * 预置安装器后处理要用的 Mojang 官方映射，并生成「跳过下载」的档案覆盖。
     *
     * <p><b>为什么必须做这一步</b>：{@code DownloadMojmaps} 把官方地址写死在类里
     * （{@code launchermeta.mojang.com} 取清单 → {@code piston-data.mojang.com} 取文件），
     * 既忽略 {@code --mirror} 也忽略 {@code --offline}；国内实测 SSL 握手被重置
     * （{@code java.net.SocketException: Connection reset}），安装器随即以退出码 1
     * 结束整个安装 —— 用户看到的就是「装到一半失败」。</p>
     *
     * <p>做法：用本地下载器（多候选 + 重试 + 哈希校验）把映射落到安装器期望的位置，
     * 再把档案里 {@code DOWNLOAD_MOJMAPS} 的参数补上 {@code --skipIfExists}。
     * 这一步成功后，加载器安装全程不再依赖任何「安装器自己发起的联网」。</p>
     *
     * <p>任何一环拿不准（档案解析不出、映射下不来、处理器不支持该开关）都<b>不</b>注入覆盖：
     * 让安装器照旧联网，最差也只是回到修复前的行为，绝不会把「可能失败」变成「必然失败」。</p>
     *
     * @param workDir      该版本的安装器工作目录（覆盖档案放这里，卸载版本时自然清掉）
     * @param installerJar 已下载的安装器
     * @param mcVersion    目标游戏版本；处理器未声明 {@code --version} 时作为兜底
     */
    private MojmapsProvision preseedMojmaps(Path workDir, Path installerJar, String mcVersion,
                                            ProgressListener listener) {
        byte[] profile;
        List<InstallerProfile.MojmapsTask> tasks;
        try {
            profile = InstallerProfile.rawInstallProfile(installerJar);
            tasks = InstallerProfile.mojmapsTasks(profile);
        } catch (IOException e) {
            log.warn("无法解析安装器档案，Mojang 映射将交由安装器自行下载: {}", e.getMessage());
            return MojmapsProvision.none();
        }
        if (tasks.isEmpty()) {
            return MojmapsProvision.none();
        }

        // 老版 installertools 不认识 --skipIfExists：注入会让处理器直接以
        // UnrecognizedOptionException 失败。先问清楚再注入。
        for (String jarPath : tasks.stream().map(InstallerProfile.MojmapsTask::processorJarPath)
                .filter(path -> !path.isBlank()).distinct().toList()) {
            if (Boolean.FALSE.equals(InstallerProfile.supportsSkipIfExists(paths.libraryFile(jarPath)))) {
                log.info("处理器不支持 --skipIfExists，Mojang 映射交由安装器自行下载：{}", jarPath);
                return MojmapsProvision.none();
            }
        }

        List<DownloadReport> reports = new ArrayList<>();
        for (InstallerProfile.MojmapsTask task : tasks) {
            DownloadReport report = preseedMojmapsFile(task, mcVersion, listener);
            if (report == null) {
                // 映射拿不到就不能让安装器跳过：跳过之后 MERGE_MAPPING 会拿空文件继续跑，
                // 失败点被推到更靠后、更难排查的位置
                return new MojmapsProvision(null, null, mergeReports(reports));
            }
            reports.add(report);
        }

        try {
            byte[] patched = InstallerProfile.withMojmapsSkip(profile).orElse(null);
            String mainClass = mainClassOf(installerJar);
            if (patched == null || mainClass == null) {
                return new MojmapsProvision(null, null, mergeReports(reports));
            }
            Path dir = workDir.resolve(MOJMAPS_OVERRIDE_DIR);
            FileUtils.ensureDir(dir);
            FileUtils.atomicWrite(dir.resolve("install_profile.json"), patched);
            log.info("已预置 Mojang 映射并生成档案覆盖（{} 个处理器），安装器后处理不再需要联网", tasks.size());
            return new MojmapsProvision(dir, mainClass, mergeReports(reports));
        } catch (IOException e) {
            log.warn("生成档案覆盖失败，Mojang 映射交由安装器自行下载: {}", e.getMessage());
            return new MojmapsProvision(null, null, mergeReports(reports));
        }
    }

    /**
     * 下载一个 {@code DownloadMojmaps} 处理器需要的映射文件。
     *
     * @return 下载汇总；失败返回 null（调用方据此放弃注入跳过参数）
     */
    private DownloadReport preseedMojmapsFile(InstallerProfile.MojmapsTask task, String mcVersion,
                                              ProgressListener listener) {
        String version = task.mcVersion() == null || task.mcVersion().isBlank()
                || task.mcVersion().startsWith("{") ? mcVersion : task.mcVersion();
        String side = task.side() == null || task.side().isBlank() ? "client" : task.side();
        try {
            // 映射的官方地址只存在于版本 JSON 里，先用版本元数据服务把它取出来
            // （这一步走镜像/缓存，与安装器自己那条「硬编码官方地址」的链路无关）
            VersionDetail detail = versionMeta.versionDetail(version, false);
            VersionDetail.DownloadInfo info = detail.download(side + "_mappings");
            if (info == null || !info.isUsable()) {
                log.warn("版本 {} 缺少 {} 映射的下载信息，跳过预置", version, side);
                return null;
            }
            DownloadItem item = new DownloadItem(paths.libraryFile(task.outputMavenPath()),
                    info.url, info.sha1, info.size, DownloadTargetType.LIBRARY,
                    Map.of(), Map.of("versionId", version), true);
            if (DownloadManager.isUpToDate(item)) {
                return DownloadReportWrapper.empty();
            }
            listener.onMessage("预置 Mojang 官方映射（" + side + " " + version + "）");
            long startedAt = System.currentTimeMillis();
            DownloadReport report = downloads.downloadAll(List.of(item), listener);
            log.info("Mojang 映射预置耗时 {} ms（{}）", System.currentTimeMillis() - startedAt,
                    report.summary());
            if (report.hasRequiredFailure()) {
                DownloadReport.Failure failure = report.failures().stream()
                        .filter(DownloadReport.Failure::required).findFirst().orElse(null);
                log.warn("Mojang 映射预置失败：{}", failure == null ? "未知原因" : failure.reason());
                return null;
            }
            return DownloadManager.isUpToDate(item) ? report : null;
        } catch (IOException e) {
            log.warn("Mojang 映射预置失败（{}）: {}", version, e.getMessage());
            return null;
        }
    }

    /** 合并若干下载汇总；供 {@link #preseedMojmaps} 在提前返回时也保留已产生的统计。 */
    private static DownloadReport mergeReports(List<DownloadReport> reports) {
        return DownloadReportWrapper.merge(reports, 0L);
    }

    /**
     * 读安装器 jar 清单里的 {@code Main-Class}。
     *
     * <p>档案覆盖要求改用 {@code -cp 覆盖目录:安装器 MainClass} 启动
     * （{@code -jar} 只认 jar 内部的类路径，无法插入覆盖目录）。
     * 主类必须从清单里取：Forge 与 NeoForge 的主类并不相同。</p>
     *
     * @return 主类名；清单缺失或读不出时返回 null（调用方放弃覆盖）
     */
    private static String mainClassOf(Path installerJar) {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(installerJar.toFile())) {
            java.util.jar.Manifest manifest = jar.getManifest();
            String main = manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
            if (main == null || main.isBlank()) {
                log.warn("安装器清单缺少 Main-Class，无法使用档案覆盖: {}", installerJar);
                return null;
            }
            return main.trim();
        } catch (IOException e) {
            log.warn("读取安装器清单失败，无法使用档案覆盖: {}", e.getMessage());
            return null;
        }
    }

    /**
     * mojmaps 预置结果。
     *
     * @param overrideDir 档案覆盖目录（内含改过的 {@code install_profile.json}）；无覆盖时为 null
     * @param mainClass   安装器主类；无覆盖时为 null
     * @param report      预置过程中的下载汇总（预置失败时也保留，便于如实统计流量）
     */
    private record MojmapsProvision(Path overrideDir, String mainClass, DownloadReport report) {

        static MojmapsProvision none() {
            return new MojmapsProvision(null, null, DownloadReportWrapper.empty());
        }

        boolean overridable() {
            return overrideDir != null && mainClass != null && !mainClass.isBlank();
        }
    }

    /**
     * 取安装器的 SHA-1：直接问国内镜像。
     *
     * <p>不走官方 maven：它在国内常常完全不可达，而 {@code .sha1} 边车文件在镜像上都有。
     * 每个镜像只给 5 秒，取不到就放弃校验 —— 安装器本身来自镜像，后续预置的运行库
     * 仍逐个校验 SHA-1，不会因为这一步没校验而失去保障。</p>
     *
     * @return 40 位小写 SHA-1；取不到返回空串
     */
    private String installerSha1(String installerUrl) {
        String path = mavenRelativePath(installerUrl);
        if (path.isBlank()) {
            return "";
        }
        for (String mirror : MAVEN_MIRRORS) {
            String url = mirror + path + ".sha1";
            try {
                String body = HttpUtils.getString(url, Map.of(), Duration.ofSeconds(5));
                if (body == null) {
                    continue;
                }
                String value = body.trim().split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
                if (value.matches("[0-9a-f]{40}")) {
                    return value;
                }
            } catch (IOException e) {
                log.debug("镜像未提供安装器 SHA-1：{} -> {}", url, e.getMessage());
            }
        }
        log.info("未能从镜像取得安装器 SHA-1，跳过校验：{}", mavenRelativePath(installerUrl));
        return "";
    }

    /** 从镜像取 maven 相对路径（去掉 base 之后的路径部分）。 */
    private static String mavenRelativePath(String url) {
        if (url == null) {
            return "";
        }
        int index = url.indexOf("/maven/");
        if (index >= 0) {
            return url.substring(index + "/maven/".length());
        }
        // 非 maven 布局（例如 neoforged 的 releases 路径）：取主机之后的完整路径
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return "";
        }
        int slash = url.indexOf('/', scheme + 3);
        return slash < 0 ? "" : url.substring(slash + 1);
    }

    /** 从下载结果里挑出仍然失败的构件（按目标路径匹配）。 */
    private static List<InstallerProfile.Artifact> retryTargets(DownloadReport report,
                                                                List<InstallerProfile.Artifact> candidates) {
        if (report == null || report.failures().isEmpty()) {
            return List.of();
        }
        Map<String, InstallerProfile.Artifact> byFileName = new LinkedHashMap<>();
        for (InstallerProfile.Artifact artifact : candidates) {
            byFileName.put(fileNameOf(artifact.path()), artifact);
        }
        List<InstallerProfile.Artifact> failed = new ArrayList<>();
        for (DownloadReport.Failure failure : report.failures()) {
            InstallerProfile.Artifact artifact = byFileName.get(fileNameOf(failure.target().toString()));
            if (artifact != null) {
                failed.add(artifact);
            }
        }
        return failed;
    }

    private static String fileNameOf(String path) {
        int index = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return index < 0 ? path : path.substring(index + 1);
    }

    /**
     * 写出最小可用的 {@code launcher_profiles.json}。
     *
     * <p>Forge 安装器会先检查「启动器档案」，缺失时直接报
     * {@code There is no Minecraft launcher profile ... you need to run the launcher first!}
     * 并退出 —— 而我们从来不生成这个文件，等于必挂在第一步。</p>
     */
    private void ensureLauncherProfiles(String mcVersion) throws IOException {
        Path file = paths.gameDir().resolve("launcher_profiles.json");
        if (Files.isRegularFile(file)) {
            return;
        }
        com.fasterxml.jackson.databind.node.ObjectNode root = JsonUtils.newObject();
        com.fasterxml.jackson.databind.node.ObjectNode profile = root.putObject("profiles")
                .putObject("misaka");
        profile.put("name", "Misaka");
        profile.put("type", "custom");
        profile.put("lastVersionId", mcVersion);
        root.put("selectedProfile", "misaka");
        root.put("clientToken", "");
        root.putObject("authenticationDatabase");
        root.putObject("launcherVersion").put("name", "2.1.1").put("format", 21);
        FileUtils.atomicWrite(file, JsonUtils.writePrettyBytes(root));
        log.info("已写出 launcher_profiles.json（Forge 安装器的前置要求）");
    }

    /**
     * 为安装器挑选 {@code --mirror}：对候选镜像做一次快速探测，取第一个可达的。
     *
     * <p>探测用根路径即可（3 秒超时）：目的只是确认「这台源现在能不能对话」，
     * 不验证具体文件 —— 具体文件缺失时安装器自己会报准确错误。</p>
     *
     * <p>候选顺序即 {@link #MAVEN_MIRRORS}（直连教育网镜像优先，BMCLAPI 主站殿后）。
     * 全部不可达时退回 {@link #INSTALLER_MIRROR}：虽然它可能坏，但没有镜像参数时
     * 安装器会先去 {@code files.minecraftforge.net} 取镜像列表，那个域名坏得更彻底
     * （实测 42 秒超时）。</p>
     */
    private String pickInstallerMirror() {
        for (String mirror : MAVEN_MIRRORS) {
            try {
                HttpUtils.Result result = HttpUtils.send("GET", mirror, Map.of(), null, null,
                        Duration.ofSeconds(3));
                if (result.status() > 0 && result.status() < 500) {
                    return mirror;
                }
                log.debug("镜像探测返回 HTTP {}，换下一个：{}", result.status(), mirror);
            } catch (IOException e) {
                log.info("镜像不可达（{}），换下一个：{}", e.getMessage(), mirror);
            }
        }
        log.warn("所有安装器镜像探测均失败，退回默认镜像 {}", INSTALLER_MIRROR);
        return INSTALLER_MIRROR;
    }

    /**
     * 执行安装器。
     *
     * @param mojmaps     mojmaps 预置结果；{@link MojmapsProvision#overridable()} 为真时
     *                    以类路径覆盖方式启动，让安装器读到「已跳过下载」的档案
     * @param withMirror  是否附带 {@code --mirror}。镜像模式快但故障面独立（镜像会间歇性挂），
     *                    官方模式（不带参数，等价于手动执行）在镜像挂掉时常能成功 ——
     *                    两者互为退路，重试链上都要有
     */
    private int runInstaller(Path java, Path installerJar, MojmapsProvision mojmaps, String mcVersion,
                             ProgressListener listener, boolean withMirror) throws IOException {
        ensureLauncherProfiles(mcVersion);

        // --mirror 不能写死：BMCLAPI 主站会间歇性握手失败（实测 Remote host terminated
        // the handshake），写死它之后安装器内部的每次请求都在坏源上撞墙 ——
        // 表现为「Forge 安装下载太慢、经常失败」。运行前快速探测，选第一个活着的镜像。
        String mirror = withMirror ? pickInstallerMirror() : null;
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        if (mojmaps.overridable()) {
            // 安装器用 Util.class.getResourceAsStream("/install_profile.json") 取档案，
            // 类路径靠前者优先命中：把覆盖目录排在安装器 jar 之前即可生效，
            // 无需改动（或重新打包）已下载的安装器 jar
            command.add("-cp");
            command.add(mojmaps.overrideDir() + CLASSPATH_SEPARATOR + installerJar);
            command.add(mojmaps.mainClass());
        } else {
            command.add("-jar");
            command.add(installerJar.toString());
        }
        command.add("--installClient");
        command.add(paths.gameDir().toString());
        if (mirror != null) {
            command.add("--mirror");
            command.add(mirror);
        }
        listener.onMessage("执行安装器：" + String.join(" ", command));
        log.info("执行加载器安装器（{}）：{}", mirror == null ? "官方源" : "镜像 " + mirror,
                String.join(" ", command));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(paths.gameDir().toFile());
        builder.redirectErrorStream(true);

        Process process = builder.start();

        // 输出必须**并发**消费，不能先读完再等进程：
        // ① 不读的话，子进程写满管道缓冲区就会自己阻塞住；
        // ② 安装器的 processors 是另起的进程、会继承这条 stdout 管道，
        //    只要还有孙进程活着，EOF 就永远不会到来。
        // 旧实现「先 while(readLine != null) 读到 EOF、再 waitFor(20min)」正是因此卡死：
        // readLine() 本身没有超时，20 分钟的超时和取消检查都排在它后面，永远执行不到 ——
        // 表现就是「不报错、不退出、界面一直等，点停止也没用」。
        AtomicLong lastOutput = new AtomicLong(System.currentTimeMillis());
        startOutputPump(process, listener, lastOutput);

        try {
            awaitInstaller(process, listener, lastOutput);
        } finally {
            // 任何退出路径都不能留下孤儿安装器：取消抛的是 CancelledException
            // （RuntimeException），旧实现只 catch IOException，于是取消时安装器仍在后台跑
            killTree(process);
        }
        return process.exitValue();
    }

    /** 把安装器输出异步转发到界面与日志，并刷新「最后输出时间」。 */
    private void startOutputPump(Process process, ProgressListener listener, AtomicLong lastOutput) {
        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lastOutput.set(System.currentTimeMillis());
                    if (!line.isBlank()) {
                        listener.onMessage("[installer] " + line);
                        // 同时落到日志：安装器卡住时，这是唯一能看出卡在哪一步的线索
                        log.info("[installer] {}", line);
                    }
                }
            } catch (IOException e) {
                // 进程被强杀时管道会异常断开，这里只记录；判定与异常由等待方负责
                log.debug("安装器输出读取结束：{}", e.getMessage());
            }
        }, "loader-installer-output");
        // 守护线程：即使等待方因异常提前退出，也不会把 JVM 拖住
        pump.setDaemon(true);
        pump.start();
    }

    /**
     * 等待安装器结束：以「静默时长」为主判据、总时长为兜底上限。
     *
     * <p>等待循环本身不再依赖子进程的输出，因此：</p>
     * <ul>
     *   <li>安装器静默挂住时，静默看门狗会在 {@link #INSTALLER_IDLE_TIMEOUT_MINUTES}
     *       分钟后判定卡死并终止，给出明确原因（缺 Java / 联网被阻断是最常见两种）；</li>
     *   <li>「停止」按钮每 {@link #INSTALLER_POLL_MILLIS} 毫秒被轮询一次，
     *       不再需要「安装器先吐一行输出」才能生效。</li>
     * </ul>
     */
    private void awaitInstaller(Process process, ProgressListener listener, AtomicLong lastOutput)
            throws IOException {
        long started = System.currentTimeMillis();
        long lastNotice = 0L;
        long idleLimitMillis = TimeUnit.MINUTES.toMillis(INSTALLER_IDLE_TIMEOUT_MINUTES);
        long hardLimitMillis = TimeUnit.MINUTES.toMillis(INSTALLER_TIMEOUT_MINUTES);

        while (process.isAlive()) {
            listener.throwIfCancelled();
            try {
                if (process.waitFor(INSTALLER_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("安装器执行被中断", e);
            }

            long now = System.currentTimeMillis();
            long silent = now - lastOutput.get();
            if (silent >= idleLimitMillis) {
                throw new IOException("安装器已连续 " + INSTALLER_IDLE_TIMEOUT_MINUTES
                        + " 分钟没有任何输出，判定为卡死并已终止。常见原因："
                        + "缺少该游戏版本所需主版本的 Java、或安装器需要联网但被网络/安全软件阻断。"
                        + "详见日志中的最后一行 [installer] 输出。");
            }
            // 长时间的合法静默（慢速下载）也要让用户看到「还活着」，否则界面和卡死无法区分
            if (silent >= INSTALLER_SILENT_NOTICE_MILLIS && now - lastNotice >= 60_000L) {
                lastNotice = now;
                listener.onMessage("[installer] 已 " + (silent / 60_000L) + " 分钟没有输出，仍在等待…");
            }
            if (now - started >= hardLimitMillis) {
                throw new IOException("安装器执行超过 " + INSTALLER_TIMEOUT_MINUTES
                        + " 分钟仍未结束，已终止（详见日志）");
            }
        }
    }

    /**
     * 结束安装器进程树。
     *
     * <p>必须连同子孙进程一起杀：Forge 的后处理 processors 是安装器另起的进程，
     * 只杀安装器本身会在客户机上留下一堆仍在跑的子 JVM，用户只能重启电脑。</p>
     */
    private void killTree(Process process) {
        if (!process.isAlive()) {
            return;
        }
        long pid = process.pid();
        if (OsUtils.isWindows()) {
            try {
                Process killer = new ProcessBuilder("taskkill", "/F", "/T", "/PID", Long.toString(pid))
                        .redirectErrorStream(true).start();
                killer.getInputStream().readAllBytes();
                if (killer.waitFor(10, TimeUnit.SECONDS)) {
                    return;
                }
                log.warn("taskkill 未在 10 秒内结束（pid {}），改用 destroyForcibly", pid);
            } catch (IOException e) {
                log.warn("taskkill 结束安装器进程树失败（pid {}）：{}", pid, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        process.destroyForcibly();
    }

    /**
     * 安装器执行后扫描新增的版本目录，定位它实际产出的版本 id。
     * <p>不依赖对版本命名规则的猜测：不同 Forge / NeoForge 版本的命名并不统一。</p>
     */
    private String detectProducedVersion(Set<String> after,
                                         Set<String> before,
                                         LoaderType loaderType,
                                         String mcVersion,
                                         String loaderVersion) {
        List<String> added = new ArrayList<>(after);
        added.removeAll(before);
        if (added.size() == 1) {
            return added.get(0);
        }
        if (!added.isEmpty()) {
            // 多个新增时优先选择名称同时包含加载器版本与游戏版本的目录
            for (String id : added) {
                if (id.contains(loaderVersion) && id.contains(mcVersion)) {
                    return id;
                }
            }
            return added.get(0);
        }
        // 未新增（可能是重复安装或就地升级）：在既有版本中寻找匹配项
        for (String id : after) {
            if (id.contains(loaderVersion) && id.contains(mcVersion)
                    && id.toLowerCase(java.util.Locale.ROOT).contains(loaderType.id())) {
                return id;
            }
        }
        return null;
    }

    private Set<String> listVersionIds() {
        Set<String> ids = new LinkedHashSet<>();
        Path versionsDir = paths.versionsDir();
        if (!Files.isDirectory(versionsDir)) {
            return ids;
        }
        try (var stream = Files.list(versionsDir)) {
            stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .forEach(ids::add);
        } catch (IOException e) {
            log.debug("列出版本目录失败: {}", e.getMessage());
        }
        return ids;
    }

    /**
     * 把安装器产出的补丁客户端 jar 就位到 {@code versions/<id>/<id>.jar}（幂等）。
     *
     * <p>Forge / NeoForge 的安装器把打过补丁的客户端 jar 放在 {@code libraries} 下，
     * 而 Forge 的启动参数里有 {@code -DignoreList=...,forge-,${version_name}.jar} ——
     * 它期望补丁 jar 位于版本目录。不搬过去的话，类路径里会同时出现原版 jar 与补丁 jar，
     * 两者都导出 {@code net.minecraft.*}，模块系统直接报 ResolutionException
     * （现象是「进程启动后 0 秒退出、退出码 1」，日志里只有一行看不出原因的异常）。
     * HMCL / PCL 等启动器同样会做这一步。</p>
     *
     * <p>做成 public static 是为了让**启动流程也能补**：加载器装好之后才升级上来的实例、
     * 或历史版本装出来的实例，版本目录里同样缺这个 jar。</p>
     *
     * @return 本次是否做了搬运
     */
    public static boolean adoptPatchedClientJar(GamePaths paths, String versionId) {
        Path target = paths.versionJar(versionId);
        if (Files.isRegularFile(target)) {
            return false;
        }
        List<Path> found = new ArrayList<>();
        for (String vendor : List.of("net/minecraftforge", "net/neoforged")) {
            Path root = paths.librariesDir().resolve(vendor);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var stream = Files.walk(root, 6)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith("-client.jar"))
                        .forEach(found::add);
            } catch (IOException e) {
                log.debug("搜索补丁客户端 jar 失败: {}", e.getMessage());
            }
        }
        if (found.isEmpty()) {
            return false;
        }
        found.sort(java.util.Comparator.comparing(Path::toString));
        Path source = found.get(found.size() - 1);
        try {
            FileUtils.ensureParent(target);
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("补丁客户端 jar 已就位：{} -> {}", source, target);
            return true;
        } catch (IOException e) {
            log.warn("搬运补丁客户端 jar 失败（可能导致启动失败）：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 用本地下载器补齐安装器遗漏的运行库。
     * <p>同时顺带修正「安装器指向官方仓库、而用户需要走镜像」的场景。</p>
     */
    private DownloadReport repairMissingLibraries(String versionId, ProgressListener listener) {
        try {
            Path versionJson = paths.versionJson(versionId);
            if (!Files.isRegularFile(versionJson)) {
                return DownloadReportWrapper.empty();
            }
            // 走「本地优先 + 继承链合并」的计划：加载器版本只是增量描述，
            // 只按它自己算会漏掉原版的运行库与客户端 jar
            InstallPlan plan = vanillaInstaller.plan(versionId, RuleEvaluator.defaultFeatures());
            if (plan.coreItems().isEmpty()) {
                return DownloadReportWrapper.empty();
            }
            int missing = 0;
            for (DownloadItem item : plan.coreItems()) {
                if (!DownloadManager.isUpToDate(item)) {
                    missing++;
                }
            }
            if (missing == 0) {
                return DownloadReportWrapper.empty();
            }
            listener.onMessage("补齐 " + missing + " 个缺失的运行库");
            return downloads.downloadAll(plan.coreItems(), listener);
        } catch (IOException e) {
            log.warn("补齐运行库失败（不影响已完成的安装）: {}", e.getMessage());
            return DownloadReportWrapper.empty();
        }
    }

    // ==================================================================
    // OptiFine：官方安装器为交互式，无法无头安装
    // ==================================================================

    /**
     * OptiFine 的官方安装器只有图形界面，没有无头安装参数。
     * <p>因此这里只负责下载并给出明确指引：用户双击执行一次安装器即可；
     * 或者改用「Forge/Fabric + OptiFine 模组」的方式，把 OptiFine 作为普通模组安装。</p>
     */
    private LoaderInstallResult downloadOptiFineInstaller(String mcVersion,
                                                          String loaderVersion,
                                                          ProgressListener listener,
                                                          long start) throws IOException {
        listener.onStage(I18n.t("install.stage.loader"));

        String url = "https://optifine.net/adloadx?f=OptiFine_" + loaderVersion + ".jar";
        Path workDir = paths.versionWorkDir("optifine-" + loaderVersion);
        FileUtils.ensureDir(workDir);
        Path installer = workDir.resolve("OptiFine_" + loaderVersion + ".jar");

        listener.onMessage("下载 OptiFine 安装器（官方安装器仅提供图形界面，需要手动执行一次）");
        DownloadReport report = downloads.downloadAll(List.of(
                DownloadItem.of(installer, url, "", -1L, DownloadTargetType.LOADER_INSTALLER).asOptional()), listener);

        if (!Files.isRegularFile(installer)) {
            throw new IOException("OptiFine 安装器下载失败。OptiFine 官方分发需要人工确认下载页，"
                    + "建议改用「Forge/Fabric + OptiFine 模组」的方式安装。");
        }

        throw new IOException("OptiFine 安装器已下载到 " + installer
                + "。官方安装器没有无头模式，请手动执行一次完成安装；"
                + "或改用 Forge/Fabric 加载器并以模组形式安装 OptiFine。");
    }

    // ==================================================================
    // 公共辅助
    // ==================================================================

    /** 确保原版版本已安装（安装器需要原版客户端 jar 才能完成补丁）。 */
    private void ensureBaseVersion(String mcVersion, ProgressListener listener) throws IOException {
        if (Files.isRegularFile(paths.versionJson(mcVersion))) {
            return;
        }
        log.info("原版 {} 尚未安装，先执行原版安装", mcVersion);
        listener.onMessage("目标版本 " + mcVersion + " 尚未安装，先安装原版");
        InstallResult base = vanillaInstaller.install(mcVersion, listener);
        if (!base.success()) {
            throw new IOException("原版 " + mcVersion + " 安装未完全成功，无法继续安装加载器");
        }
    }

    /** 读取本地版本 JSON，供上层展示或后续启动使用。 */
    public VersionDetail readInstalledVersion(String versionId) throws IOException {
        Path json = paths.versionJson(versionId);
        if (!Files.isRegularFile(json)) {
            throw new IOException("版本未安装: " + versionId);
        }
        return VersionManifestService.readLocal(json);
    }

    /** 该加载器版本是否为稳定版列表中的推荐项。 */
    public List<LoaderMetaService.LoaderVersion> recommended(LoaderType loaderType, String mcVersion) throws IOException {
        List<LoaderMetaService.LoaderVersion> all = loaderMeta.listVersions(loaderType, mcVersion);
        List<LoaderMetaService.LoaderVersion> stable = new ArrayList<>();
        for (LoaderMetaService.LoaderVersion version : all) {
            if (version.stable()) {
                stable.add(version);
            }
        }
        return stable.isEmpty() ? all : stable;
    }

    /** 汇总辅助：把多个阶段的下载统计合并为一份结果。 */
    private static final class DownloadReportWrapper {

        static DownloadReport empty() {
            return new DownloadReport(0, 0, 0, 0, 0L, 0L, List.of());
        }

        static DownloadReport merge(List<DownloadReport> reports, long elapsedMillis) {
            int total = 0;
            int downloaded = 0;
            int skipped = 0;
            int failed = 0;
            long bytes = 0L;
            List<DownloadReport.Failure> failures = new ArrayList<>();
            for (DownloadReport report : reports) {
                total += report.total();
                downloaded += report.downloaded();
                skipped += report.skipped();
                failed += report.failed();
                bytes += report.bytes();
                failures.addAll(report.failures());
            }
            return new DownloadReport(total, downloaded, skipped, failed, bytes, elapsedMillis, List.copyOf(failures));
        }
    }
}
