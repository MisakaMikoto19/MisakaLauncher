package com.misaka.launcher.ui.shell;

import com.misaka.launcher.app.AppInfo;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.model.LoaderType;
import com.misaka.launcher.ui.component.Icons;
import com.misaka.launcher.ui.component.Toast;
import com.misaka.launcher.ui.component.Ui;
import javafx.collections.SetChangeListener;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * 底部启动栏。
 *
 * <p>参照 HMCL 的底部信息架构，但把「启动」做成一枚常驻的悬浮卡片：</p>
 * <ul>
 *   <li>左侧：只有一条启动器标识（应用名 + 版本号），不再堆放实例 / Java / 内存 / 同步胶囊；</li>
 *   <li>中间：后台任务的阶段文案与进度条，任何任务都会自动在此呈现；</li>
 *   <li>右侧：双行启动卡片（标题 + 当前实例与版本），并带一个下拉箭头可直接切换实例。</li>
 * </ul>
 *
 * <p>为什么把版本信息放进启动卡片：用户点击启动前最关心的就是「我将启动哪一个实例」，
 * 放在按钮上比放在角落更容易被看到。</p>
 *
 * <p>为什么左侧只留版本：那四项状态以前各占一枚胶囊，底栏被塞得很满，而它们在主页
 * （分配内存、累计时长等）与启动卡片（当前实例与版本）里本来就能看到 ——
 * 状态栏只保留最需要被确认的一条：这是哪个版本的启动器。</p>
 */
public final class LaunchBar extends HBox {

    private static final double HEIGHT = 56;

    private final AppShell shell;

    /** 状态栏唯一的一条信息：应用名 + 版本号（版本取自 jar 清单，见 {@link AppInfo#title()}）。 */
    private final Label versionLabel = Ui.label(AppInfo.title(), "mute-2");
    private final Label stageLabel = Ui.label("", "mute-2");
    private final ProgressBar progressBar = new ProgressBar();
    /** 展开后的并行任务面板：向上悬浮在启动栏之上（IntelliJ 式）。 */
    private final VBox taskPanel = new VBox(10);
    private final VBox taskRows = new VBox(8);
    /**
     * 面板展开但没有任何任务时的占位文案。
     *
     * <p>面板允许在「零任务」时展开：否则展开按钮会随任务消失，用户看着面板自己关掉，
     * 会以为按钮坏了下一次又自己打开 —— 而现在只有用户点箭头才会变。</p>
     */
    private final Label emptyHint = Ui.label(I18n.t("task.empty"), "mute-2");
    /**
     * 面板标题。
     *
     * <p>做成字段而不是局部变量：面板是常驻节点（不随页面重建），语言切换时
     * 必须显式重设文案，否则切到法语后这里还写着「后台任务」。</p>
     */
    private final Label taskHeader = Ui.label(I18n.t("task.title"), "section-title");
    private final Button expandButton;
    /** 展开按钮的提示：收起时若有任务失败，换成「点开看原因」。 */
    private final Tooltip expandTooltip = Ui.tooltip(I18n.t("task.expand"));
    /** 面板默认收起：只有一个任务时进度条已经够用。 */
    private boolean taskPanelExpanded;

    private final Label launchTitle = Ui.label(I18n.t("action.launch"), "launch-card-title");
    private final Label launchSubtitle = Ui.label("", "launch-card-subtitle");
    private final HBox launchCard = new HBox(12);
    /** 自更新期间挂在启动卡片上的说明气泡：置灰的按钮要能说清「为什么点不动」。 */
    private final Tooltip gateTooltip = Ui.tooltip(I18n.t("update.gate.locked"));
    private final Button stopButton;
    /** 任务面板里的「全部停止」：需要按自更新阶段置灰，所以在 {@link #buildTaskPanel()} 里构造。 */
    private Button stopAllButton;

