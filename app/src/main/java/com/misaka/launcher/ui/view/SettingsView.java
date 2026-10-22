package com.misaka.launcher.ui.view;

import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.java.JavaProvider;
import com.misaka.launcher.core.launch.GpuPreference;
import com.misaka.launcher.core.launch.LaunchPresetFactory;
import com.misaka.launcher.core.log.LogCleaner;
import com.misaka.launcher.core.log.LogManager;
import com.misaka.launcher.core.meta.JavaVersionRule;
import com.misaka.launcher.core.mods.CurseForgeProvider;
import com.misaka.launcher.app.AppInfo;
import com.misaka.launcher.core.sync.SyncEngine;
import com.misaka.launcher.core.sync.SyncResult;
import com.misaka.launcher.core.update.ZooKeeperUpdateClient;
import com.misaka.launcher.core.util.ArchUtils;
import com.misaka.launcher.core.util.OsUtils;
import com.misaka.launcher.ui.component.Icons;
import com.misaka.launcher.ui.component.Toast;
import com.misaka.launcher.ui.component.Ui;
import com.misaka.launcher.ui.shell.AppShell;
import com.misaka.launcher.ui.theme.BackgroundManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 设置视图。
 * <p>交互约定：<b>改动即生效</b>。启动器里没有「草稿态」的必要，
 * 用户每改一项立即写入配置，避免出现「改完忘了点保存」的常见挫败感；
 * 需要重启才能生效的项（便携模式、数据目录）会明确提示。</p>
 */
public final class SettingsView extends BorderPane {

    private static final Logger log = LoggerFactory.getLogger(SettingsView.class);

    private final AppShell shell;

    /** 分页标识 → 顺序，必须与方法中创建 Tab 的顺序一致（供外壳的快捷入口定位分页）。 */
    private static final List<String> TAB_ORDER = List.of(
            "general", "download", "java", "launch", "theme", "sync", "logs");

    /** 「自动」选项的哨兵值：按当前实例的 Minecraft 版本安装所需 JDK。 */
    private static final String AUTO_JAVA = "auto";

    /** 游戏窗口尺寸的下限：低于此值游戏界面会被压得无法操作。 */
    private static final int MIN_WINDOW_WIDTH = 320;
    private static final int MIN_WINDOW_HEIGHT = 240;
    /** 窗口尺寸上限：再大没有实际意义（4K 也就 3840×2160）。 */
    private static final int MAX_WINDOW_SIZE = 8192;

    private final TabPane tabs;
    /** 背景模式下拉；选择图片/文件夹时需要连带切到对应模式（见 {@link #activateMode}）。 */
    private ComboBox<String> backgroundMode;
    private final ListView<LauncherConfig.JavaRuntimeEntry> javaList = new ListView<>();
    private final Label javaStatus = Ui.label("", "mute-2");
    /** 安装目标的 Java 主版本选择：自动（按游戏需求）或手动指定 8/11/17/21。 */
    private ComboBox<String> javaInstallVersion;
    /** 展示「自动」当前会解析到哪个版本，让选择有据可依。 */
    private final Label javaRequirementHint = Ui.label("", "mute-2");

    public SettingsView(AppShell shell) {
        this.shell = shell;

        this.tabs = new TabPane(
                tab("settings.general", buildGeneralTab()),
                tab("settings.download", buildDownloadTab()),
                tab("settings.java", buildJavaTab()),
                tab("settings.launch", buildLaunchTab()),
                tab("settings.theme", buildThemeTab()),
                tab("sync.title", buildSyncTab()),
                tab("settings.logs", buildLogsTab()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(tabs, Priority.ALWAYS);

        // 视图自身刻意不滚动：每个分页各自带一个 ScrollPane，因此页签栏始终固定在顶部，
        // 也不会出现「滚动容器里再套一层滚动容器」的别扭手感。
        VBox content = new VBox(12, tabs);
        content.setMaxWidth(Ui.CONTENT_MAX_WIDTH);
        StackPane holder = new StackPane(content);
        holder.setAlignment(Pos.TOP_CENTER);
        holder.getStyleClass().add("content-host");
        holder.setPadding(new Insets(4, 6, 16, 6));
        setCenter(holder);
        refreshJavaList();
    }

    /**
     * 定位到指定分页。
     * <p>用于标题栏「背景」等快捷入口：直接跳到相关设置，省去用户再找一遍。</p>
     *
     * @param id 分页标识，见 {@link #TAB_ORDER}
     */
    public void showTab(String id) {
        int index = TAB_ORDER.indexOf(id);
        if (index >= 0 && index < tabs.getTabs().size()) {
            tabs.getSelectionModel().select(index);
        }
    }

    private Tab tab(String key, Node content) {
        ScrollPane pane = Ui.scrollable(content, Ui.CONTENT_MAX_WIDTH);
        pane.setFitToHeight(true);
        Tab tab = new Tab(I18n.t(key), pane);
        tab.textProperty().bind(new javafx.beans.binding.StringBinding() {
            {
                bind(javafx.beans.binding.Bindings.createStringBinding(() -> I18n.t(key)));
            }

            @Override
            protected String computeValue() {
                return I18n.t(key);
            }
        });
        return tab;
    }

    // ==================================================================
    // 通用
    // ==================================================================

    private Node buildGeneralTab() {
        LauncherConfig config = shell.services().config();

        // 下拉里显示的是语言自己的名字（简体中文 / English / Français），
        // 而不是 zh_CN 这种标签 —— 看不懂当前语言的人更不可能看懂标签。
        // 选项来自 I18n.availableLanguages()：数据目录 i18n/ 下放的文件会自动出现在这里。
        ComboBox<String> language = Ui.comboBox(
                I18n.availableLanguages(),
                I18n.languageTag(), value -> {
                    shell.services().context().setLanguage(value);
                    // 立即重建界面：文案是构造时定下的，不重建就只有外壳变、正文还是旧语言
                    shell.refreshTexts();
                });
        language.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String value) {
                return value == null ? "" : I18n.displayName(value);
            }

            @Override
            public String fromString(String text) {
                return I18n.languageTag();
            }
        });

        CheckBox portable = Ui.checkBox("settings.portable", config.portable, value -> {
            if (shell.services().context().setPortableMode(value)) {
                shell.toast().show(I18n.t("toast.restartRequired"), Toast.Kind.SUCCESS);
            }
        });

