package com.misaka.launcher.ui.shell;

import com.misaka.launcher.app.AppInfo;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.util.OsUtils;
import com.misaka.launcher.ui.component.AppIcon;
import com.misaka.launcher.ui.component.Icons;
import com.misaka.launcher.ui.component.Toast;
import com.misaka.launcher.ui.component.Ui;
import com.misaka.launcher.ui.theme.Theme;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/**
 * 自绘标题栏。
 *
 * <p>结构对齐 HMCL：左侧品牌（图标 + 应用名 + 版本），右侧快捷操作与窗口控制。
 * 刻意保持「轻」——实例、账户、Java、内存等状态一律放在侧边栏与启动卡片里，
 * 标题栏只承担品牌识别、窗口拖动与窗口控制三件事，这样它既是拖动区，
 * 也不会因为状态文字变长而挤压窗口按钮。</p>
 *
 * <p>平台适配：macOS 遵循「红绿灯在左」的惯例把窗口按钮前置，
 * Windows / Linux 放在右侧。</p>
 */
public final class TitleBar extends HBox {

    private static final double HEIGHT = 46;
    /** 品牌图标边长；与应用图标的多档尺寸保持协调（取 64 档缩放到此尺寸）。 */
    private static final double LOGO_SIZE = 20;

    private final AppShell shell;
    private final Stage stage;

    private final Label appNameLabel;
    private final HBox windowControls = new HBox(2);
    private final Button maximizeButton;
    /** 右上角的语言入口：显示当前语言的两个字符（中 / EN / FR）。 */
    private final Button languageButton;

    /** 常驻按钮的提示文案键：语言切换后必须重建 Tooltip（它是构造时定死的）。 */
    private record Tip(Button button, String key) {
    }

    private final java.util.List<Tip> tips = new java.util.ArrayList<>();

    private Button rememberTip(Button button, String key) {
        tips.add(new Tip(button, key));
        return button;
    }

    public TitleBar(AppShell shell, Stage stage) {
        this.shell = shell;
        this.stage = stage;

        getStyleClass().add("title-bar");
        setAlignment(Pos.CENTER_LEFT);
        setPrefHeight(HEIGHT);
        setMinHeight(HEIGHT);
        setMaxHeight(HEIGHT);

        // ---- 品牌 ----
        appNameLabel = Ui.label(I18n.t("app.name"), "title-bar-title");
        Label versionLabel = Ui.label("v" + AppInfo.version(), "title-bar-version");

        HBox brand = new HBox(9, brandLogo(), appNameLabel, versionLabel);
        brand.setAlignment(Pos.CENTER_LEFT);

        // ---- 快捷操作与窗口控制 ----
        // 语言入口放在右上角、且常驻：一个看不懂当前界面语言的人，
        // 首先要能立刻找到「换成我的语言」，而不是先去猜「设置」在哪。
        // 用「地球 + A/文」的图标而不是「中/EN」文字：图标在任何语言下都读得懂，
        // 而「中」字对外国用户等于又一个看不懂的字。
        // 先建按钮再挂事件：直接在构造参数里引用 languageButton 会触发
        // 「变量可能尚未初始化」（final 字段在赋值表达式中被 lambda 捕获）
        languageButton = Ui.iconButton(Icons.LANGUAGE, 18, "home.language", event -> {
        });
        languageButton.setGraphic(languageMark(18));
        languageButton.setOnAction(event -> showLanguagePopup(languageButton));
        tips.add(new Tip(languageButton, "home.language"));

        Button themeButton = rememberTip(Ui.iconButton(Icons.PALETTE, 16, "settings.theme.mode",
                event -> cycleTheme()), "settings.theme.mode");
        Button backgroundButton = rememberTip(Ui.iconButton(Icons.IMAGE, 16, "settings.background",
                event -> shell.openSettings("theme")), "settings.background");
        Button minimizeButton = createWindowButton(Icons.MINIMIZE, "window.minimize",
                event -> stage.setIconified(true));
        maximizeButton = createWindowButton(Icons.MAXIMIZE, "window.maximize",
                event -> toggleMaximize());
        Button closeButton = createWindowButton(Icons.CLOSE, "window.close",
                event -> {
                    if (shell.selfUpdatePhase() == TaskCenter.SelfUpdatePhase.INSTALLING) {
                        // 此刻正在把原文件改名、再写入新文件（见 SelfUpdateInstaller.install）：
                        // 中断在这里会让用户手里少一个能双击打开的启动器，只能提示他等几秒
                        shell.toast().show(I18n.t("update.gate.closing"), Toast.Kind.WARN);
                        return;
                    }
                    stage.close();
                });
        closeButton.getStyleClass().add("window-button-close");

        windowControls.setAlignment(Pos.CENTER_RIGHT);
        // 固定为期望宽度：窗口变窄时优先压缩左侧品牌区，窗口按钮永不被挤掉
        windowControls.setMinWidth(Region.USE_PREF_SIZE);
        windowControls.getChildren().addAll(languageButton, themeButton, backgroundButton,
                minimizeButton, maximizeButton, closeButton);

        if (OsUtils.isMacOS()) {
            getChildren().addAll(windowControls, Ui.spacer(12), brand, Ui.grow());
        } else {
            getChildren().addAll(brand, Ui.grow(), windowControls);
        }

        refresh();
    }

