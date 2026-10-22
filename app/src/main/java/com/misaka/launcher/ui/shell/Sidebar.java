package com.misaka.launcher.ui.shell;

import com.misaka.launcher.app.AppInfo;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.ui.component.Icons;
import com.misaka.launcher.ui.component.Ui;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 左侧导航栏。
 *
 * <p>结构对齐 HMCL 的认知模型：<b>分组 + 横排图标文字</b>。</p>
 * <ul>
 *   <li>「账户」组：账户卡片（皮肤头像 + 名称 + 类型），点击进入账户页；</li>
 *   <li>「游戏」组：主页 / 实例管理 / 下载中心；</li>
 *   <li>「通用」组：设置 / 日志；</li>
 *   <li>底部：应用名与版本（版本取自 jar 清单，避免到处硬编码）。</li>
 * </ul>
 *
 * <p>相比旧版纯图标竖排（78px）的改进：文字常显，不需要靠悬停猜含义；
 * 分组标题把「与游戏相关」和「与启动器本身相关」区分开；
 * 选中态用一块会<b>滑动</b>的高亮背景表达，切换页面时有连续的空间感，
 * 而不是简单的样式切换。</p>
 */
public final class Sidebar extends StackPane {

    private static final Logger log = LoggerFactory.getLogger(Sidebar.class);

    private static final double WIDTH = 216;
    private static final double ITEM_HEIGHT = 38;
    private static final double AVATAR_SIZE = 34;
    private static final Duration SLIDE = Duration.millis(190);

    /** 一个导航条目。 */
    private record Entry(String pageId, String iconPath, String labelKey) {
    }

    /** 一个分组：标题键 + 条目。 */
    private record Group(String titleKey, List<Entry> entries) {
    }

    private static final List<Group> GROUPS = List.of(
            new Group("nav.group.game", List.of(
                    new Entry("home", Icons.HOME, "nav.home"),
                    new Entry("instances", Icons.INSTANCE, "nav.instances"),
                    new Entry("download", Icons.DOWNLOAD, "nav.download"),
                    new Entry("mods", Icons.MODS, "nav.mods"),
                new Entry("packs", Icons.IMAGE, "nav.packs"))),
            new Group("nav.group.general", List.of(
                    new Entry("settings", Icons.SETTINGS, "nav.settings"),
                    new Entry("logs", Icons.LOGS, "nav.logs"))));

    private final AppShell shell;
    private final ViewRouter router;

    private final VBox content = new VBox();
    /** 会滑动的高亮块；必须位于内容之下，因此先加入 StackPane。 */
    private final Region highlight = new Region();
    private final Map<String, HBox> items = new LinkedHashMap<>();
    /** 当前选中的条目：高亮必须跟随它的边界变化，而不是只在切换时定位一次。 */
    private HBox selectedItem;

    private final StackPane avatarHolder = new StackPane();
    private final Label accountName = Ui.label("", "side-account-name");
    private final Label accountType = Ui.label("", "side-account-type");

    public Sidebar(ViewRouter router, AppShell shell) {
        this.router = router;
        this.shell = shell;

        getStyleClass().add("sidebar");
        setPrefWidth(WIDTH);
        setMinWidth(WIDTH);
        setMaxWidth(WIDTH);
        setAlignment(Pos.TOP_LEFT);

        highlight.getStyleClass().add("side-highlight");
        highlight.setManaged(false);
        highlight.setMouseTransparent(true);
        highlight.resize(WIDTH - 12, ITEM_HEIGHT);
        // 首帧布局完成前 bounds 全是 0，此时显示高亮会出现在内容区顶部
        // （正好压在「账户」分组标题上，看起来像选错了页面）。等真正对齐后再显示。
        highlight.setVisible(false);

        content.setSpacing(0);
        content.setFillWidth(true);

        buildContent();

        getChildren().add(content);

        router.addListener(this::refreshSelection);
        // 头像加载完成后由缓存广播回来：账户页与侧边栏因此同时更新
        shell.services().avatars().subscribe(this::refresh);
        refresh();
        // 首次布局完成后再把高亮对到当前条目上（此时才知道条目的真实 Y）
        Platform.runLater(() -> refreshSelection());
    }

    // ==================================================================
    // 构建
    // ==================================================================

