package com.misaka.launcher.ui.view;

import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.mods.McModProvider;
import com.misaka.launcher.core.mods.ModCategory;
import com.misaka.launcher.core.mods.ModContentType;
import com.misaka.launcher.core.mods.ModFile;
import com.misaka.launcher.core.mods.ModInstaller;
import com.misaka.launcher.core.mods.ModProject;
import com.misaka.launcher.core.mods.ModProvider;
import com.misaka.launcher.core.mods.ModSearchQuery;
import com.misaka.launcher.core.mods.ModUpdateService;
import com.misaka.launcher.core.mods.ModpackInstaller;
import com.misaka.launcher.core.model.LoaderType;
import com.misaka.launcher.ui.component.Icons;
import com.misaka.launcher.ui.component.ModIcons;
import com.misaka.launcher.ui.component.Toast;
import com.misaka.launcher.ui.component.Ui;
import com.misaka.launcher.ui.shell.AppShell;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 下载中心。
 *
 * <p><b>默认就是「能看到内容」</b>：打开即按热门度列出 CurseForge 的模组第一页，
 * 而不是一个空白列表等用户去搜 —— 这才是下载中心该有的样子。之后的筛选
 * （分类 / 版本 / 加载器 / 排序）与翻页都在这套列表上做。</p>
 *
 * <p>三个来源的能力差异仍显式呈现：Modrinth 与 CurseForge 都支持检索、分类、分页；
 * MC 百科没有公开 API，只做中文资料跳转。</p>
 */
public final class DownloadView extends ScrollPane {

    private static final Logger log = LoggerFactory.getLogger(DownloadView.class);

    /** 每页条数：CurseForge 单页上限 50，所以不给 100 这种会被它拒绝的选项。 */
    private static final List<Integer> PAGE_SIZES = List.of(20, 50);

    /** 列表缩略图边长（与 HMCL 那类列表的观感一致）。 */
    private static final double ICON_SIZE = 44;

    private final AppShell shell;

    private final TextField searchField = Ui.searchField("download.searchPlaceholder", null);
    private final TextField gameVersionField = Ui.textField(null, "");
    private final ComboBox<String> loaderCombo = Ui.comboBox(
            List.of("", "fabric", "forge", "neoforge", "quilt", "optifine"), "", null);
    private final ComboBox<ModContentType> typeCombo = Ui.comboBox(
            List.of(ModContentType.MOD, ModContentType.MODPACK, ModContentType.RESOURCE_PACK,
                    ModContentType.SHADER), ModContentType.MOD, null);
    /** 分类：内容随「来源 + 内容类型」变化，因此是可变列表而不是固定枚举。 */
    private final ComboBox<ModCategory> categoryCombo = new ComboBox<>();
    private final ComboBox<ModSearchQuery.Sort> sortCombo = Ui.comboBox(
            List.of(ModSearchQuery.Sort.POPULARITY, ModSearchQuery.Sort.DOWNLOADS,
                    ModSearchQuery.Sort.NEWEST, ModSearchQuery.Sort.UPDATED,
                    ModSearchQuery.Sort.RELEVANCE),
            ModSearchQuery.Sort.POPULARITY, null);
    private final ComboBox<Integer> pageSizeCombo = Ui.comboBox(PAGE_SIZES, 20, null);
    /** 排序方向：true = 从高到低。CurseForge 支持，Modrinth 不支持（见 supportsSortDirection）。 */
    private final ComboBox<Boolean> directionCombo = Ui.comboBox(List.of(true, false), true, null);

    private final ListView<ModProject> resultList = new ListView<>();
    private final Label statusLabel = Ui.label("", "mute-2");
    private final Label pageLabel = Ui.label("", "mute-2");
    private final Label targetLabel = Ui.label("", "mute-2");
    /** 更新卡片左侧那句提示：必须反映「实际检测到多少个模组」，不能写死。 */
    private final Label updateHint = Ui.muted("");
    private final Label cfWarning = Ui.callout(I18n.t("download.cf.notConfigured"), true);

    private final Button prevPage = Ui.ghostButtonText("<", event -> turnPage(-1));
    private final Button nextPage = Ui.ghostButtonText(">", event -> turnPage(1));