    /**
     * 标题栏品牌图标。
     *
     * <p>直接用<b>应用图标本体</b>（构建期由 {@code icon.webp} 转出的 PNG，与窗口 / 任务栏图标同源），
     * 而不是另画一个抽象方块 —— 否则同一个软件会出现两种互不相干的「品牌标识」。</p>
     *
     * <p>图标资源缺失时退回纯 CSS 方块，保证界面不会出现空洞。</p>
     */
    private static Node brandLogo() {
        javafx.scene.image.Image image = AppIcon.image(64);
        if (image == null) {
            Region logo = new Region();
            logo.getStyleClass().add("brand-logo");
            logo.setMinSize(LOGO_SIZE, LOGO_SIZE);
            logo.setPrefSize(LOGO_SIZE, LOGO_SIZE);
            logo.setMaxSize(LOGO_SIZE, LOGO_SIZE);
            return logo;
        }
        javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(image);
        view.setFitWidth(LOGO_SIZE);
        view.setFitHeight(LOGO_SIZE);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        // 圆角裁剪：应用图标是方形像素画，小尺寸下加圆角更接近系统里的应用磁贴观感
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(LOGO_SIZE, LOGO_SIZE);
        clip.setArcWidth(LOGO_SIZE * 0.34);
        clip.setArcHeight(LOGO_SIZE * 0.34);
        view.setClip(clip);
        return view;
    }

    /** 刷新与状态相关的展示（当前仅最大化图标）。 */
    public void refresh() {
        updateMaximizeIcon();
    }

    /** 语言切换后刷新文案。 */
    public void refreshTexts() {
        appNameLabel.setText(I18n.t("app.name"));
        // 图标不随语言变化（它本身就表示"语言"），但所有常驻按钮的提示文案都要重建：
        // Tooltip 的内容是构造时定死的，不重建的话切到法语后鼠标悬停仍是中文
        for (Tip tip : tips) {
            tip.button().setTooltip(Ui.tooltip(I18n.t(tip.key())));
        }
    }

