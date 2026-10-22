package com.misaka.launcher.app;

import com.misaka.launcher.core.auth.AccountManager;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.install.InstallResult;
import com.misaka.launcher.core.install.LoaderInstaller;
import com.misaka.launcher.core.java.JavaRuntimeManager;
import com.misaka.launcher.core.launch.AccountInfo;
import com.misaka.launcher.core.launch.GpuPreference;
import com.misaka.launcher.core.launch.LaunchOptions;
import com.misaka.launcher.core.launch.LaunchPlan;
import com.misaka.launcher.core.launch.LaunchPresetFactory;
import com.misaka.launcher.core.launch.ProcessLauncher;
import com.misaka.launcher.core.launch.ResolvedVersion;
import com.misaka.launcher.core.launch.VersionResolver;
import com.misaka.launcher.core.log.CrashAdvisor;
import com.misaka.launcher.core.log.LogManager;
import com.misaka.launcher.core.meta.JavaVersionRule;
import com.misaka.launcher.core.model.LoaderType;
import com.misaka.launcher.core.mods.ModUpdateService;
import com.misaka.launcher.core.sync.SyncEngine;
import com.misaka.launcher.core.sync.SyncResult;
import com.misaka.launcher.core.task.ProgressListener;
import com.misaka.launcher.core.texture.AtlasTextureCompressor;
import com.misaka.launcher.core.texture.GameOptionsResourcePacks;
import com.misaka.launcher.core.texture.GpuTextureBudget;
import com.misaka.launcher.core.util.FileUtils;
import com.misaka.launcher.core.util.OsUtils;
import com.misaka.launcher.core.util.PathSafety;
import com.misaka.launcher.ui.component.CrashReportDialog;
import com.misaka.launcher.ui.component.Toast;
import com.misaka.launcher.ui.shell.AppShell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 启动前流水线与进程生命周期管理。
 *
 * <p>流水线阶段（与主页展示的步骤链一一对应）：</p>
 * <ol>
 *   <li><b>校验文件</b>：按需检查完整性，缺失则自动补齐（幂等，已最新的文件不会重新下载）；</li>
 *   <li><b>准备 Java</b>：按版本判定主版本；缺失且允许自动安装时下载安装；</li>
 *   <li><b>同步模组</b>：可选，连自有更新服务器比对并同步；</li>
 *   <li><b>检查更新</b>：可选，从官方源更新已装模组；</li>
 *   <li><b>构建命令行</b>：合并继承链、拼装类路径、替换占位符；</li>
 *   <li><b>启动进程</b>：拉起游戏并接管输出。</li>
 * </ol>
 *
 * <p><b>失败处理原则</b>：只有「校验/Java/命令行」失败才阻断启动；
 * 同步与模组更新属于可选增强，默认失败不阻断（可由用户改成阻断），
 * 避免因为一个第三方服务器不可用就完全玩不了游戏。</p>
 */
public final class LaunchPipeline {

    private static final Logger log = LoggerFactory.getLogger(LaunchPipeline.class);

    private final AppShell shell;

    private volatile Process process;
    private volatile String runningInstanceId;
    private volatile long launchedAt;
    /** 最近一次启动的游戏日志文件（崩溃时用于提取异常尾部）。 */
    private volatile Path lastGameLogFile;
    /**
     * 本次运行是否由用户点了「停止」结束。
     *
     * <p>必须有这个标志：主动结束走的是 {@code Process#destroy()}，进程退出码必然非零，
     * 与崩溃在退出码上无法区分 —— 不记录意图就会在用户自己关掉游戏后弹一个
     * 莫名其妙的「游戏崩溃」诊断。</p>
     */
    private volatile boolean userStopped;

    public LaunchPipeline(AppShell shell) {
        this.shell = shell;
    }

    // ==================================================================
    // 启动
    // ==================================================================

