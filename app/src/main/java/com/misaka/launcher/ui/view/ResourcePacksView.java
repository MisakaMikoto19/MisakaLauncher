package com.misaka.launcher.ui.view;

import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.pack.ResourcePackService;
import com.misaka.launcher.ui.component.Icons;
import com.misaka.launcher.ui.component.PackIcons;
import com.misaka.launcher.ui.component.Toast;
import com.misaka.launcher.ui.component.Ui;
import com.misaka.launcher.ui.shell.AppShell;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 资源包管理（只针对<b>当前选中实例</b>）。
 *
 * <p><b>顺序</b>：列表按 {@code options.txt} 的 {@code resourcePacks} 顺序展示，
 * 越靠下优先级越高（覆盖靠上的包）。拖动右侧的「≡」并<b>长按</b>即可调整，
 * 松手后写回配置。</p>
 *
 * <p><b>为什么不用 ListView</b>：ListView 的单元格复用与内建 DnD 都不提供位移动画，
 * 拖动时只能看到整行「瞬移」。这里用 VBox + 手动位移：被拖的行跟手，其它行用
 * {@link TranslateTransition} 让出空位，松手后再归位。</p>
 *
 * <p><b>与贴图压缩的关系</b>：启动器生成的 {@code misaka_atlas_compat} 显示为
 * 「由启动器管理」，<b>不可拖动、不可停用</b> —— 它必须永远排在最后才能盖掉
 * AMD / Intel 显卡上超限的大贴图。</p>
 */
public final class ResourcePacksView extends ScrollPane {

    private static final Logger log = LoggerFactory.getLogger(ResourcePacksView.class);

    /** 固定行高：拖动时靠它算位移，所以行高必须一致（间距为 0，见 packList）。 */
    private static final double ROW_HEIGHT = 58;
    /** 缩略图边长。 */
    private static final double ICON_SIZE = 40;
    /** 让位动画时长。 */
    private static final Duration GAP_ANIM = Duration.millis(130);
    /** 长按判定：按住这么久之后才进入拖拽，避免误触。 */
    private static final Duration HOLD_DELAY = Duration.millis(180);

    private final AppShell shell;

    private final Label instanceLabel = Ui.label("", "mute-2");
    private final Label dirLabel = Ui.label("", "mute-2");
    private final Label orderHint = Ui.label("", "mute-2");
    private final Label emptyHint = Ui.label("", "mute-2");
    private final TextField searchField = Ui.searchField("packs.searchPlaceholder", null);
    private final VBox packList = new VBox(0);

    private List<ResourcePackService.PackEntry> allEntries = List.of();
    private final List<Row> rows = new ArrayList<>();

    private final PauseTransition holdTimer = new PauseTransition(HOLD_DELAY);
    private Row dragging;
    /** 被拖的行在**静态列表**里的原始下标；拖动过程中不变，用来算「谁该让位」。 */
    private int dragFromIndex;
    /** 当前指针指向的目标槽位（静态列表坐标）。 */
    private int dragTargetIndex;
    private double dragStartSceneY;
    private double dragStartTranslateY;
    private double pressSceneY;
    /** 拖拽开始时列表顶部的场景坐标：**必须在没有位移时取**，之后一直用它换算槽位。 */
    private double listTopSceneY;
    /** 按下点相对被拖行顶部的偏移，用来还原「行的中心」而不是手指位置。 */
    private double grabOffsetY;
    /** 可落位的槽位范围：只允许落在「已启用且非托管」的行之间。 */
    private int minDragSlot;
    private int maxDragSlot;
    /** 正在跑的让位动画：同一个节点上不能并发两个，否则会互相打架。 */
    private final java.util.Map<javafx.scene.Node, TranslateTransition> activeShifts =
            new java.util.HashMap<>();

    public ResourcePacksView(AppShell shell) {
        this.shell = shell;

        VBox content = new VBox(16, buildHeader(), buildListCard());
        Ui.mountScrollable(this, content, Ui.CONTENT_MAX_WIDTH);
        refresh();
    }

    // ==================================================================
    // 界面
    // ==================================================================

