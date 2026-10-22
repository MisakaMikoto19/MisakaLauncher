package com.misaka.launcher.app.update;

import com.misaka.launcher.app.AppInfo;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.task.ProgressListener;
import com.misaka.launcher.core.task.ProgressListeners;
import com.misaka.launcher.core.update.SelfUpdateInstaller;
import com.misaka.launcher.core.update.UpdateInfo;
import com.misaka.launcher.core.update.ZooKeeperUpdateClient;
import com.misaka.launcher.core.util.NetDiagnostics;
import com.misaka.launcher.core.util.StallWatchdog;
import com.misaka.launcher.ui.component.Toast;
import com.misaka.launcher.ui.component.UpdateDialog;
import com.misaka.launcher.ui.shell.AppShell;
import com.misaka.launcher.ui.shell.TaskCenter;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 启动器自更新流程编排（检查 → 提示 → 下载 → 启动新版）。
 *
 * <p><b>线程约定</b>：检查在独立的守护线程池里做，绝不占用 JavaFX 线程 ——
 * 启动器打开时界面照常可用，服务端慢或宕机只会让日志里多一行提示；
 * 结果回到 JavaFX 线程后才弹对话框。下载走任务中心（{@code TaskCenter}），
 * 这样进度、取消、失败提示都复用既有的一套交互，而不是另起一套「孤儿进度条」。</p>
 *
 * <p><b>三个刻意的取舍</b>：</p>
 * <ul>
 *   <li>启动时的检查失败<b>不打扰用户</b>：自建服务器可能没开、可能断网，
 *       这与用户无关（手动检查才报错）；</li>
 *   <li>同一版本在一次运行里只提示一次，且「跳过」只跳过该版本（见
 *       {@link LauncherConfig.UpdateSettings#skippedVersion}）；</li>
 *   <li>有游戏在跑、或有安装 / 同步等作业在跑时拒绝更新：更新完要退出启动器，
 *       而退出启动器的正常路径会顺带结束游戏进程与所有后台任务
 *       （见 {@code MainWindow.shutdown}），玩家的一局游戏、装到一半的版本不该被一次更新终止。</li>
 * </ul>
 *
 * <p><b>更新期间的独占与可中止</b>：更新任务一启动就把 {@code TaskCenter} 切到
 * 「自更新中」，界面上的启动游戏 / 安装版本 / 同步模组等入口全部置灰，任务中心也会
 * 拒绝任何其它任务（见 {@link TaskCenter#runTask}）。但用户仍可在<b>下载阶段</b>
 * 点「停止」放弃这次更新继续用当前版本 —— 入口随即全部恢复；一旦下载完成、进入替换阶段，
 * 停止就没有意义了，停止按钮随之置灰。若下载连续 {@link #STALL_TIMEOUT} 没有任何进展，
 * 看门狗会自动中止并解锁界面，用户不必陪着对端的超时干等。</p>
 */
public final class LauncherUpdater {

    private static final Logger log = LoggerFactory.getLogger(LauncherUpdater.class);

    /** 进度回调节流间隔：界面进度条不需要每个数据块都刷一次。 */
    private static final long PROGRESS_THROTTLE_MILLIS = 120L;

    /**
     * 下载「无反应」的判定阈值。
     *
     * <p>比 {@code HttpUtils} 那条 60 秒的正文停滞阈值宽得多，因为它管的是另一件事：
     * 那条只管「正文读到一半不再来数据」，而「连接建立了、对端迟迟不回响应头」的请求
     * 会一直阻塞到 HttpClient 的请求超时（这里配的是 30 分钟）。5 分钟是「用户已经
     * 认定它卡死了」的量级，又不至于误杀慢速网络下的大包下载（有进展就不算卡死）。</p>
     */
    private static final Duration STALL_TIMEOUT = Duration.ofMinutes(5);

    /** 停滞检查的间隔：判定精度够用，又不至于让它变成热点。 */
    private static final Duration STALL_CHECK_INTERVAL = Duration.ofSeconds(15);

    /** 失败自检时每个地址的 TCP 探测超时：诊断不该比请求本身还慢。 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final AppShell shell;

    /**
     * 检查与看门狗用的线程池：单线程守护线程即可 —— 检查只发一两个请求，
     * 真正耗时的下载交给任务中心的线程池，这里只是定时看一眼有没有进展。
     */
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "misaka-update-check");
        thread.setDaemon(true);
        return thread;
    });

    /** 同一时刻只允许一次检查在飞，避免连点「立即检查」打出多份请求。 */
    private final AtomicBoolean checking = new AtomicBoolean();

    /** 本次运行已经提示过的版本号。 */
    private final Set<String> prompted = ConcurrentHashMap.newKeySet();

    public LauncherUpdater(AppShell shell) {
        this.shell = shell;
    }

    /** 启动时调用：清理上次更新的残留，并按配置在后台检查新版本。 */
    public void checkOnStartup() {
        // 上次更新留下的 .old 备份只能等到新进程起来才删得掉（旧进程退出前文件被占用），
        // 所以清理放在这里，而不是更新成功的那一刻
        executor.execute(() -> SelfUpdateInstaller.cleanupLeftovers());
        check(false);
    }

    /** 设置页「立即检查更新」：失败与「已是最新」都要明确告知用户。 */
    public void checkManually() {
        check(true);
    }

    /** 释放线程池（应用退出时调用）。 */
    public void shutdown() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // 内部流程
    // ------------------------------------------------------------------

    private void check(boolean manual) {
        LauncherConfig.UpdateSettings settings = shell.services().config().update;
        if (!manual && !settings.checkOnStartup) {
            log.debug("配置为不在启动时检查更新，跳过");
            return;
        }
        if (settings.serverUrl == null || settings.serverUrl.isBlank()) {
            if (manual) {
                shell.toast().show(I18n.t("update.toast.failed", I18n.t("update.error.notConfigured")),
                        Toast.Kind.ERROR);
            }
            return;
        }
        if (!checking.compareAndSet(false, true)) {
            log.info("已有一次更新检查在进行中，忽略本次请求");
            return;
        }
        String localVersion = AppInfo.version();
        executor.execute(() -> {
            try {
                // 产品标识与显示名称固定在更新客户端里（不来自配置）；平台/架构按当前系统推导，
                // 日志里一并打出来，现场排查「为什么挑了这个包」时不必再问用户机器是什么
                ZooKeeperUpdateClient client = client(settings);
                // 打的是规范化之后的地址（用户可能只填了主机名，协议是客户端补的）：
                // 「改了地址却没生效」这种现场，第一眼就要能看出到底连的是哪个地址
                log.info("检查 {}（{}）更新：本地 v{}，通道 {}，平台 {}/{}，服务 {}",
                        ZooKeeperUpdateClient.DISPLAY_NAME, ZooKeeperUpdateClient.PRODUCT, localVersion,
                        settings.channel, ZooKeeperUpdateClient.currentPlatform(),
                        ZooKeeperUpdateClient.currentArch(), client.baseUrl());
                ZooKeeperUpdateClient.CheckResult result =
                        client.check(localVersion, settings.channel);
                UpdateInfo info = result.info();
                if (result.ok() && info != null && info.hasUpdate()) {
                    // 说明要跟对话框一起出现，就在后台一起取回来，别让对话框先弹再补内容
                    info = withNotes(info, client);
                }
                UpdateInfo finalInfo = info;
                Platform.runLater(() -> handle(result, finalInfo, manual, settings, localVersion));
            } catch (Exception e) {
                // 异常的 message 经常是空的（HttpClient 建连失败会被自己的重试路径吞掉原因），
                // 直接拼 getMessage() 就得到「更新失败：null」—— 先用 NetDiagnostics 翻成人话
                String reason = NetDiagnostics.describe(e);
                log.warn("{} 检查更新失败：{}", ZooKeeperUpdateClient.DISPLAY_NAME, reason, e);
                if (manual) {
                    Platform.runLater(() -> shell.toast().show(
                            I18n.t("update.toast.failed", reason), Toast.Kind.ERROR));
                }
                // 现场自检：解析到哪些地址、哪些地址的 TCP 真的通。
                // 只在失败时做，正常检查不会为此多花一次网络往返
                log.info("更新服务连通性自检（{}）：\n{}", settings.serverUrl,
                        NetDiagnostics.probe(settings.serverUrl, PROBE_TIMEOUT));
            } finally {
                checking.set(false);
            }
        });
    }

    private void handle(ZooKeeperUpdateClient.CheckResult result, UpdateInfo info, boolean manual,
                        LauncherConfig.UpdateSettings settings, String localVersion) {
        if (result == null || !result.ok()) {
            // error 为空时也要说人话：否则提示里只剩「更新失败：」半句话
            String reason = result == null || result.error() == null || result.error().isBlank()
                    ? I18n.t("update.error.checkFailed") : result.error();
            if (manual) {
                shell.toast().show(I18n.t("update.toast.failed", reason), Toast.Kind.ERROR);
            } else {
                log.info("启动时检查更新未得到结论：{}", reason);
            }
            return;
        }
        if (info == null || !info.hasUpdate()) {
            if (manual) {
                shell.toast().show(I18n.t("update.toast.upToDate", localVersion), Toast.Kind.SUCCESS);
            }
            return;
        }
        if (!manual && info.latestVersion().equalsIgnoreCase(settings.skippedVersion)) {
            log.info("用户已跳过版本 {}，本次不再提示", info.latestLabel());
            return;
        }
        if (!prompted.add(info.latestVersion())) {
            log.debug("版本 {} 本次运行已提示过", info.latestLabel());
            return;
        }
        switch (UpdateDialog.show(shell, info)) {
            case UPDATE -> downloadAndRestart(info, settings);
            case SKIP -> {
                settings.skippedVersion = info.latestVersion();
                shell.services().configStore().save();
                shell.toast().show(I18n.t("update.toast.skipped", info.latestLabel()));
            }
            case LATER -> log.info("用户选择稍后更新到 {}", info.latestLabel());
        }
    }

    private void downloadAndRestart(UpdateInfo info, LauncherConfig.UpdateSettings settings) {
        if (shell.gameRunning()) {
            // 更新完成后要退出启动器，而退出路径会结束游戏进程 —— 不能拿玩家正在进行的游戏换一次更新
            log.info("有游戏正在运行，暂不更新启动器");
            shell.toast().show(I18n.t("update.toast.gameRunning"), Toast.Kind.WARN);
            return;
        }
        if (shell.tasks().hasRunningTasks()) {
            // 同理：安装版本 / 同步模组 / 下载跑到一半，会被「退出启动器」一起带走，
            // 留下的半成品（游戏目录写了一半、模组删了一半）比干脆不做更糟
            log.info("有后台任务正在进行，暂不更新启动器");
            shell.toast().show(I18n.t("update.toast.busy"), Toast.Kind.WARN);
            return;
        }
        SelfUpdateInstaller.Location location = SelfUpdateInstaller.locate();
        if (location.form() == SelfUpdateInstaller.Form.UNSUPPORTED) {
            // 没有可替换的文件（例如从 IDE 里跑的开发构建）：说明白原因，别让用户以为「点了没反应」
            String reason = I18n.t("update.error.unsupportedForm", location.description());
            log.warn("当前运行方式不支持自动更新：{}", location.description());
            shell.toast().show(I18n.t("update.toast.failed", reason), Toast.Kind.ERROR);
            return;
        }
        Path root = shell.services().context().paths().root();
        Path downloadDir = SelfUpdateInstaller.defaultDownloadDir(
                shell.services().context().paths().cacheDir());

        // 自更新期间其它入口一律置灰（见 AppShell.selfUpdating）；这一句必须在启动任务之前，
        // 否则界面会有「任务已经在跑、按钮还能点」的窗口
        shell.tasks().setSelfUpdatePhase(TaskCenter.SelfUpdatePhase.DOWNLOADING);
        // 看门狗判定卡死时就靠它让下载循环退出：HttpUtils 的停滞看门狗每 500ms 会问一次
        // isCancelled()，这里答「是」，阻塞中的 read() 会立刻断开
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> stallTick = new AtomicReference<>();

        TaskCenter.TaskHandle handle = shell.tasks().runSelfUpdate("update.task.download", listener -> {
            ProgressListener watched = ProgressListeners.composite(listener, new ProgressListener() {
                @Override
                public boolean isCancelled() {
                    return aborted.get();
                }
            });
            ZooKeeperUpdateClient client = client(settings);
            Path artifact = downloadDir.resolve(info.targetFileName());
            client.download(info, artifact, ProgressListeners.throttled(watched, PROGRESS_THROTTLE_MILLIS));
            // 包已下载且校验通过：从这一刻起停下来已经没有意义（旧版本文件即将被替换），
            // 所以切到「替换中」——界面上的停止入口据此置灰
            shell.tasks().setSelfUpdatePhase(TaskCenter.SelfUpdatePhase.INSTALLING);
            listener.onStage(I18n.t("update.stage.install"));
            SelfUpdateInstaller.Relaunch relaunch = SelfUpdateInstaller.install(artifact, root);
            SelfUpdateInstaller.start(relaunch);
            listener.onMessage(relaunch.description());
        }, result -> {
            // 先把自更新状态清掉：不管是用户停的、看门狗停的还是失败了，界面上的入口
            // 都要立刻恢复可用（成功时紧接着就退出进程，清不清都一样）
            shell.tasks().setSelfUpdatePhase(TaskCenter.SelfUpdatePhase.IDLE);
            cancelTick(stallTick);
            if (result.cancelled()) {
                if (aborted.get()) {
                    // 提示已经在自动停止时给过了，不再重复弹一条
                    log.info("启动器更新因长时间无进展被自动停止");
                    return;
                }
                log.info("用户停止了启动器更新，继续使用当前版本");
                shell.toast().show(I18n.t("update.toast.stopped"));
                return;
            }
            if (result.failed()) {
                String reason = NetDiagnostics.describe(result.error());
                log.warn("启动器更新失败：{}", reason, result.error());
                shell.toast().show(I18n.t("update.toast.failed", reason), Toast.Kind.ERROR);
                return;
            }
            // 新版本进程已经起来了，本进程收工：走 Platform.exit() 而不是 System.exit()，
            // 让 JavaFX 的 stop() 正常保存配置并关闭日志（此时没有游戏在跑，见上面的前置检查）
            log.info("新版启动器已启动，退出当前进程");
            Platform.exit();
        });
        if (handle == null) {
            // 兜底：runSelfUpdate 是自更新期间唯一被允许的任务，正常不会拒绝；
            // 真出现时必须把界面解锁，否则用户会卡在一个什么都点不动的启动器里
            log.error("自更新任务未能启动");
            shell.tasks().setSelfUpdatePhase(TaskCenter.SelfUpdatePhase.IDLE);
            shell.toast().show(I18n.t("update.toast.failed", I18n.t("status.failed")), Toast.Kind.ERROR);
            return;
        }
        watchForStall(handle, aborted, stallTick);
    }

    /**
     * 盯住自更新任务：连续 {@link #STALL_TIMEOUT} 没有任何进展就自动停止本次更新。
     *
     * <p>为什么需要它：HttpUtils 那条停滞看门狗只覆盖「正文读到一半不再来数据」，
     * 覆盖不到「连接建立了、对端迟迟不回响应头」——那段会一直阻塞到请求超时（30 分钟），
     * 界面上就是进度条不动、点停止也毫无反应。用户不需要为对端的问题陪着等半小时。</p>
     *
     * <p>字节进度与阶段文案都算「有进展」：替换阶段没有字节进度，但有阶段切换与提示文案。</p>
     */
    private void watchForStall(TaskCenter.TaskHandle handle, AtomicBoolean aborted,
                               AtomicReference<ScheduledFuture<?>> tickRef) {
        StallWatchdog watchdog = new StallWatchdog(STALL_TIMEOUT);
        handle.progressProperty().addListener((observable, was, now) -> watchdog.touch());
        handle.messageProperty().addListener((observable, was, now) -> watchdog.touch());

        tickRef.set(executor.scheduleWithFixedDelay(() -> {
            if (!watchdog.check()) {
                return;
            }
            log.warn("{} 更新已 {} 分钟没有任何进展，自动停止本次更新",
                    ZooKeeperUpdateClient.DISPLAY_NAME, STALL_TIMEOUT.toMinutes());
            // 置位之后下载循环会在下一个检查点（≤500ms）退出
            aborted.set(true);
            // 已经判定过卡死，没必要继续盯着；也不必等任务真正退出 ——
            // 阻塞在 HTTP 请求里的线程可能要等到对端超时才放行，界面不能跟着一起卡住
            cancelTick(tickRef);
            Platform.runLater(() -> {
                // 任务句柄的状态与提示都只允许在 JavaFX 线程上动
                shell.tasks().cancel(handle);
                shell.tasks().setSelfUpdatePhase(TaskCenter.SelfUpdatePhase.IDLE);
                shell.toast().show(I18n.t("update.toast.stalled", STALL_TIMEOUT.toMinutes()),
                        Toast.Kind.WARN);
            });
        }, STALL_CHECK_INTERVAL.toSeconds(), STALL_CHECK_INTERVAL.toSeconds(), TimeUnit.SECONDS));
    }

    private static void cancelTick(AtomicReference<ScheduledFuture<?>> tickRef) {
        ScheduledFuture<?> tick = tickRef.getAndSet(null);
        if (tick != null) {
            tick.cancel(false);
        }
    }

    private UpdateInfo withNotes(UpdateInfo info, ZooKeeperUpdateClient client) {
        String notes = client.fetchNotes(info.notesUrl());
        if (notes == null || notes.isBlank()) {
            return info;
        }
        return new UpdateInfo(info.product(), info.currentVersion(), info.hasUpdate(), info.latestVersion(),
                info.channel(), info.releasedAt(), notes, info.notesUrl(), info.downloadUrl(),
                info.fileName(), info.fileSize(), info.sha256(), info.chunkSize(), info.chunkCount());
    }

    private ZooKeeperUpdateClient client(LauncherConfig.UpdateSettings settings) {
        // 带上下载账号口令：服务端据此把下载记到账号名下，管理台才不会全是 (anonymous)
        return new ZooKeeperUpdateClient(settings.serverUrl, settings.apiKey,
                settings.timeoutSeconds, settings.downloadThreads, settings.downloadPassword);
    }
}
