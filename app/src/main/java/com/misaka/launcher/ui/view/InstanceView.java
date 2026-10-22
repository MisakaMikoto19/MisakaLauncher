package com.misaka.launcher.ui.view;

import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.instance.GameOptionsPreset;
import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.install.InstallResult;
import com.misaka.launcher.core.launch.LaunchPresetFactory;
import com.misaka.launcher.core.meta.JavaVersionRule;
import com.misaka.launcher.core.task.ProgressListener;
import com.misaka.launcher.core.meta.LoaderMetaService;
import com.misaka.launcher.core.model.LoaderType;
import com.misaka.launcher.core.model.VersionManifest;
import com.misaka.launcher.ui.component.Dialogs;
import com.misaka.launcher.ui.component.Icons;
import com.misaka.launcher.ui.component.Ui;
import com.misaka.launcher.ui.shell.AppShell;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.List;

/**
 * 实例与版本安装视图。
 * <p>一个页面上同时承载三件事：实例列表、选中实例的详细设置、以及「安装游戏」向导。
 * 它们天然属于同一工作流（选实例 → 定版本 → 装加载器），拆成多个页面反而增加往返成本。</p>
 */
public final class InstanceView extends ScrollPane {

    private static final Logger log = LoggerFactory.getLogger(InstanceView.class);

    /** 内存可调下限（MB）。低于此值游戏基本无法运行。 */
    private static final int MEMORY_FLOOR_MB = 512;

    /** Java 版本下拉里「自动」的哨兵值：按 Minecraft 版本自动判定。 */
    private static final String AUTO_JAVA = "auto";

    /**
     * 内存可调上限（MB）= 本机物理内存。
     *
     * <p>取值策略集中在 {@link LaunchPresetFactory#memoryCeilingMb()}：它读的是系统物理内存，
     * 而不是 {@code Runtime.maxMemory()}（那只是启动器自身 JVM 的堆上限）。
     * 旧实现把滑杆上限建在后者之上，于是 16GB 机器上最高只能拉到 4GB。</p>
     */
    private static final int MEMORY_CEILING_MB = LaunchPresetFactory.memoryCeilingMb();

    private final AppShell shell;
    private final ListView<LauncherConfig.InstanceEntry> instanceList = new ListView<>();
    private final TextField nameField = Ui.textField("instance.name", "");
    private final TextField dirField = Ui.textField("instance.gameDir", "");
    private final TextField groupField = Ui.textField("instance.group", "");
    private final TextArea notesArea = new TextArea();
    /** 实例级 Java 主版本选择（{@link #AUTO_JAVA} 表示按版本自动判定）。 */
    private ComboBox<String> javaVersionCombo;
    /** 实例级 JVM 参数；留空表示跟随设置里的全局配置。 */
    private final TextArea jvmArgsArea = new TextArea();

    /** 详情区的模组加载器状态与「安装 / 移除」入口。 */
    private final Label loaderLabel = Ui.label("", "card-value");
    private Button removeLoaderButton;
    private Button installLoaderButton;
    /**
     * 内存分配：「拖动条 + 可编辑数值」组合控件。
     * <p>数值框里的数才是最终应用值（拖动条同步到该位置），并负责合法性校验
     * （非数字回退、超出本机物理内存时夹取），详见 {@link Ui#sliderField}。</p>
     */
    private final Ui.SliderField memoryField = Ui.sliderField(
            MEMORY_FLOOR_MB, MEMORY_CEILING_MB, 4096, value -> {
                updateMemoryHint((int) value);
                // 内存改动即时生效：拖动/输入提交（回车或失焦）时就写回实例，
                // 不等「保存」按钮 —— 用户调完内存直接点启动，用的必须是新值
                persistMemory((int) value);
            });
    /** 数值框只显示数字，因此「是否等于机器自适应建议」单独用一行说明。 */
    private final Label memoryAutoHint = Ui.label("", "mute-2");
    /** 机器自适应建议值缓存；避免拖动滑杆时反复探测系统信息。 */
    private Integer autoMemoryCache;

    private final Button releasePill = Ui.pill(I18n.t("install.release"), true, null);
    private final Button snapshotPill = Ui.pill(I18n.t("install.snapshot"), false, null);
    private final Button oldPill = Ui.pill(I18n.t("install.oldAlpha"), false, null);

    private final ListView<VersionManifest.VersionRef> versionList = new ListView<>();
    /** 版本清单状态行：加载中 / 共 N 个版本 / 获取失败（附原因，可通过刷新重试）。 */
    private final Label versionStatus = Ui.label("", "mute-2");
    /**
     * 加载器卡片（Forge / NeoForge / Fabric / Quilt / OptiFine）。
     *
     * <p>改成卡片多选而非下拉单选的直接原因：Forge 与 OptiFine 是<b>可共存</b>的
     * （Fabric 与 Forge 之类则互斥），单选列表无法表达这种组合关系。
     * 卡片各自带一个版本选择控件；互斥关系由 {@link LoaderType#conflictsWith} 驱动，
     * 勾选时会自动取消冲突项。</p>
     */
    private final List<LoaderCard> loaderCards = new ArrayList<>();
    /** 分组筛选框；选「全部分组」时不筛。 */
    private ComboBox<String> groupFilter;
    /** 未经筛选的完整实例列表：分组筛选只在内存里做，不碰配置。 */
    private final List<LauncherConfig.InstanceEntry> allInstances = new ArrayList<>();
    /** 安装主按钮：文案随「选中版本 + 已勾选加载器」变化，提成字段才能就地更新。 */
    private Button installGameButton;
    /**
     * 每个加载器「希望预选」的版本（来自当前实例已装的那个）。
     *
     * <p>版本列表是异步拉的，且切换实例时版本下拉的选中会再触发一次重载 ——
     * 只把版本号当局部变量传下去会在第二次重载时丢失，所以记在这里。</p>
     */
    private final java.util.Map<LoaderType, String> preferredLoaderVersions =
            new java.util.EnumMap<>(LoaderType.class);
    private final FlowPane loaderFlow = new FlowPane(12, 12);
    /** 编程修改勾选态时的防重入标志：避免「自动取消冲突项」再触发一次冲突处理。 */
    private boolean mutatingLoaders;

    private final ComboBox<String> sourceCombo = buildSourceCombo();
    private final Label installHint = Ui.label("", "mute-2");

    /**
     * 下载源下拉框。
     * <p>顺序按「默认会走的路径」排：自动 → 仅官方 → 镜像 → 自定义；
     * 值到文案的映射由 {@link Ui#localizeSourceCombo} 负责（否则显示的是 {@code auto} 之类的内部值）。</p>
     */
    private ComboBox<String> buildSourceCombo() {
        ComboBox<String> box = Ui.comboBox(List.of(
                        LauncherConfig.DownloadSettings.SOURCE_AUTO,
                        LauncherConfig.DownloadSettings.SOURCE_OFFICIAL,
                        LauncherConfig.DownloadSettings.SOURCE_BMCLAPI,
                        LauncherConfig.DownloadSettings.SOURCE_CUSTOM),
                LauncherConfig.DownloadSettings.SOURCE_AUTO, this::onSourceChanged);
        Ui.localizeSourceCombo(box);
        return box;
    }

    /** 版本类型筛选。 */
    private enum VersionFilter { RELEASE, SNAPSHOT, OLD }

    private VersionFilter currentFilter = VersionFilter.RELEASE;
    private List<VersionManifest.VersionRef> loadedVersions = List.of();

    public InstanceView(AppShell shell) {
        this.shell = shell;

        VBox content = new VBox(16, buildInstanceSection(), buildDetailSection(), buildInstallSection());
        Ui.mountScrollable(this, content, Ui.CONTENT_MAX_WIDTH);

        releasePill.setOnAction(event -> applyFilter(VersionFilter.RELEASE));
        snapshotPill.setOnAction(event -> applyFilter(VersionFilter.SNAPSHOT));
        oldPill.setOnAction(event -> applyFilter(VersionFilter.OLD));

        // 切换游戏版本后，已勾选加载器的版本要跟着自动切换（默认选最新适配版本）
        versionList.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> refreshAllSelectedLoaders());