    private Node buildHeader() {
        Button refresh = Ui.ghostButton("action.refresh", event -> refresh(),
                Icons.create(Icons.REFRESH, 14));
        Button add = Ui.ghostButton("packs.add", event -> addPack(), Icons.create(Icons.PLUS, 14));
        Button openFolder = Ui.ghostButton("packs.openFolder", event -> openFolder(),
                Icons.create(Icons.FOLDER, 14));
        HBox actions = new HBox(8, refresh, add, openFolder);
        actions.setAlignment(Pos.CENTER_LEFT);

        instanceLabel.setWrapText(true);
        dirLabel.setWrapText(true);
        orderHint.setWrapText(true);
        return Ui.glassCard(12, Ui.cardHeader("packs.title", actions),
                instanceLabel, dirLabel, orderHint);
    }

    private Node buildListCard() {
        searchField.textProperty().addListener((observable, oldValue, value) -> rebuildRows());
        HBox searchRow = new HBox(10, searchField);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        emptyHint.setWrapText(true);
        orderHint.setText(I18n.t("packs.orderHint"));
        return Ui.glassCard(12, Ui.cardHeader("packs.list"), searchRow, packList, emptyHint);
    }

    // ==================================================================
    // 数据
    // ==================================================================

    /** 刷新：只针对当前选中实例。 */
    public void refresh() {
        LauncherConfig.InstanceEntry instance = currentInstance();
        if (instance == null) {
            instanceLabel.setText(I18n.t("packs.noInstance"));
            dirLabel.setText("");
            allEntries = List.of();
            rebuildRows();
            return;
        }
        GamePaths paths = shell.services().instances().gamePaths(instance);
        instanceLabel.setText(I18n.t("packs.current", instance.name));
        dirLabel.setText(I18n.t("packs.dir", paths.resourcePacksDir().toString()));
        allEntries = shell.services().resourcePacks().list(paths);
        rebuildRows();
        log.debug("资源包页刷新：实例 {}，{} 个包", instance.id, allEntries.size());
    }

    private void rebuildRows() {
        packList.getChildren().clear();
        rows.clear();
        List<ResourcePackService.PackEntry> shown = filtered();
        for (ResourcePackService.PackEntry entry : shown) {
            Row row = buildRow(entry);
            rows.add(row);
            packList.getChildren().add(row.box);
        }
        if (shown.isEmpty()) {
            emptyHint.setText(searchText().isEmpty()
                    ? I18n.t("packs.empty") : I18n.t("packs.search.noResult"));
        } else {
            emptyHint.setText("");
        }
    }

    private List<ResourcePackService.PackEntry> filtered() {
        String keyword = searchText();
        if (keyword.isEmpty()) {
            return allEntries;
        }
        return allEntries.stream()
                .filter(entry -> entry.fileName().toLowerCase(Locale.ROOT).contains(keyword))
                .toList();
    }

    private String searchText() {
        return searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
    }