    /** 执行一次完整启动流程（在任何线程调用都安全，实际工作会在后台线程完成）。 */
    public void launch() {
        if (isRunning()) {
            shell.toast().show(I18n.t("action.launch") + "：已在运行", Toast.Kind.ERROR);
            return;
        }
        // 启动器正在自更新：更新结束时会退出启动器，而退出路径会结束游戏进程。
        // 界面上的启动入口此刻已置灰（启动栏 / 主页），这里兜住别的旁路
        if (shell.selfUpdating()) {
            shell.toast().show(I18n.t("update.gate.locked"), Toast.Kind.WARN);
            return;
        }
        LauncherConfig.InstanceEntry instance = shell.services().instances().current();
        if (instance == null) {
            shell.toast().show(I18n.t("instance.empty"), Toast.Kind.ERROR);
            return;
        }
        // 该实例正在被后台任务改动（安装游戏 / 加载器 / 模组、同步模组）时直接拦住。
        // 改到一半启动必然失败：版本 JSON 可能只写了一半、补丁 jar 还没落地、
        // 同步正在删除或覆盖文件。与其让用户等流水线跑半天再报一个莫名其妙的错，
        // 不如现在就说明白。界面上的启动入口此时已经置灰（LaunchBar / 主页），
        // 这里兜住快捷键之类的旁路。
        if (shell.tasks().isInstanceBusy(instance.id)) {
            shell.toast().show(I18n.t("instance.busy.launchBlocked", instance.name),
                    Toast.Kind.ERROR);
            return;
        }
        // 版本缺失不再直接拒绝：先在任务里自愈，必要时现场补齐。
        // 点「启动游戏」的期望就是「缺什么补什么」，而不是被一句「版本未安装」堵在门外
        // （安装因个别文件下载失败而中止时，版本 JSON 往往已经在盘上了）。

        GamePaths paths = shell.services().instances().gamePaths(instance);
        LauncherConfig config = shell.services().config();

        // 新的一次启动：清掉上一次「用户主动停止」的标记，否则本次真崩溃时
        // 会被当成主动结束而跳过诊断
        userStopped = false;

        // runLaunch：状态栏据此把启动按钮切到「启动中」；
        // 普通后台任务（下载/安装）不会触发该状态
        shell.tasks().runLaunch("home.pipeline.verify", listener -> {
            // 0) 版本就位（缺失时自愈，或现场安装）
            ensureVersionReady(instance, paths, listener);

            // 1) Java —— **必须早于加载器安装**：加载器安装会拉起 Forge 安装器这个
            //    独立的 JVM 进程，机器上没有对应主版本的 Java 时它会静默卡死
            //    （真实故障：没装 Java 11 的机器上装 1.16.5 + Forge，安装器卡住不动）。
            //    排在版本就位之后，是为了能读到版本 JSON 里权威的 javaVersion 字段。
            Path java = ensureJava(instance, listener);

            // 2) 加载器安装完整性（补丁 jar 就位；装了一半则补装）
            ensureLoaderInstallComplete(instance, paths, listener);

            // 3) 校验与修复
            verifyAndRepair(instance, paths, listener);

            // 4) 模组同步（可选）
            syncIfEnabled(instance, paths, listener);

            // 4) 模组官方源更新（可选）
            updateModsIfEnabled(instance, paths, listener);

            // 4.5) 高清贴图兼容（可选）：非 NVIDIA 显卡的图集上限只有 16384
            //      （N 卡 32768，容量差 4 倍），贴图总量超限的整合包会崩在拼接阶段。
            //      必须排在启动之前 —— 它要写出覆盖资源包并登记进 options.txt，
            //      游戏一旦开始读取就来不及了
            ensureTextureCompression(paths, config, listener);

            // 5) 账户（可能触发令牌续期）
            AccountInfo account = resolveAccount(listener);

            // 6) 构建并启动
            LaunchPlan plan = buildPlan(instance, paths, java, account, config);

            // 6.5) 独显优先（写 Windows 图形首选项，失败不影响启动）
            applyGpuPreference(plan, config, listener);

            listener.onStage(I18n.t("home.pipeline.launch"));
            listener.onMessage(LogManager.maskCommandLine(plan.command()));

            ProcessLauncher.StartResult started = ProcessLauncher.start(
                    plan, new GameProcessListenerImpl(instance, paths));
            this.process = started.process();
            this.runningInstanceId = instance.id;
            this.launchedAt = System.currentTimeMillis();
            this.lastGameLogFile = started.logFile();
        }, result -> {
            if (result.success()) {
                shell.toast().show(I18n.t("action.launch") + "：" + I18n.t("status.done"), Toast.Kind.SUCCESS);
            } else if (result.failed()) {
                shell.toast().show(String.valueOf(result.error() == null
                        ? I18n.t("status.failed") : result.error().getMessage()), Toast.Kind.ERROR);
            }
            shell.refreshShell();
        });
    }

    /** 结束正在运行的游戏。 */
    public void stop() {
        Process current = process;
        if (current == null) {
            return;
        }
        // 先记意图再杀进程：destroy() 产生的非零退出码与崩溃无法从退出码本身区分，
        // 这个标记是 onExit 里「不弹崩溃诊断」的唯一依据
        userStopped = true;
        ProcessLauncher.terminate(current);
        shell.toast().show(I18n.t("action.stop"));
    }

