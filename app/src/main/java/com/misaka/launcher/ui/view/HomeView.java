package com.misaka.launcher.ui.view;

import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.install.InstallResult;
import com.misaka.launcher.core.launch.LaunchPresetFactory;
import com.misaka.launcher.core.meta.JavaVersionRule;
import com.misaka.launcher.core.model.LoaderType;
import com.misaka.launcher.core.util.FileUtils;
import com.misaka.launcher.ui.component.Icons;
import com.misaka.launcher.ui.component.Ui;
import com.misaka.launcher.ui.shell.AppShell;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主页。
 * <p>布局采用非对称英雄区：左侧是当前实例主卡片与启动动作，右侧是统计卡片，
 * 底部是「启动前流水线」步骤链 —— 让用户在点击启动前就能看到会发生什么。</p>
 */
public final class HomeView extends ScrollPane {

    private final AppShell shell;

    // 这些标签的内容长度不可控（实例名、游戏目录路径），统一包成可压缩：
    // 否则窗口变窄时它们会把右侧的启动按钮区挤出边界
    private final Label instanceName = Ui.shrinkable(Ui.label("", "page-title"));
    private final HBox badges = new HBox(6);
    private final Label gameDirLabel = Ui.shrinkable(Ui.label("", "mute-2"));
    private final Label versionHint = Ui.shrinkable(Ui.label("", "muted"));

    private final Label memoryValue = Ui.shrinkable(Ui.label("—", "card-value"));
    private final Label lastLaunchValue = Ui.shrinkable(Ui.label("—", "card-value"));
    private final Label playTimeValue = Ui.shrinkable(Ui.label("—", "card-value"));
    private final Label modsValue = Ui.shrinkable(Ui.label("—", "card-value"));

    private final Map<String, Label> stepChips = new LinkedHashMap<>();

    /** 主页的启动按钮：当前实例正被后台任务改动（安装 / 同步）时置灰。 */
    private Button launchButton;

    public HomeView(AppShell shell) {
        this.shell = shell;

        VBox content = new VBox(16);
        // 语言入口只在标题栏右上角，首页不再重复摆一排按钮
        content.getChildren().addAll(buildHero(), buildStats(), buildPipeline());
        Ui.mountScrollable(this, content, Ui.CONTENT_MAX_WIDTH);

        refresh();
    }

    // ------------------------------------------------------------------
    // 区块
    // ------------------------------------------------------------------

    private Node buildHero() {
        VBox left = new VBox(8);
        left.getChildren().addAll(instanceName, badges, gameDirLabel, versionHint);

        launchButton = Ui.primaryButton("action.launch", event -> {
            if (shell.launchHandler() != null) {
                shell.launchHandler().run();
            } else {
                shell.toast().show(I18n.t("error.versionMissing"));
            }
        }, Icons.createFilled(Icons.PLAY, 15, "icon-fill-accent"));
        launchButton.setPrefWidth(168);
        launchButton.setPrefHeight(46);

        Button switchInstance = Ui.ghostButton("instance.switch",
                event -> shell.navigate("instances"), Icons.create(Icons.INSTANCE, 15));
        Button logs = Ui.ghostButton("nav.logs",
                event -> shell.navigate("logs"), Icons.create(Icons.LOGS, 15));

        VBox actions = new VBox(10, launchButton, switchInstance, logs);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox hero = new HBox(20, left, Ui.grow(), actions);
        hero.setAlignment(Pos.CENTER_LEFT);
        VBox card = Ui.heroCard(0, hero);
        HBox.setHgrow(left, Priority.ALWAYS);
        return card;
    }