    private Row buildRow(ResourcePackService.PackEntry entry) {
        CheckBox toggle = new CheckBox();
        toggle.setSelected(entry.enabled());
        toggle.setDisable(entry.locked() || shell.selfUpdating());
        toggle.selectedProperty().addListener((observable, was, now) -> {
            if (was != now) {
                setEnabled(entry, now);
            }
        });

        Label title = Ui.label(entry.fileName(), "section-title");
        HBox nameRow = new HBox(8, title);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        if (entry.locked()) {
            nameRow.getChildren().add(Ui.badge(I18n.t("packs.managed"), "badge-accent"));
        }
        String metaText = entry.locked()
                ? I18n.t("packs.managedTip")
                : entry.sizeBytes() > 0 ? Ui.humanSize(entry.sizeBytes()) : "";
        Label meta = Ui.label(metaText, "mute-2");
        meta.setWrapText(true);
        meta.setMaxWidth(520);
        VBox text = new VBox(2, nameRow, meta);
        text.setAlignment(Pos.CENTER_LEFT);

        // 三横杠拖拽手柄：长按后拖动排序
        Label handle = Ui.label("≡", "mute-2");
        handle.setStyle("-fx-font-size: 17px; -fx-padding: 0 10 0 10;");
        boolean draggable = entry.enabled() && !entry.locked()
                && !shell.selfUpdating() && searchText().isEmpty();
        handle.setOpacity(draggable ? 1 : 0.28);
        handle.setCursor(draggable ? Cursor.OPEN_HAND : Cursor.DEFAULT);
        Tooltip.install(handle, Ui.tooltip(I18n.t(
                draggable ? "packs.dragTip" : entry.locked() ? "packs.dragLocked"
                        : entry.enabled() ? "packs.dragFiltered" : "packs.dragDisabled")));

        HBox box = new HBox(12, toggle, packIcon(entry), text, Ui.grow(), handle);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMinHeight(ROW_HEIGHT);
        box.setPrefHeight(ROW_HEIGHT);
        box.setMaxHeight(ROW_HEIGHT);
        box.setStyle("-fx-padding: 0 6 0 6; -fx-background-radius: 8;");

        Row row = new Row(entry, box);
        if (draggable) {
            installDrag(row, handle);
        }
        return row;
    }

    /**
     * 一行左侧的缩略图（{@code pack.png}）。
     *
     * <p>没有 {@code pack.png} 的包退回「首字母方块」，保证每行高度一致 ——
     * 否则有图/没图的行动画位移对不齐，拖动排序时会看出来在跳。</p>
     */
    private Node packIcon(ResourcePackService.PackEntry entry) {
        StackPane holder = new StackPane();
        holder.setMinSize(ICON_SIZE, ICON_SIZE);
        holder.setPrefSize(ICON_SIZE, ICON_SIZE);
        holder.setMaxSize(ICON_SIZE, ICON_SIZE);

        LauncherConfig.InstanceEntry instance = currentInstance();
        GamePaths paths = instance == null ? null : shell.services().instances().gamePaths(instance);
        javafx.scene.image.Image image = paths == null ? null : PackIcons.image(
                entry.fileName() + "|" + entry.sizeBytes() + "|" + ICON_SIZE,
                () -> shell.services().resourcePacks().iconBytes(paths, entry.fileName()),
                ICON_SIZE);
        if (image != null) {
            javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(image);
            view.setFitWidth(ICON_SIZE);
            view.setFitHeight(ICON_SIZE);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            holder.getChildren().add(view);
        } else {
            String name = entry.fileName().replaceFirst("\\.[^.]+$", "").trim();
            holder.getChildren().add(Ui.label(
                    name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(), "badge"));
        }
        return holder;
    }

    // ==================================================================
    // 长按拖拽排序
    // ==================================================================

    private void installDrag(Row row, Node handle) {
        handle.setOnMousePressed(event -> {
            if (shell.selfUpdating() || dragging != null) {
                return;
            }
            pressSceneY = event.getSceneY();
            holdTimer.stop();
            holdTimer.setOnFinished(fired -> startDrag(row, pressSceneY));
            holdTimer.playFromStart();
        });
        handle.setOnMouseDragged(event -> {
            if (dragging != null) {
                updateDrag(event.getSceneY());
                event.consume();
            } else if (Math.abs(event.getSceneY() - pressSceneY) > 8) {
                // 按下后就开始移动：不是长按，按普通点击处理
                holdTimer.stop();
            }
        });
        handle.setOnMouseReleased(event -> {
            holdTimer.stop();
            if (dragging != null) {
                finishDrag();
            }
        });
    }