    public boolean isRunning() {
        Process current = process;
        return current != null && current.isAlive();
    }

    // ==================================================================
    // 各阶段
    // ==================================================================

    /**
     * 确保实例有可启动的版本：缺失时先按磁盘事实自愈，仍不够才现场安装。
     *
     * <p><b>为什么要放在启动流程里</b>：安装可能因个别文件下载失败（国内到官方库的连接
     * 会随机重置）而整体判定失败，此时版本 JSON 与客户端往往已经落盘，
     * 但实例的 {@code versionId} 可能是空的。直接弹「版本未安装」会让用户除了重下几百 MB
     * 别无选择 —— 而点「启动游戏」的合理期望就是「缺什么补什么」。</p>
     */
    private void ensureVersionReady(LauncherConfig.InstanceEntry instance, GamePaths paths,
                                    ProgressListener listener) throws IOException {
        if (shell.services().instances().isLaunchable(instance)) {
            return;
        }
        listener.onStage(I18n.t("home.pipeline.verify"));
        // 先按磁盘事实补齐版本与元信息（versionId / mcVersion / 加载器），
        // 补上了就说明版本其实可用，不必再走后面的重新安装
        if (shell.services().instances().syncVersionMetadata(instance)
                && shell.services().instances().isLaunchable(instance)) {
            listener.onMessage("已从游戏目录恢复版本：" + instance.versionId);
            return;
        }

        String mcVersion = instance.mcVersion == null || instance.mcVersion.isBlank()
                ? (instance.versionId == null ? "" : instance.versionId) : instance.mcVersion;
        if (mcVersion.isBlank()) {
            // 连该装哪个版本都不知道：这里只能明确拒绝，并让用户回实例页安装
            throw new IOException(I18n.t("error.versionMissing"));
        }

        LoaderType loader = LoaderType.fromId(instance.loaderType);
        boolean hasLoader = !loader.isVanilla()
                && instance.loaderVersion != null && !instance.loaderVersion.isBlank();
        listener.onStage(I18n.t("install.progress"));
        listener.onMessage("版本尚未安装完整，正在补齐：" + mcVersion);
        if (hasLoader) {
            com.misaka.launcher.core.install.LoaderInstallResult result = shell.services()
                    .loaderInstaller(paths)
                    .install(loader, mcVersion, instance.loaderVersion, listener);
            shell.services().instances().updateVersion(instance.id, result.versionId(), loader,
                    instance.loaderVersion, instance.optifineVersion);
        } else {
            InstallResult result = shell.services().vanillaInstaller(paths).install(mcVersion, listener);
            shell.services().instances().updateVersion(instance.id, result.versionId(),
                    LoaderType.VANILLA, "", instance.optifineVersion);
            if (!result.success()) {
                throw new IOException("安装未完全成功：" + result.downloadReport().summary());
            }
        }
    }

    /**
     * 确保加载器安装完整（幂等）。
     *
     * <p>Forge / NeoForge 需要 {@code versions/<id>/<id>.jar} 里放着它自己那版打过补丁的
     * 客户端 jar；缺失时类路径上会同时出现原版 jar 与补丁 jar，模块系统直接冲突
     * （进程启动后 0 秒退出）。</p>
     *
     * <p>三层处理：</p>
     * <ol>
     *   <li>补丁 jar 还在 libraries 下（安装器产出的原始位置）→ 搬运就位；</li>
     *   <li>版本目录与 libraries 里都没有 → <b>上次安装没跑完</b>。Forge 的 processors
     *       要联网取 mojmaps，网络一抖就会中断 —— 此时版本 JSON 已经在盘上，
     *       若不补装，实例会一直处于「可启动但必崩」的状态（真实故障：每次启动
     *       都以退出码 1 在 3 秒内退出）→ 重新执行安装补完它；</li>
     *   <li>都在 → 无需处理。</li>
     * </ol>
     */
    private void ensureLoaderInstallComplete(LauncherConfig.InstanceEntry instance, GamePaths paths,
                                             ProgressListener listener) throws IOException {
        LoaderType loader = LoaderType.fromId(instance.loaderType);
        if (loader.isVanilla() || instance.versionId == null || instance.versionId.isBlank()) {
            return;
        }
        Path versionJar = paths.versionJar(instance.versionId);
        if (Files.isRegularFile(versionJar)) {
            return;
        }
        if (LoaderInstaller.adoptPatchedClientJar(paths, instance.versionId)) {
            log.info("已为实例 {} 补齐加载器客户端 jar（{}）", instance.name, instance.versionId);
            return;
        }
        listener.onStage(I18n.t("install.stage.loader"));
        listener.onMessage("检测到上次 " + loader.displayName() + " 安装未完成，正在补装…");
        log.warn("实例 {} 的加载器安装不完整（缺 {}），重新执行安装", instance.name, versionJar.getFileName());
        String mcVersion = instance.mcVersion == null || instance.mcVersion.isBlank()
                ? instance.versionId : instance.mcVersion;
        var result = shell.services().loaderInstaller(paths)
                .install(loader, mcVersion, instance.loaderVersion, listener);
        shell.services().instances().updateVersion(instance.id, result.versionId(), loader,
                instance.loaderVersion, instance.optifineVersion);
    }