        // 路径可能很长：包成可压缩，避免把整行控件宽度撑出边界
        Label dataDir = Ui.shrinkable(Ui.mono(
                shell.services().context().paths().dataRoot().toString(), "mute-2"));
        Label rootDir = Ui.shrinkable(Ui.mono(
                shell.services().context().paths().root().toString(), "mute-2"));
        Label javaDir = Ui.shrinkable(Ui.mono(
                shell.services().context().paths().javaRoot().toString(), "mute-2"));
        // 版本号取自 jar 清单（AppInfo），不要再硬编码，否则界面版本与实际构建会不一致
        Label version = Ui.mono(AppInfo.version(), "mute-2");
        Label platform = Ui.mono(OsUtils.id() + " / " + ArchUtils.id(), "mute-2");

        // 无边框窗口被拉伸或拖偏后可能抓不到边缘，这里给一个明确的恢复入口
        Button resetWindow = Ui.ghostButton("settings.window.reset.button",
                event -> shell.resetWindowSize(), Icons.create(Icons.REFRESH, 14));
        HBox resetHolder = new HBox(resetWindow);
        resetHolder.setAlignment(Pos.CENTER_LEFT);

        // 启动器更新：服务地址指向用户自建的 ZooKeeper，与同步服务器同一套路数
        CheckBox checkOnStartup = Ui.checkBox("settings.update.checkOnStartup",
                config.update.checkOnStartup, value -> {
                    config.update.checkOnStartup = value;
                    shell.services().configStore().save();
                });

        TextField updateServer = Ui.textField("settings.update.serverUrl", config.update.serverUrl);
        // 回车与失焦都要提交：只挂 setOnAction 时，用户填完直接点「立即检查更新」
        // 会连的还是旧地址（界面上却显示新地址）—— 现场真的踩过
        Runnable commitUpdateServer = Ui.commitOnEnterOrBlur(updateServer,
                () -> config.update.serverUrl, value -> {
                    config.update.serverUrl = value;
                    shell.services().configStore().save();
                    shell.toast().show(I18n.t("toast.saved"));
                });

        ComboBox<String> updateChannel = Ui.comboBox(ZooKeeperUpdateClient.CHANNELS,
                config.update.channel, value -> {
                    config.update.channel = value;
                    shell.services().configStore().save();
                });

        TextField updateApiKey = Ui.textField("settings.update.apiKey", config.update.apiKey);
        Ui.commitOnEnterOrBlur(updateApiKey, () -> config.update.apiKey, value -> {
            config.update.apiKey = value;
            shell.services().configStore().save();
            shell.toast().show(I18n.t("toast.saved"));
        });

        // 下载账号口令（可选）：留空也按账号记归属，填了才升级成「已验证」
        PasswordField updatePassword = Ui.passwordField("settings.update.downloadPassword",
                config.update.downloadPassword);
        Ui.commitOnEnterOrBlur(updatePassword, () -> config.update.downloadPassword, value -> {
            config.update.downloadPassword = value;
            shell.services().configStore().save();
            shell.toast().show(I18n.t("toast.saved"));
        });

        // 手动检查更新：与启动时的自动检查走同一条流程，只是结果一定会反馈给用户
        Button checkUpdate = Ui.ghostButton("settings.update.checkNow", event -> {
            // 万一输入框还没失焦（部分平台点按钮不抢焦点），这里再提交一次：
            // 「改了地址却按旧地址去检查」是这个页面最容易让人误判的行为
            commitUpdateServer.run();
            shell.updates().checkManually();
        }, Icons.create(Icons.REFRESH, 14));
        checkUpdate.setDisable(shell.selfUpdating());
        HBox updateActions = new HBox(8, checkUpdate);
        updateActions.setAlignment(Pos.CENTER_LEFT);