    private void startDrag(Row row, double sceneY) {
        if (dragging != null) {
            return;
        }
        dragging = row;
        dragFromIndex = rows.indexOf(row);
        dragTargetIndex = dragFromIndex;
        dragStartSceneY = sceneY;
        dragStartTranslateY = row.box.getTranslateY();

        // 两个基准都必须在「所有行都没有位移」时取：拖起来之后再取，
        // 取到的就是被挤开后的位置，换算槽位会来回跳。
        Bounds rowBounds = row.box.localToScene(row.box.getBoundsInLocal());
        grabOffsetY = sceneY - rowBounds.getMinY();
        listTopSceneY = packList.localToScene(packList.getBoundsInLocal()).getMinY();

        // 只能落在「已启用且非托管」的行之间：拖到未启用的包下方或兼容包之后
        // 在配置里是没有位置的，松手后必然弹回去，不如当场就不让它越界
        minDragSlot = rows.size() - 1;
        maxDragSlot = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row candidate = rows.get(i);
            if (candidate.entry.enabled() && !candidate.entry.locked()) {
                minDragSlot = Math.min(minDragSlot, i);
                maxDragSlot = Math.max(maxDragSlot, i);
            }
        }
        if (minDragSlot > maxDragSlot) {
            minDragSlot = 0;
            maxDragSlot = rows.size() - 1;
        }