    /**
     * 让游戏进程优先使用独立显卡。
     *
     * <p>Java 进程无法通过导出 {@code NvOptimusEnablement} /
     * {@code AmdPowerXpressRequestHighPerformance} 之类的符号来声明「我要独显」——
     * 宿主 exe 是 {@code javaw.exe}，启动器无权改动它（需要重新链接或注入 DLL）。
     * 因此这里写 Windows 公开的图形首选项，与用户在「设置 → 显示 → 图形」里
     * 给 javaw.exe 选「高性能」是同一件事，NVIDIA 与 AMD 都由系统统一分发。</p>
     *
     * <p>整段都是尽力而为：写不进去只提示，绝不阻断启动。</p>
     */
    /**
     * 高清贴图兼容（幂等，可选）。
     *
     * <p><b>解决什么</b>：Minecraft 把方块与物品贴图拼进<b>一张</b>纹理（texture atlas），
     * 这张纹理的边长上限就是驱动报告的 {@code GL_MAX_TEXTURE_SIZE} —— NVIDIA 通常 32768，
     * AMD / Intel 通常只有 16384，容量整整差 4 倍。贴图总量超限的整合包在 A/I 卡上必然崩在
     * 拼接阶段（{@code StitcherException: Unable to fit}），在 N 卡上却能正常进。
     * 真实案例：一个 1.20.1 Forge 包有 10,709 张贴图、合计 3.26 亿像素，长边压到 512 后
     * 降到 1.20 亿，A 卡才进得去。</p>
     *
     * <p><b>压谁</b>：不靠目录白名单，而是读游戏自己的图集定义
     * （{@code assets/&lt;ns&gt;/atlases/&lt;名字&gt;.json}），因此以后装任何按官方方式声明图集的模组
     * 都会自动纳入；不占图集空间的贴图（例如实体、GUI）不再被白白压掉。
     * 逐图集分别定档位 —— 会崩的是「某一张图集塞不下」，不是全包总和。</p>
     *
     * <p><b>做法</b>：把塞不进去的大贴图等比缩小，写成一份覆盖资源包，并登记进 options.txt。
     * 绝不改 {@code mods/*.jar} —— 改了会破坏同步/校验，而且 mod 一升级就白改；
     * 资源包随时能在游戏的资源包界面里关掉。</p>
     *
     * <p><b>为什么必须排在启动之前</b>：游戏一开始读资源就来不及了。整段尽力而为，
     * 失败只记日志与提示，绝不阻断启动 —— 压缩只是「进得去」的补强。</p>
     */
    private void ensureTextureCompression(GamePaths paths, LauncherConfig config,
                                          ProgressListener listener) {
        if (!AtlasTextureCompressor.shouldCompress(config.launch.textureCompression)) {
            // 选了「关闭」：不只不再生成，还要把以前留下的覆盖撤掉，
            // 否则游戏里那份旧包会一直生效，等于关不掉
            AtlasTextureCompressor.removeGeneratedPack(paths);
            return;
        }
        try {
            listener.onStage(I18n.t("home.pipeline.texture"));
            AtlasTextureCompressor.Plan plan = AtlasTextureCompressor.plan(paths);
            if (!plan.needsCompression()) {
                if (!plan.scanComplete()) {
                    // 有来源没读到，估算必然偏低 —— 此时绝不下「装得下」的结论：
                    // 撤掉旧的覆盖包等于把客户机推回「下次启动崩在贴图拼接」的状态。
                    // 真出过：一个打不开的 jar 让 mods 目录的枚举提前结束，估算小了一个数量级
                    log.warn("贴图来源没能全部读到（{}），本次不判定「装得下」，保留已有覆盖包",
                            String.join("；", plan.scanFailures()));
                    listener.onMessage(I18n.t("home.pipeline.texture.incomplete",
                            String.join("；", plan.scanFailures())));
                    return;
                }
                // 装得下就别动玩家的画面：能装下还压，等于白白牺牲清晰度；
                // 顺手撤掉按更严档位留下的旧覆盖（整合包瘦身后会走到这里）
                log.info("各图集均未超预算（合计 {} M px / 预算 {} M px），跳过贴图压缩",
                        plan.rawPixels() / 1_000_000,
                        GpuTextureBudget.budgetPixels(GpuTextureBudget.MAX_TEXTURE_SIZE_NON_NVIDIA)
                                / 1_000_000);
                AtlasTextureCompressor.removeGeneratedPack(paths);
                return;
            }
            AtlasTextureCompressor.Result result = AtlasTextureCompressor.compress(paths, plan);
            listener.onMessage(I18n.t("home.pipeline.texture.done", result.cap(), result.compressed()));
            if (!result.changed()) {
                // 一张都没写出来时不能登记：那等于让游戏去找一个不存在的包，
                // 还会把「保护已生效」的假象留给下一个人
                log.warn("贴图兼容包没有写出任何文件（需覆盖 {} 张，实际扫描到 {} 张），不登记资源包",
                        plan.capByTexture().size(), result.scanned());
                listener.onMessage(I18n.t("home.pipeline.texture.failed",
                        "未写出任何覆盖文件"));
                return;
            }
            // 必须保持在 resourcePacks 列表的末尾：靠后的包覆盖靠前的包，
            // 而 mod_resources / tacz_resources 这类自动加入的包（含我们覆盖的那些原始大贴图）
            // 都排在用户包前面 —— 我们的包一旦不在末尾，压缩就白做了
            Path optionsFile = paths.gameDir().resolve("options.txt");
            boolean rewritten = GameOptionsResourcePacks.ensureLast(optionsFile,
                    AtlasTextureCompressor.packEntry());
            log.info("已把 {} 登记到 {}（{}）", AtlasTextureCompressor.packEntry(), optionsFile,
                    rewritten ? "本次改写了 resourcePacks 列表" : "原本已在末尾");
        } catch (Exception e) {
            // 必须带堆栈：这句 WARN 是客户机上唯一能说明「保护为什么没生效」的线索，
            // 只打 getMessage 会让现场无法定位（真实反馈：客户机照样崩，日志里什么都没有）
            log.warn("贴图兼容处理失败（忽略，不影响启动）", e);
            listener.onMessage(I18n.t("home.pipeline.texture.failed",
                    String.valueOf(e.getMessage())));
        }
    }

