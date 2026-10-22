package com.misaka.launcher.ui.view;

import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.mods.InstalledMods;
import com.misaka.launcher.ui.component.Icons;
import com.misaka.launcher.ui.component.Toast;
import com.misaka.launcher.ui.component.Ui;
import com.misaka.launcher.ui.shell.AppShell;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 模组管理。
 *
 * <p>只做一件事：把当前实例 {@code mods/} 目录里的模组列出来，让用户勾选后批量启用 / 禁用。</p>
 *
 * <ul>
 *   <li><b>启用 / 禁用的实现是改名</b>（{@code x.jar} ↔ {@code x.jar.disabled}）——
 *       游戏只加载 {@code .jar}，因此改名既能真正生效，又保留了随时恢复的能力；</li>
 *   <li><b>勾选框是「选中」而不是「启用」</b>：这样「全选 → 禁用」才能一次作用于多个模组，
 *       启用状态由状态徽标单独表达，两者不混在一个控件里；</li>
 *   <li>改名是本地文件操作（微秒级），因此直接同步执行，不必走后台任务 ——
 *       走任务反而会让每次点击都闪一下进度条。</li>
 * </ul>
 *
 * <p><b>搜索与分类筛选</b>只改变「列表里显示哪些」，不改变模组本身：搜索词在输入时即生效，
 * 匹配展示名与所在子目录；分类分「全部 / 启用 / 禁用」，用于在几百个模组里定位某一类。</p>
 *
 * <p>两者的作用范围刻意分开，避免出现「我明明只选了看得见的几个」这类意外：</p>
 * <ul>
 *   <li><b>全选 / 全不选</b>只作用于<b>当前筛选出的</b>条目 —— 于是「筛出禁用的这一批 → 全选 → 启用」
 *       是最顺手的用法，也不会波及被隐藏的模组；</li>
 *   <li><b>启用 / 禁用</b>作用于所有<b>已勾选</b>的条目（含当前被筛选隐藏的）：勾选是全局状态，
 *       统计行始终显示勾选总数，所以「我勾了什么」不会变成不可见的暗账。</li>
 * </ul>
 */
public final class ModsView extends ScrollPane {

    private static final Logger log = LoggerFactory.getLogger(ModsView.class);

    private final AppShell shell;

    /** 分类筛选的三个档位：全部 / 启用 / 禁用。取值同时是 i18n 键后缀（mods.filter.*）。 */
    private static final String CATEGORY_ALL = "all";
    private static final String CATEGORY_ENABLED = "enabled";
    private static final String CATEGORY_DISABLED = "disabled";
    private static final List<String> CATEGORIES =
            List.of(CATEGORY_ALL, CATEGORY_ENABLED, CATEGORY_DISABLED);

    private final Label instanceLabel = Ui.label("", "mute-2");
    private final Label dirLabel = Ui.label("", "mute-2");
    private final ListView<InstalledMods.Entry> modList = new ListView<>();
    private final Label summaryLabel = Ui.label("", "mute-2");
    private final Label emptyHint = Ui.label("", "mute-2");

    /** 搜索框：随输入实时过滤，不需要按回车。 */
    private final TextField searchField = Ui.searchField("mods.searchPlaceholder", value -> {
        query = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    });
    /** 分类胶囊；用 Map 保存是为了切换选中态时能按 id 找到对应按钮。 */
    private final Map<String, Button> categoryPills = new LinkedHashMap<>();

    private final Button enableButton = Ui.ghostButton("mods.enable",
            event -> apply(true), Icons.create(Icons.PLAY, 13));
    private final Button disableButton = Ui.ghostButton("mods.disable",
            event -> apply(false), Icons.create(Icons.STOP, 13));

    /** 已勾选的模组：按「相对路径」记录，刷新后仍能保持勾选。 */
    private final Set<String> checked = new LinkedHashSet<>();

    /** 扫描到的全部模组（不受搜索与筛选影响）。 */
    private List<InstalledMods.Entry> entries = List.of();
    /** 当前显示在列表里的模组（= entries 经搜索 + 分类筛选的结果）。 */
    private List<InstalledMods.Entry> visible = List.of();
    private String query = "";
    private String category = CATEGORY_ALL;

    public ModsView(AppShell shell) {
        this.shell = shell;

        VBox content = new VBox(16, buildModSection());
        Ui.mountScrollable(this, content, Ui.CONTENT_MAX_WIDTH);

        modList.setCellFactory(view -> new ModCell());
        Ui.shrinkable(modList);
        VBox.setVgrow(modList, Priority.ALWAYS);

        refresh();
    }

    // ==================================================================
    // 界面
    // ==================================================================