        // 抬起来：阴影 + 压在其它行上面（viewOrder 只改绘制层级，不动布局）
        row.box.setEffect(new DropShadow(14, 0, 6, Color.rgb(0, 0, 0, 0.5)));
        row.box.setViewOrder(-1);
        row.box.setCursor(Cursor.CLOSED_HAND);
    }

    private void updateDrag(double sceneY) {
        // 行跟手（直接跟随，不加动画，否则会拖沓）
        dragging.box.setTranslateY(dragStartTranslateY + (sceneY - dragStartSceneY));

        int target = slotAt(sceneY);
        if (target == dragTargetIndex) {
            return;
        }
        // 关键：以「原始下标 ↔ 目标槽位」为准重算所有行的目标位移，
        // 而不是拿上一次的中间状态继续叠加 —— 否则已经让开的行会被挪回去。
        for (int i = 0; i < rows.size(); i++) {
            if (i == dragFromIndex) {
                continue;
            }
            double shift = 0;
            if (dragFromIndex < target && i > dragFromIndex && i <= target) {
                shift = -ROW_HEIGHT;
            } else if (dragFromIndex > target && i >= target && i < dragFromIndex) {
                shift = ROW_HEIGHT;
            }
            animateTo(rows.get(i).box, shift);
        }
        dragTargetIndex = target;
    }

    /**
     * 指针所在的槽位（按静态布局换算）。
     *
     * <p>用「列表顶部 + 固定行高」算，而不是遍历各行的实时坐标：拖动时行带着
     * {@code translateY}，遍历会读到被挤开后的位置，导致目标槽位在相邻两格之间
     * 反复跳动（表现就是"其它行不挤、松手位置也不对"）。</p>
     */
    private int slotAt(double sceneY) {
        double rowCenter = sceneY - grabOffsetY + ROW_HEIGHT / 2;
        int slot = (int) Math.floor((rowCenter - listTopSceneY) / ROW_HEIGHT);
        return Math.max(minDragSlot, Math.min(maxDragSlot, slot));
    }

    private void finishDrag() {
        Row row = dragging;
        dragging = null;
        row.box.setCursor(Cursor.OPEN_HAND);

        int to = Math.max(0, Math.min(dragTargetIndex, rows.size() - 1));
        // 落位：被拖的行滑到目标槽位，其它行的让位位移同时归零
        TranslateTransition slide = new TranslateTransition(Duration.millis(150), row.box);
        slide.setToY((to - dragFromIndex) * ROW_HEIGHT);
        slide.setInterpolator(Interpolator.EASE_BOTH);
        slide.setOnFinished(event -> {
            // 这里**不能**把 translateY 归零：归零会让这一行瞬时弹回"原槽位"，
            // 紧接着 refresh() 又按新顺序把它画到"目标槽位" —— 观感就是"先跳上去再跳下来"。
            // commitOrder 最后会重建整片行，旧节点连同它的位移一起被丢弃，天然无缝。
            row.box.setEffect(null);
            row.box.setViewOrder(0);
            commitOrder(row, to);
        });
        slide.play();
        for (Row other : rows) {
            if (other != row) {
                animateTo(other.box, 0);
            }
        }
    }

    private void commitOrder(Row moved, int to) {
        List<Row> ordered = new ArrayList<>(rows);
        ordered.remove(moved);
        ordered.add(Math.max(0, Math.min(to, ordered.size())), moved);
        List<String> names = ordered.stream()
                .filter(row -> row.entry.enabled() && !row.entry.locked())
                .map(row -> row.entry.fileName())
                .toList();

        LauncherConfig.InstanceEntry instance = currentInstance();
        if (instance == null) {
            rebuildRows();
            return;
        }
        try {
            shell.services().resourcePacks()
                    .reorder(shell.services().instances().gamePaths(instance), names);
        } catch (Exception e) {
            log.warn("保存资源包顺序失败：{}", e.getMessage());
            shell.toast().show(String.valueOf(e.getMessage()), Toast.Kind.ERROR);
        }
        // 必须 refresh()（重新读盘）而不是 rebuildRows()：rebuildRows 用的是旧的
        // allEntries 快照，画出来还是原来的顺序 —— 表现就是"松手后没有交换位置"。
        refresh();
    }

    /**
     * 位移动画：让出空位、或归位。
     *
     * <p>同一节点上先停掉上一个动画：鼠标快速划过几行时，同一个 TranslateTransition
     * 属性会被多个动画同时驱动，结果会停在错误的中间位置（看起来像"没挤动"或"挤歪了"）。</p>
     */
    private void animateTo(Node node, double targetY) {
        TranslateTransition running = activeShifts.remove(node);
        if (running != null) {
            running.stop();
        }
        if (Math.abs(node.getTranslateY() - targetY) < 0.5) {
            node.setTranslateY(targetY);
            return;
        }
        TranslateTransition transition = new TranslateTransition(GAP_ANIM, node);
        transition.setToY(targetY);
        transition.setInterpolator(Interpolator.EASE_BOTH);
        activeShifts.put(node, transition);
        transition.setOnFinished(event -> activeShifts.remove(node));
        transition.play();
    }

    // ==================================================================
    // 操作
    // ==================================================================

    private void setEnabled(ResourcePackService.PackEntry entry, boolean enabled) {
        LauncherConfig.InstanceEntry instance = currentInstance();
        if (instance == null) {
            return;
        }
        GamePaths paths = shell.services().instances().gamePaths(instance);
        try {
            shell.services().resourcePacks().setEnabled(paths, entry.fileName(), enabled);
        } catch (Exception e) {
            log.warn("切换资源包失败：{}", e.getMessage());
            shell.toast().show(String.valueOf(e.getMessage()), Toast.Kind.ERROR);
        }
        refresh();
    }

    private void addPack() {
        LauncherConfig.InstanceEntry instance = currentInstance();
        if (instance == null) {
            shell.toast().show(I18n.t("packs.noInstance"), Toast.Kind.WARN);
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("packs.add"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.t("packs.add"), "*.zip"));
        File file = chooser.showOpenDialog(shell.stage());
        if (file == null) {
            return;
        }
        GamePaths paths = shell.services().instances().gamePaths(instance);
        try {
            shell.services().resourcePacks().install(paths, file.toPath());
            shell.toast().show(I18n.t("packs.added"), Toast.Kind.SUCCESS);
        } catch (Exception e) {
            shell.toast().show(String.valueOf(e.getMessage()), Toast.Kind.ERROR);
        }
        refresh();
    }

    private void openFolder() {
        LauncherConfig.InstanceEntry instance = currentInstance();
        if (instance == null) {
            return;
        }
        java.nio.file.Path dir = shell.services().instances().gamePaths(instance).resourcePacksDir();
        try {
            java.nio.file.Files.createDirectories(dir);
            java.awt.Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            shell.toast().show(dir.toString());
        }
    }

    private LauncherConfig.InstanceEntry currentInstance() {
        return shell.services().instances().current();
    }

    /** 一行：条目 + 容器。拖动时需要两者一起用。 */
    private static final class Row {
        final ResourcePackService.PackEntry entry;
        final HBox box;

        Row(ResourcePackService.PackEntry entry, HBox box) {
            this.entry = entry;
            this.box = box;
        }
    }
}