        return Ui.glassCard(12,
                Ui.cardHeader("settings.general"),
                Ui.settingRow("settings.language", language, null),
                Ui.settingRow("settings.portable", portable, I18n.t("settings.portable.tip")),
                Ui.settingRow("settings.dataDir", dataDir, null),
                Ui.divider(),
                Ui.cardHeader("settings.update"),
                Ui.settingRow("settings.update.checkOnStartup", checkOnStartup,
                        I18n.t("settings.update.checkOnStartup.tip")),
                Ui.settingRow("settings.update.serverUrl", updateServer,
                        I18n.t("settings.update.serverUrl.tip")),
                Ui.settingRow("settings.update.channel", updateChannel,
                        I18n.t("settings.update.channel.tip")),
                Ui.settingRow("settings.update.apiKey", updateApiKey,
                        I18n.t("settings.update.apiKey.tip")),
                Ui.settingRow("settings.update.downloadPassword", updatePassword,
                        I18n.t("settings.update.downloadPassword.tip", ZooKeeperUpdateClient.DOWNLOAD_USER)),
                Ui.settingRow("settings.update.actions", updateActions, null),
                Ui.divider(),
                Ui.cardHeader("settings.about"),
                Ui.settingRow("settings.about.version", version, null),
                Ui.settingRow("settings.about.root", rootDir, null),
                Ui.settingRow("settings.about.javaDir", javaDir, null),
                Ui.settingRow("settings.window.reset", resetHolder,
                        I18n.t("settings.window.reset.tip")),
                Ui.settingRow("settings.advanced", platform, null));
    }

    // ==================================================================
    // 下载
    // ==================================================================

    private Node buildDownloadTab() {
        LauncherConfig.DownloadSettings download = shell.services().config().download;

        ComboBox<String> source = Ui.comboBox(List.of(
                        LauncherConfig.DownloadSettings.SOURCE_AUTO,
                        LauncherConfig.DownloadSettings.SOURCE_OFFICIAL,
                        LauncherConfig.DownloadSettings.SOURCE_BMCLAPI,
                        LauncherConfig.DownloadSettings.SOURCE_CUSTOM),
                download.source, value -> {
                    download.source = value;
                    shell.services().configStore().save();
                    shell.toast().show(I18n.t("toast.saved"));
                });
        Ui.localizeSourceCombo(source);

        TextField customSource = Ui.textField(null, download.customSourceUrl);
        Ui.commitOnEnterOrBlur(customSource, () -> download.customSourceUrl, value -> {
            download.customSourceUrl = value;
            shell.services().configStore().save();
        });

        Region concurrency = slider(1, 32, download.concurrency, value -> {
            download.concurrency = (int) value;
            shell.services().configStore().save();
        });
        Region retries = slider(1, 10, download.retries, value -> {
            download.retries = (int) value;
            shell.services().configStore().save();
        });
        Region timeout = slider(5, 180, download.timeoutSeconds, value -> {
            download.timeoutSeconds = (int) value;
            shell.services().configStore().save();
        });
        Region speedLimit = slider(0, 102400, download.speedLimitKbps, value -> {
            download.speedLimitKbps = (int) value;
            shell.services().configStore().save();
        });

        Button probe = Ui.ghostButtonText("测速并重排下载源", event -> shell.tasks().run("settings.download", listener -> {
            shell.services().refreshDownloadSources();
            listener.onMessage("已按实测延迟重排");
        }, result -> shell.toast().show(I18n.t("status.done"), Toast.Kind.SUCCESS)));

        // Key 只有编译期常量一处来源：界面上只显示「已配置/未配置」，不提供输入框
        String builtInKey = com.misaka.launcher.core.config.LauncherConfig.ModsSettings
                .BUILT_IN_CURSEFORGE_API_KEY;
        Label curseForgeKey = Ui.label(builtInKey == null || builtInKey.isBlank()
                ? I18n.t("download.cf.notConfiguredShort")
                : I18n.t("download.cf.keyBuiltIn", CurseForgeProvider.maskKey(builtInKey)), "mute-2");

        return Ui.glassCard(12,
                Ui.cardHeader("settings.download", probe),
                Ui.settingRow("settings.downloadSource", source, null),
                Ui.settingRow("install.source.custom", customSource, null),
                Ui.settingRow("settings.concurrency", concurrency, null),
                Ui.settingRow("settings.retries", retries, null),
                Ui.settingRow("settings.timeout", timeout, null),
                Ui.settingRow("settings.speedLimit", speedLimit, null),
                Ui.divider(),
                Ui.settingRow("download.cf.apiKey", curseForgeKey, I18n.t("download.cf.keyBuiltInTip")));
    }

    // ==================================================================
    // Java
    // ==================================================================

    private Node buildJavaTab() {
        LauncherConfig.JavaSettings java = shell.services().config().java;

        ComboBox<String> vendor = Ui.comboBox(
                shell.services().java().providers().stream().map(JavaProvider::id).toList(),
                java.preferredVendor, value -> {
                    java.preferredVendor = value;
                    shell.services().configStore().save();
                });

        CheckBox autoInstall = Ui.checkBox("settings.autoInstallJava", java.autoInstall, value -> {
            java.autoInstall = value;
            shell.services().configStore().save();
        });

        // 安装版本：自动（按当前实例的 Minecraft 版本）或手动预装 8/11/17/21
        List<String> versionItems = new ArrayList<>();
        versionItems.add(AUTO_JAVA);
        for (int major : JavaVersionRule.INSTALLABLE_MAJORS) {
            versionItems.add(String.valueOf(major));
        }
        javaInstallVersion = Ui.comboBox(versionItems, AUTO_JAVA, null);
        javaInstallVersion.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String value) {
                return AUTO_JAVA.equals(value)
                        ? I18n.t("java.install.auto")
                        : JavaVersionRule.describe(Integer.parseInt(value));
            }

            @Override
            public String fromString(String value) {
                return value;
            }
        });

        javaList.setPrefHeight(200);
        javaList.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(LauncherConfig.JavaRuntimeEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label title = Ui.label(JavaVersionRule.describe(item.majorVersion)
                        + " · " + item.vendor + " · " + item.source, "section-title");
                Label path = Ui.mono(item.home, "mute-2");
                VBox box = new VBox(2, title, path);
                setGraphic(box);
                setText(null);
            }
        });

        Button scan = Ui.ghostButton("settings.scanJava", event -> scanJava(),
                Icons.create(Icons.REFRESH, 14));
        Button install = Ui.ghostButton("java.download", event -> installJava(),
                Icons.create(Icons.DOWNLOAD, 15));
        Button remove = Ui.dangerButton("action.remove", event -> removeJava());
        // 装/删 JDK 都是长耗时写盘操作，自更新期间不许开始
        install.setDisable(shell.selfUpdating());
        remove.setDisable(shell.selfUpdating());

        HBox actions = new HBox(8, scan, install, remove);
        actions.setAlignment(Pos.CENTER_LEFT);

        return Ui.glassCard(12,
                Ui.cardHeader("settings.java"),
                Ui.settingRow("settings.preferVendor", vendor, null),
                Ui.settingRow("settings.autoInstallJava", autoInstall, I18n.t("java.autoInstallTip")),
                Ui.divider(),
                Ui.settingRow("java.install.version", javaInstallVersion,
                        I18n.t("java.install.version.tip")),
                javaRequirementHint,
                javaStatus,
                javaList,
                actions);
    }

    private void refreshJavaList() {
        var entries = new ArrayList<>(shell.services().java().registered());
        javaList.getItems().setAll(entries);
        javaStatus.setText(entries.isEmpty() ? I18n.t("java.title") : entries.size() + " 个运行时");
        updateJavaRequirementHint();
    }

    /** 展示「自动」模式当前会解析到的 Java 版本。 */
    private void updateJavaRequirementHint() {
        LauncherConfig.InstanceEntry instance = shell.services().instances().current();
        if (instance == null || instance.versionId == null || instance.versionId.isBlank()) {
            javaRequirementHint.setText(I18n.t("java.install.auto.none"));
            return;
        }
        javaRequirementHint.setText(I18n.t("java.required",
                JavaVersionRule.requiredMajorVersion(instance.versionId)));
    }

    private void scanJava() {
        shell.tasks().run("settings.scanJava", listener -> {
            var added = shell.services().java().scanLocal();
            listener.onMessage("发现 " + added.size() + " 个新运行时");
        }, result -> {
            refreshJavaList();
            shell.refreshShell();
            shell.toast().show(I18n.t("settings.scanJava") + "：" + I18n.t("status.done"), Toast.Kind.SUCCESS);
        });
    }

    private void installJava() {
        int required = selectedInstallMajorVersion();
        shell.tasks().run("java.download", listener -> shell.services().java().install(required, listener),
                result -> {
                    refreshJavaList();
                    shell.refreshShell();
                    if (result.success()) {
                        shell.toast().show(I18n.t("java.installing", required) + " " + I18n.t("status.done"),
                                Toast.Kind.SUCCESS);
                    } else if (result.failed()) {
                        shell.toast().show(String.valueOf(result.error() == null ? ""
                                : result.error().getMessage()), Toast.Kind.ERROR);
                    }
                });
    }

    /**
     * 安装目标主版本：手动指定优先，否则按当前实例的 Minecraft 版本自动判定。
     * <p>无实例时回落到 17（覆盖面最广的 LTS）。</p>
     */
    private int selectedInstallMajorVersion() {
        String selection = javaInstallVersion == null ? AUTO_JAVA : javaInstallVersion.getValue();
        if (selection != null && !AUTO_JAVA.equals(selection) && !selection.isBlank()) {
            try {
                return Integer.parseInt(selection);
            } catch (NumberFormatException ignored) {
                // 无法解析时落到自动判定
            }
        }
        LauncherConfig.InstanceEntry instance = shell.services().instances().current();
        return instance == null || instance.versionId == null || instance.versionId.isBlank()
                ? 17 : JavaVersionRule.requiredMajorVersion(instance.versionId);
    }

    private void removeJava() {
        LauncherConfig.JavaRuntimeEntry selected = javaList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        shell.services().java().remove(selected.home, true);
        refreshJavaList();
        shell.refreshShell();
    }

    // ==================================================================
    // 启动
    // ==================================================================

    private Node buildLaunchTab() {
        LauncherConfig.LaunchSettings launch = shell.services().config().launch;

        TextArea jvmArgs = new TextArea();
        jvmArgs.setPrefRowCount(4);
        TextArea gameArgs = new TextArea();
        gameArgs.setPrefRowCount(3);
        Label argsHint = Ui.mono("", "mute-2");

        // 自动模式下把「这次启动真正会使用的参数」回填进两个框，并且只读。
        // 以前自动模式下两个框永远是空的，旁边却写着「自动预设：8192 MB 最大堆，7 项 JVM 参数」——
        // 用户根本看不到那 7 项是什么，更无从判断是否合适。
        // 只读是刻意的：勾选自动时这些值由启动器生成，改它们没有意义（下次启动会被重新生成），
        // 想改就取消勾选 —— 那时框里才是用户自己的配置，也才可以编辑。
        Runnable syncArgs = () -> {
            boolean auto = launch.autoJvmArgs;
            LaunchPresetFactory.Preset preset = shell.services().launchPreset(0, 0);

            List<String> effectiveJvm = new ArrayList<>();
            if (auto) {
                effectiveJvm.addAll(preset.jvmArgs());
            }
            // 与 LaunchPipeline#buildPlan 保持一致：自动预设在前，用户参数追加在后
            effectiveJvm.addAll(launch.jvmArgs);
            jvmArgs.setEditable(!auto);
            jvmArgs.setText(String.join("\n", auto ? effectiveJvm : launch.jvmArgs));

            List<String> effectiveGame = new ArrayList<>(launch.gameArgs);
            if (auto) {
                effectiveGame.addAll(autoGameArgs(launch));
            }
            gameArgs.setEditable(!auto);
            gameArgs.setText(String.join("\n", auto ? effectiveGame : launch.gameArgs));

            argsHint.setText(auto
                    ? I18n.t("settings.args.auto", preset.maxMemoryMb(), preset.jvmArgs().size())
                    : I18n.t("settings.args.custom"));
        };

        CheckBox autoJvm = Ui.checkBox("settings.autoJvmArgs", launch.autoJvmArgs, value -> {
            launch.autoJvmArgs = value;
            shell.services().configStore().save();
            syncArgs.run();
        });

        // 失焦保存。自动模式下这两个框是只读回填，绝不能把回填内容写进配置，
        // 否则「取消勾选」时用户会看到一堆自己没填过的参数
        jvmArgs.focusedProperty().addListener((observable, oldValue, focused) -> {
            if (!focused && !launch.autoJvmArgs) {
                launch.jvmArgs = splitLines(jvmArgs.getText());
                shell.services().configStore().save();
            }
        });

        gameArgs.focusedProperty().addListener((observable, oldValue, focused) -> {
            if (!focused && !launch.autoJvmArgs) {
                launch.gameArgs = splitLines(gameArgs.getText());
                shell.services().configStore().save();
            }
        });

        CheckBox integrity = Ui.checkBox("settings.checkIntegrity", launch.checkIntegrityOnLaunch, value -> {
            launch.checkIntegrityOnLaunch = value;
            shell.services().configStore().save();
        });
        CheckBox modUpdates = Ui.checkBox("settings.checkModUpdates", launch.checkModUpdatesOnLaunch, value -> {
            launch.checkModUpdatesOnLaunch = value;
            shell.services().configStore().save();
        });

        // 独显优先：写的是 Windows 公开的图形首选项（与系统「图形设置 → 高性能」同一处），
        // 因此非 Windows 上直接置灰并说明，而不是留一个勾了没用的开关
        CheckBox highPerformanceGpu = Ui.checkBox("settings.preferDiscreteGpu",
                launch.preferHighPerformanceGpu, value -> {
                    launch.preferHighPerformanceGpu = value;
                    shell.services().configStore().save();
                });
        highPerformanceGpu.setDisable(!GpuPreference.isSupported());
        String adapterNames = String.join(" / ", GpuPreference.displayAdapterNames());
        String gpuTip = !GpuPreference.isSupported()
                ? I18n.t("settings.preferDiscreteGpu.unsupported")
                : I18n.t("settings.preferDiscreteGpu.tip")
                + (adapterNames.isBlank() ? ""
                        : I18n.t("settings.preferDiscreteGpu.detected", adapterNames));

        // 高清贴图压缩：图集是一张正方形纹理，边长上限就是驱动报告的最大纹理尺寸
        // （N 卡通常 32768，A/I 卡通常 16384，容量整整差 4 倍）。贴图总量超限的整合包
        // 在 A/I 卡上必然崩在拼接阶段，在 N 卡上却能正常进 —— 所以默认交给显卡判定，
        // 同时留出「始终开启 / 关闭」让用户自己定：这台机器到底需不需要用户比启发式更清楚，
        // 而且并非所有平台都有这个问题（比如 macOS）
        ComboBox<String> textureCompression = Ui.comboBox(List.of(
                        LauncherConfig.LaunchSettings.COMPRESSION_AUTO,
                        LauncherConfig.LaunchSettings.COMPRESSION_ALWAYS,
                        LauncherConfig.LaunchSettings.COMPRESSION_OFF),
                launch.textureCompression == null
                        ? LauncherConfig.LaunchSettings.COMPRESSION_AUTO : launch.textureCompression,
                value -> {
                    launch.textureCompression = value;
                    shell.services().configStore().save();
                });
        // 下拉里显示中文档位名，存进配置的仍是 auto / always / off
        textureCompression.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String value) {
                String mode = value == null ? LauncherConfig.LaunchSettings.COMPRESSION_AUTO : value;
                return I18n.t("settings.textureCompression." + mode);
            }

            @Override
            public String fromString(String text) {
                return text;
            }
        });

        // ---- 游戏窗口大小（全局）----
        // 以前这两个值是配置里写死的 854×480，界面上无处可改；现在做成全局设置，
        // 默认 1600×900。改动后立即重算游戏参数框里的回填内容
        TextField windowWidth = windowSizeField(launch.windowWidth, MIN_WINDOW_WIDTH, value -> {
            launch.windowWidth = value;
            shell.services().configStore().save();
            syncArgs.run();
        });
        TextField windowHeight = windowSizeField(launch.windowHeight, MIN_WINDOW_HEIGHT, value -> {
            launch.windowHeight = value;
            shell.services().configStore().save();
            syncArgs.run();
        });
        var windowSizeRow = Ui.hbox(6, windowWidth, Ui.label("×", "mute-2"), windowHeight);

        syncArgs.run();

        return Ui.glassCard(12,
                Ui.cardHeader("settings.launch"),
                Ui.settingRow("settings.autoJvmArgs", autoJvm, I18n.t("settings.autoJvmArgs.tip")),
                argsHint,
                Ui.settingRow("settings.jvmArgs", jvmArgs, null),
                Ui.settingRow("settings.gameArgs", gameArgs, null),
                Ui.settingRow("settings.windowSize", windowSizeRow, I18n.t("settings.windowSize.tip")),
                Ui.settingRow("settings.checkIntegrity", integrity, null),
                Ui.settingRow("settings.checkModUpdates", modUpdates, null),
                Ui.settingRow("settings.preferDiscreteGpu", highPerformanceGpu, gpuTip),
                Ui.settingRow("settings.textureCompression", textureCompression,
                        I18n.t("settings.textureCompression.tip")));
    }

    /**
     * 启动器会自己追加的游戏参数（不由用户填写的那些）。
     *
     * <p>窗口分辨率经版本 JSON 里的 {@code ${resolution_width}} /
     * {@code ${resolution_height}} 占位符进入最终命令行（由 {@code has_custom_resolution}
     * 规则控制），全屏则由命令构建器追加 {@code --fullscreen}。</p>
     *
     * <p><b>只在只读预览里展示，不会写进用户配置。</b>把 {@code --width} 同时填进「游戏参数」
     * 会和版本 JSON 自带的那一份重复，而 Minecraft 的参数解析对重复的非多值选项会直接报
     * 「Multiple arguments for option width」启动失败 —— 窗口大小有专门的设置项，
     * 不需要也不应该走「游戏参数」这条路。</p>
     */
    private static List<String> autoGameArgs(LauncherConfig.LaunchSettings launch) {
        List<String> args = new ArrayList<>();
        if (launch.windowWidth > 0 && launch.windowHeight > 0) {
            args.add("--width " + launch.windowWidth);
            args.add("--height " + launch.windowHeight);
        }
        if (launch.fullscreen) {
            args.add("--fullscreen");
        }
        return args;
    }

    /**
     * 游戏窗口尺寸的单个数值输入框：只收数字，回车或失焦时夹进合法范围并写回配置。
     *
     * <p>不做「原样存下来」这种偷懒处理：窗口宽高会直接进游戏命令行，填 0 或负数会让
     * {@code has_custom_resolution} 判定失效（分辨率占位符被替换成 0），
     * 表现为「设了窗口大小却按默认尺寸启动」，用户在界面上完全看不出原因。</p>
     *
     * @param current 当前值（也是输入非法时的回退值）
     * @param min     下限
     * @param onChange 合法值回调
     */
    private static TextField windowSizeField(int current, int min, IntConsumer onChange) {
        TextField field = Ui.textField(null, String.valueOf(current));
        field.setPrefColumnCount(5);
        field.setMaxWidth(96);
        // 只允许数字，且限长：避免用户粘进来 "1600x900" 这种整体字符串
        field.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().matches("\\d{0,5}") ? change : null));

        int[] last = {current};
        Runnable commit = () -> {
            int parsed;
            try {
                parsed = Integer.parseInt(field.getText().trim());
            } catch (NumberFormatException e) {
                // 空串或非法输入：回退到上一次的合法值，而不是静默变成 0
                parsed = last[0];
            }
            int clamped = Math.max(min, Math.min(parsed, MAX_WINDOW_SIZE));
            last[0] = clamped;
            field.setText(String.valueOf(clamped));
            onChange.accept(clamped);
        };
        field.setOnAction(event -> commit.run());
        field.focusedProperty().addListener((observable, oldValue, focused) -> {
            if (!focused) {
                commit.run();
            }
        });
        return field;
    }

    private static List<String> splitLines(String text) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(text.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    // ==================================================================
    // 主题与背景
    // ==================================================================

    private Node buildThemeTab() {
        LauncherConfig config = shell.services().config();
        LauncherConfig.BackgroundSettings background = config.background;

        ComboBox<String> theme = Ui.comboBox(List.of("dark", "light", "custom"), config.theme, value ->
                shell.themeManager().apply(com.misaka.launcher.ui.theme.Theme.fromId(value),
                        config.themeAccent));

        TextField accent = Ui.textField(null, config.themeAccent);
        Ui.commitOnEnterOrBlur(accent, () -> config.themeAccent, value -> {
            config.themeAccent = value;
            shell.services().configStore().save();
            // 只换强调色，保留当前明暗基底。
            // 以前这里硬切到 CUSTOM（CUSTOM 的基底是 dark.css），于是「选了浅色再填强调色」
            // 会被悄悄弹回暗色；而且当主题不是 CUSTOM 时强调色根本不生效。
            shell.themeManager().applyAccent(value);
        });

        backgroundMode = Ui.comboBox(List.of(
                        LauncherConfig.BackgroundSettings.MODE_SOLID,
                        LauncherConfig.BackgroundSettings.MODE_GRADIENT,
                        LauncherConfig.BackgroundSettings.MODE_IMAGE,
                        LauncherConfig.BackgroundSettings.MODE_FOLDER),
                background.mode, value -> {
                    background.mode = value;
                    applyBackground();
                });
        ComboBox<String> mode = backgroundMode;

        TextField imagePath = Ui.textField(null, background.imagePath);
        Ui.commitOnEnterOrBlur(imagePath, () -> background.imagePath,
                value -> selectImage(imagePath, value));
        Button browseImage = Ui.ghostButton("action.browse", event -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle(I18n.t("settings.background.imagePath"));
            chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                    "Images", "*.png", "*.jpg", "*.jpeg", "*.webp", "*.bmp", "*.gif"));
            java.io.File file = chooser.showOpenDialog(shell.stage());
            if (file != null) {
                selectImage(imagePath, file.getAbsolutePath());
            }
        });

        TextField folderPath = Ui.textField(null, background.folderPath);
        Ui.commitOnEnterOrBlur(folderPath, () -> background.folderPath,
                value -> selectFolder(folderPath, value));
        Button browseFolder = Ui.ghostButton("action.browse", event -> {
            javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle(I18n.t("settings.background.folderPath"));
            java.io.File dir = chooser.showDialog(shell.stage());
            if (dir != null) {
                selectFolder(folderPath, dir.getAbsolutePath());
            }
        });

        Region blur = slider(0, 40, background.blur, value -> {
            background.blur = value;
            applyBackground();
        });
        Region dim = slider(0, 80, background.dim, value -> {
            background.dim = value;
            applyBackground();
        });
        // 下限放宽到 5%：图片背景需要足够淡才不会压过文字（上限由 BackgroundManager 兜住）
        Region opacity = slider(5, 100, background.opacity, value -> {
            background.opacity = value;
            applyBackground();
        });
        Region rotate = slider(0, 600, background.rotateIntervalSeconds, value -> {
            background.rotateIntervalSeconds = (int) value;
            applyBackground();
        });
        // 「明暗」「不透明度」只对纯色/渐变背景有意义（一个是黑色压暗层、一个是整层透明度）。
        // 图片模式下它们不起作用，继续显示只会让人误以为在调背景图 —— 直接隐藏
        javafx.beans.binding.BooleanBinding imageLike = backgroundMode.valueProperty()
                .isEqualTo(LauncherConfig.BackgroundSettings.MODE_IMAGE)
                .or(backgroundMode.valueProperty().isEqualTo(LauncherConfig.BackgroundSettings.MODE_FOLDER));
        Node dimRow = Ui.settingRow("settings.background.dim", dim, null);
        dimRow.visibleProperty().bind(imageLike.not());
        dimRow.managedProperty().bind(imageLike.not());
        Node opacityRow = Ui.settingRow("settings.background.opacity", opacity, null);
        opacityRow.visibleProperty().bind(imageLike.not());
        opacityRow.managedProperty().bind(imageLike.not());

        CheckBox shuffle = Ui.checkBox("settings.background.shuffle", background.shuffle, value -> {
            background.shuffle = value;
            applyBackground();
        });
        CheckBox overlay = Ui.checkBox("settings.background.overlay", background.showOverlayGradient, value -> {
            background.showOverlayGradient = value;
            applyBackground();
        });
        // 覆盖层透明度：图片/轮播模式下唯一的压暗旋钮 ——
        // 100% = 除控件外完整显示背景图；越低图片越暗、渐变遮罩越强（文字可读性）。
        // 纯色/渐变模式下它只控制渐变遮罩的强度
        Region overlayOpacity = slider(0, 100, background.overlayOpacity, value -> {
            background.overlayOpacity = (int) value;
            applyBackground();
        });

        TextField gradientStart = Ui.textField(null, background.gradientStart);
        Ui.commitOnEnterOrBlur(gradientStart, () -> background.gradientStart, value -> {
            background.gradientStart = value;
            applyBackground();
        });
        TextField gradientEnd = Ui.textField(null, background.gradientEnd);
        Ui.commitOnEnterOrBlur(gradientEnd, () -> background.gradientEnd, value -> {
            background.gradientEnd = value;
            applyBackground();
        });

        return Ui.glassCard(12,
                Ui.cardHeader("settings.theme"),
                Ui.settingRow("settings.theme.mode", theme, null),
                Ui.settingRow("settings.theme.custom", accent, null),
                Ui.divider(),
                Ui.cardHeader("settings.background"),
                Ui.settingRow("settings.background.mode", mode, null),
                Ui.settingRow("settings.background.imagePath", new HBox(8, imagePath, browseImage), null),
                Ui.settingRow("settings.background.folderPath", new HBox(8, folderPath, browseFolder), null),
                Ui.settingRow("settings.background.blur", blur, null),
                Ui.settingRow("settings.background.overlayOpacity", overlayOpacity,
                        I18n.t("settings.background.overlayOpacity.tip")),
                Ui.settingRow("settings.background.overlay", overlay, null),
                dimRow,
                opacityRow,
                Ui.settingRow("settings.background.rotate", rotate, null),
                Ui.settingRow("settings.background.shuffle", shuffle, null),
                Ui.settingRow("settings.background.gradient", new HBox(8, gradientStart, gradientEnd), null));
    }

    /**
     * 选中单张背景图：写入路径、**并把模式切到「单图」**，然后立即应用。
     *
     * <p>为什么必须连带切模式：用户的直觉是「选了图片，背景就该变成这张图」。
     * 若只写路径而模式还停在渐变上，界面不会有任何变化 —— 看起来就是「选了没反应」，
     * 用户还得自己去上面把模式改成「单图」，这步没有任何理由让人来做。</p>
     */
    private void selectImage(TextField field, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        LauncherConfig.BackgroundSettings background = shell.services().config().background;
        field.setText(path);
        background.imagePath = path;
        activateMode(LauncherConfig.BackgroundSettings.MODE_IMAGE);

        // 立刻给出可核对的反馈。以前加载失败是静默的，用户只能盯着"看起来没变化"的界面猜：
        // 是没生效？还是太淡看不出？这条提示直接回答这个问题。
        String fileName = java.nio.file.Path.of(path).getFileName().toString();
        if (shell.backgroundManager().isImageVisible()) {
            shell.toast().show(I18n.t("settings.background.applied", fileName), Toast.Kind.SUCCESS);
        } else {
            shell.toast().show(I18n.t("settings.background.failed", path), Toast.Kind.ERROR);
        }
    }

    /** 选中轮播文件夹：同样连带把模式切到「文件夹轮播」。 */
    private void selectFolder(TextField field, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        LauncherConfig.BackgroundSettings background = shell.services().config().background;
        field.setText(path);
        background.folderPath = path;
        activateMode(LauncherConfig.BackgroundSettings.MODE_FOLDER);
    }

    /**
     * 切到指定背景模式并立即重绘。
     * <p>模式本来就一致时下拉框不会触发回调，因此这里显式补一次 {@link #applyBackground()}。</p>
     */
    private void activateMode(String mode) {
        if (backgroundMode != null && !mode.equals(backgroundMode.getValue())) {
            // 下拉框的回调会设置 background.mode 并应用（只应用一次，避免重复解码大图）
            backgroundMode.setValue(mode);
            return;
        }
        applyBackground();
    }

    /** 任何背景项改动都立即重绘并持久化。 */
    private void applyBackground() {
        BackgroundManager manager = shell.backgroundManager();
        manager.setSettings(shell.services().config().background);
        manager.apply();
        // 背景图上压着两层：图片自身的压暗效果（apply 里处理）与整窗外壳的遮罩。
        // 后者原先是写死的，导致「覆盖层透明度」拉到 100% 时图仍被主题色染色，
        // 所以这里把它一起接上同一个旋钮
        shell.themeManager().applyShellVeil(manager.shellVeilFactor());
        shell.services().configStore().save();
    }

    // ==================================================================
    // 同步
    // ==================================================================

    private Node buildSyncTab() {
        LauncherConfig.SyncSettings sync = shell.services().config().sync;

        CheckBox enabled = Ui.checkBox("sync.enable", sync.enabled, value -> {
            sync.enabled = value;
            persistSync();
        });
        CheckBox auto = Ui.checkBox("sync.autoBeforeLaunch", sync.autoSyncBeforeLaunch, value -> {
            sync.autoSyncBeforeLaunch = value;
            // 勾「启动游戏前自动执行」比总开关更强 —— 勾了它就是要跑。
            // 若总开关还关着就成了自相矛盾的状态（用户看到的现象正是「勾了没用」），
            // 这里顺手把总开关打开，让操作意图落地
            if (value && !sync.enabled) {
                sync.enabled = true;
                enabled.setSelected(true);
            }
            persistSync();
        });
        CheckBox block = Ui.checkBox("sync.blockOnFailure", sync.blockLaunchOnFailure, value -> {
            sync.blockLaunchOnFailure = value;
            persistSync();
        });
        // 这两项只在启用后才有意义：置灰，让"总开关 → 子项"的层级一眼可见
        // （两者的默认值正好是「总开关关、自动执行开」，不置灰就会被误读成已生效）
        auto.disableProperty().bind(enabled.selectedProperty().not());
        block.disableProperty().bind(enabled.selectedProperty().not());
        CheckBox allowDelete = Ui.checkBox("sync.allowDelete", sync.allowDeleteLocal, value -> {
            sync.allowDeleteLocal = value;
            persistSync();
        });

        TextField address = Ui.textField("sync.serverAddress", sync.serverAddress);
        address.focusedProperty().addListener((observable, oldValue, focused) -> {
            if (!focused) {
                sync.serverAddress = address.getText().trim();
                persistSync();
            }
        });
        TextField port = Ui.textField("sync.serverPort", sync.serverPort);
        port.focusedProperty().addListener((observable, oldValue, focused) -> {
            if (!focused) {
                sync.serverPort = port.getText().trim();
                persistSync();
            }
        });

        TextArea directories = new TextArea(String.join("\n", sync.directories));
        directories.setPrefRowCount(4);
        directories.focusedProperty().addListener((observable, oldValue, focused) -> {
            if (!focused) {
                sync.directories = splitLines(directories.getText());
                persistSync();
            }
        });

        TextArea ignored = new TextArea(String.join("\n", sync.ignoredFiles));
        ignored.setPrefRowCount(2);
        ignored.focusedProperty().addListener((observable, oldValue, focused) -> {
            if (!focused) {
                sync.ignoredFiles = splitLines(ignored.getText());
                persistSync();
            }
        });

        Region concurrency = slider(1, 16, sync.concurrency, value -> {
            sync.concurrency = (int) value;
            persistSync();
        });
        Region retries = slider(1, 10, sync.retries, value -> {
            sync.retries = (int) value;
            persistSync();
        });

        Label status = Ui.label("", "mute-2");

        // 测试连接与健康检查是两件事：前者只 ping 服务器，后者比对本地与服务端的文件差异。
        // 两个按钮曾经共用同一个 i18n 键，界面上就出现了两个一模一样的「检查文件状态」
        Button test = Ui.ghostButton("sync.testConnection", event -> shell.tasks().run("sync.stage.config", listener -> {
            boolean healthy = SyncEngine.of(sync).client().health();
            if (!healthy) {
                throw new java.io.IOException(I18n.t("sync.connectFailed", sync.serverAddress));
            }
            listener.onMessage("连接正常：" + sync.serverAddress + ":" + sync.serverPort);
        }, result -> {
            status.setText(result.success() ? "连接正常" : I18n.t("status.failed"));
            shell.toast().show(status.getText(), result.success() ? Toast.Kind.SUCCESS : Toast.Kind.ERROR);
        }));

        Button check = Ui.ghostButton("sync.check", event -> runSyncCheck(status));
        Button synchronize = Ui.primaryButton("sync.start", event -> runSync(status));
        // 同步是往游戏目录写文件：自更新期间不许开始（更新结束会退出启动器打断它）
        synchronize.setDisable(shell.selfUpdating());

        return Ui.glassCard(12,
                Ui.cardHeader("sync.title", test, check, synchronize),
                status,
                Ui.settingRow("sync.enable", enabled, null),
                Ui.settingRow("sync.autoBeforeLaunch", auto, null),
                Ui.settingRow("sync.blockOnFailure", block, null),
                Ui.settingRow("sync.serverAddress", new HBox(8, address, port), null),
                Ui.settingRow("sync.directories", directories, I18n.t("sync.directories.tip")),
                Ui.settingRow("sync.ignoredFiles", ignored, null),
                Ui.settingRow("sync.allowDelete", allowDelete, null),
                Ui.settingRow("sync.concurrency", concurrency, null),
                Ui.settingRow("sync.retries", retries, null));
    }

    private void persistSync() {
        shell.services().configStore().save();
        // 外壳（启动卡片上的实例信息与启动按钮可用性）要跟着变，否则会停留在旧配置上
        shell.refreshShell();
    }

    private void runSyncCheck(Label status) {
        LauncherConfig.InstanceEntry instance = shell.services().instances().current();
        if (instance == null) {
            return;
        }
        Path root = shell.services().instances().gamePaths(instance).gameDir();
        LauncherConfig.SyncSettings sync = shell.services().config().sync;
        shell.tasks().run("sync.stage.scan", listener -> {
            var plan = SyncEngine.of(sync).check(root, listener);
            listener.onMessage(plan.hasChanges()
                    ? "待处理 " + plan.toDownload().size() + " 项下载，" + plan.toDelete().size() + " 项删除"
                    : I18n.t("sync.noChanges"));
            javafx.application.Platform.runLater(() -> status.setText(plan.hasChanges()
                    ? "有 " + plan.toDownload().size() + " 项待更新" : I18n.t("sync.noChanges")));
        }, result -> {
            if (result.failed()) {
                shell.toast().show(String.valueOf(result.error() == null ? ""
                        : result.error().getMessage()), Toast.Kind.ERROR);
            }
        });
    }

    private void runSync(Label status) {
        LauncherConfig.InstanceEntry instance = shell.services().instances().current();
        if (instance == null) {
            return;
        }
        Path root = shell.services().instances().gamePaths(instance).gameDir();
        LauncherConfig.SyncSettings sync = shell.services().config().sync;
        // 同步会往这个实例的游戏目录里下载与删除文件：期间它的启动入口必须置灰，
        // 否则用户可能在文件被覆盖到一半时把游戏拉起来。
        // （上面的 runSyncCheck 只是比对差异，不写盘，因此不声明实例）
        shell.tasks().runForInstance(instance.id, "sync.start", listener -> {
            SyncResult result = SyncEngine.of(sync).sync(root, listener);
            listener.onMessage(result.summary());
            javafx.application.Platform.runLater(() -> status.setText(result.summary()));
        }, result -> {
            if (result.success()) {
                shell.toast().show(I18n.t("toast.updated"), Toast.Kind.SUCCESS);
            } else if (result.failed()) {
                shell.toast().show(String.valueOf(result.error() == null ? ""
                        : result.error().getMessage()), Toast.Kind.ERROR);
            }
        });
    }

    // ==================================================================
    // 日志
    // ==================================================================

    private Node buildLogsTab() {
        LauncherConfig.LogSettings logSettings = shell.services().config().log;

        Region retainDays = slider(1, 90, logSettings.retainDays, value -> {
            logSettings.retainDays = (int) value;
            shell.services().configStore().save();
        });
        Region maxTotal = slider(16, 4096, logSettings.maxTotalMb, value -> {
            logSettings.maxTotalMb = (int) value;
            shell.services().configStore().save();
        });
        Region maxFile = slider(1, 64, logSettings.maxFileMb, value -> {
            logSettings.maxFileMb = (int) value;
            shell.services().configStore().save();
        });
        Region maxFiles = slider(4, 500, logSettings.maxFiles, value -> {
            logSettings.maxFiles = (int) value;
            shell.services().configStore().save();
        });

        CheckBox gameOutput = Ui.checkBox("settings.logs.gameOutput", logSettings.logGameOutput, value -> {
            logSettings.logGameOutput = value;
            shell.services().configStore().save();
        });
        CheckBox console = Ui.checkBox("settings.logs.console", logSettings.consoleOutput, value -> {
            logSettings.consoleOutput = value;
            LogManager.applySettings(logSettings);
            shell.services().configStore().save();
        });

        ComboBox<String> level = Ui.comboBox(List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR"),
                logSettings.level, value -> {
                    logSettings.level = value;
                    LogManager.applySettings(logSettings);
                    shell.services().configStore().save();
                });

        Button cleanNow = Ui.ghostButton("settings.logs.cleanNow", event -> {
            var result = LogCleaner.clean(shell.services().context().paths().logsDir(), logSettings,
                    LogManager.launcherLogFile());
            shell.toast().show("已清理 " + result.deletedFiles() + " 个文件（释放 "
                    + Ui.humanSize(result.freedBytes()) + "）", Toast.Kind.SUCCESS);
        }, Icons.create(Icons.TRASH, 14));
        // 自更新期间要留着日志看它到底卡在哪一步，不允许清空
        cleanNow.setDisable(shell.selfUpdating());

        return Ui.glassCard(12,
                Ui.cardHeader("settings.logs", cleanNow),
                Ui.settingRow("settings.logs.retainDays", retainDays, null),
                Ui.settingRow("settings.logs.maxTotal", maxTotal, null),
                Ui.settingRow("settings.logs.maxFile", maxFile, null),
                Ui.settingRow("settings.logs.maxFiles", maxFiles, null),
                Ui.settingRow("settings.logs.gameOutput", gameOutput, null),
                Ui.settingRow("settings.logs.console", console, null),
                Ui.settingRow("settings.logs.level", level, null));
    }

    // ==================================================================

    /**
     * 设置项里的数值滑杆：一律带可编辑的数值框。
     * <p>返回「滑杆 + 数值框」的组合节点。数值框是最终生效值并负责合法性校验
     * （非数字回退、越界夹取），详见 {@link Ui#sliderField}。</p>
     */
    private Region slider(double min, double max, double value, java.util.function.DoubleConsumer onChange) {
        return Ui.sliderField(min, max, value, onChange).node();
    }

    /** 供外部在 CurseForge Key 变更后刷新下载中心的可用性提示。 */
    public String maskedCurseForgeKey() {
        return CurseForgeProvider.maskKey(
                com.misaka.launcher.core.config.LauncherConfig.ModsSettings
                        .BUILT_IN_CURSEFORGE_API_KEY);
    }

    /** 与日志页共享的日志目录，便于调试。 */
    public Path logsDir() {
        return shell.services().context().paths().logsDir();
    }
}