    private Node buildModSection() {
        modList.setPrefHeight(360);

        // 搜索框铺满剩余宽度，分类胶囊贴在右侧：一行里同时承担「找」与「筛」
        searchField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        HBox filterRow = new HBox(10, searchField, buildCategoryFilter());
        filterRow.setAlignment(Pos.CENTER_LEFT);

        Button selectAll = Ui.ghostButton("mods.selectAll", event -> setAllChecked(true));
        Button selectNone = Ui.ghostButton("mods.selectNone", event -> setAllChecked(false));
        Button openFolder = Ui.ghostButton("mods.openFolder", event -> openModsFolder(),
                Icons.create(Icons.FOLDER, 14));
        Button refresh = Ui.ghostButton("action.refresh", event -> refresh(),
                Icons.create(Icons.REFRESH, 14));

        HBox actions = new HBox(8, selectAll, selectNone, enableButton, disableButton, openFolder, refresh);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(10, instanceLabel, dirLabel, filterRow, actions, modList, summaryLabel, emptyHint);
        return Ui.glassCard(14, Ui.cardHeader("mods.title"), body);
    }

    /** 分类筛选胶囊：全部 / 启用 / 禁用。 */
    private Node buildCategoryFilter() {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        for (String id : CATEGORIES) {
            Button pill = Ui.pill(I18n.t("mods.filter." + id), id.equals(category), event -> {
                category = id;
                updateCategoryPills();
                applyFilter();
            });
            categoryPills.put(id, pill);
            row.getChildren().add(pill);
        }
        return row;
    }

    /** 只高亮当前分类。{@code pill-button} 的选中态是样式类，需要自己维护互斥。 */
    private void updateCategoryPills() {
        categoryPills.forEach((id, pill) -> {
            pill.getStyleClass().remove("selected");
            if (id.equals(category)) {
                pill.getStyleClass().add("selected");
            }
        });
    }

    // ==================================================================
    // 数据
    // ==================================================================

    /** 重新扫描模组目录并刷新列表（切换实例、安装模组后调用）。 */
    public void refresh() {
        LauncherConfig.InstanceEntry instance = shell.services().instances().current();
        if (instance == null) {
            instanceLabel.setText(I18n.t("mods.noInstance"));
            dirLabel.setText("");
            entries = List.of();
        } else {
            instanceLabel.setText(I18n.t("mods.current") + "：" + instance.name);
            Path modsDir = modsDir(instance);
            dirLabel.setText(I18n.t("mods.dir") + "：" + modsDir);
            entries = InstalledMods.scan(modsDir);
        }

        // 已不存在的条目要从勾选集合里剔除，否则「已选」会虚高
        Set<String> alive = new LinkedHashSet<>();
        for (InstalledMods.Entry entry : entries) {
            alive.add(entry.relative());
        }
        checked.retainAll(alive);

        applyFilter();
    }

    private Path modsDir(LauncherConfig.InstanceEntry instance) {
        GamePaths paths = shell.services().instances().gamePaths(instance);
        return paths.modsDir();
    }

    /**
     * 按当前搜索词与分类重算可见列表。
     *
     * <p>扫描是「重活」（要遍历目录、读文件大小），而筛选只是内存里的过滤，
     * 所以两者分开：输入搜索词、切换分类只走这里，不会重新扫描磁盘。</p>
     */
    private void applyFilter() {
        visible = entries.stream()
                .filter(this::matchesQuery)
                .filter(this::matchesCategory)
                .toList();
        modList.getItems().setAll(visible);

        LauncherConfig.InstanceEntry instance = shell.services().instances().current();
        if (instance == null) {
            emptyHint.setText("");
        } else if (entries.isEmpty()) {
            emptyHint.setText(I18n.t("mods.empty.hint"));
        } else {
            // 有模组但一个都没筛出来：提示改搜索词或切回「全部」，而不是显示空白列表
            emptyHint.setText(visible.isEmpty() ? I18n.t("mods.search.noResult") : "");
        }
        updateSummary();
    }

    /** 搜索词匹配「展示名」，也匹配所在子目录（子目录里的模组同样能被搜到）。 */
    private boolean matchesQuery(InstalledMods.Entry entry) {
        if (query.isEmpty()) {
            return true;
        }
        String haystack = (parentOf(entry.relative()) + " " + entry.name()).toLowerCase(Locale.ROOT);
        return haystack.contains(query);
    }

    private boolean matchesCategory(InstalledMods.Entry entry) {
        return switch (category) {
            case CATEGORY_ENABLED -> entry.enabled();
            case CATEGORY_DISABLED -> !entry.enabled();
            default -> true;
        };
    }

    /** 筛选是否生效（决定统计行用哪种文案）。 */
    private boolean filtering() {
        return !query.isEmpty() || !CATEGORY_ALL.equals(category);
    }

    /**
     * 全选 / 全不选。
     *
     * <p>只作用于<b>当前可见</b>的条目：用户看到的就是这几行，勾选范围与视觉一致 ——
     * 「筛出禁用的一批 → 全选 → 启用」也因此能一步到位。</p>
     */
    private void setAllChecked(boolean value) {
        for (InstalledMods.Entry entry : visible) {
            if (value) {
                checked.add(entry.relative());
            } else {
                checked.remove(entry.relative());
            }
        }
        modList.refresh();
        updateSummary();
    }