    /** 重建全部内容（语言切换时也走这里，避免逐个替换文案出错）。 */
    private void buildContent() {
        items.clear();
        // 重建后旧的选中引用会失效，交给 refreshSelection 重新指向
        selectedItem = null;
        List<Node> children = new ArrayList<>();

        // 高亮必须是内容区的第一个子节点。
        // 原因：条目的 boundsInParent 是「内容 VBox 坐标系」，而 StackPane 的 layoutX/layoutY
        // 原点是面板自身的左上角（**不含 CSS padding**）。把高亮挂在面板上，它会整体左上偏移
        // 一个 padding（10, 12），看上去就是「高亮与条目对不齐、还越过面板左边界」。
        // 放进同一个 VBox 后，两者坐标系一致，padding 由 VBox 的定位统一承担。
        // unmanaged 使其不参与 VBox 布局，只作为背景绘制在条目之下。
        children.add(highlight);

        children.add(groupHeader("nav.group.account"));
        children.add(buildAccountCard());

        for (Group group : GROUPS) {
            children.add(groupHeader(group.titleKey()));
            for (Entry entry : group.entries()) {
                children.add(createItem(entry));
            }
        }

        Region spacer = new Region();
        VBox.setVgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        children.add(spacer);
        children.add(Ui.label(AppInfo.title(), "side-footer"));

        content.getChildren().setAll(children);
    }

    private VBox groupHeader(String titleKey) {
        Label label = Ui.label(I18n.t(titleKey), "side-group");
        Region line = new Region();
        line.getStyleClass().add("side-group-line");
        line.setPrefHeight(1);
        line.setMinHeight(1);
        line.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(5, label, line);
        box.getStyleClass().add("side-group-box");
        return box;
    }

    private HBox createItem(Entry entry) {
        StackPane iconHolder = new StackPane(Icons.create(entry.iconPath(), 17));
        iconHolder.setMinSize(18, 18);
        iconHolder.setPrefSize(18, 18);
        iconHolder.setMaxSize(18, 18);

        Label label = Ui.label(I18n.t(entry.labelKey()), "side-item-label");
        HBox item = new HBox(11, iconHolder, label);
        item.setAlignment(Pos.CENTER_LEFT);
        item.getStyleClass().add("side-item");
        item.setPrefHeight(ITEM_HEIGHT);
        item.setMinHeight(ITEM_HEIGHT);
        item.setMaxWidth(Double.MAX_VALUE);
        item.setUserData(entry.pageId());
        item.setOnMouseClicked(event -> shell.navigate(entry.pageId()));
        Tooltip.install(item, Ui.tooltip(I18n.t(entry.labelKey())));
        registerHighlightTarget(entry.pageId(), item);
        return item;
    }

    /**
     * 登记一个「可承载高亮」的节点。
     *
     * <p>除了普通导航条目，账户卡片也要登记：它本身就是进入账户页的入口，
     * 而「账户」页在导航里没有独立条目 —— 不登记的话，切到账户页时高亮找不到目标，
     * 就会停在原地（表现为高亮飘在分组标题上、与任何条目都不对齐）。</p>
     */
    private void registerHighlightTarget(String pageId, HBox node) {
        items.put(pageId, node);
        // 条目位置会随布局变化而变（异步加载头像改变账户卡片高度、切换语言重建文案、
        // 窗口拉伸等）。只在「切换选中」那一刻算一次位置，之后必然对不齐 ——
        // 因此让高亮直接跟随条目的实际边界。
        node.boundsInParentProperty().addListener((observable, was, now) -> {
            if (node == selectedItem) {
                alignHighlight(node, false);
            }
        });
    }

    private HBox buildAccountCard() {
        avatarHolder.setMinSize(AVATAR_SIZE, AVATAR_SIZE);
        avatarHolder.setPrefSize(AVATAR_SIZE, AVATAR_SIZE);
        avatarHolder.setMaxSize(AVATAR_SIZE, AVATAR_SIZE);

        VBox text = new VBox(1, accountName, accountType);
        text.setAlignment(Pos.CENTER_LEFT);

        HBox card = new HBox(10, avatarHolder, text);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("side-account");
        card.setOnMouseClicked(event -> shell.navigate("accounts"));
        card.setStyle("-fx-cursor: hand;");
        // 账户页导航里没有独立条目，账户卡片就是它的入口，因此也作为高亮目标
        registerHighlightTarget("accounts", card);
        return card;
    }

    // ==================================================================
    // 刷新
    // ==================================================================

    /** 刷新账户卡片与选中态（切换实例 / 账户 / 设置变更后调用）。 */
    public void refresh() {
        LauncherConfig.AccountEntry account = shell.services().accounts().current();
        accountName.setText(account == null ? I18n.t("account.title") : account.username);
        accountType.setText(account == null
                ? I18n.t("account.none") : typeText(account));
        refreshAvatar(account);
        refreshSelection();
    }

    /** 语言切换后重建静态文案。 */
    public void refreshTexts() {
        buildContent();
        refresh();
        // 重建后条目位置会变，重新对齐高亮（不做动画，避免闪动）
        Platform.runLater(this::refreshSelection);
    }

    private static String typeText(LauncherConfig.AccountEntry account) {
        // 账户体系只剩微软正版一种类型，不必再按 type 分支
        return I18n.t("account.type.microsoft");
    }