    /**
     * 语言选择浮层：搜索框 + 语言按钮列表。
     *
     * <p>不用 {@code ListView}：它依赖场景样式表推导文字与背景色，而 Popup 的内容
     * 并不跟随主窗口的样式表 —— 实测表现为「文字发红、点了没有任何反馈」。
     * 这里改用真实按钮排成列表：点击语义明确，也不依赖任何 ListCell 样式。</p>
     */
    private void showLanguagePopup(javafx.scene.Node anchor) {
        javafx.stage.Popup popup = new javafx.stage.Popup();
        popup.setAutoHide(true);

        // 第一个参数是「占位提示的文案键」，第二个是初值。
        // 写反的后果：把「搜索语言」四个字当成文本填进输入框，过滤器再拿这四个字去匹配，
        // 列表于是永远是空的。
        javafx.scene.control.TextField search = Ui.textField("language.search", "");
        search.setPrefWidth(232);
        search.setMaxWidth(232);

        javafx.scene.layout.VBox list = new javafx.scene.layout.VBox(2);

        java.util.function.Consumer<String> choose = tag -> {
            popup.hide();
            if (tag == null) {
                return;
            }
            shell.services().context().setLanguage(tag);
            shell.refreshTexts();
        };

        Runnable filter = () -> {
            String keyword = search.getText() == null
                    ? "" : search.getText().trim().toLowerCase(java.util.Locale.ROOT);
            list.getChildren().clear();
            for (String tag : I18n.availableLanguages()) {
                String name = I18n.displayName(tag);
                if (!keyword.isEmpty()
                        && !name.toLowerCase(java.util.Locale.ROOT).contains(keyword)
                        && !tag.toLowerCase(java.util.Locale.ROOT).contains(keyword)) {
                    continue;
                }
                boolean active = tag.equals(I18n.languageTag());
                Button row = Ui.ghostButtonText(active ? name + "   ✓" : name, event -> {
                });
                row.setOnAction(event -> choose.accept(tag));
                row.setMaxWidth(Double.MAX_VALUE);
                row.setPrefHeight(32);
                list.getChildren().add(row);
            }
        };

        search.textProperty().addListener((observable, was, now) -> filter.run());
        // 回车 = 选中第一条可见项，省一次鼠标
        search.setOnAction(event -> {
            if (!list.getChildren().isEmpty()
                    && list.getChildren().get(0) instanceof Button first) {
                first.fire();
            }
        });
        filter.run();

        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(list);
        scroll.setPrefViewportWidth(232);
        // 高度按语言数量给足：最少也能看到 4 行，多了就滚动。
        // 之前只有约 40px（一行），点开像"只列了一项"。
        double listHeight = Math.min(284, Math.max(148, list.getChildren().size() * 38 + 12));
        scroll.setPrefViewportHeight(listHeight);
        scroll.setPrefHeight(listHeight + 2);
        scroll.setMinHeight(listHeight + 2);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; -fx-padding: 0;");

        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(8, search, scroll);
        box.getStyleClass().add("glass-card");
        // 弹层是独立场景，拿不到主窗口根节点的字体覆盖：不显式指定的话，
        // 「简体中文」会显示成方块（主界面正常、只有这个浮层是方的）
        box.setStyle("-fx-padding: 10; "
                + com.misaka.launcher.ui.theme.Fonts.rootStyleFragment());
        box.setPrefWidth(252);
        // Popup 的内容不属于主场景，必须把主窗口的样式表挂到它自己身上，
        // 否则字体、颜色、圆角会全部回落成 JavaFX 默认外观。
        if (shell.stage().getScene() != null) {
            box.getStylesheets().setAll(shell.stage().getScene().getStylesheets());
        }
        popup.getContent().add(box);

        javafx.geometry.Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        // 右对齐到按钮：语言按钮靠近窗口右缘，左对齐会把浮层顶出屏幕
        popup.show(anchor, bounds.getMaxX() - 252, bounds.getMaxY() + 6);
        javafx.application.Platform.runLater(search::requestFocus);
    }

    /** 标题栏语言图标：地球 + 左上 A / 右下 文。 */
    private static javafx.scene.Node languageMark(double size) {
        javafx.scene.Node globe = Icons.create(Icons.LANGUAGE, size);
        javafx.scene.control.Label latin = Ui.label("A", "lang-mark");
        latin.setStyle("-fx-font-size: " + Math.round(size * 0.46) + "px; -fx-font-weight: bold;");
        javafx.scene.control.Label cjk = Ui.label("文", "lang-mark");
        cjk.setStyle("-fx-font-size: " + Math.round(size * 0.46) + "px;");
        javafx.scene.layout.StackPane mark =
                new javafx.scene.layout.StackPane(globe, latin, cjk);
        mark.setMaxSize(size, size);
        javafx.scene.layout.StackPane.setAlignment(latin, javafx.geometry.Pos.TOP_LEFT);
        javafx.scene.layout.StackPane.setAlignment(cjk, javafx.geometry.Pos.BOTTOM_RIGHT);
        return mark;
    }

    private Button createWindowButton(String iconPath, String tooltipKey,
                                      EventHandler<ActionEvent> handler) {
        Button button = new Button();
        button.setGraphic(Icons.create(iconPath, 15));
        button.getStyleClass().add("window-button");
        button.setTooltip(Ui.tooltip(I18n.t(tooltipKey)));
        button.setOnAction(handler);
        // 记下键：Tooltip 文案是构造时定死的，语言切换后要重建
        tips.add(new Tip(button, tooltipKey));
        return button;
    }

    private void toggleMaximize() {
        stage.setMaximized(!stage.isMaximized());
        updateMaximizeIcon();
    }

    private void updateMaximizeIcon() {
        maximizeButton.setGraphic(Icons.create(
                stage.isMaximized() ? Icons.RESTORE : Icons.MAXIMIZE, 15));
    }

    /** 在暗色 / 浅色 / 自定义之间循环切换（标题栏快捷入口）。 */
    private void cycleTheme() {
        Theme current = shell.themeManager().current();
        Theme next = switch (current) {
            case DARK -> Theme.LIGHT;
            case LIGHT -> Theme.CUSTOM;
            default -> Theme.DARK;
        };
        shell.themeManager().apply(next, shell.services().config().themeAccent);
        shell.toast().show(I18n.t(next.i18nKey()));
        // 浅色/暗色切换会改变背景叠加强度的观感，这里重绘一次背景
        shell.backgroundManager().refresh();
    }
}