    private void applyGpuPreference(LaunchPlan plan, LauncherConfig config, ProgressListener listener) {
        if (!config.launch.preferHighPerformanceGpu || !GpuPreference.isSupported()) {
            return;
        }
        if (!GpuPreference.hasDiscreteGpu()) {
            log.info("未检测到独立显卡，跳过图形首选项设置");
            return;
        }
        Path executable = plan.javaExecutable();
        boolean applied = GpuPreference.preferHighPerformance(executable);
        listener.onMessage(applied
                ? "已请求由独立显卡渲染（" + executable.getFileName() + "）"
                : "未能写入独显图形首选项，可在系统「图形设置」里手动指定 javaw.exe");
    }

    /** 校验完整性并在缺失时补齐。 */
    private void verifyAndRepair(LauncherConfig.InstanceEntry instance, GamePaths paths,
                                 ProgressListener listener) throws IOException {
        if (!shell.services().config().launch.checkIntegrityOnLaunch) {
            listener.onMessage("已跳过完整性校验（可在设置中开启）");
            return;
        }
        listener.onStage(I18n.t("home.pipeline.verify"));
        InstallResult.IntegrityReport report = shell.services().vanillaInstaller(paths)
                .verify(instance.versionId, null);
        if (report.complete()) {
            listener.onMessage("文件完整（共 " + report.total() + " 项）");
            return;
        }
        listener.onMessage("缺失 " + report.missing() + " 个文件，正在补齐");
        InstallResult installed = shell.services().vanillaInstaller(paths).install(instance.versionId, listener);
        if (!installed.success()) {
            throw new IOException("补全文件失败：" + installed.downloadReport().summary());
        }
    }