    private Node buildStats() {
        HBox row = new HBox(14,
                statCard("home.stats.memory", memoryValue, Icons.SETTINGS),
                statCard("home.stats.lastLaunch", lastLaunchValue, Icons.REFRESH),
                statCard("home.stats.playTime", playTimeValue, Icons.LOGS),
                statCard("home.stats.mods", modsValue, Icons.FOLDER));
        row.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));
        return row;
    }

    private Node statCard(String titleKey, Label value, String icon) {
        HBox header = new HBox(8, Icons.create(icon, 15), Ui.i18n(titleKey, "muted"));
        header.setAlignment(Pos.CENTER_LEFT);
        VBox card = Ui.glassCard(8, header, value);
        card.setPrefWidth(240);
        return card;
    }

    private Node buildPipeline() {
        // 用 FlowPane 而不是 HBox：窗口变窄时步骤链会自动换行，
        // 而不是把末尾的步骤挤出可视区域
        FlowPane chain = new FlowPane(8, 8);
        chain.setAlignment(Pos.CENTER_LEFT);
        for (String key : PIPELINE_STEPS) {
            Label chip = Ui.i18n(key, "step-chip");
            stepChips.put(key, chip);
            chain.getChildren().add(chip);
            if (!key.equals(PIPELINE_STEPS[PIPELINE_STEPS.length - 1])) {
                chain.getChildren().add(Ui.muted("›"));
            }
        }

        Button check = Ui.ghostButton("action.refresh", event -> runCheck(), Icons.create(Icons.REFRESH, 14));
        HBox header = new HBox(8, Ui.i18n("home.pipeline", "section-title"), Ui.grow(), check);
        header.setAlignment(Pos.CENTER_LEFT);

        return Ui.glassCard(12, header, chain);
    }

    private static final String[] PIPELINE_STEPS = {
            "home.pipeline.verify", "home.pipeline.java", "home.pipeline.sync",
            "home.pipeline.update", "home.pipeline.launch"
    };

    // ------------------------------------------------------------------
    // 刷新与检查
    // ------------------------------------------------------------------

    /** 重新读取配置并刷新展示。 */
    public void refresh() {
        LauncherConfig config = shell.services().config();
        LauncherConfig.InstanceEntry instance = shell.services().instances().current();

        if (instance == null) {
            instanceName.setText(I18n.t("instance.empty"));
            badges.getChildren().clear();
            gameDirLabel.setText("");
            versionHint.setText(I18n.t("home.noVersion"));
            memoryValue.setText("—");
            lastLaunchValue.setText("—");
            playTimeValue.setText("—");
            modsValue.setText("—");
            updateLaunchAvailability(instance);
            refreshPipeline(instance);
            return;
        }

        instanceName.setText(instance.name);
        fillBadges(instance);

        GamePaths paths = shell.services().instances().gamePaths(instance);
        gameDirLabel.setText(paths.gameDir().toString());
        gameDirLabel.setTooltip(Ui.tooltip(paths.gameDir().toString()));

        // 正在被占用的实例没有「就绪」可言：此时说「就绪」会让用户以为可以启动
        boolean busy = shell.tasks().isInstanceBusy(instance.id);
        boolean installed = shell.services().instances().isLaunchable(instance);
        versionHint.setText(busy ? I18n.t("status.working")
                : installed ? I18n.t("status.ready") : I18n.t("error.versionMissing"));

        LaunchPresetFactory.Preset preset = shell.services().launchPreset(
                instance.minMemoryMb, instance.maxMemoryMb);
        memoryValue.setText(preset.maxMemoryMb() + " MB");
        lastLaunchValue.setText(Ui.formatRelative(instance.lastLaunchedAt));
        playTimeValue.setText(Ui.formatDuration(instance.playTimeSeconds));

        long modCount = countMods(paths);
        modsValue.setText(String.valueOf(modCount));

        updateLaunchAvailability(instance);
        refreshPipeline(instance);
    }

    /**
     * 当前实例正被后台任务改动（安装游戏 / 加载器 / 模组、同步模组）时把「启动游戏」置灰。
     *
     * <p>改到一半启动必然失败（版本 JSON 可能只写了一半、加载器补丁 jar 还没落地、
     * 同步正在删除/覆盖文件），与其让用户在流水线里等半天再看到一句报错，
     * 不如现在就把入口拦住。只按当前实例判定：别的实例在动不影响这里。</p>
     *
     * <p>启动器自更新期间同样置灰，且与实例无关：更新结束时会退出启动器，
     * 而退出路径会结束游戏进程。</p>
     */
    private void updateLaunchAvailability(LauncherConfig.InstanceEntry instance) {
        if (launchButton != null) {
            launchButton.setDisable(shell.selfUpdating()
                    || (instance != null && shell.tasks().isInstanceBusy(instance.id)));
        }
    }

    /**
     * 版本徽标：{@code Minecraft-<版本>} + 加载器（有才显示）。
     *
     * <p><b>不显示「原版」</b>：那只是加载器字段缺省时的说法，不是有效信息，
     * 还会让人误以为它是一个加载器。没有加载器时只保留版本徽标。</p>
     */
    private void fillBadges(LauncherConfig.InstanceEntry entry) {
        badges.getChildren().clear();
        if (entry == null || entry.versionId == null || entry.versionId.isBlank()) {
            badges.getChildren().add(Ui.badge(I18n.t("home.noVersion"), "badge-accent"));
            return;
        }
        String mc = entry.mcVersion == null || entry.mcVersion.isBlank()
                ? entry.versionId : entry.mcVersion;
        badges.getChildren().add(Ui.badge("Minecraft-" + mc, "badge-accent"));

        LoaderType loader = LoaderType.fromId(entry.loaderType);
        if (!loader.isVanilla()) {
            String text = loader.displayName() + (entry.loaderVersion == null || entry.loaderVersion.isBlank()
                    ? "" : " " + entry.loaderVersion);
            badges.getChildren().add(Ui.badge(text, "badge-info"));
        }
        if (entry.optifineVersion != null && !entry.optifineVersion.isBlank()) {
            badges.getChildren().add(Ui.badge("OptiFine " + entry.optifineVersion, "badge-info"));
        }
    }

    private long countMods(GamePaths paths) {
        Path modsDir = paths.modsDir();
        if (!Files.isDirectory(modsDir)) {
            return 0;
        }
        return FileUtils.listFiles(modsDir).stream()
                .filter(path -> "jar".equals(FileUtils.extensionOf(path)) || "jar.disabled".equals(
                        path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)))
                .count();
    }

    /** 依据当前配置预估每个步骤的状态（无需发起网络请求）。 */
    private void refreshPipeline(LauncherConfig.InstanceEntry instance) {
        LauncherConfig config = shell.services().config();
        boolean hasInstance = instance != null;
        String versionId = hasInstance ? instance.versionId : "";

        boolean installed = hasInstance && shell.services().instances().isLaunchable(instance);
        int requiredJava = JavaVersionRule.requiredMajorVersion(versionId);
        boolean javaReady = shell.services().java().isReady(requiredJava);
        // 用 core 的单一判定：总开关与「启动游戏前自动执行」都成立才算这一环会跑
        boolean syncEnabled = config.sync.autoSyncActive() && hasInstance;
        boolean updateCheck = config.launch.checkModUpdatesOnLaunch;

        mark("home.pipeline.verify", installed ? "done" : "failed");
        mark("home.pipeline.java", javaReady ? "done" : (installed ? "failed" : ""));
        mark("home.pipeline.sync", syncEnabled ? "done" : "");
        mark("home.pipeline.update", updateCheck ? "done" : "");
        mark("home.pipeline.launch", installed && javaReady ? "active" : "");
    }

    private void mark(String key, String state) {
        Label chip = stepChips.get(key);
        if (chip == null) {
            return;
        }
        chip.getStyleClass().removeAll("done", "active", "failed");
        if (state != null && !state.isBlank()) {
            chip.getStyleClass().add(state);
        }
    }

    /** 执行一次启动条件检查（后台线程）。 */
    private void runCheck() {
        LauncherConfig.InstanceEntry instance = shell.services().instances().current();
        if (instance == null || instance.versionId == null || instance.versionId.isBlank()) {
            shell.toast().show(I18n.t("error.versionMissing"));
            return;
        }
        GamePaths paths = shell.services().instances().gamePaths(instance);
        int requiredJava = JavaVersionRule.requiredMajorVersion(instance.versionId);

        shell.tasks().run("home.pipeline.verify", listener -> {
            InstallResult.IntegrityReport report =
                    shell.services().vanillaInstaller(paths).verify(instance.versionId, null);
            listener.onMessage(report.complete()
                    ? "文件完整度 100%"
                    : "缺失 " + report.missing() + " 个文件（完整度 "
                    + String.format("%.1f%%", report.completeness() * 100) + "）");
            if (!shell.services().java().isReady(requiredJava)) {
                listener.onMessage(I18n.t("java.missing", requiredJava));
            }
        }, result -> {
            shell.toast().show(result.success()
                    ? I18n.t("status.done") : I18n.t("status.failed"));
            refresh();
        });
    }
}