        refresh();
        loadVersions(false);
    }

    // ==================================================================
    // 实例
    // ==================================================================

    private Node buildInstanceSection() {
        instanceList.setPrefHeight(190);
        instanceList.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(LauncherConfig.InstanceEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label title = Ui.label(item.name, "section-title");
                Label meta = Ui.label(instanceSummary(item), "mute-2");
                // 分组用徽标挂在标题后面：填了分组却哪儿都不显示，等于这个字段不存在
                VBox box;
                if (item.group != null && !item.group.isBlank()) {
                    Label chip = Ui.label(item.group.trim(), "badge");
                    HBox head = new HBox(8, title, chip);
                    head.setAlignment(Pos.CENTER_LEFT);
                    box = new VBox(2, head, meta);
                } else {
                    box = new VBox(2, title, meta);
                }
                setGraphic(box);
                setText(null);
            }
        });
        instanceList.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, value) -> fillDetails(value));

        Button create = Ui.ghostButton("instance.create", event -> createInstance(),
                Icons.create(Icons.PLUS, 14));
        // 新建/删除都会改数据目录，自更新期间不许动手（更新结束会退出启动器打断它）
        create.setDisable(shell.selfUpdating());
        Button remove = Ui.dangerButton("action.delete", event -> deleteInstance());
        remove.setDisable(shell.selfUpdating());
        Button use = Ui.ghostButton("instance.switch", event -> useSelected(), Icons.create(Icons.PLAY, 13));

        // 分组筛选：实例多了以后，「同一套整合包的几个版本」需要能一键只看这一组
        groupFilter = Ui.comboBox(new ArrayList<>(List.of(groupAllLabel())), groupAllLabel(),
                value -> applyGroupFilter());
        groupFilter.setPrefWidth(150);
        groupFilter.setMaxWidth(150);

        HBox actions = new HBox(8, groupFilter, create, use, remove);
        return Ui.glassCard(12, Ui.cardHeader("instance.title", actions), instanceList);
    }

    private Node buildDetailSection() {
        Slider memorySlider = memoryField.slider();
        memorySlider.setMajorTickUnit(1024);
        // 大步长按 128MB 走：范围可达数十 GB，逐 MB 调节没有意义，
        // 且与自动预设的对齐粒度保持一致
        memorySlider.setBlockIncrement(128);
        memoryField.setValue(autoMemoryMb());
        updateMemoryHint(autoMemoryMb());

        notesArea.setPrefRowCount(2);
        notesArea.setWrapText(true);

        Button browse = Ui.ghostButton("action.browse", event -> chooseDirectory(),
                Icons.create(Icons.FOLDER, 14));
        HBox dirRow = new HBox(8, dirField, browse);
        HBox.setHgrow(dirField, Priority.ALWAYS);

        // 实例级 Java：列出已登记的运行时（按目录区分），「自动」表示按 Minecraft 版本判定
        javaVersionCombo = Ui.comboBox(List.of(AUTO_JAVA), AUTO_JAVA, null);
        javaVersionCombo.setMaxWidth(Double.MAX_VALUE);
        // 每次打开下拉时重建选项：JDK 往往是页面创建之后才装好的（安装任务会顺带装 Java、
        // 或在设置页手动装），静态快照会让用户「看不到刚装好的 Java」，表现得像无法选择
        javaVersionCombo.setOnShowing(event -> {
            LauncherConfig.InstanceEntry current = instanceList.getSelectionModel().getSelectedItem();
            if (current != null) {
                rebuildJavaChoices(current);
            }
        });
        javaVersionCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String value) {
                // value 可能为 null：getItems().setAll(...) 会先清空列表，
                // 期间下拉框会用「当前值 = null」回调本方法 —— 不处理就是 NPE，
                // 而异常会打断整个填充过程，表现为「Java 版本下拉里什么都没有」
                if (value == null || value.isBlank() || AUTO_JAVA.equals(value)) {
                    return I18n.t("instance.javaVersion.auto");
                }
                return describeRuntime(value);
            }

            @Override
            public String fromString(String value) {
                return value;
            }
        });

        jvmArgsArea.setPrefRowCount(3);
        jvmArgsArea.setWrapText(true);
        jvmArgsArea.setPromptText(I18n.t("instance.jvmArgs.prompt"));

        Button save = Ui.primaryButton("action.save", event -> saveDetails());

        VBox form = new VBox(10,
                Ui.settingRow("instance.name", nameField, null),
                Ui.settingRow("instance.gameDir", dirRow, I18n.t("instance.delete.gameDirToo")),
                Ui.settingRow("instance.group", groupField, I18n.t("instance.group.tip")),
                Ui.settingRow("instance.memory", memoryBox(),
                        I18n.t("instance.memory.hint", MEMORY_CEILING_MB)),
                Ui.divider(),
                Ui.settingRow("instance.javaVersion", javaVersionCombo,
                        I18n.t("instance.javaVersion.tip")),
                Ui.settingRow("instance.jvmArgs", jvmArgsArea,
                        I18n.t("instance.jvmArgs.tip")),
                Ui.divider(),
                Ui.settingRow("instance.loader", loaderRow(), I18n.t("instance.loader.tip")),
                Ui.divider(),
                Ui.settingRow("instance.options", optionsPresetRow(), I18n.t("instance.options.tip")),
                Ui.divider(),
                Ui.settingRow("instance.notes", notesArea, null),
                new HBox(save));

        return Ui.glassCard(12, Ui.cardHeader("instance.current"), form);
    }

    /**
     * 模组加载器行：当前状态 + 「安装加载器」（选类型，取该游戏版本下最新版）+ 「移除」。
     *
     * <p>加载器绑死在实例的游戏版本上，所以这里不给版本选择 ——
     * 换游戏版本必须新建实例，加载器只是挂在实例上的可选组件。</p>
     */
    private Region loaderRow() {
        installLoaderButton = Ui.ghostButton("instance.loader.install",
                event -> showLoaderMenu(), Icons.create(Icons.PLUS, 13));
        removeLoaderButton = Ui.dangerButton("action.removeLoader", event -> removeLoader());
        HBox row = new HBox(10, loaderLabel, installLoaderButton, removeLoaderButton);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * 移除当前实例的模组加载器：把实例切回它的原版版本。
     *
     * <p><b>不删除磁盘上的加载器文件</b>：那些文件（版本 JSON、运行库、打过补丁的客户端）
     * 是加载器安装的产物，留着既能让用户随时再装回来，也避免误删后要求重下几百 MB。
     * 用户若确实想清理，可自行删除游戏目录里的对应版本文件夹。</p>
     */
    private void removeLoader() {
        LauncherConfig.InstanceEntry entry = instanceList.getSelectionModel().getSelectedItem();
        if (entry == null) {
            return;
        }
        LoaderType loader = LoaderType.fromId(entry.loaderType);
        if (loader.isVanilla()) {
            shell.toast().show(I18n.t("instance.loader.noneToRemove"));
            return;
        }
        String vanillaId = entry.mcVersion == null || entry.mcVersion.isBlank()
                ? entry.versionId : entry.mcVersion;
        if (!Dialogs.confirm(shell, I18n.t("instance.loader.remove.confirmTitle"),
                I18n.t("instance.loader.remove.confirmMessage", entry.name, vanillaId),
                "action.removeLoader")) {
            return;
        }
        shell.services().instances().updateVersion(entry.id, vanillaId, LoaderType.VANILLA, "", "");
        refresh();
        shell.refreshShell();
        shell.toast().show(I18n.t("instance.loader.removed"),
                com.misaka.launcher.ui.component.Toast.Kind.SUCCESS);
    }

    /**
     * 「应用键位预设」行。
     *
     * <p>安装游戏时已经自动铺过一次（且只在游戏目录还没有 {@code options.txt} 时）。
     * 这颗按钮补的是它覆盖不到的场景：进过一次游戏后 Minecraft 自己写了一份
     * {@code options.txt}，或者玩家把键位改乱了想刷回出厂状态。</p>
     */
    private Region optionsPresetRow() {
        Button apply = Ui.ghostButton("instance.options.apply",
                event -> applyOptionsPreset(), Icons.create(Icons.REFRESH, 13));
        return new HBox(10, apply);
    }

    /**
     * 用内置预设整份覆盖游戏目录里的 {@code options.txt}。
     *
     * <p><b>必须先确认</b>：这是覆盖写，玩家自己的键位、画面与音量都会被替换掉，
     * 而选项文件没有版本历史，撤销不了。</p>
     */
    private void applyOptionsPreset() {
        LauncherConfig.InstanceEntry entry = instanceList.getSelectionModel().getSelectedItem();
        if (entry == null) {
            return;
        }
        // 打包遗漏时直接说清楚，而不是让用户点了一颗没反应的按钮
        if (!GameOptionsPreset.isBundled()) {
            shell.toast().show(I18n.t("instance.options.missing"),
                    com.misaka.launcher.ui.component.Toast.Kind.ERROR);
            return;
        }
        if (!Dialogs.confirm(shell, I18n.t("instance.options.confirmTitle"),
                I18n.t("instance.options.confirmMessage", entry.name), "action.apply")) {
            return;
        }
        try {
            GameOptionsPreset.overwrite(shell.services().instances().gamePaths(entry));
            shell.toast().show(I18n.t("instance.options.applied"),
                    com.misaka.launcher.ui.component.Toast.Kind.SUCCESS);
        } catch (IOException e) {
            shell.toast().show(I18n.t("instance.options.failed", String.valueOf(e.getMessage())),
                    com.misaka.launcher.ui.component.Toast.Kind.ERROR);
        }
    }

    private void createInstance() {
        LauncherConfig.InstanceEntry created = shell.services().instances().create("新实例");
        refresh();
        instanceList.getSelectionModel().select(created);
        shell.refreshShell();
        shell.toast().show(I18n.t("instance.create"));
    }

    private void useSelected() {
        LauncherConfig.InstanceEntry selected = instanceList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        shell.services().instances().switchTo(selected.id);
        // 实例切换会改变所有页面的数据源，因此整体失效重建。
        // mods / packs 之前漏了：切完实例再进这两个页面，看到的还是上一个实例的内容
        for (String page : List.of("home", "download", "logs", "mods", "packs")) {
            shell.invalidatePage(page);
        }
        shell.refreshShell();
        shell.toast().show(I18n.t("instance.switch"));
    }

    private void deleteInstance() {
        LauncherConfig.InstanceEntry selected = instanceList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        // 删除前把「将失去什么」算清楚给用户看：路径、文件数、总体积
        Path gameDir = selected.gameDir == null || selected.gameDir.isBlank()
                ? null : Path.of(selected.gameDir);
        long files = 0;
        long bytes = 0;
        if (gameDir != null && Files.isDirectory(gameDir)) {
            try (var stream = Files.walk(gameDir)) {
                List<Path> regular = stream.filter(Files::isRegularFile).toList();
                files = regular.size();
                bytes = regular.stream().mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (IOException e) {
                        return 0L;
                    }
                }).sum();
            } catch (IOException e) {
                log.debug("统计游戏目录失败: {}", e.getMessage());
            }
        }
        String message = files > 0
                ? I18n.t("instance.delete.confirmMessage", selected.name, gameDir, files, Ui.humanSize(bytes))
                : I18n.t("instance.delete.confirmNoFiles", selected.name);
        if (!Dialogs.confirm(shell, I18n.t("instance.delete.confirmTitle"), message, "action.delete")) {
            return;
        }
        shell.services().instances().delete(selected.id, files > 0);
        refresh();
        shell.refreshShell();
    }

    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.t("instance.gameDir"));
        File chosen = chooser.showDialog(shell.stage());
        if (chosen != null) {
            dirField.setText(chosen.getAbsolutePath());
        }
    }

    private void fillDetails(LauncherConfig.InstanceEntry entry) {
        if (entry == null) {
            // 没选中实例时安装区不该保留上一个实例的勾选，否则很容易误装到别的实例上
            syncLoaderSelection(null);
            return;
        }
        // 选中实例就先按磁盘事实核对一遍：版本 JSON 在不在、mcVersion / 加载器信息全不全。
        // 用户（尤其从旧版本升上来的配置）经常遇到"实例明明装好了、界面却说不认识"的情况，
        // 这里主动检测并回填，比让用户自己去猜要可靠得多
        if (shell.services().instances().syncVersionMetadata(entry)) {
            instanceList.refresh();
            shell.refreshShell();
        }
        nameField.setText(entry.name);
        dirField.setText(entry.gameDir);
        groupField.setText(entry.group == null ? "" : entry.group);
        notesArea.setText(entry.notes == null ? "" : entry.notes);
        int memory = entry.maxMemoryMb > 0 ? entry.maxMemoryMb : autoMemoryMb();
        // 存的值可能来自另一台机器（或更早的版本），先夹进当前机器的可调范围，
        // 否则滑杆会把值静默截断，而标签仍显示原值
        memory = (int) Math.min(MEMORY_CEILING_MB, Math.max(MEMORY_FLOOR_MB, memory));
        memoryField.setValue(memory);
        updateMemoryHint(memory);

        // Java 版本：列出全部已登记的运行时并选中实例指定的那一个
        if (javaVersionCombo != null) {
            rebuildJavaChoices(entry);
        }
        jvmArgsArea.setText(entry.jvmArgsOverride == null
                ? "" : String.join("\n", entry.jvmArgsOverride));
        updateLoaderRow(entry);
        // 安装区要反映「这个实例现在装的是什么」：否则用户看到一片未勾选，
        // 会以为没装加载器，或者不小心把加载器版本改成最新版
        syncLoaderSelection(entry);
    }

    /**
     * 把实例已装的加载器同步到安装区的勾选状态。
     *
     * <p>之前切实例只会把卡片重置成「未勾选 + 最新版」：实例明明写着
     * {@code Forge 47.4.26}，安装区却什么都没勾；想重装或换个加载器版本时，
     * 用户得自己重新勾一遍，还容易在没注意的情况下把版本改成最新版 ——
     * 对整合包来说这会直接换掉整套运行环境。</p>
     *
     * <p>{@code entry == null} 表示没有选中实例：把所有勾选与版本清空。
     * 整个过程套在 {@code mutatingLoaders} 里，避免「程序同步」被当成用户操作
     * 而弹出加载器冲突提示。</p>
     */
    private void syncLoaderSelection(LauncherConfig.InstanceEntry entry) {
        if (loaderCards.isEmpty()) {
            return;
        }
        LoaderType loader = LoaderType.fromId(entry == null ? null : entry.loaderType);
        String loaderVersion = entry == null || entry.loaderVersion == null
                ? "" : entry.loaderVersion.trim();
        String optifineVersion = entry == null || entry.optifineVersion == null
                ? "" : entry.optifineVersion.trim();

        preferredLoaderVersions.clear();
        mutatingLoaders = true;
        try {
            for (LoaderCard card : loaderCards) {
                boolean checked = !loader.isVanilla() && card.type == loader;
                card.toggle.setSelected(checked);
                card.versionControl.setDisable(!checked);
                if (!checked) {
                    clearVersionControl(card);
                }
            }
            // OptiFine 是附加加载器，与主加载器可以并存，单独回填
            LoaderCard optifineCard = cardOf(LoaderType.OPTIFINE);
            if (optifineCard != null && !optifineVersion.isBlank()) {
                optifineCard.toggle.setSelected(true);
                optifineCard.versionControl.setDisable(false);
                if (optifineCard.versionControl instanceof TextField field) {
                    field.setText(optifineVersion);
                }
            }
        } finally {
            mutatingLoaders = false;
        }

        LoaderCard card = cardOf(loader);
        if (card != null && card.toggle.isSelected()) {
            // 记下实例当前的加载器版本：版本列表是异步拉的，回来的那一刻要按它选中，
            // 而不是一律选最新
            if (!loaderVersion.isBlank()) {
                preferredLoaderVersions.put(card.type, loaderVersion);
            }
            refreshLoaderVersions(card);
        }
        updateInstallAction();
    }

    /** 刷新加载器显示与「移除」按钮的可用性。 */
    private void updateLoaderRow(LauncherConfig.InstanceEntry entry) {
        LoaderType loader = LoaderType.fromId(entry == null ? null : entry.loaderType);
        StringBuilder text = new StringBuilder();
        if (loader.isVanilla()) {
            text.append(I18n.t("instance.loader.none"));
        } else {
            text.append(loader.displayName());
            if (entry.loaderVersion != null && !entry.loaderVersion.isBlank()) {
                text.append(' ').append(entry.loaderVersion);
            }
            if (entry.optifineVersion != null && !entry.optifineVersion.isBlank()) {
                text.append(" + OptiFine ").append(entry.optifineVersion);
            }
        }
        loaderLabel.setText(text.toString());
        if (removeLoaderButton != null) {
            removeLoaderButton.setDisable(loaderLocked(entry) || loader.isVanilla());
        }
        if (installLoaderButton != null) {
            // 游戏版本还没装好时无从匹配加载器版本，先禁用并给出原因
            installLoaderButton.setDisable(loaderLocked(entry) || instanceMcVersion(entry).isBlank());
        }
    }

    /**
     * 加载器的「安装 / 移除」此刻是否应当锁住。
     *
     * <p>两个理由：没有选中实例（无从动手），或启动器正在自更新 ——
     * 更新结束会退出启动器，装到一半的加载器补丁 jar 会留在实例目录里。</p>
     */
    private boolean loaderLocked(LauncherConfig.InstanceEntry entry) {
        return entry == null || shell.selfUpdating();
    }

    /**
     * 重建 Java 选择列表：启动器安装的与本机发现的运行时都会出现，
     * 选项文本带目录，多个同类 JDK 才能区分开。
     */
    private void rebuildJavaChoices(LauncherConfig.InstanceEntry entry) {
        List<String> items = new ArrayList<>();
        items.add(AUTO_JAVA);
        for (LauncherConfig.JavaRuntimeEntry runtime : shell.services().java().registered()) {
            if (runtime.home != null && !runtime.home.isBlank()) {
                items.add(runtime.home);
            }
        }
        // 实例此前指向的目录可能已被移出登记表（例如手动删掉）：保留该选项让用户知情
        String current = entry.javaPath == null ? "" : entry.javaPath;
        if (!current.isBlank() && !items.contains(current)) {
            items.add(current);
        }
        javaVersionCombo.getItems().setAll(items);
        javaVersionCombo.setValue(current.isBlank() ? AUTO_JAVA : current);
    }

    /**
     * 运行时的展示文案：{@code <目录>（Java <主版本> · <发行版>）} —— 按目录选择，版本只作参考。
     *
     * <p>对空值与非法路径都要兜住：本方法会被下拉框的 {@code StringConverter} 调用，
     * 任何异常都会打断列表填充（历史上就是这里把 Java 下拉弄成了空的）。</p>
     */
    private String describeRuntime(String home) {
        if (home == null || home.isBlank()) {
            return "";
        }
        Path path;
        try {
            path = Path.of(home);
        } catch (RuntimeException e) {
            // 不是合法路径（用户手改过配置等）：原样显示，不给用户添乱
            return home;
        }
        return shell.services().java().findEntryByHome(path)
                .map(entry -> entry.home + "（" + JavaVersionRule.describe(entry.majorVersion)
                        + " · " + entry.vendor + "）")
                .orElse(home);
    }

    /** 机器自适应建议值（由本机物理内存推导）。 */
    private int autoMemoryMb() {
        Integer cached = autoMemoryCache;
        if (cached == null) {
            cached = shell.services().launchPreset(0, 0).maxMemoryMb();
            autoMemoryCache = cached;
        }
        return cached;
    }

    /** 内存分配行：数值框 + 单位 + 自适应标注。 */
    private Region memoryBox() {
        HBox row = new HBox(8, memoryField.node(), Ui.label("MB", "mute-2"));
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(4, row, memoryAutoHint);
    }

    /**
     * 与机器自适应建议一致时标注「自动适配本机」。
     * <p>让用户知道这个值还会跟着机器变，而不是被固定钉死在这个数字上。</p>
     */
    private void updateMemoryHint(int memory) {
        memoryAutoHint.setText(memory == autoMemoryMb() ? I18n.t("instance.memory.auto") : "");
    }

    /**
     * 实例级 Java 目录：{@link #AUTO_JAVA} 或未指定时返回空串（交给启动流程按版本判定）。
     */
    private String selectedJavaPath() {
        String value = javaVersionCombo == null ? AUTO_JAVA : javaVersionCombo.getValue();
        if (value == null || AUTO_JAVA.equals(value) || value.isBlank()) {
            return "";
        }
        return value.trim();
    }

    /** 内存改动即时写回当前实例（滑杆/数值框提交时触发，无需点保存）。 */
    private void persistMemory(int memory) {
        LauncherConfig.InstanceEntry entry = instanceList.getSelectionModel().getSelectedItem();
        if (entry == null) {
            return;
        }
        // 与机器自适应建议相同时存 0（= 跟随机器）：否则会把「当时这台机器的一半内存」
        // 永久钉死在实例上，换到更大的机器也不会再自动提升
        entry.maxMemoryMb = memory == autoMemoryMb() ? 0 : memory;
        shell.services().instances().save(entry);
    }

    private void saveDetails() {
        LauncherConfig.InstanceEntry entry = instanceList.getSelectionModel().getSelectedItem();
        if (entry == null) {
            return;
        }
        String newDir = dirField.getText().trim();
        if (!newDir.isBlank()) {
            Path path = Path.of(newDir).toAbsolutePath().normalize();
            if (shell.services().instances().gameDirInUse(path, entry.id)) {
                shell.toast().show(I18n.t("instance.gameDir") + " 已被其他实例占用", com.misaka.launcher
                        .ui.component.Toast.Kind.ERROR);
                return;
            }
            entry.gameDir = path.toString();
        }
        entry.name = nameField.getText().isBlank() ? entry.name : nameField.getText().trim();
        entry.group = groupField.getText().trim();
        entry.notes = notesArea.getText();
        entry.javaPath = selectedJavaPath();
        // 留空 = 跟随设置里的全局启动参数；有内容则整份替换全局
        entry.jvmArgsOverride = splitLines(jvmArgsArea.getText());
        persistMemory(memoryField.current());
        shell.services().instances().save(entry);
        shell.services().instances().ensurePrepared(entry);
        refresh();
        shell.refreshShell();
        shell.toast().show(I18n.t("toast.saved"), com.misaka.launcher.ui.component.Toast.Kind.SUCCESS);
    }

    // ==================================================================
    // 安装
    // ==================================================================

    private Node buildInstallSection() {
        HBox pills = new HBox(8, releasePill, snapshotPill, oldPill);

        versionList.setPrefHeight(220);
        versionList.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(VersionManifest.VersionRef item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label title = Ui.mono(item.id);
                Label meta = Ui.label(item.type + " · " + shorten(item.releaseTime), "mute-2");
                HBox row = new HBox(10, title, meta);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
                setText(null);
            }
        });

        // 刷新按钮紧贴版本列表：获取失败时用户第一眼就能看到「重试」的位置
        Button refresh = Ui.ghostButton("install.versions.refresh",
                event -> loadVersions(true), Icons.create(Icons.REFRESH, 14));
        Button install = Ui.primaryButton("action.install", event -> install(),
                Icons.create(Icons.DOWNLOAD, 15));
        // 装到一个一半被「退出启动器」打断，游戏目录里就是半成品
        install.setDisable(shell.selfUpdating());

        // 加载器卡片（多选 + 版本选择 + 冲突约束）
        loaderCards.clear();
        loaderFlow.getChildren().clear();
        for (LoaderType type : List.of(LoaderType.FORGE, LoaderType.NEOFORGE,
                LoaderType.FABRIC, LoaderType.QUILT, LoaderType.OPTIFINE)) {
            LoaderCard card = buildLoaderCard(type);
            loaderCards.add(card);
            loaderFlow.getChildren().add(card.root);
        }

        VBox loaderBox = new VBox(8, Ui.i18n("install.loader.caption", "muted"), loaderFlow);
        installHint.setText(I18n.t("install.loader.none"));

        // 这里只负责「新建实例」：游戏版本一旦确定就锁定，改版本必须新建 ——
        // 否则存档、模组、加载器全都会对不上号，是很贵的隐性代价。
        // 给现有实例加加载器走实例详情的「安装加载器」。
        Label versionLock = Ui.label(I18n.t("install.versionLock"), "mute-2");
        versionLock.setWrapText(true);

        installGameButton = install;
        updateInstallAction();

        // 主按钮单独放在底部（分割线 + 一行说明），不再紧贴加载器卡片：
        // 之前它就在加载器卡片下面，读起来像「安装加载器」，而它装的是上面选中的游戏版本，
        // 加载器只是这一批里顺带勾选的可选项。
        VBox form = new VBox(10,
                versionLock,
                Ui.settingRow("install.source", sourceCombo, null),
                loaderBox,
                installHint,
                Ui.divider(),
                new HBox(8, install));

        VBox listCard = Ui.glassCard(10,
                Ui.cardHeader("install.versions", pills, refresh), versionList, versionStatus);
        VBox formCard = Ui.glassCard(12, Ui.cardHeader("install.title"), form);

        return new VBox(14, listCard, formCard);
    }

    /**
     * 选择要安装的模组加载器类型。
     *
     * <p><b>版本自动取最新</b>：加载器版本号五花八门（47.4.26 / 0.15.11 / 20.4.235-beta），
     * 在这里再放一个版本下拉只会让用户挑错；需要指定版本时走「新建实例」里的完整列表。</p>
     */
    private void showLoaderMenu() {
        LauncherConfig.InstanceEntry entry = instanceList.getSelectionModel().getSelectedItem();
        if (entry == null) {
            return;
        }
        if (instanceMcVersion(entry).isBlank()) {
            shell.toast().show(I18n.t("instance.loader.noGameVersion"),
                    com.misaka.launcher.ui.component.Toast.Kind.ERROR);
            return;
        }
        ContextMenu menu = new ContextMenu();
        menu.setStyle(com.misaka.launcher.ui.theme.Fonts.rootStyleFragment());
        for (LoaderType type : List.of(LoaderType.FORGE, LoaderType.NEOFORGE,
                LoaderType.FABRIC, LoaderType.QUILT)) {
            MenuItem item = new MenuItem(type.displayName());
            item.setOnAction(event -> installLoader(type));
            menu.getItems().add(item);
        }
        menu.show(installLoaderButton, Side.TOP, 0, 0);
    }

    /** 实例的游戏版本（加载器必须与它匹配，且不可更改）。 */
    private static String instanceMcVersion(LauncherConfig.InstanceEntry entry) {
        if (entry == null) {
            return "";
        }
        if (entry.mcVersion != null && !entry.mcVersion.isBlank()) {
            return entry.mcVersion;
        }
        return entry.versionId == null ? "" : entry.versionId;
    }

    /**
     * 把指定加载器安装进当前实例（游戏版本保持不变）。
     *
     * <p>与「新建实例」的区别只在于目标：加载器牢牢绑定实例的游戏版本，
     * 因此这里不支持选版本 —— 这也是「游戏版本不可更改」的直接推论。</p>
     */
    private void installLoader(LoaderType type) {
        LauncherConfig.InstanceEntry entry = instanceList.getSelectionModel().getSelectedItem();
        if (entry == null) {
            return;
        }
        String mcVersion = instanceMcVersion(entry);
        if (mcVersion.isBlank()) {
            shell.toast().show(I18n.t("instance.loader.noGameVersion"),
                    com.misaka.launcher.ui.component.Toast.Kind.ERROR);
            return;
        }
        GamePaths paths = shell.services().instances().gamePaths(entry);
        // 声明目标实例：安装期间它的启动入口会置灰（别的实例不受影响）
        shell.tasks().runForInstance(entry.id, "install.stage.loader", listener -> {
            listener.onMessage(I18n.t("instance.loader.resolving", type.displayName(), mcVersion));
            List<LoaderMetaService.LoaderVersion> versions =
                    shell.services().loaders().listVersions(type, mcVersion);
            LoaderMetaService.LoaderVersion latest = LoaderMetaService.latest(versions);
            if (latest == null) {
                throw new java.io.IOException(
                        I18n.t("instance.loader.noneAvailable", type.displayName(), mcVersion));
            }
            listener.onMessage(I18n.t("instance.loader.installing", type.displayName(), latest.version()));
            com.misaka.launcher.core.install.LoaderInstallResult result = shell.services()
                    .loaderInstaller(paths)
                    .install(type, mcVersion, latest.version(), listener);
            shell.services().instances().updateVersion(entry.id, result.versionId(), type,
                    latest.version(), entry.optifineVersion);
        }, result -> {
            if (result.success()) {
                shell.toast().show(I18n.t("instance.loader.installed", type.displayName()),
                        com.misaka.launcher.ui.component.Toast.Kind.SUCCESS);
                refresh();
                shell.refreshShell();
            } else if (result.failed()) {
                shell.toast().show(I18n.t("install.failed", String.valueOf(result.error() == null
                        ? "" : result.error().getMessage())),
                        com.misaka.launcher.ui.component.Toast.Kind.ERROR);
            }
        });
    }

    private void applyFilter(VersionFilter filter) {
        currentFilter = filter;
        releasePill.getStyleClass().remove("selected");
        snapshotPill.getStyleClass().remove("selected");
        oldPill.getStyleClass().remove("selected");
        switch (filter) {
            case RELEASE -> releasePill.getStyleClass().add("selected");
            case SNAPSHOT -> snapshotPill.getStyleClass().add("selected");
            case OLD -> oldPill.getStyleClass().add("selected");
        }
        populateVersions();
    }

    /** 拉取版本清单（带缓存，forceRefresh 会强制刷新）。 */
    private void loadVersions(boolean forceRefresh) {
        versionStatus.setText(I18n.t("install.versions.loading"));
        shell.tasks().run("install.stage.meta", listener -> {
            listener.onMessage(I18n.t("install.versions.loading"));
            var manifest = shell.services().versions().manifest(forceRefresh);
            loadedVersions = manifest.versions;
        }, result -> {
            if (result.failed()) {
                // 失败必须留在界面上（而不是只弹一个转瞬即逝的提示）：
                // 用户需要看到原因，并且知道点「刷新版本列表」可以重试
                String reason = result.error() == null ? "" : String.valueOf(result.error().getMessage());
                versionStatus.setText(I18n.t("install.versions.failed", reason));
                shell.toast().show(I18n.t("error.network", reason),
                        com.misaka.launcher.ui.component.Toast.Kind.ERROR);
                return;
            }
            versionStatus.setText(I18n.t("install.versions.count", loadedVersions.size()));
            populateVersions();
        });
    }

    private void populateVersions() {
        List<VersionManifest.VersionRef> filtered = new ArrayList<>();
        for (VersionManifest.VersionRef ref : loadedVersions) {
            boolean match = switch (currentFilter) {
                case RELEASE -> ref.isRelease();
                case SNAPSHOT -> ref.isSnapshot();
                case OLD -> ref.isOld();
            };
            if (match) {
                filtered.add(ref);
            }
        }
        versionList.getItems().setAll(filtered);
        selectInstalledOrDefault();
    }

    /**
     * 版本列表默认选中项：**优先选中当前实例已装的版本**。
     *
     * <p>用户点进实例页就是想看这个实例的情况。默认停在最新快照上既没有信息量，
     * 也容易让人怀疑「我装的版本是不是没生效」。实例的版本恰好被当前筛选隐藏时，
     * 会自动切到能容纳它的筛选（例如实例装的是正式版而列表停在快照上）。</p>
     */
    private void selectInstalledOrDefault() {
        String installed = installedVersionId();
        if (installed != null && !installed.isBlank()) {
            for (VersionManifest.VersionRef ref : versionList.getItems()) {
                if (installed.equals(ref.id)) {
                    versionList.getSelectionModel().select(ref);
                    versionList.scrollTo(ref);
                    return;
                }
            }
            // 当前筛选里没有：找到它属于哪一类，切过去（applyFilter 会重新填充并再次选中）
            for (VersionManifest.VersionRef ref : loadedVersions) {
                if (installed.equals(ref.id)) {
                    VersionFilter target = ref.isRelease() ? VersionFilter.RELEASE
                            : ref.isSnapshot() ? VersionFilter.SNAPSHOT : VersionFilter.OLD;
                    if (target != currentFilter) {
                        applyFilter(target);
                        return;
                    }
                    break;
                }
            }
        }
        if (!versionList.getItems().isEmpty()) {
            versionList.getSelectionModel().selectFirst();
        }
    }

    /**
     * 当前实例的「用户可识别的游戏版本」。
     * <p>原版实例取 {@code versionId}；加载器实例的 {@code versionId} 是
     * {@code 1.20.1-forge-47.4.26} 这类复合串，清单里没有，要用 {@code mcVersion}。</p>
     */
    private String installedVersionId() {
        LauncherConfig.InstanceEntry entry = instanceList.getSelectionModel().getSelectedItem();
        if (entry == null) {
            return null;
        }
        if (entry.mcVersion != null && !entry.mcVersion.isBlank()) {
            return entry.mcVersion;
        }
        return entry.versionId;
    }

    /** 构建单个加载器卡片：勾选框 + 版本选择控件。 */
    private LoaderCard buildLoaderCard(LoaderType type) {
        CheckBox toggle = Ui.checkBox(type.i18nKey(), false,
                checked -> onLoaderToggled(type, checked));
        Region versionControl;
        if (type == LoaderType.OPTIFINE) {
            // OptiFine 没有公开的版本列表接口，只能让用户手动填写版本号
            versionControl = Ui.textField("install.loader.version.prompt", "");
        } else {
            versionControl = Ui.comboBox(List.of(), null, null);
        }
        versionControl.setDisable(true);

        VBox card = Ui.glassCard(8, toggle, versionControl);
        card.setPrefWidth(210);
        return new LoaderCard(type, card, toggle, versionControl);
    }

    /**
     * 勾选 / 取消勾选一个加载器。
     * <p>勾选时自动取消所有与之冲突的项（核心加载器互斥；OptiFine 只与 Forge 共存），
     * 并异步拉取版本列表、默认选最新。</p>
     */
    private void onLoaderToggled(LoaderType type, boolean checked) {
        if (mutatingLoaders) {
            return;
        }
        LoaderCard card = cardOf(type);
        if (card == null) {
            return;
        }
        // 用户自己动了这张卡片：之前从实例同步来的「已装版本」不再适用，
        // 让他重新勾选时看到的是最新的可选版本，而不是被旧偏好钉住
        preferredLoaderVersions.remove(type);
        if (checked) {
            mutatingLoaders = true;
            try {
                for (LoaderCard other : loaderCards) {
                    if (other != card && other.toggle.isSelected() && type.conflictsWith(other.type)) {
                        other.toggle.setSelected(false);
                        other.versionControl.setDisable(true);
                        clearVersionControl(other);
                        shell.toast().show(I18n.t("install.loader.conflict",
                                other.type.displayName()), com.misaka.launcher.ui.component.Toast.Kind.INFO);
                    }
                }
            } finally {
                mutatingLoaders = false;
            }
            card.versionControl.setDisable(false);
            refreshLoaderVersions(card);
        } else {
            card.versionControl.setDisable(true);
            clearVersionControl(card);
        }
        updateInstallAction();
    }

    private LoaderCard cardOf(LoaderType type) {
        for (LoaderCard card : loaderCards) {
            if (card.type == type) {
                return card;
            }
        }
        return null;
    }

    private void clearVersionControl(LoaderCard card) {
        if (card.versionControl instanceof ComboBox<?> box) {
            box.getItems().clear();
        } else if (card.versionControl instanceof TextField field) {
            field.clear();
        }
    }

    /**
     * 拉取某加载器在当前游戏版本下的版本列表，并默认选中最新版。
     * <p>结果回到 FX 线程后校验勾选态：等待期间用户可能已切换版本或取消勾选，
     * 过期结果必须丢弃，否则会把上一个游戏版本的版本号塞进来。</p>
     */
    private void refreshLoaderVersions(LoaderCard card) {
        if (!card.toggle.isSelected() || card.type == LoaderType.OPTIFINE) {
            return;
        }
        VersionManifest.VersionRef selected = versionList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        card.versionControl.setDisable(false);
        installHint.setText(I18n.t("status.working"));
        shell.tasks().run("install.stage.loader", listener -> {
            List<LoaderMetaService.LoaderVersion> versions =
                    shell.services().loaders().listVersions(card.type, selected.id);
            List<String> ids = versions.stream().map(LoaderMetaService.LoaderVersion::version).toList();
            LoaderMetaService.LoaderVersion latest = LoaderMetaService.latest(versions);
            javafx.application.Platform.runLater(() -> {
                if (!card.toggle.isSelected()) {
                    return;
                }
                ComboBox<String> box = card.versionCombo();
                box.getItems().setAll(ids);
                // 实例当前装的那个加载器版本优先（列表里有才选）：用户看到的应当是
                // 「现在装的是什么」，而不是「最新是什么」
                String preferred = preferredLoaderVersions.get(card.type);
                if (preferred != null && ids.contains(preferred)) {
                    box.getSelectionModel().select(preferred);
                } else if (latest != null) {
                    box.getSelectionModel().select(latest.version());
                } else if (!ids.isEmpty()) {
                    box.getSelectionModel().selectFirst();
                }
                installHint.setText(ids.isEmpty()
                        ? card.type.displayName() + "：" + I18n.t("install.loader.none")
                        : card.type.displayName() + " · " + (latest == null ? "" : latest.version()));
                // 加载器版本列表到手后才算「可装什么」确定，按钮文案要跟着更新
                updateInstallAction();
            });
        }, null);
    }

    /** 游戏版本切换后，刷新所有已勾选加载器的版本列表（默认选最新）。 */
    private void refreshAllSelectedLoaders() {
        for (LoaderCard card : loaderCards) {
            if (card.toggle.isSelected()) {
                refreshLoaderVersions(card);
            }
        }
        updateInstallAction();
    }

    /**
     * 刷新安装主按钮：把「这次会装什么」直接写在按钮上。
     *
     * <p>为什么值得这么做：这个按钮原来只叫「安装」，又紧挨着加载器卡片，
     * 很容易被读成「安装加载器」；而它实际装的是<b>上面选中的游戏版本</b>，
     * 加载器只是顺带勾选的可选项。把两者写进按钮文案，比任何说明都直接。</p>
     */
    private void updateInstallAction() {
        if (installGameButton == null) {
            return;
        }
        VersionManifest.VersionRef version = versionList.getSelectionModel().getSelectedItem();
        if (version == null) {
            installGameButton.setText(I18n.t("action.install"));
            installGameButton.setDisable(true);
            return;
        }
        List<String> loaders = new ArrayList<>();
        for (LoaderCard card : loaderCards) {
            if (!card.toggle.isSelected()) {
                continue;
            }
            String loaderVersion = versionOf(card.type);
            loaders.add(loaderVersion.isBlank()
                    ? card.type.displayName()
                    : card.type.displayName() + " " + loaderVersion);
        }
        installGameButton.setDisable(shell.selfUpdating());
        installGameButton.setText(loaders.isEmpty()
                ? I18n.t("install.action.only", version.id)
                : I18n.t("install.action.with", version.id, String.join(" + ", loaders)));
    }

    /** 当前勾选的核心加载器；未勾选返回 {@code null}（= 原版）。 */
    private LoaderType selectedCoreLoader() {
        for (LoaderCard card : loaderCards) {
            if (card.toggle.isSelected() && card.type.isCore()) {
                return card.type;
            }
        }
        return null;
    }

    private boolean isLoaderSelected(LoaderType type) {
        LoaderCard card = cardOf(type);
        return card != null && card.toggle.isSelected();
    }

    private String versionOf(LoaderType type) {
        LoaderCard card = cardOf(type);
        if (card == null) {
            return "";
        }
        if (card.versionControl instanceof ComboBox<?> box) {
            return box.getValue() == null ? "" : String.valueOf(box.getValue());
        }
        if (card.versionControl instanceof TextField field) {
            return field.getText() == null ? "" : field.getText().trim();
        }
        return "";
    }

    /**
     * 按命名约定生成实例名：{@code Minecraft-<版本>-<加载器>-<加载器版本>}。
     *
     * <p>例如 {@code Minecraft-1.20.1-Forge-47.0.1}；原版只有 {@code Minecraft-<版本>}；
     * 叠加 OptiFine 时追加 {@code -OptiFine-<版本>}。加载器名取自 {@link LoaderType#displayName()}
     * （Forge / NeoForge / Fabric / Quilt / OptiFine，均为英文标识）。</p>
     */
    private static String buildInstanceName(String mcVersion, LoaderType core, String coreVersion,
                                            boolean optifine, String optifineVersion) {
        StringBuilder name = new StringBuilder("Minecraft-").append(mcVersion);
        if (core != null && !coreVersion.isBlank()) {
            name.append('-').append(core.displayName()).append('-').append(coreVersion);
        }
        if (optifine && !optifineVersion.isBlank()) {
            name.append("-OptiFine-").append(optifineVersion);
        }
        return name.toString();
    }

    private void onSourceChanged(String source) {
        shell.services().config().download.source = source;
        shell.services().configStore().save();
    }

    /**
     * 安装前准备目标版本所需的 Java。
     *
     * <p>所需主版本由 {@link JavaVersionRule#requiredMajorVersion(String)} 判定
     * （1.16.x → 11、1.20.1 → 17 …），缺失且允许自动安装时先下载安装，
     * 然后才继续装游戏本体与加载器。</p>
     *
     * @param mandatory 是否「没有 Java 就不能继续」。带加载器 / OptiFine 时为 true ——
     *                  它们的安装器本身就是 JVM 程序，缺 Java 只会卡死或失败；
     *                  纯原版安装不依赖 Java，故为 false：装不上也只提示，不阻断下载。
     */
    private void ensureJavaForInstall(String mcVersion, ProgressListener listener, boolean mandatory)
            throws java.io.IOException {
        int required = JavaVersionRule.requiredMajorVersion(mcVersion);
        listener.onStage(I18n.t("install.stage.java", required));
        try {
            shell.services().ensureJava(required, shell.services().config().java.autoInstall, listener);
        } catch (java.io.IOException e) {
            if (mandatory) {
                throw e;
            }
            listener.onMessage("Java " + required + " 尚未就绪（不影响下载游戏文件，启动时会再提示）："
                    + e.getMessage());
        }
    }

    /** 执行安装：点击即自动生成实例，原版走 VanillaInstaller，加载器走 LoaderInstaller，OptiFine 走手动下载。 */
    private void install() {
        VersionManifest.VersionRef version = versionList.getSelectionModel().getSelectedItem();
        if (version == null) {
            shell.toast().show(I18n.t("install.versions"));
            return;
        }

        LoaderType core = selectedCoreLoader();
        boolean optifine = isLoaderSelected(LoaderType.OPTIFINE);
        String coreVersion = core == null ? "" : versionOf(core);
        String optifineVersion = optifine ? versionOf(LoaderType.OPTIFINE) : "";

        // 勾选了但没选版本：明确报错而不是走到安装器才失败
        if (core != null && coreVersion.isBlank()) {
            shell.toast().show(I18n.t("install.loader.version.missing", core.displayName()),
                    com.misaka.launcher.ui.component.Toast.Kind.ERROR);
            return;
        }
        if (optifine && optifineVersion.isBlank()) {
            shell.toast().show(I18n.t("install.loader.version.missing",
                            LoaderType.OPTIFINE.displayName()),
                    com.misaka.launcher.ui.component.Toast.Kind.ERROR);
            return;
        }

        String mcVersion = version.id;
        // 加载器安装一律作用于**当前实例**：新增/更换 ModLoader 不该凭空多出一个实例。
        // 只有「先新建实例 → 选原版版本 → 选加载器」这条流程才创建实例（用户明确要求）。
        LauncherConfig.InstanceEntry current = shell.services().instances().current();
        LauncherConfig.InstanceEntry instance = null;
        if (core != null && current != null) {
            String currentMc = instanceMcVersion(current);
            if (!currentMc.isBlank() && !currentMc.equals(mcVersion)) {
                // 实例的游戏版本是锁定的：加载器只能装到同版本的实例上，
                // 想装到别的游戏版本必须先新建实例（否则会悄悄改掉现有实例的版本）
                shell.toast().show(I18n.t("install.loader.otherVersion",
                        current.name, currentMc, mcVersion),
                        com.misaka.launcher.ui.component.Toast.Kind.ERROR);
                return;
            }
            instance = current;
            if (currentMc.isBlank()) {
                current.mcVersion = mcVersion;
            }
            shell.services().instances().save(current);
        }
        if (instance == null) {
            // 原版安装（或还没有任何实例）：沿用「自动建实例」的既有流程，
            // 命名约定：Minecraft-<版本>-<加载器>-<加载器版本>，例如 Minecraft-1.20.1-Forge-47.0.1
            instance = shell.services().instances()
                    .create(buildInstanceName(mcVersion, core, coreVersion, optifine, optifineVersion));
            // 记下 Minecraft 版本本身：加载器装好后 versionId 会变成
            // 1.20.1-forge-47.0.1 这类复合串，界面展示与 Java 判定都要用到原始版本号
            instance.mcVersion = mcVersion;
            shell.services().instances().save(instance);
            shell.services().instances().switchTo(instance.id);
        }
        refresh();

        GamePaths paths = shell.services().instances().gamePaths(instance);
        // lambda 只能捕获 final 变量：instance 在两个分支里被赋值，这里取一个最终别名
        final LauncherConfig.InstanceEntry target = instance;

        // 部分失败的信息要带出任务体：任务本身仍算「完成」，但提示必须是警告而不是成功
        boolean[] partial = {false};
        // 安装期间该实例不能启动：装到一半点「启动游戏」必然失败
        shell.tasks().runForInstance(target.id, "install.progress", listener -> {
            // 0) Java 先行。加载器安装器是独立的 JVM 进程：
            //    机器上没有对应主版本的 Java 时，它会静默卡住（真实故障：没装 Java 11 的
            //    机器上装 1.16.5 + Forge，安装器卡死不动，用户只能杀进程）。
            //    1.16.5 → Java 11、1.20.1 → Java 17，缺什么先装什么。
            //    纯原版安装本身不需要 Java，所以那一支只做「尽力而为」，不阻断文件下载。
            ensureJavaForInstall(mcVersion, listener, core != null || optifine);

            if (core == null) {
                // 仅原版（或仅 OptiFine 时先装原版，OptiFine 安装器需要原版客户端）
                InstallResult result = shell.services().vanillaInstaller(paths).install(mcVersion, listener);
                // 先落版本，再看成败：版本 JSON 与客户端此时已经写盘，实例必须先「认识」这个版本，
                // 否则用户永远停在「版本未安装」，连「校验文件」这个修复入口都进不去
                shell.services().instances().updateVersion(target.id, mcVersion,
                        optifine ? LoaderType.OPTIFINE : LoaderType.VANILLA, "", optifineVersion);
                if (!result.success()) {
                    partial[0] = true;
                    listener.onMessage("部分文件未下载成功，可在实例页点「校验文件」补齐");
                }
            } else {
                try {
                    com.misaka.launcher.core.install.LoaderInstallResult result =
                            shell.services().loaderInstaller(paths).install(core, mcVersion, coreVersion, listener);
                    shell.services().instances().updateVersion(target.id, result.versionId(),
                            core, coreVersion, optifineVersion);
                } catch (java.io.IOException e) {
                    // 加载器安装会先确保原版完整；原版个别文件失败就会中止到这里。
                    // 但原版其实可能已经可用 —— 按磁盘事实补记版本，用户至少能启动原版，
                    // 而不是被一个空 versionId 彻底堵死
                    shell.services().instances().recoverVersionFromDisk(target);
                    throw e;
                }
            }
        }, result -> {
            if (result.success()) {
                String message = optifine
                        ? I18n.t("install.loader.optifine.manual", optifineVersion)
                        : I18n.t("install.success");
                shell.toast().show(partial[0] ? I18n.t("install.partial") : message,
                        partial[0] ? com.misaka.launcher.ui.component.Toast.Kind.WARN
                                : com.misaka.launcher.ui.component.Toast.Kind.SUCCESS);
                for (String page : List.of("home", "download")) {
                    shell.invalidatePage(page);
                }
                refresh();
                shell.refreshShell();
            } else if (result.failed()) {
                shell.toast().show(I18n.t("install.failed", String.valueOf(result.error() == null
                        ? "" : result.error().getMessage())), com.misaka.launcher.ui.component.Toast.Kind.ERROR);
                // 失败也要刷新：版本自愈可能已经把版本补记上了，界面必须反映出来
                refresh();
                shell.refreshShell();
            }
        });
    }

    // ==================================================================

    /** 重新加载实例列表并保持选中项。 */
    public void refresh() {
        LauncherConfig.InstanceEntry previous = instanceList.getSelectionModel().getSelectedItem();
        List<LauncherConfig.InstanceEntry> entries = new ArrayList<>(shell.services().instances().list());
        // 版本自愈 + 元信息回填：安装中途失败会留下「版本 JSON 已落盘、但实例没记上
        // versionId」的中间态；旧配置里 mcVersion / 加载器字段也可能是空的。
        // 两者都按磁盘事实补齐，否则界面会一直说「尚未安装游戏版本」
        boolean recovered = false;
        for (LauncherConfig.InstanceEntry entry : entries) {
            if (shell.services().instances().syncVersionMetadata(entry)) {
                recovered = true;
            }
        }
        allInstances.clear();
        allInstances.addAll(entries);
        refreshGroupFilter();
        applyGroupFilter();

        LauncherConfig.InstanceEntry target = previous != null
                ? entries.stream().filter(entry -> entry.id.equals(previous.id)).findFirst().orElse(null)
                : null;
        if (target == null) {
            LauncherConfig.InstanceEntry current = shell.services().instances().current();
            target = current != null && entries.contains(current) ? current : (entries.isEmpty() ? null : entries.get(0));
        }
        // 目标可能被当前分组筛掉了：只在列表里确实存在时选中，否则保持筛选后的默认选中项
        if (target != null && instanceList.getItems().contains(target)) {
            instanceList.getSelectionModel().select(target);
        }
        if (recovered) {
            // 版本刚被补记上：外壳（底部状态栏 / 启动栏）也要跟着更新，
            // 否则列表已显示版本、状态栏却还说「尚未安装游戏版本」
            shell.refreshShell();
        }
        log.debug("实例视图刷新完成，共 {} 个实例", entries.size());
    }

    /** 分组筛选里「不筛」的那一项（跟随语言）。 */
    private static String groupAllLabel() {
        return I18n.t("instance.group.all");
    }

    /**
     * 重建分组下拉的选项。
     *
     * <p>分组名不是独立配置，而是从各实例的 {@code group} 字段汇总出来的
     * （见 {@code InstanceManager.groups()}）：改一个实例的分组，下拉里就多一项或少一项。
     * 之前选中的分组若已消失（改名、实例删光），必须退回「全部分组」——
     * 否则列表会莫名其妙变成空的，而用户不知道是自己筛掉了。</p>
     */
    private void refreshGroupFilter() {
        if (groupFilter == null) {
            return;
        }
        String all = groupAllLabel();
        String previous = groupFilter.getValue();
        List<String> options = new ArrayList<>();
        options.add(all);
        options.addAll(shell.services().instances().groups());
        groupFilter.getItems().setAll(options);
        groupFilter.setValue(options.contains(previous) ? previous : all);
    }

    /** 按分组筛选实例列表；选「全部分组」时展示全部。 */
    private void applyGroupFilter() {
        if (groupFilter == null) {
            return;
        }
        String all = groupAllLabel();
        String selected = groupFilter.getValue();
        boolean showAll = selected == null || selected.isBlank() || selected.equals(all);
        LauncherConfig.InstanceEntry keep = instanceList.getSelectionModel().getSelectedItem();
        List<LauncherConfig.InstanceEntry> shown = showAll
                ? new ArrayList<>(allInstances)
                : allInstances.stream()
                        .filter(entry -> selected.equals(entry.group == null ? "" : entry.group.trim()))
                        .toList();
        instanceList.getItems().setAll(shown);
        // 重设 items 后必须强制重绘：否则单元格仍是旧条目，徽标不会更新
        instanceList.refresh();
        if (keep != null && shown.contains(keep)) {
            instanceList.getSelectionModel().select(keep);
        } else if (!shown.isEmpty()) {
            instanceList.getSelectionModel().selectFirst();
        } else {
            fillDetails(null);
        }
    }

    /**
     * 实例的组成摘要：{@code Minecraft-<版本>[-<加载器>-<版本>][-OptiFine-<版本>]}。
     *
     * <p><b>不显示「原版」</b>：那只是加载器字段取缺省值时的说法，对用户没有信息量，
     * 还会让人误以为是个加载器。统一用 {@code Minecraft-<版本>} 起头，
     * 有没有加载器、是什么版本一眼可辨。</p>
     */
    private static String instanceSummary(LauncherConfig.InstanceEntry entry) {
        if (entry == null) {
            return "";
        }
        if (entry.versionId == null || entry.versionId.isBlank()) {
            return I18n.t("home.noVersion");
        }
        // 优先用单独记录的 Minecraft 版本：加载器装好后 versionId 是复合串，
        // 无法稳定反推；取不到时再退回 versionId（原版两者相同）
        String mc = entry.mcVersion == null || entry.mcVersion.isBlank()
                ? entry.versionId : entry.mcVersion;
        StringBuilder text = new StringBuilder("Minecraft-").append(mc);
        LoaderType loader = LoaderType.fromId(entry.loaderType);
        if (!loader.isVanilla()) {
            text.append('-').append(loader.displayName());
            if (entry.loaderVersion != null && !entry.loaderVersion.isBlank()) {
                text.append('-').append(entry.loaderVersion);
            }
        }
        if (entry.optifineVersion != null && !entry.optifineVersion.isBlank()) {
            text.append("-OptiFine-").append(entry.optifineVersion);
        }
        return text.toString();
    }

    /** 把多行文本拆成参数列表（空行忽略；同时容忍每行内以空格分隔的多个参数）。 */
    private static List<String> splitLines(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            result.addAll(List.of(trimmed.split("\\s+")));
        }
        return result;
    }

    private static String shorten(String iso) {
        return iso == null || iso.length() < 10 ? "" : iso.substring(0, 10);
    }

    /**
     * 一张加载器卡片：勾选框 + 版本选择控件。
     *
     * <p>核心加载器（Forge / NeoForge / Fabric / Quilt）用 {@link ComboBox} 提供版本列表；
     * OptiFine 没有公开版本接口，用 {@link TextField} 让用户手动填写。</p>
     */
    private static final class LoaderCard {
        final LoaderType type;
        final VBox root;
        final CheckBox toggle;
        final Region versionControl;

        LoaderCard(LoaderType type, VBox root, CheckBox toggle, Region versionControl) {
            this.type = type;
            this.root = root;
            this.toggle = toggle;
            this.versionControl = versionControl;
        }

        @SuppressWarnings("unchecked")
        ComboBox<String> versionCombo() {
            return (ComboBox<String>) versionControl;
        }
    }

    /** 轻量确认框。 */
}