    private String activeSource;
    /** 来源按钮容器：自动回退时要同步高亮，所以要留引用。 */
    private HBox sourcePills;
    /** 0 起的页码。 */
    private int page;
    /** 上一页返回的数量等于每页条数 → 还有下一页。 */
    private boolean hasMore;
    private boolean loading;
    private List<ModUpdateService.ModUpdate> pendingUpdates = List.of();

    public DownloadView(AppShell shell) {
        this.shell = shell;

        categoryCombo.setPrefHeight(32);
        categoryCombo.setMaxWidth(Double.MAX_VALUE);
        Ui.shrinkable(categoryCombo);
        // 这个下拉框是直接 new 出来的（不经过 Ui.comboBox），弹出列表同样要补字体
        Ui.styleComboPopup(categoryCombo);
        categoryCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(ModCategory category) {
                return category == null || category.isAll()
                        ? I18n.t("download.category.all") : category.name();
            }

            @Override
            public ModCategory fromString(String text) {
                return ModCategory.ALL;
            }
        });

        directionCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Boolean value) {
                return Boolean.FALSE.equals(value)
                        ? I18n.t("download.direction.asc") : I18n.t("download.direction.desc");
            }

            @Override
            public Boolean fromString(String text) {
                return Boolean.TRUE;
            }
        });

        // 各筛选器统一在变化后重新检索（分类/内容类型/排序变化都等于换结果集，页码归零）
        typeCombo.valueProperty().addListener((observable, oldValue, value) -> {
            reloadCategories();
            doSearch(true);
        });
        directionCombo.valueProperty().addListener((observable, oldValue, value) -> doSearch(true));
        sortCombo.valueProperty().addListener((observable, oldValue, value) -> doSearch(true));
        pageSizeCombo.valueProperty().addListener((observable, oldValue, value) -> doSearch(true));
        categoryCombo.valueProperty().addListener((observable, oldValue, value) -> {
            if (!loading) {
                doSearch(true);
            }
        });

        VBox content = new VBox(16, buildSearchBar(), buildSourceBar(), buildResults(),
                buildModpackCard(), buildUpdateCard());
        Ui.mountScrollable(this, content, Ui.CONTENT_MAX_WIDTH);

        refreshContext();
    }

    // ==================================================================
    // 界面
    // ==================================================================

    private Node buildSearchBar() {
        searchField.setOnAction(event -> doSearch(true));
        HBox.setHgrow(searchField, Priority.ALWAYS);

        Button search = Ui.primaryButton("action.search", event -> doSearch(true),
                Icons.create(Icons.SEARCH, 15));
        HBox row = new HBox(10, searchField, search);
        row.setAlignment(Pos.CENTER_LEFT);

        HBox filters = new HBox(10,
                labeled("download.gameVersion", gameVersionField),
                labeled("download.loader", loaderCombo),
                labeled("download.type", typeCombo),
                labeled("download.category", categoryCombo));
        filters.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));

        HBox second = new HBox(10,
                labeled("download.sort", sortCombo),
                labeled("download.direction", directionCombo),
                labeled("download.pageSize", pageSizeCombo));
        second.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));

        return Ui.glassCard(12, Ui.cardHeader("download.title"), row, filters, second);
    }

    private Node labeled(String key, Node control) {
        VBox box = new VBox(4, Ui.i18n(key, "mute-2"), control);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private Node buildSourceBar() {
        HBox pills = new HBox(8);
        sourcePills = pills;
        for (ModProvider provider : shell.services().modProviders()) {
            Button pill = Ui.pill(provider.displayName(),
                    provider.id().equals(activeSource), event -> selectSource(provider, pills));
            pill.setUserData(provider.id());
            pills.getChildren().add(pill);
        }

        boolean configured = isCurseForgeConfigured();
        cfWarning.setVisible(!configured);
        cfWarning.setManaged(!configured);

        targetLabel.setWrapText(true);
        return Ui.glassCard(10, pills, targetLabel, cfWarning);
    }

    private void selectSource(ModProvider provider, HBox pills) {
        activeSource = provider.id();
        refreshSourcePills();
        updateDirectionAvailability();
        if (!provider.supportsSearch()) {
            // MC 百科：没有公开 API，直接把检索词丢给浏览器
            if (provider instanceof McModProvider mcMod) {
                openUrl(mcMod.searchUrl(searchField.getText()));
                shell.toast().show(mcMod.capabilityNote());
            }
            return;
        }
        if (!provider.isConfigured()) {
            shell.toast().show(I18n.t("download.cf.keyInvalid"), Toast.Kind.ERROR);
            return;
        }
        reloadCategories();
        doSearch(true);
    }

    /**
     * 让来源按钮的高亮永远等于「当前实际在用的来源」。
     *
     * <p>之前只在用户点击时改高亮：初次打开（或自动回退到 Modrinth 之后）界面上
     * 一颗按钮都没选中，用户看不出当前在用的是哪个站。</p>
     */
    private void refreshSourcePills() {
        if (sourcePills == null) {
            return;
        }
        for (Node node : sourcePills.getChildren()) {
            if (node instanceof Button button && node.getUserData() instanceof String id) {
                button.getStyleClass().remove("selected");
                if (id.equals(activeSource)) {
                    button.getStyleClass().add("selected");
                }
            }
        }
    }

    /**
     * 排序方向只对支持它的来源可用。
     *
     * <p>Modrinth 的搜索接口没有方向参数（index 自带固定方向），因此对它要置灰，
     * 并说明原因 —— 给一个点了没反应的开关比没有更糟。</p>
     */
    private void updateDirectionAvailability() {
        ModProvider provider = currentProvider();
        boolean supported = provider != null && provider.supportsSortDirection();
        directionCombo.setDisable(!supported);
        directionCombo.setTooltip(supported ? null
                : Ui.tooltip(I18n.t("download.direction.unsupported")));
    }

    private boolean isCurseForgeConfigured() {
        return shell.services().modProviders().stream()
                .filter(provider -> provider.id().equals("curseforge"))
                .anyMatch(ModProvider::isConfigured);
    }

    private Node buildResults() {
        resultList.setPrefHeight(380);
        resultList.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(ModProject item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label title = Ui.label(item.title(), "section-title");
                Label subtitle = Ui.label(item.subtitle(), "mute-2");
                Label description = Ui.muted(item.description());
                description.setWrapText(true);
                description.setMaxWidth(620);
                VBox text = new VBox(3, title, subtitle, description);
                // 缩略图：两个来源都已经把 iconUrl 解析出来了，之前只是没画
                HBox row = new HBox(12, modIcon(item), text);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
                setText(null);
            }
        });
        resultList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                installSelected();
            }
        });

        Button install = Ui.primaryButton("action.install", event -> installSelected(),
                Icons.create(Icons.DOWNLOAD, 15));
        install.setDisable(shell.selfUpdating());
        Button openPage = Ui.ghostButton("action.open", event -> openSelectedPage(),
                Icons.create(Icons.REFRESH, 14));

        HBox pager = new HBox(8, prevPage, pageLabel, nextPage);
        pager.setAlignment(Pos.CENTER_LEFT);
        HBox actions = new HBox(8, statusLabel, Ui.grow(), pager, openPage, install);
        actions.setAlignment(Pos.CENTER_LEFT);
        updatePager();
        return Ui.glassCard(12, Ui.cardHeader("download.title"), resultList, actions);
    }

    /** 整合包导入：本地 .zip / .mrpack 直接装进当前实例。 */
    private Node buildModpackCard() {
        Button importPack = Ui.ghostButton("download.modpackImport", event -> importModpack(),
                Icons.create(Icons.DOWNLOAD, 14));
        importPack.setDisable(shell.selfUpdating());
        // 不在两个模组库里的模组（GitHub Release / 作者站点）走这条：
        // 装的时候把直链记下来，之后按这个地址检查更新
        Button fromUrl = Ui.ghostButton("download.fromUrl", event -> installFromUrl(),
                Icons.create(Icons.DOWNLOAD, 14));
        fromUrl.setDisable(shell.selfUpdating());
        Label hint = Ui.muted(I18n.t("download.modpackImportTip"));
        HBox row = new HBox(10, hint, Ui.grow(), fromUrl, importPack);
        row.setAlignment(Pos.CENTER_LEFT);
        return Ui.glassCard(10, Ui.cardHeader("download.modpack", row));
    }

    /**
     * 列表项左侧的缩略图。
     *
     * <p>没有图标时退回「首字母方块」而不是留白：否则有图标与没图标的行高不一致，
     * 列表看起来会跳。图标本身走 {@link ModIcons} 的后台加载，不阻塞滚动。</p>
     */
    private static Node modIcon(ModProject item) {
        javafx.scene.image.Image image = ModIcons.image(item.iconUrl(), ICON_SIZE);
        if (image != null) {
            javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(image);
            view.setFitWidth(ICON_SIZE);
            view.setFitHeight(ICON_SIZE);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            StackPane box = new StackPane(view);
            box.setMinSize(ICON_SIZE, ICON_SIZE);
            box.setPrefSize(ICON_SIZE, ICON_SIZE);
            box.setMaxSize(ICON_SIZE, ICON_SIZE);
            return box;
        }
        String title = item.title() == null ? "" : item.title().trim();
        Label initial = Ui.label(title.isEmpty() ? "?" : title.substring(0, 1).toUpperCase(),
                "badge");
        StackPane box = new StackPane(initial);
        box.setMinSize(ICON_SIZE, ICON_SIZE);
        box.setPrefSize(ICON_SIZE, ICON_SIZE);
        box.setMaxSize(ICON_SIZE, ICON_SIZE);
        return box;
    }

    private Node buildUpdateCard() {
        Button check = Ui.ghostButton("download.checkUpdates", event -> checkUpdates(),
                Icons.create(Icons.REFRESH, 14));
        Button updateAll = Ui.primaryButton("download.updateAll", event -> updateAll(),
                Icons.create(Icons.DOWNLOAD, 15));
        updateAll.setDisable(shell.selfUpdating());

        updateHint.setWrapText(true);
        HBox row = new HBox(10, updateHint, Ui.grow(), check, updateAll);
        row.setAlignment(Pos.CENTER_LEFT);
        return Ui.glassCard(12, Ui.cardHeader("download.updates", row));
    }

    /**
     * 刷新「检测到 N 个模组」。
     *
     * <p>之前这里是一句写死的「未检测到已安装的模组」，实例里明明有模组也这么显示 ——
     * 现在改为数磁盘上的 {@code mods/*.jar}（与用户在模组管理页看到的一致）。</p>
     */
    private void refreshUpdateHint() {
        LauncherConfig.InstanceEntry instance = shell.services().instances().current();
        if (instance == null) {
            updateHint.setText(I18n.t("download.noInstalled"));
            return;
        }
        GamePaths paths = shell.services().instances().gamePaths(instance);
        int count = shell.services().modUpdates().installedModCount(paths);
        updateHint.setText(count == 0
                ? I18n.t("download.noInstalled")
                : I18n.t("download.installedCount", count));
    }

    // ==================================================================
    // 检索与翻页
    // ==================================================================

    /**
     * 执行检索。
     *
     * @param resetPage 换条件时归零到第一页；单纯翻页传 false
     */
    private void doSearch(boolean resetPage) {
        ModProvider provider = currentProvider();
        if (provider == null) {
            return;
        }
        if (!provider.supportsSearch()) {
            return;
        }
        if (!provider.isConfigured()) {
            cfWarning.setVisible(true);
            cfWarning.setManaged(true);
            return;
        }
        if (resetPage) {
            page = 0;
        }
        int size = pageSizeCombo.getValue() == null ? 20 : pageSizeCombo.getValue();
        ModCategory category = categoryCombo.getValue();
        ModSearchQuery query = new ModSearchQuery(searchField.getText(), typeCombo.getValue(),
                gameVersionField.getText(), loaderFilter(),
                category == null || category.isAll() ? "" : category.id(),
                page * size, size,
                sortCombo.getValue() == null ? ModSearchQuery.Sort.POPULARITY : sortCombo.getValue(),
                !Boolean.FALSE.equals(directionCombo.getValue()));

        loading = true;
        statusLabel.setText(I18n.t("status.working"));
        updatePager();
        log.debug("下载中心检索：{} {} 第 {} 页", provider.id(), query.contentType(), page + 1);
        shell.tasks().run("download.title", listener -> {
            List<ModProject> projects = provider.search(query);
            Platform.runLater(() -> applyResults(projects, size));
        }, result -> {
            loading = false;
            if (result.failed()) {
                String message = String.valueOf(result.error() == null ? ""
                        : result.error().getMessage());
                if ("curseforge".equals(activeSource) && message.contains("403")) {
                    // 内置 Key 失效时别把用户留在空白页：自动切到免 Key 的 Modrinth，
                    // 并明确告诉他是 Key 的问题（而不是"加载失败，请重试"这种无信息量的话）
                    shell.toast().show(I18n.t("download.cf.fallback"), Toast.Kind.WARN);
                    ModProvider fallback = shell.services().modProviders().stream()
                            .filter(candidate -> "modrinth".equals(candidate.id()))
                            .findFirst().orElse(null);
                    if (fallback != null) {
                        selectSource(fallback, sourcePills);
                        return;
                    }
                }
                statusLabel.setText(I18n.t("status.failed"));
                updatePager();
                shell.toast().show(message, Toast.Kind.ERROR);
            }
        });
    }

    private void applyResults(List<ModProject> projects, int pageSize) {
        loading = false;
        resultList.getItems().setAll(projects);
        // 「本页拿满了」就认为还有下一页：两个站点都不返回总数，这是唯一不需要额外请求的判据
        hasMore = projects.size() >= pageSize;
        statusLabel.setText(projects.isEmpty()
                ? I18n.t("download.noResult") : I18n.t("download.resultCount", projects.size()));
        updatePager();
    }

    private void turnPage(int delta) {
        int target = page + delta;
        if (target < 0 || (delta > 0 && !hasMore)) {
            return;
        }
        page = target;
        doSearch(false);
        resultList.scrollTo(0);
    }

    private void updatePager() {
        prevPage.setDisable(loading || page <= 0);
        nextPage.setDisable(loading || !hasMore);
        pageLabel.setText(I18n.t("download.page.info", page + 1));
    }

    private String loaderFilter() {
        String value = loaderCombo.getValue();
        return value == null ? "" : value;
    }

    /** 按「来源 + 内容类型」重建分类下拉；失败只记日志，不影响检索本身。 */
    private void reloadCategories() {
        ModProvider provider = currentProvider();
        if (provider == null || !provider.supportsSearch()) {
            setCategories(List.of());
            return;
        }
        ModContentType type = typeCombo.getValue();
        shell.tasks().run("download.category", listener -> {
            List<ModCategory> categories = provider.categories(type);
            Platform.runLater(() -> setCategories(categories));
        }, null);
    }

    private void setCategories(List<ModCategory> categories) {
        ModCategory previous = categoryCombo.getValue();
        List<ModCategory> items = new ArrayList<>();
        items.add(ModCategory.ALL);
        items.addAll(categories);
        loading = true;
        try {
            categoryCombo.getItems().setAll(items);
            // 之前选中的分类在新类型里可能不存在（如模组的「魔法」在光影里没有）
            ModCategory restored = previous == null ? ModCategory.ALL
                    : items.stream().filter(item -> item.id().equals(previous.id()))
                            .findFirst().orElse(ModCategory.ALL);
            categoryCombo.setValue(restored);
        } finally {
            loading = false;
        }
    }

    private ModProvider currentProvider() {
        return shell.services().modProviders().stream()
                .filter(provider -> provider.id().equals(activeSource))
                .findFirst().orElse(null);
    }

    // ==================================================================
    // 安装
    // ==================================================================

    private void installSelected() {
        ModProject project = resultList.getSelectionModel().getSelectedItem();
        ModProvider provider = currentProvider();
        if (project == null || provider == null) {
            return;
        }
        LauncherConfig.InstanceEntry instance = shell.services().instances().current();
        if (instance == null) {
            shell.toast().show(I18n.t("instance.empty"), Toast.Kind.ERROR);
            return;
        }
        if (typeCombo.getValue() == ModContentType.MODPACK) {
            installModpackProject(provider, project, instance);
            return;
        }
        String loader = targetLoader(instance);
        if (typeCombo.getValue() == ModContentType.MOD && loader.isBlank()) {
            // 原版实例装模组不会生效，先在下载中心挡住，比事后排查"模组怎么没加载"划算
            shell.toast().show(I18n.t("download.needLoader"), Toast.Kind.WARN);
            return;
        }

        GamePaths paths = shell.services().instances().gamePaths(instance);
        String gameVersion = targetGameVersion(instance);
        shell.tasks().runForInstance(instance.id, "download.installing", listener -> {
            ModInstaller.InstallOutcome outcome = shell.services().modInstaller().install(
                    instance.id, paths, provider.id(), project.id(), null,
                    gameVersion, loader, listener);
            listener.onMessage(outcome.summary());
        }, result -> {
            if (result.success()) {
                shell.toast().show(I18n.t("download.installed"), Toast.Kind.SUCCESS);
                shell.invalidatePage("home");
                shell.invalidatePage("mods");
            } else if (result.failed()) {
                shell.toast().show(String.valueOf(result.error() == null ? ""
                        : result.error().getMessage()), Toast.Kind.ERROR);
            }
        });
    }

    /**
     * 从搜索结果装整合包：先下归档，再交给 {@link ModpackInstaller} 解包安装。
     *
     * <p>与「装一个模组文件」不同：整合包要按清单把上百个文件放进对应目录，
     * 还得匹配它的游戏版本与加载器，直接丢进 {@code modpacks/} 是没有意义的。</p>
     */
    private void installModpackProject(ModProvider provider, ModProject project,
                                       LauncherConfig.InstanceEntry instance) {
        GamePaths paths = shell.services().instances().gamePaths(instance);
        shell.tasks().runForInstance(instance.id, "download.installing", listener -> {
            List<ModFile> files = provider.versions(project.id(), "", "");
            Optional<ModFile> primary = files.stream().filter(ModFile::primary).findFirst();
            ModFile file = primary.orElse(files.isEmpty() ? null : files.get(0));
            if (file == null) {
                throw new java.io.IOException(I18n.t("download.modpack.noFile"));
            }
            String url = provider.resolveDownloadUrl(file)
                    .orElseThrow(() -> new java.io.IOException(I18n.t("download.modpack.noFile")));
            Path temp = Files.createTempFile("misaka-modpack-", ".zip");
            try {
                shell.services().downloadManager().download(
                        com.misaka.launcher.core.download.DownloadItem.of(
                                temp, url, "", file.size(),
                                com.misaka.launcher.core.download.DownloadTargetType.OTHER),
                        listener);
                ModpackInstaller.InstallResult result =
                        shell.services().modpackInstaller(paths).install(temp, listener);
                listener.onMessage(result.summary());
            } finally {
                Files.deleteIfExists(temp);
            }
        }, result -> {
            if (result.success()) {
                shell.toast().show(I18n.t("download.modpack.installed"), Toast.Kind.SUCCESS);
                shell.invalidatePage("home");
                shell.invalidatePage("mods");
            } else if (result.failed()) {
                shell.toast().show(String.valueOf(result.error() == null ? ""
                        : result.error().getMessage()), Toast.Kind.ERROR);
            }
        });
    }

    /** 导入本地整合包（.zip / .mrpack）到当前实例。 */
    private void importModpack() {
        LauncherConfig.InstanceEntry instance = shell.services().instances().current();
        if (instance == null) {
            shell.toast().show(I18n.t("download.modpackNeedInstance"), Toast.Kind.WARN);
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("download.modpackImport"));
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Modpack", "*.zip", "*.mrpack"),
                new FileChooser.ExtensionFilter(I18n.t("action.selectAll"), "*.*"));
        File file = chooser.showOpenDialog(shell.stage());
        if (file == null) {
            return;
        }
        GamePaths paths = shell.services().instances().gamePaths(instance);
        shell.tasks().runForInstance(instance.id, "download.modpackImport", listener -> {
            ModpackInstaller.InstallResult result =
                    shell.services().modpackInstaller(paths).install(file.toPath(), listener);
            listener.onMessage(result.summary());
        }, result -> {
            if (result.success()) {
                shell.toast().show(I18n.t("download.modpack.installed"), Toast.Kind.SUCCESS);
                shell.invalidatePage("home");
                shell.invalidatePage("mods");
            } else if (result.failed()) {
                shell.toast().show(String.valueOf(result.error() == null ? ""
                        : result.error().getMessage()), Toast.Kind.ERROR);
            }
        });
    }

    /** 从官方直链安装一个模组（不在 CurseForge / Modrinth 上的那类）。 */
    private void installFromUrl() {
        LauncherConfig.InstanceEntry instance = shell.services().instances().current();
        if (instance == null) {
            shell.toast().show(I18n.t("instance.empty"), Toast.Kind.WARN);
            return;
        }
        TextField urlField = Ui.textField(null, "https://example.com/mymod-1.0.jar");
        TextField probe = urlField;
        Button cancel = Ui.ghostButton("action.cancel", event -> {
        });
        Button ok = Ui.primaryButton("action.install", event -> {
        });
        VBox card = new VBox(12,
                Ui.muted(I18n.t("download.fromUrlTip")),
                probe,
                new HBox(8, Ui.grow(), cancel, ok));
        javafx.stage.Stage stage = com.misaka.launcher.ui.component.Dialogs
                .build(shell, I18n.t("download.fromUrl"), card, 560);
        cancel.setOnAction(event -> stage.close());
        ok.setOnAction(event -> {
            String value = probe.getText() == null ? "" : probe.getText().trim();
            if (value.isBlank()) {
                return;
            }
            stage.close();
            GamePaths paths = shell.services().instances().gamePaths(instance);
            shell.tasks().runForInstance(instance.id, "download.installing", listener -> {
                ModInstaller.InstallOutcome outcome = shell.services().modInstaller()
                        .installFromUrl(instance.id, paths, value, listener);
                listener.onMessage(outcome.summary());
            }, result -> {
                if (result.success()) {
                    shell.toast().show(I18n.t("download.installed"), Toast.Kind.SUCCESS);
                    shell.invalidatePage("home");
                    shell.invalidatePage("mods");
                    refreshUpdateHint();
                } else if (result.failed()) {
                    shell.toast().show(String.valueOf(result.error() == null ? ""
                            : result.error().getMessage()), Toast.Kind.ERROR);
                }
            });
        });
        stage.showAndWait();
    }

    /** 从实例信息推导目标游戏版本；缺失时回退到筛选框的输入。 */
    private String targetGameVersion(LauncherConfig.InstanceEntry instance) {
        if (instance.mcVersion != null && !instance.mcVersion.isBlank()) {
            return instance.mcVersion;
        }
        if (instance.versionId != null && !instance.versionId.isBlank()) {
            // 版本 id 形如 1.20.1-forge-47.2.0，取前面纯版本号部分
            String[] parts = instance.versionId.split("-");
            return parts.length > 0 && parts[0].matches("\\d+\\.\\d+(\\.\\d+)?") ? parts[0]
                    : instance.versionId;
        }
        return gameVersionField.getText() == null ? "" : gameVersionField.getText().trim();
    }

    /** 当前实例的加载器；原版返回空串（调用方据此决定是否拦截）。 */
    private String targetLoader(LauncherConfig.InstanceEntry instance) {
        LoaderType type = LoaderType.fromId(instance.loaderType);
        return type.isVanilla() ? "" : type.id();
    }

    private void openSelectedPage() {
        ModProject project = resultList.getSelectionModel().getSelectedItem();
        if (project != null && project.pageUrl() != null && !project.pageUrl().isBlank()) {
            openUrl(project.pageUrl());
        }
    }

    private void openUrl(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception e) {
            shell.toast().show(url);
        }
    }

    // ==================================================================
    // 更新
    // ==================================================================

    private void checkUpdates() {
        LauncherConfig.InstanceEntry instance = shell.services().instances().current();
        if (instance == null) {
            return;
        }
        GamePaths paths = shell.services().instances().gamePaths(instance);
        String gameVersion = targetGameVersion(instance);
        String loader = targetLoader(instance);

        shell.tasks().run("download.checkUpdates", listener -> {
            List<ModUpdateService.ModUpdate> updates = shell.services().modUpdates()
                    .check(instance.id, paths, gameVersion, loader, listener);
            Platform.runLater(() -> statusLabel.setText(updates.isEmpty()
                    ? I18n.t("download.upToDate") : updates.size() + " 项可更新"));
            pendingUpdates = updates;
        }, result -> {
            if (result.failed()) {
                shell.toast().show(String.valueOf(result.error() == null ? ""
                        : result.error().getMessage()), Toast.Kind.ERROR);
            }
        });
    }

    private void updateAll() {
        if (pendingUpdates.isEmpty()) {
            checkUpdates();
            shell.toast().show(I18n.t("download.upToDate"));
            return;
        }
        List<ModUpdateService.ModUpdate> updates = new ArrayList<>(pendingUpdates);
        LauncherConfig.InstanceEntry instance = shell.services().instances().current();
        shell.tasks().runForInstance(instance == null ? null : instance.id, "download.updateAll", listener -> {
            ModUpdateService.UpdateOutcome outcome = shell.services().modUpdates().apply(updates, listener);
            listener.onMessage(outcome.summary());
        }, result -> {
            if (result.success()) {
                shell.toast().show(I18n.t("toast.updated"), Toast.Kind.SUCCESS);
                pendingUpdates = List.of();
                shell.invalidatePage("home");
                shell.invalidatePage("mods");
            }
        });
    }

    // ==================================================================
    // 上下文
    // ==================================================================

    /** 依据当前实例刷新筛选条件、目标实例提示与默认来源。 */
    public void refreshContext() {
        LauncherConfig.InstanceEntry instance = shell.services().instances().current();
        if (instance != null) {
            String gameVersion = targetGameVersion(instance);
            if (!gameVersion.isBlank() && !gameVersion.equals(instance.versionId)) {
                gameVersionField.setText(gameVersion);
            }
            String loader = targetLoader(instance);
            loaderCombo.getSelectionModel().select(loader);
            targetLabel.setText(I18n.t("download.target", instance.name + " · "
                    + instanceSummary(instance)));
        } else {
            targetLabel.setText(I18n.t("download.target.none"));
        }

        if (activeSource == null) {
            activeSource = shell.services().config().mods.defaultSource;
            if (activeSource == null || activeSource.isBlank()) {
                activeSource = "curseforge";
            }
            pageSizeCombo.setValue(shell.services().config().mods.pageSize <= 0
                    ? 20 : shell.services().config().mods.pageSize);
        }

        boolean configured = isCurseForgeConfigured();
        cfWarning.setVisible(!configured);
        cfWarning.setManaged(!configured);

        // 高亮必须跟着「实际在用的来源」走（含自动回退之后的状态）
        refreshSourcePills();
        updateDirectionAvailability();
        refreshUpdateHint();
        reloadCategories();
        // 打开即展示热门内容：这正是"下载中心"与"搜索框"的区别
        if (resultList.getItems().isEmpty()) {
            doSearch(true);
        }
        log.debug("下载中心上下文已刷新，来源={} 目标实例={}", activeSource,
                instance == null ? "无" : instance.id);
    }

    private static String instanceSummary(LauncherConfig.InstanceEntry entry) {
        StringBuilder text = new StringBuilder("Minecraft ")
                .append(entry.mcVersion == null || entry.mcVersion.isBlank()
                        ? "?" : entry.mcVersion);
        LoaderType loader = LoaderType.fromId(entry.loaderType);
        if (!loader.isVanilla()) {
            text.append(" + ").append(loader.displayName());
            if (entry.loaderVersion != null && !entry.loaderVersion.isBlank()) {
                text.append(' ').append(entry.loaderVersion);
            }
        }
        return text.toString();
    }

    /** 供设置页在 Key 改动后调用。 */
    public void onCurseForgeKeyChanged() {
        refreshContext();
    }

    /** 当前选中实例下的可用来源（供测试与调试）。 */
    public Map<String, Boolean> sourceAvailability() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (ModProvider provider : shell.services().modProviders()) {
            result.put(provider.id(), provider.isConfigured() && provider.supportsSearch());
        }
        return result;
    }
}