    /**
     * 确保存在匹配的 Java 运行时；缺失时按配置自动安装。
     *
     * <p>选取顺序：实例显式指定的 Java 目录（用户在实例上选了「不同目录下的 Java」）
     * → 按所需主版本自动挑最合适的。指定目录失效时回退自动判定，
     * 不能因为一个失效路径就完全无法启动。</p>
     */
    private Path ensureJava(LauncherConfig.InstanceEntry instance, ProgressListener listener) throws IOException {
        listener.onStage(I18n.t("home.pipeline.java"));

        if (instance.javaPath != null && !instance.javaPath.isBlank()) {
            Optional<Path> configured =
                    JavaRuntimeManager.locateJavaExecutable(Path.of(instance.javaPath));
            if (configured.isPresent()) {
                listener.onMessage("使用实例指定的 Java：" + configured.get());
                return configured.get();
            }
            log.warn("实例指定的 Java 不可用，回退到自动判定: {}", instance.javaPath);
        }

        int required = requiredJavaVersion(instance);
        // 与「安装游戏」用的是同一个入口（LauncherServices#ensureJava）：
        // 缺失就自动装，装不了就给一句能照做的报错，不再各写一套
        Path executable = shell.services().ensureJava(required,
                shell.services().config().java.autoInstall, listener);
        listener.onMessage("使用 " + JavaVersionRule.describe(required) + "：" + executable);
        return executable;
    }

    /**
     * 实例所需 Java 主版本：实例级覆盖优先，否则读本地版本 JSON 的官方字段。
     *
     * <p>实例级覆盖的存在意义：部分老模组 / 加载器只在特定 Java 上能跑，
     * 自动判定无法覆盖这种例外，必须让用户能钉死版本。</p>
     */
    private int requiredJavaVersion(LauncherConfig.InstanceEntry instance) {
        if (instance.javaMajorVersion > 0) {
            return instance.javaMajorVersion;
        }
        // 用单独记录的 Minecraft 版本做估算：加载器装好后 versionId 是复合串
        String versionId = instance.mcVersion == null || instance.mcVersion.isBlank()
                ? instance.versionId : instance.mcVersion;
        try {
            GamePaths paths = shell.services().instances().gamePaths(instance);
            ResolvedVersion resolved = VersionResolver.resolve(paths, instance.versionId);
            return JavaVersionRule.requiredMajorVersion(resolved.detail(), versionId);
        } catch (IOException e) {
            // 版本 JSON 缺失时退化为按版本号估算，避免因为读不到描述就彻底无法启动
            log.debug("读取版本描述失败，按版本号估算 Java：{}", e.getMessage());
            return JavaVersionRule.requiredMajorVersion(versionId);
        }
    }

    private void syncIfEnabled(LauncherConfig.InstanceEntry instance, GamePaths paths,
                               ProgressListener listener) throws IOException {
        LauncherConfig.SyncSettings sync = shell.services().config().sync;
        // 跳过也必须说清楚原因：以前这里是静默 return，用户勾了开关却看不到任何反馈，
        // 只能得出「这个功能没用」的结论（实际是总开关没开）
        if (!sync.enabled) {
            listener.onMessage("已跳过模组同步（未启用，可在「设置 → 模组同步」打开总开关）");
            return;
        }
        if (!sync.autoSyncBeforeLaunch) {
            listener.onMessage("已跳过模组同步（未勾选「启动游戏前自动执行」）");
            return;
        }
        listener.onStage(I18n.t("home.pipeline.sync"));
        try {
            SyncResult result = SyncEngine.of(sync).sync(paths.gameDir(), listener);
            listener.onMessage(result.summary());
            if (!result.success()) {
                handleOptionalFailure(sync.blockLaunchOnFailure,
                        "同步存在失败项：" + result.failures().size() + " 个");
            }
        } catch (IOException e) {
            handleOptionalFailure(sync.blockLaunchOnFailure, "同步失败：" + e.getMessage());
        }
    }

    private void updateModsIfEnabled(LauncherConfig.InstanceEntry instance, GamePaths paths,
                                     ProgressListener listener) throws IOException {
        if (!shell.services().config().launch.checkModUpdatesOnLaunch) {
            return;
        }
        listener.onStage(I18n.t("home.pipeline.update"));
        try {
            String gameVersion = baseVersionOf(instance);
            String loader = LoaderType.fromId(instance.loaderType).isVanilla()
                    ? "" : instance.loaderType;
            List<ModUpdateService.ModUpdate> updates = shell.services().modUpdates()
                    .check(instance.id, paths, gameVersion, loader, listener);
            if (updates.isEmpty()) {
                listener.onMessage(I18n.t("download.upToDate"));
                return;
            }
            ModUpdateService.UpdateOutcome outcome = shell.services().modUpdates().apply(updates, listener);
            listener.onMessage(outcome.summary());
        } catch (IOException e) {
            // 模组更新失败绝不应阻断启动：游戏本身仍可运行
            listener.onMessage("检查模组更新失败（已忽略）：" + e.getMessage());
        }
    }