    /**
     * 刷新头像。
     *
     * <p>走全局 {@link com.misaka.launcher.app.AvatarCache}：命中缓存立即显示真实头像，
     * 未命中先用默认 Steve / Alex 占位并触发一次后台加载；加载完成后由缓存<b>广播</b>回来，
     * 因此侧边栏与账户页是同一次请求、同一帧替换 —— 不再「一个先变、一个后变」。</p>
     */
    private void refreshAvatar(LauncherConfig.AccountEntry account) {
        String model = account == null ? null : account.skinModel;
        Image image = shell.services().avatars().request(account);
        avatarHolder.getChildren().setAll(Ui.playerHead(image, model, AVATAR_SIZE));
    }

    /** 按路由当前页刷新选中态与滑动高亮。 */
    private void refreshSelection() {
        String current = router.currentId();
        for (Map.Entry<String, HBox> entry : items.entrySet()) {
            boolean selected = entry.getKey().equals(current);
            entry.getValue().getStyleClass().remove("selected");
            if (selected) {
                entry.getValue().getStyleClass().add("selected");
            }
        }
        HBox target = items.get(current);
        selectedItem = target;
        if (target == null) {
            // 没有对应条目（例如未来新增的页面）：宁可隐藏高亮，
            // 也不要让它停在上一个位置误导用户
            highlight.setVisible(false);
            return;
        }
        // 不在这里直接显示：布局完成前 bounds 为 0，显示出来会飘到内容区顶部。
        // 由 alignHighlight 在拿到真实尺寸后再决定是否可见。
        // 条目位置只有布局完成后才准确
        Platform.runLater(() -> alignHighlight(target, true));
    }

    /**
     * 把高亮块对齐到目标条目。
     *
     * <p>位置与尺寸一律取条目的<b>真实边界</b>（{@code boundsInParent}），不要硬编码。
     * 前提是高亮与条目处在<b>同一个坐标系</b>里 —— 两者都是 {@code content} 的子节点，
     * 因此这里拿到的值可以直接用。若把高亮挂到面板上，就必须再加一层 padding 补偿，
     * 否则会整体左上偏移一个 padding。</p>
     *
     * <p>高度直接切换、纵向位置做插值：条目高度一致时视觉上完全平滑，
     * 而给高度也做动画会让每帧都触发重新布局，得不偿失。</p>
     *
     * @param animate 是否做滑动动画；布局被动变化时传 false，避免窗口拉伸时高亮「乱飞」
     */
    private void alignHighlight(Node target, boolean animate) {
        Bounds bounds = target.getBoundsInParent();
        // 布局还没跑：宽高为 0，这时候摆高亮必然错位（会停在内容区顶部）
        if (bounds.getWidth() <= 1 || bounds.getHeight() <= 1) {
            highlight.setVisible(false);
            return;
        }
        highlight.setVisible(true);
        highlight.resize(bounds.getWidth(), bounds.getHeight());
        double x = bounds.getMinX();
        double y = bounds.getMinY();

        if (Math.abs(highlight.getLayoutX() - x) > 0.5) {
            highlight.setLayoutX(x);
        }
        if (Math.abs(highlight.getLayoutY() - y) < 0.5) {
            highlight.setLayoutY(y);
            verifyAlignment(target);
            return;
        }
        if (!animate
                || !shell.services().config().ui.animationsEnabled
                || shell.services().config().ui.reduceMotion) {
            highlight.setLayoutY(y);
            verifyAlignment(target);
            return;
        }
        Timeline timeline = new Timeline(new KeyFrame(SLIDE,
                new KeyValue(highlight.layoutYProperty(), y, Interpolator.EASE_BOTH)));
        // 动画结束后位置才最终确定，此时再做自检
        timeline.setOnFinished(event -> verifyAlignment(target));
        timeline.play();
    }

    /**
     * 自检：高亮与目标条目在<b>场景坐标</b>下必须重合。
     *
     * <p>坐标系不一致是这类「选中特效错位」的唯一种类的原因 —— 一旦高亮与条目不在同一层
     * （例如一个挂在 StackPane、一个挂在带 padding 的 VBox），本地坐标算得再对也会整体偏移，
     * 而肉眼下往往只表现为「差一点点」。这里用场景坐标做硬校验，出问题直接告警，
     * 不必再靠截图比对。</p>
     */
    private void verifyAlignment(Node target) {
        Bounds targetScene = target.localToScene(target.getBoundsInLocal());
        Bounds highlightScene = highlight.localToScene(highlight.getBoundsInLocal());
        double dx = Math.abs(targetScene.getMinX() - highlightScene.getMinX());
        double dy = Math.abs(targetScene.getMinY() - highlightScene.getMinY());
        if (dx > 0.5 || dy > 0.5) {
            log.warn("侧边栏高亮未对齐：偏移 dx={} dy={}，条目={} 高亮={}",
                    Math.round(dx), Math.round(dy), targetScene, highlightScene);
        }
    }
}