    /** 对勾选项批量启用 / 禁用；已经处于目标状态的会被跳过而不是重复改名。 */
    private void apply(boolean enabled) {
        List<InstalledMods.Entry> targets = entries.stream()
                .filter(entry -> checked.contains(entry.relative()))
                .filter(entry -> entry.enabled() != enabled)
                .toList();

        if (targets.isEmpty()) {
            shell.toast().show(I18n.t(checked.isEmpty() ? "mods.noneSelected" : "mods.nothingToDo"));
            return;
        }

        int done = 0;
        List<String> failures = new ArrayList<>();
        for (InstalledMods.Entry entry : targets) {
            try {
                InstalledMods.setEnabled(entry, enabled);
                done++;
            } catch (IOException e) {
                // 单个失败不应中断其余模组：把失败项汇总给用户即可
                failures.add(entry.fileName() + "（" + e.getMessage() + "）");
                log.warn("模组 {} 操作失败: {}", entry.fileName(), e.getMessage());
            }
        }

        if (!failures.isEmpty()) {
            shell.toast().show(I18n.t("mods.failed", failures.size(), String.join("；", failures)),
                    Toast.Kind.ERROR);
        }
        if (done > 0) {
            shell.toast().show(I18n.t(enabled ? "mods.enabledCount" : "mods.disabledCount", done),
                    Toast.Kind.SUCCESS);
        }
        refresh();
    }

    private void openModsFolder() {
        LauncherConfig.InstanceEntry instance = shell.services().instances().current();
        if (instance == null) {
            shell.toast().show(I18n.t("mods.noInstance"), Toast.Kind.ERROR);
            return;
        }
        Path dir = modsDir(instance);
        try {
            // 目录可能还没被创建（从未装过模组），先补上再打开，避免资源管理器报错
            Files.createDirectories(dir);
            java.awt.Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            // 打不开就退化为把路径显示出来，用户可自行复制
            shell.toast().show(dir.toString());
        }
    }

    private void updateSummary() {
        long enabled = entries.stream().filter(InstalledMods.Entry::enabled).count();
        long disabled = entries.size() - enabled;
        // 筛选生效时额外报出「筛出几个」：否则「共 300 个」与列表里 5 行会对不上，
        // 用户会以为列表渲染坏了
        summaryLabel.setText(filtering()
                ? I18n.t("mods.summary.filtered", checked.size(), entries.size(),
                        visible.size(), enabled, disabled)
                : I18n.t("mods.summary", checked.size(), entries.size(), enabled, disabled));
        boolean hasChecked = !checked.isEmpty();
        // 启用/禁用是本地改名（同步执行，不走任务中心），所以这里也要跟着自更新置灰：
        // 更新结束会退出启动器，改到一半的模组目录会留下半成品
        boolean locked = shell.selfUpdating();
        enableButton.setDisable(!hasChecked || locked);
        disableButton.setDisable(!hasChecked || locked);
    }

    /** 相对路径所在的子目录；顶层模组返回空串（列表行与搜索都用到）。 */
    private static String parentOf(String relative) {
        int index = relative.lastIndexOf(java.io.File.separatorChar);
        return index > 0 ? relative.substring(0, index) : "";
    }

    // ==================================================================
    // 列表单元
    // ==================================================================

    /** 一行模组：勾选框 + 名称 + 状态徽标 + 体积。 */
    private final class ModCell extends ListCell<InstalledMods.Entry> {

        private final CheckBox box = new CheckBox();
        private final Label name = Ui.shrinkable(Ui.label("", "section-title"));
        private final Label status = Ui.label("", "badge");
        private final Label meta = Ui.label("", "mute-2");
        private final VBox texts = new VBox(2, new HBox(8, name, status), meta);
        private final HBox root = new HBox(10, box, texts);

        private InstalledMods.Entry bound;

        ModCell() {
            root.setAlignment(Pos.CENTER_LEFT);
            root.setPadding(new javafx.geometry.Insets(6, 4, 6, 4));
            HBox.setHgrow(texts, Priority.ALWAYS);
            box.setOnAction(event -> {
                if (bound == null) {
                    return;
                }
                if (box.isSelected()) {
                    checked.add(bound.relative());
                } else {
                    checked.remove(bound.relative());
                }
                updateSummary();
            });
        }

        @Override
        protected void updateItem(InstalledMods.Entry item, boolean empty) {
            super.updateItem(item, empty);
            bound = item;
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            name.setText(item.name());
            status.setText(I18n.t(item.enabled() ? "mods.status.enabled" : "mods.status.disabled"));
            // 禁用态去掉强调色，回落到 .badge 的低调默认外观
            status.getStyleClass().remove("badge-info");
            if (item.enabled()) {
                status.getStyleClass().add("badge-info");
            }
            // 子目录里的模组额外标出来源，否则用户会找不到文件在哪
            String location = item.relative().equals(item.fileName())
                    ? "" : I18n.t("mods.inFolder", parentOf(item.relative())) + " · ";
            meta.setText(location + Ui.humanSize(item.size()));
            // 禁用态整行降低对比度：一眼能看出哪些不会生效
            root.setOpacity(item.enabled() ? 1.0 : 0.55);
            box.setSelected(checked.contains(item.relative()));
            setGraphic(root);
            setText(null);
        }
    }
}