    private AccountInfo resolveAccount(ProgressListener listener) throws IOException {
        AccountManager accounts = shell.services().accounts();
        LauncherConfig.AccountEntry entry = accounts.current();
        if (entry == null) {
            throw new IOException(I18n.t("error.accountMissing"));
        }
        listener.onStage(I18n.t("account.title"));
        LauncherConfig.AccountEntry valid = accounts.ensureValid(entry);
        return accounts.toLaunchInfo(valid);
    }

    private LaunchPlan buildPlan(LauncherConfig.InstanceEntry instance,
                                 GamePaths paths,
                                 Path java,
                                 AccountInfo account,
                                 LauncherConfig config) throws IOException {
        ResolvedVersion resolved = VersionResolver.resolve(paths, instance.versionId);

        LaunchPresetFactory.Preset preset = shell.services().launchPreset(
                instance.minMemoryMb, instance.maxMemoryMb);
        List<String> jvmArgs = new java.util.ArrayList<>();
        if (config.launch.autoJvmArgs) {
            jvmArgs.addAll(preset.jvmArgs());
        }
        // 设置里的启动参数是全局配置：实例没有单独配置时才用全局，
        // 有则整份替换（不是追加 —— 追加会让「改了实例参数却仍带着全局参数」难以理解）
        List<String> userJvm = (instance.jvmArgsOverride == null || instance.jvmArgsOverride.isEmpty())
                ? config.launch.jvmArgs : instance.jvmArgsOverride;
        jvmArgs.addAll(userJvm);

        List<String> gameArgs = new java.util.ArrayList<>(config.launch.gameArgs);
        gameArgs.addAll(instance.gameArgs);

        LaunchOptions options = new LaunchOptions(account, java,
                preset.minMemoryMb(), preset.maxMemoryMb(),
                jvmArgs, gameArgs,
                config.launch.windowWidth, config.launch.windowHeight, config.launch.fullscreen);

        Path gameLog = config.log.logGameOutput ? LogManager.newGameLogFile(instance.id) : null;
        return shell.services().launchBuilder().build(resolved, paths, options, gameLog);
    }

    private void handleOptionalFailure(boolean block, String message) throws IOException {
        if (block) {
            throw new IOException(message);
        }
        log.warn("可选阶段失败但不阻断启动：{}", message);
    }

    /** 从版本 id 提取纯游戏版本号（{@code 1.20.1-forge-47.2.0 → 1.20.1}）。 */
    private static String baseVersionOf(LauncherConfig.InstanceEntry instance) {
        String versionId = instance.versionId;
        if (versionId == null || versionId.isBlank()) {
            return "";
        }
        String[] parts = versionId.split("-");
        return parts.length > 0 && parts[0].matches("\\d+\\.\\d+(\\.\\d+)?") ? parts[0] : versionId;
    }

    // ==================================================================

    /** 游戏进程事件：把输出转发给日志页，退出后结算游玩时长；崩溃时给出诊断建议。 */
    private final class GameProcessListenerImpl implements ProcessLauncher.GameProcessListener {

        private final LauncherConfig.InstanceEntry instance;
        private final GamePaths paths;

        private GameProcessListenerImpl(LauncherConfig.InstanceEntry instance, GamePaths paths) {
            this.instance = instance;
            this.paths = paths;
        }

        @Override
        public void onOutput(String line) {
            // 游戏输出直接进启动器日志页：崩溃时用户不必去翻游戏日志文件
            //（logs/game/ 里同时会留一份完整文件）
            log.info("[游戏] {}", line);
        }