    public LaunchBar(AppShell shell) {
        this.shell = shell;

        getStyleClass().add("launch-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(12);
        setPrefHeight(HEIGHT);
        setMinHeight(HEIGHT);
        setMaxHeight(HEIGHT);

        // ---- 状态栏左侧：只有启动器版本 ----
        // 允许收缩省略：窗口很窄时宁可省略版本号，也不能把右侧的启动卡片挤掉
        versionLabel.setMinWidth(0);
        versionLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

        HBox status = new HBox(versionLabel);
        status.setAlignment(Pos.CENTER_LEFT);
        status.setMinWidth(0);

        // ---- 进度与阶段 ----
        progressBar.setPrefWidth(160);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressBar.setVisible(false);
        progressBar.setManaged(false);

        stageLabel.setMinWidth(0);
        stageLabel.setMaxWidth(240);
        stageLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

        stopButton = Ui.ghostButton("action.stop", event -> {
            shell.tasks().cancel();
            if (shell.stopHandler() != null) {
                shell.stopHandler().run();
            }
        }, Icons.create(Icons.STOP, 14));
        // 单独一个样式类：停止按钮的尺寸与悬停反馈要自己说了算。
        // 只挂 ghost-button 的话会被「启动栏内收窄内边距」那类规则压回去，
        // 浅色主题下更是连按钮边界都看不见
        stopButton.getStyleClass().add("stop-button");
        stopButton.setVisible(false);
        stopButton.setManaged(false);

        // 展开并行任务面板（IntelliJ 式）：多于一个任务时进度条只显示波浪，
        // 各自的进度在这里逐个查看
        expandButton = new Button();
        expandButton.setGraphic(Icons.create(Icons.CHEVRON_UP, 13));
        // 不复用 .launch-card-caret：那套配色是给紫青渐变卡片上的箭头当「前景色」用的，
        // 而这颗按钮站在启动栏的普通背景上 —— 浅色主题下会变成白箭头 + 白圆底 + 白页面
        expandButton.getStyleClass().add("task-expand");
        expandButton.setTooltip(expandTooltip);
        // 常显：即使没有任务也要能点开/收起（理由是「面板开合只由用户决定」，
        // 按钮跟着任务消失会让这个约定看起来没生效）
        expandButton.setOnAction(event -> setTaskPanelExpanded(!taskPanelExpanded));

        HBox progress = new HBox(10, stageLabel, progressBar, stopButton, expandButton);
        progress.setAlignment(Pos.CENTER_RIGHT);
        progress.setMinWidth(Region.USE_PREF_SIZE);

        // ---- 启动卡片 ----
        StackPane playIcon = new StackPane(Icons.createFilled(Icons.PLAY, 14, "icon-fill-accent"));
        playIcon.getStyleClass().add("launch-card-icon");
        playIcon.setMinSize(30, 30);
        playIcon.setPrefSize(30, 30);
        playIcon.setMaxSize(30, 30);

        VBox texts = new VBox(1, launchTitle, launchSubtitle);
        texts.setAlignment(Pos.CENTER_LEFT);
        texts.setMinWidth(0);

        Button switchInstance = new Button();
        switchInstance.setGraphic(Icons.create(Icons.CHEVRON_DOWN, 14));
        switchInstance.getStyleClass().add("launch-card-caret");
        switchInstance.setTooltip(Ui.tooltip(I18n.t("instance.quickSwitch")));
        switchInstance.setOnAction(event -> showInstanceMenu(switchInstance));

        launchCard.getChildren().addAll(playIcon, texts, switchInstance);
        launchCard.setAlignment(Pos.CENTER_LEFT);
        launchCard.getStyleClass().add("launch-card");
        launchCard.setMinWidth(Region.USE_PREF_SIZE);
        launchCard.setOnMouseClicked(event -> {
            if (shell.launchHandler() != null) {
                shell.launchHandler().run();
            }
        });
        Ui.hoverLift(launchCard, 2);

        getChildren().addAll(status, Ui.grow(), progress, launchCard);

        // ---- 任务状态自动联动 ----
        // 进度条与「停止」跟着「有任务在跑」（下载/安装同样要能看到进度与取消）
        shell.tasks().busyProperty().addListener((observable, oldValue, busy) -> {
            progressBar.setVisible(busy);
            progressBar.setManaged(busy);
            stopButton.setVisible(busy);
            stopButton.setManaged(busy);
        });
        // 启动按钮的可用性由两件事共同决定：
        //  ① 已经有「启动游戏」任务在跑（launching）—— 防重复点击；
        //  ② 当前实例正在被后台任务改动（安装游戏 / 加载器 / 模组、同步模组）——
        //     这时启动必然失败。
        // 注意只按「当前实例」判定：别的实例在装不该影响这个实例的启动。
        // 文案仍只跟 launching 走：下载/安装同样是忙碌，但显示成「启动中」会让用户
        // 误以为游戏已经在运行了
        shell.tasks().launchingProperty().addListener(
                (observable, oldValue, launching) -> updateLaunchAvailability());
        shell.tasks().busyInstanceIds().addListener(
                (SetChangeListener<String>) change -> updateLaunchAvailability());
        shell.tasks().progressProperty().addListener((observable, oldValue, value) ->
                progressBar.setProgress(value.doubleValue() < 0
                        ? ProgressBar.INDETERMINATE_PROGRESS : value.doubleValue()));
        shell.tasks().messageProperty().addListener((observable, oldValue, value) ->
                stageLabel.setText(truncate(value)));

        // ---- 并行任务面板：向上悬浮，逐任务显示进度 ----
        buildTaskPanel();
        shell.tasks().tasksProperty().addListener((observable, oldValue, newValue) -> rebuildTaskRows());

        refresh();
    }

    /** 组装并行任务面板（浮动在启动栏上方，由 MainWindow 放进悬浮层）。 */
    private void buildTaskPanel() {
        taskPanel.getStyleClass().add("task-panel");
        taskPanel.setAlignment(Pos.TOP_LEFT);

        taskHeader.setText(I18n.t("task.title"));
        // 面板内的「全部停止」：展开详情后仍要能一键停掉全部，
        // 否则多任务时得逐个点（底部那颗总停止按钮在面板展开时容易被面板挡住视线）
        stopAllButton = Ui.ghostButtonText(I18n.t("task.stopAll"), event -> shell.tasks().cancel());
        stopAllButton.getStyleClass().add("stop-button");
        stopAllButton.setVisible(false);
        stopAllButton.setManaged(false);
        shell.tasks().busyProperty().addListener((observable, was, busy) -> {
            stopAllButton.setVisible(busy);
            stopAllButton.setManaged(busy);
        });
        HBox headerRow = Ui.hbox(8, taskHeader, Ui.grow(), stopAllButton);

        // 空状态占位与任务行并列：面板允许在「零任务」时展开
        emptyHint.setWrapText(true);
        taskPanel.getChildren().addAll(headerRow, taskRows, emptyHint);
        rebuildTaskRows();
        updateTaskPanelVisibility();
    }

    /** 面板里的行随任务增删重建；行内的进度、文案、按钮通过句柄属性监听保持最新。 */
    private void rebuildTaskRows() {
        taskRows.getChildren().clear();
        for (TaskCenter.TaskHandle handle : shell.tasks().getTasks()) {
            taskRows.getChildren().add(taskRow(handle));
        }
        updateTaskPanelVisibility();
    }

    /**
     * 一行一个任务：左侧标题 / 进度条 / 具体计数 / 阶段文案，右侧操作按钮。
     *
     * <p>按钮随状态切换：运行中给「停止」，已结束给「重试」与「忽略」。
     * 之所以每个任务都要能单独停：并行跑着「装游戏 + 同步模组」时，
     * 用户想停的往往只是其中一个，总停止会把另一个也一起杀掉。</p>
     */
    private Node taskRow(TaskCenter.TaskHandle handle) {
        Label title = Ui.label(handle.getTitle(), "section-title");
        Label stateBadge = Ui.badge("", "");
        stateBadge.setVisible(false);
        stateBadge.setManaged(false);
        HBox titleRow = Ui.hbox(6, title, stateBadge);

        ProgressBar bar = new ProgressBar();
        bar.setPrefWidth(220);
        bar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(bar, Priority.ALWAYS);
        bar.setProgress(handle.getProgress() < 0 ? ProgressBar.INDETERMINATE_PROGRESS : handle.getProgress());
        handle.progressProperty().addListener((observable, was, now) ->
                bar.setProgress(now.doubleValue() < 0
                        ? ProgressBar.INDETERMINATE_PROGRESS : now.doubleValue()));

        // 具体数量（12/300）：只有进度条不够直观，用户想知道的是「还剩几个文件」。
        // 没有项数概念的任务（如校验、启动）不显示这一格
        Label count = Ui.mono("", "mute-2");
        count.setVisible(false);
        count.setManaged(false);
        Label percent = Ui.mono("", "mute-2");
        HBox barRow = Ui.hbox(8, bar, percent, count);

        Runnable updateNumbers = () -> {
            int total = handle.getTotalItems();
            boolean hasItems = total > 0;
            count.setVisible(hasItems);
            count.setManaged(hasItems);
            if (hasItems) {
                count.setText(handle.getCompletedItems() + "/" + total);
            }
            double value = handle.getProgress();
            percent.setText(value < 0 ? "" : Math.round(value * 100) + "%");
        };
        updateNumbers.run();
        handle.completedItemsProperty().addListener((observable, was, now) -> updateNumbers.run());
        handle.totalItemsProperty().addListener((observable, was, now) -> updateNumbers.run());
        handle.progressProperty().addListener((observable, was, now) -> updateNumbers.run());

        Label message = Ui.label("", "mute-2");
        message.setWrapText(true);
        message.setMaxWidth(340);
        message.setText(truncate(handle.getMessage(), 80));
        handle.messageProperty().addListener((observable, was, now) ->
                message.setText(truncate(now, 80)));

        VBox info = new VBox(4, titleRow, barRow, message);
        HBox.setHgrow(info, Priority.ALWAYS);

        VBox actions = new VBox(4);
        actions.setAlignment(Pos.TOP_RIGHT);
        Runnable refreshRow = () -> {
            updateStateBadge(handle, stateBadge);
            actions.getChildren().setAll(taskActions(handle));
        };
        refreshRow.run();
        handle.runningProperty().addListener((observable, was, running) -> {
            refreshRow.run();
            // 任务结束时刷新展开按钮的提示态：失败要让收起状态看得出来，
            // 但**不替用户展开** —— 面板开不开只由用户点那颗箭头决定
            updateTaskPanelVisibility();
        });

        HBox row = new HBox(10, info, actions);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("task-row");
        return row;
    }

    /** 已结束任务的右侧按钮。 */
    private Node taskActions(TaskCenter.TaskHandle handle) {
        if (handle.isRunning()) {
            Button stop = Ui.iconButton(Icons.STOP, 12, "task.stopOne",
                    event -> shell.tasks().cancel(handle));
            stop.getStyleClass().add("stop-button");
            // 替换启动器自身文件的那一段不能停：包已下载完、旧版本即将被覆盖，
            // 停下既回不到干净的旧版本、也拿不到新版本（此时面板里正在跑的也只有这一个任务，
            // 自更新期间其它任务一律被拒）
            stop.setDisable(shell.selfUpdatePhase() == TaskCenter.SelfUpdatePhase.INSTALLING);
            return stop;
        }
        VBox box = new VBox(4);
        box.setAlignment(Pos.TOP_RIGHT);
        if (handle.canRestart()) {
            box.getChildren().add(Ui.ghostButtonText(I18n.t("task.retry"),
                    event -> shell.tasks().restart(handle)));
        }
        box.getChildren().add(Ui.ghostButtonText(I18n.t("task.dismiss"),
                event -> shell.tasks().dismiss(handle)));
        return box;
    }

    private void updateStateBadge(TaskCenter.TaskHandle handle, Label badge) {
        String text = "";
        String kind = "info";
        switch (handle.getState()) {
            case CANCELLED -> {
                text = I18n.t("task.stopped");
                kind = "warn";
            }
            case FAILED -> {
                text = I18n.t("task.failed");
                kind = "error";
            }
            default -> {
            }
        }
        badge.setText(text);
        badge.setVisible(!text.isEmpty());
        badge.setManaged(!text.isEmpty());
        if (!text.isEmpty()) {
            badge.getStyleClass().removeIf(style -> style.startsWith("badge-"));
            badge.getStyleClass().add("badge-" + kind);
        }
    }

    /**
     * 面板的显隐<b>只由用户意图决定</b>，与任务数量无关；展开按钮常显。
     *
     * <p>之前是 {@code show = taskPanelExpanded && hasTasks}：任务清空后面板自己消失，
     * 但 {@code taskPanelExpanded} 仍停在 true —— 于是下一次有后台任务时面板「自动」又开了。
     * 用户看到的现象就是「点忽略关掉后，下个任务又自己弹出来」。
     * 现在零任务时面板照样能开着，里面显示「无后台运行任务」，状态不再有隐藏的第二份。</p>
     */
    private void updateTaskPanelVisibility() {
        boolean hasTasks = !shell.tasks().getTasks().isEmpty();
        boolean show = taskPanelExpanded;
        taskPanel.setVisible(show);
        taskPanel.setManaged(show);
        // 常显：没有任务时也允许展开/收起
        expandButton.setVisible(true);
        expandButton.setManaged(true);
        emptyHint.setVisible(!hasTasks);
        emptyHint.setManaged(!hasTasks);

        // 失败不再自动展开面板，但收起的启动栏只会显示「就绪」，失败就彻底无声了：
        // 把展开按钮换成警示配色并改提示文案，让用户知道该点开看看。
        // 只改外观 —— 展开与否仍然只由用户点击决定。
        boolean alert = !taskPanelExpanded && hasFailedTask();
        expandButton.getStyleClass().remove("task-expand-alert");
        if (alert) {
            expandButton.getStyleClass().add("task-expand-alert");
        }
        // 提示文案要跟着开合状态走：展开着还说「展开」会让人以为按钮没生效
        expandTooltip.setText(I18n.t(alert ? "task.expandAlert"
                : taskPanelExpanded ? "task.collapse" : "task.expand"));
    }

    /** 是否有已结束但未被处理的失败任务（重试 / 忽略之后就不再提示）。 */
    private boolean hasFailedTask() {
        for (TaskCenter.TaskHandle handle : shell.tasks().getTasks()) {
            if (handle.getState() == TaskCenter.TaskState.FAILED) {
                return true;
            }
        }
        return false;
    }

    private void setTaskPanelExpanded(boolean expanded) {
        if (taskPanelExpanded == expanded) {
            return;
        }
        taskPanelExpanded = expanded;
        expandButton.setGraphic(Icons.create(
                expanded ? Icons.CHEVRON_DOWN : Icons.CHEVRON_UP, 13));
        updateTaskPanelVisibility();
    }

    /** 并行任务面板（MainWindow 把它放进悬浮层，显示在启动栏上方）。 */
    public Region taskPanel() {
        return taskPanel;
    }

    /**
     * 刷新状态摘要（实例、账户、配置变化后由主窗口调用）。
     *
     * <p>状态栏本身只有版本号（构建期确定，运行期不会变），因此这里真正要更新的是
     * 启动卡片上的实例信息与启动按钮的可用性。</p>
     */
    public void refresh() {
        LauncherConfig.InstanceEntry instance = shell.services().instances().current();
        launchSubtitle.setText(describeInstance(instance));
        // 实例切换后「当前实例是否正被安装」可能完全变了，可用性必须跟着重算
        updateLaunchAvailability();
        // 自更新阶段变化后，停止入口的可用性与任务行里的按钮都要跟着变 —— 行是按状态现建的，
        // 重建一次最省事（面板本来就是靠句柄属性刷新的，重建不丢用户状态）
        updateStopAvailability();
        rebuildTaskRows();
    }

    /** 语言切换后刷新静态文案。 */
    public void refreshTexts() {
        // 气泡文案是自己构造的（Tooltip 不受 Ui.i18n 绑定），语言切换时要单独跟上
        gateTooltip.setText(I18n.t("update.gate.locked"));
        // 任务面板是常驻节点：它不参与页面重建，所以这里必须逐个重设文案，
        // 否则切到英语/法语后，面板标题与空状态仍然是中文（截图里那一处）
        taskHeader.setText(I18n.t("task.title"));
        emptyHint.setText(I18n.t("task.empty"));
        stopAllButton.setText(I18n.t("task.stopAll"));
        stopButton.setText(I18n.t("action.stop"));
        updateTaskPanelVisibility();
        updateLaunchAvailability();
        refresh();
    }

    /** 主行动按钮的文案（启动 / 安装等场景可切换）。 */
    public void setPrimaryText(String i18nKey) {
        launchTitle.setText(I18n.t(i18nKey));
    }

    /** 供外部（启动流水线）临时禁用主按钮。 */
    public void setPrimaryDisabled(boolean disabled) {
        launchCard.setDisable(disabled);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /** 启动卡片副标题：版本 + 加载器，缺任一则退化为实例名。 */
    private static String describeInstance(LauncherConfig.InstanceEntry instance) {
        if (instance == null) {
            return I18n.t("instance.empty");
        }
        StringBuilder sb = new StringBuilder();
        if (instance.versionId != null && !instance.versionId.isBlank()) {
            sb.append(instance.versionId);
        }
        LoaderType loader = LoaderType.fromId(instance.loaderType);
        if (loader != LoaderType.VANILLA) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(loader.displayName());
            if (instance.loaderVersion != null && !instance.loaderVersion.isBlank()) {
                sb.append(' ').append(instance.loaderVersion);
            }
        }
        return sb.length() == 0 ? instance.name : sb.toString();
    }

    /**
     * 按「是否正在启动」与「当前实例是否正被安装」决定主按钮的可用性与文案。
     *
     * <p>只有在真正启动游戏时才把标题切换为「启动中」：下载、安装、扫描等后台任务
     * 同样是「忙碌」，但它们不是启动 —— 显示成「启动中」会让用户误判游戏已在运行。
     * 当前实例被占用时则显示为「处理中」（复用 {@code status.working}），
     * 让置灰有明确的解释。</p>
     */
    private void updateLaunchAvailability() {
        boolean launching = shell.tasks().launchingProperty().get();
        boolean updating = shell.selfUpdating();
        LauncherConfig.InstanceEntry instance = shell.services().instances().current();
        boolean instanceBusy = instance != null && shell.tasks().isInstanceBusy(instance.id);

        launchCard.setDisable(launching || instanceBusy || updating);
        // 置灰的主按钮要能说明原因：更新期间悬停给出解释，更新结束后撤掉
        if (updating) {
            Tooltip.install(launchCard, gateTooltip);
        } else {
            Tooltip.uninstall(launchCard, gateTooltip);
        }
        if (launching) {
            launchTitle.setText(I18n.t("status.running"));
        } else if (updating) {
            // 自更新期间启动游戏必然被「退出启动器」带走，主按钮直接把处境写在脸上，
            // 而不是只留一颗点不动的灰色卡片让用户猜
            launchTitle.setText(I18n.t("update.status.updating"));
        } else {
            launchTitle.setText(I18n.t(instanceBusy ? "status.working" : "action.launch"));
        }
    }

    /**
     * 停止入口的可用性。
     *
     * <p>自更新<b>下载阶段</b>必须能停：用户可以放弃这次更新继续用当前版本。
     * 进入替换阶段后就不能停了 —— 包已下载校验完、旧版本文件即将被替换，
     * 这时停下既回不到干净的旧版本，也拿不到新版本。</p>
     */
    private void updateStopAvailability() {
        boolean locked = shell.selfUpdatePhase() == TaskCenter.SelfUpdatePhase.INSTALLING;
        stopButton.setDisable(locked);
        stopAllButton.setDisable(locked);
    }

    /** 实例快速切换菜单。 */
    private void showInstanceMenu(Region anchor) {
        List<LauncherConfig.InstanceEntry> instances = shell.services().instances().list();
        ContextMenu menu = new ContextMenu();
        menu.setStyle(com.misaka.launcher.ui.theme.Fonts.rootStyleFragment());
        String currentId = shell.services().config().currentInstanceId;
        if (instances.isEmpty()) {
            MenuItem empty = new MenuItem(I18n.t("instance.empty"));
            empty.setDisable(true);
            menu.getItems().add(empty);
        }
        for (LauncherConfig.InstanceEntry entry : instances) {
            String version = entry.versionId == null || entry.versionId.isBlank()
                    ? I18n.t("home.noVersion") : entry.versionId;
            MenuItem item = new MenuItem(entry.name + " · " + version);
            item.setOnAction(event -> switchInstance(entry));
            if (entry.id.equals(currentId)) {
                // 当前实例直接标出来，省去用户对比
                item.setText("✓ " + item.getText());
            }
            menu.getItems().add(item);
        }
        menu.show(anchor, Side.BOTTOM, 0, 6);
    }

    private void switchInstance(LauncherConfig.InstanceEntry entry) {
        if (entry.id.equals(shell.services().config().currentInstanceId)) {
            return;
        }
        try {
            shell.services().instances().switchTo(entry.id);
        } catch (RuntimeException e) {
            shell.toast().show(I18n.t("error.instanceSwitch", e.getMessage()), Toast.Kind.ERROR);
            return;
        }
        // 切换实例会改变页面数据源，相关的缓存页必须失效重建
        for (String page : List.of("home", "instances", "download", "mods", "logs")) {
            shell.invalidatePage(page);
        }
        shell.refreshShell();
        shell.toast().show(I18n.t("instance.current") + "：" + entry.name, Toast.Kind.SUCCESS);
    }

    private static String truncate(String value) {
        return truncate(value, 42);
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }
}