        @Override
        public void onExit(int exitCode, long durationMillis) {
            process = null;
            long playSeconds = durationMillis / 1000L;
            Path gameLog = lastGameLogFile;
            // 「用户点了停止」同样是非零退出码，但它不是崩溃 —— 拿它去弹诊断
            // 只会让用户以为启动器坏了
            boolean crashed = exitCode != 0 && !userStopped;
            Path crashReport = crashed ? findCrashReport() : null;
            CrashAdvisor.Diagnosis diagnosis = crashed ? diagnoseCrash(crashReport, gameLog) : null;

            if (crashed) {
                // 崩溃时把游戏输出的异常尾部捞进启动器日志：只提示「退出码 -1」毫无价值，
                // 真正的原因（缺库/类路径/模组冲突）都在游戏输出的末尾。
                // onExit 在输出泵线程上调用，这里做文件读取不会碰 FX 线程
                String tail = FileUtils.readTailLines(gameLog, 64 * 1024, 15);
                if (!tail.isBlank()) {
                    log.error("游戏异常退出（退出码 {}），输出末尾：\n{}", exitCode, tail);
                }
            }

            javafx.application.Platform.runLater(() -> {
                shell.services().instances().recordLaunch(instance.id, null, playSeconds);
                shell.refreshShell();
                for (String page : List.of("home", "logs")) {
                    shell.invalidatePage(page);
                }
                log.info("游戏结束：实例={} 退出码={} 时长={}s", instance.name, exitCode, playSeconds);
                if (crashed) {
                    // 给结论与下一步，而不是一句「退出码 1」加一个日志路径
                    CrashReportDialog.show(shell, instance.name, exitCode, crashReport, gameLog, diagnosis);
                } else if (exitCode == 0) {
                    shell.toast().show(I18n.t("logs.title") + "：" + I18n.t("status.done"),
                            Toast.Kind.SUCCESS);
                } else {
                    // 用户主动结束：这是一次正常操作，不该显示成失败
                    shell.toast().show(I18n.t("action.stop"), Toast.Kind.INFO);
                }
            });
        }

        /**
         * 找出本次启动期间新产生的崩溃报告。
         *
         * <p>报告由游戏（含 Forge / NeoForge）写在 {@code crash-reports/} 下。用
         * 「修改时间不早于本次启动」过滤，否则会把上一次的崩溃报告当成这一次的原因
         * —— 那比不给建议更糟。</p>
         */
        private Path findCrashReport() {
            Path dir = paths.crashReportsDir();
            if (!Files.isDirectory(dir)) {
                return null;
            }
            // 留 2 秒容差：文件系统时间戳精度与时钟回拨都可能让写入时间略微早于 launchedAt
            long since = launchedAt - 2000L;
            Path newest = null;
            long newestTime = Long.MIN_VALUE;
            try (var stream = Files.list(dir)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    long modified = Files.getLastModifiedTime(file).toMillis();
                    if (modified >= since && modified > newestTime) {
                        newestTime = modified;
                        newest = file;
                    }
                }
            } catch (IOException e) {
                log.debug("扫描崩溃报告失败：{}", e.getMessage());
            }
            return newest;
        }
    }

    /**
     * 汇总诊断文本并交给 {@link CrashAdvisor} 判定。
     *
     * <p>崩溃报告取<b>头部</b>：{@code Description:} 与首个异常栈就在前几十行，
     * 而报告尾部往往是完整的模组与系统信息（可以有数 MB）。
     * 游戏日志取<b>尾部</b>：那里的异常才是进程退出前最后的输出。</p>
     */
    private static CrashAdvisor.Diagnosis diagnoseCrash(Path crashReport, Path gameLog) {
        StringBuilder text = new StringBuilder();
        if (crashReport != null) {
            text.append(FileUtils.readHeadLines(crashReport, 256 * 1024, 400)).append('\n');
        }
        if (gameLog != null) {
            text.append(FileUtils.readTailLines(gameLog, 128 * 1024, 400));
        }
        return CrashAdvisor.analyze(text.toString());
    }

    /** 供界面展示的当前状态摘要。 */
    public String describeState() {
        Process current = process;
        if (current == null || !current.isAlive()) {
            return I18n.t("status.ready");
        }
        long seconds = (System.currentTimeMillis() - launchedAt) / 1000L;
        return "运行中 pid=" + current.pid() + " · " + seconds + "s"
                + (runningInstanceId == null ? "" : " · " + runningInstanceId);
    }

    /** 校验游戏目录可写 —— 提前暴露权限问题，避免启动到一半才失败。 */
    public boolean checkGameDirectoryWritable(LauncherConfig.InstanceEntry instance) {
        try {
            GamePaths paths = shell.services().instances().gamePaths(instance);
            Files.createDirectories(paths.gameDir());
            Path probe = paths.gameDir().resolve(".misaka-write-test");
            Files.writeString(probe, "test");
            Files.deleteIfExists(probe);
            return PathSafety.isInside(paths.gameDir(), probe);
        } catch (IOException e) {
            log.warn("游戏目录不可写：{}", e.getMessage());
            return false;
        }
    }

    /** 平台提示：Windows 上首次运行可能触发防火墙，这里提前说明以减少困惑。 */
    public String platformHint() {
        return OsUtils.isWindows() ? "首次启动如出现防火墙提示，请允许 Java 访问" : "";
    }
}
