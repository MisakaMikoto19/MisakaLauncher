package com.misaka.launcher.ui.component;

import com.misaka.launcher.core.i18n.I18n;
import javafx.animation.Interpolator;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/**
 * 界面构建与交互的工具集合。
 * <p>把「节点构造 + 样式类 + 微动效」收敛到一处，避免每个视图各写一遍，
 * 也保证全局交互手感一致（悬浮位移、按压反馈、缓动曲线）。</p>
 */
public final class Ui {

    /** 内容区最大宽度：超宽屏下居中显示，避免行宽过大影响阅读。 */
    public static final double CONTENT_MAX_WIDTH = 1240;

    private static final Duration HOVER_IN = Duration.millis(140);
    private static final Duration HOVER_OUT = Duration.millis(180);

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private Ui() {
    }

    // ==================================================================
    // 文本与容器
    // ==================================================================

    public static Label label(String text, String... styleClasses) {
        Label label = new Label(text == null ? "" : text);
        label.getStyleClass().addAll(styleClasses);
        label.setWrapText(false);
        return label;
    }

    /**
     * 让标签在空间不足时可以被压缩并省略显示。
     *
     * <p>JavaFX 的 {@code Label} 默认最小宽度就等于文字宽度，因此一行里的长文本
     * （游戏目录路径、实例名、错误信息）会把同行其它控件挤出边界，
     * 表现为「界面显示不全」。凡是内容可能很长的标签，都用它包一层。</p>
     */
    public static <T extends Label> T shrinkable(T label) {
        label.setMinWidth(0);
        label.setTextOverrun(OverrunStyle.ELLIPSIS);
        return label;
    }

    /** 按 i18n 键创建标签。 */
    public static Label i18n(String key, String... styleClasses) {
        return label(I18n.t(key), styleClasses);
    }

    public static Label title(String text) {
        return label(text, "card-title");
    }

    public static Label bigTitle(String text) {
        return label(text, "page-title");
    }

    public static Label subtitle(String text) {
        return label(text, "page-subtitle");
    }

    public static Label sectionTitle(String text) {
        return label(text, "section-title");
    }

    public static Label muted(String text) {
        return label(text, "muted");
    }

    public static Label mono(String text, String... styleClasses) {
        String[] classes = new String[styleClasses.length + 1];
        classes[0] = "mono";
        System.arraycopy(styleClasses, 0, classes, 1, styleClasses.length);
        return label(text, classes);
    }

    public static HBox hbox(double spacing, Node... children) {
        HBox box = new HBox(spacing, children);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    public static VBox vbox(double spacing, Node... children) {
        VBox box = new VBox(spacing, children);
        return box;
    }

    /** 水平方向的弹性占位：把后续节点推到右侧。 */
    public static Region grow() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /** 垂直方向的弹性占位。 */
    public static Region vgrow() {
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    public static Region divider() {
        Region region = new Region();
        region.getStyleClass().add("divider");
        return region;
    }

    public static Region spacer(double width) {
        Region region = new Region();
        region.setMinWidth(width);
        region.setPrefWidth(width);
        return region;
    }

    /** 玻璃拟态卡片。 */
    public static VBox glassCard(double spacing, Node... children) {
        VBox card = new VBox(spacing, children);
        card.getStyleClass().add("glass-card");
        hoverLift(card, 2);
        return card;
    }

    public static VBox glassCard(Node... children) {
        return glassCard(10, children);
    }

    public static VBox heroCard(double spacing, Node... children) {
        VBox card = new VBox(spacing, children);
        card.getStyleClass().add("hero-card");
        return card;
    }

    /** 带标题与右侧操作区的卡片头部。 */
    public static HBox cardHeader(String titleKey, Node... trailing) {
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().add(i18n(titleKey, "card-title"));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().add(spacer);
        header.getChildren().addAll(trailing);
        return header;
    }

    /**
     * 把页面内容包进可滚动容器，并在宽屏下限制内容宽度。
     * <p>做法：外层 StackPane 拉伸至全宽，内层内容设最大宽度并居中 ——
     * 这样滚动条贴窗口右侧，而内容始终居中。</p>
     */
    public static ScrollPane scrollable(Node content, double maxWidth) {
        ScrollPane scrollPane = new ScrollPane();
        mountScrollable(scrollPane, content, maxWidth);
        return scrollPane;
    }

    public static ScrollPane scrollable(Node content) {
        return scrollable(content, CONTENT_MAX_WIDTH);
    }

    /**
     * 把内容挂载进已有的 ScrollPane。
     * <p>供「视图类直接继承 ScrollPane」的写法使用，避免出现「ScrollPane 里再套 ScrollPane」。</p>
     */
    public static void mountScrollable(ScrollPane pane, Node content, double maxWidth) {
        StackPane centering = new StackPane(content);
        centering.setAlignment(Pos.TOP_CENTER);
        centering.getStyleClass().add("content-host");
        if (content instanceof Region region) {
            region.setMaxWidth(maxWidth);
            // 把内容宽度上限钉在「视口宽度」，并保留最大内容宽度限制。
            // 只靠 StackPane 的 resize 是不够的：窗口尺寸变化后它会把子节点留在旧宽度上
            // （实测内容比视口宽 20~30px），多出来的部分被裁剪，用户看到的就是「控件丢失」。
            // 用 maxWidth 表达上限是硬约束，布局必须遵守。
            centering.widthProperty().addListener((observable, was, now) -> {
                double limit = now.doubleValue() > 0
                        ? Math.min(now.doubleValue(), maxWidth) : maxWidth;
                region.setMaxWidth(limit);
            });
        }
        pane.setContent(centering);
        pane.setFitToWidth(true);
        pane.setFitToHeight(false);
        pane.getStyleClass().add("content-scroll");
        pane.setPadding(new Insets(4, 6, 16, 6));
        // 横向滚动条用 AS_NEEDED 而不是 NEVER：
        // 窗口被拉得很窄时，内容的最小宽度可能大于视口，用 NEVER 会**静默裁掉右侧内容**
        // （按钮、卡片直接看不见且无法访问）。宁可出现一条滚动条，也不要让界面显示不全。
        pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // 竖向滚动条按需出现即可：滚动条出现/消失会改变视口宽度，但上面的
        // maxWidth 约束会在每次视口变化时把内容宽度重新钉住，因此不会再出现
        // 「内容比视口宽一点、右侧被静默裁掉」的情况。
        pane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    }

    /**
     * 玩家头像节点。
     *
     * <p>有真实皮肤就用它；没有（image 为空或解码失败）则用 core 绘制的默认 Steve / Alex。
     * <b>不要退化成「首字母」</b>：那会让「该账户没有皮肤」「头像加载失败」「名字只有一个字」
     * 三种情况长得完全一样，玩家无法判断到底发生了什么。</p>
     *
     * @param skinModel {@code default}（Steve）或 {@code slim}（Alex），见
     *                  {@link com.misaka.launcher.core.auth.PlayerHeads#normalizeModel}
     */
    public static Node playerHead(javafx.scene.image.Image image, String skinModel, double size) {
        javafx.scene.image.Image effective = image;
        if (effective == null || effective.isError()) {
            byte[] png = com.misaka.launcher.core.auth.PlayerHeads
                    .defaultHeadPng(skinModel, (int) Math.max(8, Math.round(size)));
            effective = new javafx.scene.image.Image(new java.io.ByteArrayInputStream(png));
        }
        return avatar(effective, null, size);
    }

    /**
     * 生成一个「圆角矩形头像」节点：优先用图片，取不到时退回首字母。
     *
     * <p><b>始终保持完整方形、用裁切而非压缩比例</b>：源图一律按正方形对待，
     * {@code preserveRatio} 保证等比缩放（非方形源图只会居中留白、绝不拉伸变形），
     * 再用圆角矩形 clip 裁掉四个角 —— 头像因此是「完整方形 → 裁圆角」，而不是被压扁。</p>
     */
    public static Node avatar(javafx.scene.image.Image image, String fallbackText, double size) {
        if (image != null && !image.isError()) {
            javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(image);
            view.setFitWidth(size);
            view.setFitHeight(size);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(size, size);
            // 圆角矩形：圆角半径约为边长的 24%（最小 5px），比正圆保留更多画面
            double corner = Math.max(5, size * 0.24);
            clip.setArcWidth(corner * 2);
            clip.setArcHeight(corner * 2);
            view.setClip(clip);
            StackPane holder = new StackPane(view);
            holder.setMinSize(size, size);
            holder.setPrefSize(size, size);
            holder.setMaxSize(size, size);
            return holder;
        }
        Label label = new Label(fallbackText == null || fallbackText.isBlank()
                ? "?" : fallbackText.substring(0, 1).toUpperCase());
        label.getStyleClass().add("avatar-fallback");
        label.setMinSize(size, size);
        label.setPrefSize(size, size);
        label.setMaxSize(size, size);
        label.setAlignment(Pos.CENTER);
        return label;
    }

    // ==================================================================
    // 尺寸策略
    // ==================================================================

    /**
     * 输入类控件可被压缩到的最小宽度。
     *
     * <p>要的是原生 Windows 应用的行为：窗口变窄时，<b>输入框先被压缩，按钮和标签保持完整</b>。
     * 但 {@code TextField} / {@code ComboBox} 的默认 {@code minWidth} 等于其首选宽度
     * （ComboBox 尤其顽固，几乎不收缩），窗口一窄它们就拒绝让位，
     * 把整行顶出可视区，连同一行的按钮一起被裁掉。</p>
     */
    private static final double CONTROL_MIN_WIDTH = 72;

    /**
     * 让任意输入控件具备「可被压缩」的能力。
     * <p>供不走 {@link #textField} 工厂的控件（如密码框）复用同一套尺寸策略。</p>
     */
    public static <T extends Region> T shrinkable(T control) {
        control.setMinWidth(CONTROL_MIN_WIDTH);
        return control;
    }

    /**
     * 标记为「不可压缩」：按钮在窗口变窄时保持完整可见。
     * <p>主行动按钮被裁掉一半，比输入框变窄严重得多。</p>
     */
    private static <T extends Button> T keepIntact(T button) {
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    // ==================================================================
    // 按钮
    // ==================================================================

    public static Button primaryButton(String key, EventHandler<ActionEvent> handler, Node... graphics) {
        Button button = new Button(I18n.t(key));
        button.getStyleClass().add("primary-button");
        if (graphics.length > 0) {
            button.setGraphic(graphics[0]);
        }
        if (handler != null) {
            button.setOnAction(handler);
        }
        pressFeedback(button);
        keepIntact(button);
        return button;
    }

    public static Button ghostButton(String key, EventHandler<ActionEvent> handler, Node... graphics) {
        Button button = new Button(I18n.t(key));
        button.getStyleClass().add("ghost-button");
        if (graphics.length > 0) {
            button.setGraphic(graphics[0]);
        }
        if (handler != null) {
            button.setOnAction(handler);
        }
        pressFeedback(button);
        keepIntact(button);
        return button;
    }

    /** 直接给定文案（非 i18n 键）的次要按钮。 */
    public static Button ghostButtonText(String text, EventHandler<ActionEvent> handler) {
        Button button = new Button(text);
        button.getStyleClass().add("ghost-button");
        if (handler != null) {
            button.setOnAction(handler);
        }
        pressFeedback(button);
        keepIntact(button);
        return button;
    }

    public static Button dangerButton(String key, EventHandler<ActionEvent> handler) {
        Button button = new Button(I18n.t(key));
        button.getStyleClass().add("danger-button");
        if (handler != null) {
            button.setOnAction(handler);
        }
        pressFeedback(button);
        keepIntact(button);
        return button;
    }

    /**
     * 带主题字体的提示气泡。
     *
     * <p>Tooltip 是独立窗口，<b>不继承</b>主场景根节点上的字体；不显式指定时，
     * 中文提示在缺少 CJK 字体的系统上会显示成方块（主界面正常、提示框里是方的）。</p>
     */
    public static Tooltip tooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setStyle(com.misaka.launcher.ui.theme.Fonts.rootStyleFragment());
        return tooltip;
    }

    /** 仅图标的按钮（标题栏、卡片右上角操作）。 */
    public static Button iconButton(String pathData, double size, String tooltipKey,
                                    EventHandler<ActionEvent> handler) {
        Button button = new Button();
        button.setGraphic(Icons.create(pathData, size));
        button.getStyleClass().add("icon-button");
        if (tooltipKey != null) {
            button.setTooltip(tooltip(I18n.t(tooltipKey)));
        }
        if (handler != null) {
            button.setOnAction(handler);
        }
        keepIntact(button);
        return button;
    }

    /** 胶囊型选择按钮（用于分段筛选）。 */
    public static Button pill(String text, boolean selected, EventHandler<ActionEvent> handler) {
        Button button = new Button(text);
        button.getStyleClass().add("pill-button");
        if (selected) {
            button.getStyleClass().add("selected");
        }
        if (handler != null) {
            button.setOnAction(handler);
        }
        pressFeedback(button);
        keepIntact(button);
        return button;
    }

    public static Label badge(String text, String kind) {
        Label label = new Label(text);
        label.getStyleClass().add("badge");
        if (kind != null && !kind.isBlank()) {
            label.getStyleClass().add(kind);
        }
        return label;
    }

    public static Label callout(String text, boolean warning) {
        Label label = label(text, warning ? "warn-callout" : "info-callout");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    // ==================================================================
    // 输入
    // ==================================================================

    public static TextField textField(String promptKey, String value) {
        TextField field = new TextField(value == null ? "" : value);
        if (promptKey != null) {
            field.setPromptText(I18n.t(promptKey));
        }
        field.setPrefHeight(34);
        // 可压缩：窗口变窄时输入框让位，而不是把整行顶出可视区
        field.setMinWidth(CONTROL_MIN_WIDTH);
        return field;
    }

    /**
     * 与 {@link #textField(String, String)} 同规格的密码输入框。
     *
     * <p>用 {@code PasswordField} 而不是普通输入框：口令一旦以明文显示在设置页，
     * 截图、录屏、旁边的人都会顺手看到。同规格指的是高度与可压缩性完全一致，
     * 免得密码行比其它行矮一截。</p>
     */
    public static PasswordField passwordField(String promptKey, String value) {
        PasswordField field = new PasswordField();
        field.setText(value == null ? "" : value);
        if (promptKey != null) {
            field.setPromptText(I18n.t(promptKey));
        }
        field.setPrefHeight(34);
        field.setMinWidth(CONTROL_MIN_WIDTH);
        return field;
    }

    /**
     * 让输入框在「回车」与「失焦」两个时机都提交，且值没变就不提交。
     *
     * <p>只挂 {@code setOnAction} 是同一个坑：它<b>只在回车时</b>触发。用户更常做的是
     * 填完直接点旁边的按钮或切页 —— 于是值从未写进配置，而界面上还显示着新值，
     * 现场就长成「我明明把地址改了，它还是连旧地址」（真实发生过，排查了半天）。</p>
     *
     * @param field  输入框
     * @param stored 当前已生效的值；用它判断是否真的改了，避免每次失焦都存盘 + 弹「已保存」
     * @param apply  值变化后执行：写进配置并持久化
     * @return 提交动作本身 —— 调用方可以在「点了按钮但输入框没失焦」的场合手动再跑一次
     */
    public static Runnable commitOnEnterOrBlur(TextField field, Supplier<String> stored,
                                               Consumer<String> apply) {
        Runnable commit = () -> {
            String value = field.getText() == null ? "" : field.getText().trim();
            String current = stored.get() == null ? "" : stored.get().trim();
            if (value.equals(current)) {
                return;
            }
            apply.accept(value);
        };
        field.setOnAction(event -> commit.run());
        field.focusedProperty().addListener((observable, was, focused) -> {
            if (!focused) {
                commit.run();
            }
        });
        return commit;
    }

    public static TextField searchField(String promptKey, Consumer<String> onTextChanged) {
        TextField field = new TextField();
        if (promptKey != null) {
            field.setPromptText(I18n.t(promptKey));
        }
        field.getStyleClass().add("search-field");
        field.setMinWidth(CONTROL_MIN_WIDTH);
        if (onTextChanged != null) {
            field.textProperty().addListener((observable, oldValue, newValue) -> onTextChanged.accept(newValue));
        }
        return field;
    }

    public static <T> ComboBox<T> comboBox(List<T> items, T selected, Consumer<T> onChange) {
        ComboBox<T> box = new ComboBox<>();
        box.getItems().setAll(items);
        if (selected != null) {
            box.getSelectionModel().select(selected);
        } else if (!items.isEmpty()) {
            box.getSelectionModel().selectFirst();
        }
        box.setPrefHeight(32);
        box.setMaxWidth(Double.MAX_VALUE);
        // ComboBox 默认 minWidth == prefWidth（按最宽的选项算），几乎不收缩；
        // 不放开的话，窄窗口下它会带着整行一起溢出
        box.setMinWidth(CONTROL_MIN_WIDTH);
        if (onChange != null) {
            box.getSelectionModel().selectedItemProperty()
                    .addListener((observable, oldValue, newValue) -> onChange.accept(newValue));
        }
        styleComboPopup(box);
        return box;
    }

    /**
     * 下载源下拉框的显示文案：值（{@code auto} / {@code bmclapi} …）映射到
     * {@code install.source.<值>}。
     *
     * <p>下拉框默认直接显示 {@code String.valueOf(value)}，不处理的话用户看到的是
     * {@code auto}、{@code bmclapi} 这种内部标识；而且「自动」到底优先哪个源
     * 也只能靠文案说清，所以在文案里就把「官方优先、镜像兜底」写明。</p>
     */
    public static void localizeSourceCombo(ComboBox<String> box) {
        box.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String value) {
                return value == null || value.isBlank() ? "" : I18n.t("install.source." + value);
            }

            @Override
            public String fromString(String text) {
                return text;
            }
        });
    }

    /**
     * 给下拉框的<b>弹出列表</b>配字体。
     *
     * <p>下拉列表是独立 Popup，控制本身的 {@code -fx-font-family} 传不进去，只能逐个单元格设。
     * 不处理的话，语言下拉里的「简体中文」、分类下拉里的中文都会显示成方块，
     * 而收起状态（button cell）却是正常的 —— 这种「一半正常一半方」最难被当成字体问题。</p>
     *
     * <p>单元格渲染沿用控制自己的 {@code converter}：语言下拉显示的是「简体中文」而不是
     * {@code zh_CN}，靠的就是它。已经自定义过 cellFactory 的下拉框会<b>包一层</b>而不是替换。</p>
     */
    public static <T> void styleComboPopup(ComboBox<T> box) {
        String fontStyle = com.misaka.launcher.ui.theme.Fonts.rootStyleFragment();
        if (fontStyle.isBlank()) {
            return;
        }
        javafx.util.Callback<javafx.scene.control.ListView<T>, javafx.scene.control.ListCell<T>> existing =
                box.getCellFactory();
        box.setCellFactory(list -> {
            javafx.scene.control.ListCell<T> cell = existing == null ? null : existing.call(list);
            if (cell == null) {
                cell = new javafx.scene.control.ListCell<>() {
                    @Override
                    protected void updateItem(T item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty || item == null) {
                            setText(null);
                            return;
                        }
                        javafx.util.StringConverter<T> converter = box.getConverter();
                        setText(converter == null ? String.valueOf(item) : converter.toString(item));
                    }
                };
            }
            cell.setStyle(fontStyle);
            return cell;
        });
        // 收起状态显示的那一格同样要设：否则选中项是方块、展开才正常
        javafx.scene.control.ListCell<T> button = new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                javafx.util.StringConverter<T> converter = box.getConverter();
                setText(converter == null ? String.valueOf(item) : converter.toString(item));
            }
        };
        button.setStyle(fontStyle);
        box.setButtonCell(button);
    }

    public static CheckBox checkBox(String key, boolean selected, Consumer<Boolean> onChange) {
        CheckBox box = new CheckBox(I18n.t(key));
        box.setSelected(selected);
        if (onChange != null) {
            box.selectedProperty().addListener((observable, oldValue, newValue) -> onChange.accept(newValue));
        }
        return box;
    }

    /**
     * 裸数值滑杆（不带数值显示）。
     * <p>回调使用 {@link DoubleConsumer}（primitive double）而不是 {@code Consumer<Double>}：
     * 后者在调用方写 {@code (int) value} 时会因「拆箱 + 窄化」不被允许而编译失败，
     * 用原始类型可以让调用方自然地取整。</p>
     *
     * <p><b>设置项请用 {@link #sliderField}</b>：它额外带一个可编辑的数值框，
     * 数值框才是最终生效值并负责合法性校验。裸滑杆只在确实不需要精确数值时使用。</p>
     */
    public static Slider slider(double min, double max, double value, DoubleConsumer onChange) {
        Slider slider = new Slider(min, max, value);
        slider.setShowTickLabels(false);
        slider.setShowTickMarks(false);
        slider.setBlockIncrement(1);
        if (onChange != null) {
            slider.valueProperty().addListener((observable, oldValue, newValue) ->
                    onChange.accept(newValue.doubleValue()));
        }
        return slider;
    }

    /**
     * 「拖动条 + 可编辑数值」组合控件。
     *
     * <p><b>数值框是最终生效值，而不是只做展示</b>：拖动条的步长与端点未必能精确落到
     * 用户想要的数（例如 0~102400 这种大范围），因此应用时取数值框里的值，
     * 拖动条同步到该数值对应的位置。这也意味着数值框必须承担合法性校验。</p>
     *
     * <p>校验规则（{@link SliderField#commit()}）：</p>
     * <ul>
     *   <li>非数字（含空串）→ 回显滑块当前值，<b>不应用</b>；</li>
     *   <li>超出 {@code [min, max]} → 夹取到边界并回显，让用户立刻看到实际生效值；</li>
     *   <li>合法 → 滑块同步到该位置，并回调 {@code onChange}。</li>
     * </ul>
     *
     * <p>提交时机：回车，或焦点离开输入框（两者都会立即给出校正后的回显）。</p>
     */
    public static SliderField sliderField(double min, double max, double value, DoubleConsumer onChange) {
        return new SliderField(min, max, value, onChange);
    }

    /** {@link #sliderField} 返回的组合控件句柄；用 {@link #node()} 取节点放入布局。 */
    public static final class SliderField {

        private final Slider slider;
        private final TextField field;
        private final HBox root;
        private final double min;
        private final double max;
        private final DoubleConsumer onChange;
        /** 防重入：滑块与数值框的双向同步不能互相触发。 */
        private boolean syncing;

        private SliderField(double min, double max, double value, DoubleConsumer onChange) {
            this.min = min;
            this.max = max;
            this.onChange = onChange;

            // 必须是 int：如果这里是 double，String.valueOf 会把初始值显示成 "8.0"
            int initial = clamp(value);
            slider = new Slider(min, max, initial);
            slider.setShowTickLabels(false);
            slider.setShowTickMarks(false);
            slider.setBlockIncrement(1);
            slider.setMaxWidth(Double.MAX_VALUE);
            slider.setPrefWidth(240);
            HBox.setHgrow(slider, Priority.ALWAYS);

            field = new TextField(String.valueOf(initial));
            field.setPrefWidth(84);
            shrinkable(field);
            field.setAlignment(Pos.CENTER_RIGHT);

            root = new HBox(10, slider, field);
            root.setAlignment(Pos.CENTER_LEFT);
            root.setMaxWidth(Double.MAX_VALUE);

            slider.valueProperty().addListener((observable, was, now) -> {
                if (syncing) {
                    return;
                }
                int current = (int) Math.round(now.doubleValue());
                field.setText(String.valueOf(current));
                if (onChange != null) {
                    onChange.accept(current);
                }
            });

            field.setOnAction(event -> commit());
            field.focusedProperty().addListener((observable, was, focused) -> {
                if (!focused) {
                    commit();
                }
            });
        }

        /** 提交数值框：校验 → 夹取 → 同步滑块 → 应用。 */
        private void commit() {
            if (syncing) {
                return;
            }
            String text = field.getText() == null ? "" : field.getText().trim();
            Integer parsed = null;
            if (!text.isEmpty()) {
                try {
                    parsed = Integer.valueOf(text);
                } catch (NumberFormatException ignored) {
                    // 非数字：下面回显当前值，不应用
                }
            }
            if (parsed == null) {
                field.setText(String.valueOf(current()));
                return;
            }
            int clamped = clamp(parsed);
            // 越界或格式不规整（例如 "007"）时回显规范化后的值
            if (!text.equals(String.valueOf(clamped))) {
                field.setText(String.valueOf(clamped));
            }
            syncing = true;
            try {
                slider.setValue(clamped);
            } finally {
                syncing = false;
            }
            // 数值框是最终生效值：即使滑块位置未变（例如夹取回原值）也要确保应用
            if (onChange != null) {
                onChange.accept(clamped);
            }
        }

        private int clamp(double value) {
            if (Double.isNaN(value)) {
                return (int) Math.round(min);
            }
            return (int) Math.round(Math.min(max, Math.max(min, value)));
        }

        /** 当前生效值（整数）。 */
        public int current() {
            return (int) Math.round(slider.getValue());
        }

        public Region node() {
            return root;
        }

        public Slider slider() {
            return slider;
        }

        public TextField field() {
            return field;
        }

        /**
         * 以编程方式设置值（滑块与数值框同步）。
         * <p>刻意<b>不</b>触发 {@code onChange}：初始化与切换实例时只是回显已有配置，
         * 不该产生「用户改了设置」的副作用（否则会凭空写一次配置）。</p>
         */
        public void setValue(double value) {
            syncing = true;
            try {
                int clamped = clamp(value);
                slider.setValue(clamped);
                field.setText(String.valueOf(clamped));
            } finally {
                syncing = false;
            }
        }
    }

    /**
     * 「标签 + 控件」的一行设置项（横向）。
     * <p>控件参数类型取 {@link Region} 而非 {@code Node}：需要设置最大宽度与水平伸缩，
     * 这两者只有 Region 才具备。</p>
     * <p><b>适合宽度充裕的卡片</b>（设置页）。窄卡片请用 {@link #settingRowStacked}：
     * 这里的标签列固定占 132px，卡片一窄就会把控件压到几十像素宽。</p>
     */
    public static HBox settingRow(String labelKey, Region control, String hint) {
        Label label = i18n(labelKey, "muted");
        label.setMinWidth(132);
        label.setPrefWidth(132);
        control.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(control, Priority.ALWAYS);
        HBox row = new HBox(12, label, control);
        row.setAlignment(Pos.CENTER_LEFT);
        if (hint != null && !hint.isBlank()) {
            Tooltip.install(row, tooltip(hint));
        }
        return row;
    }

    /**
     * 「标签在上、控件在下」的设置项（竖向）。
     *
     * <p>用于并排的窄卡片，例如账户页的三张卡片：横向布局里标签占固定宽度，
     * 卡片被压到 170px 时输入框只剩 20~30px 宽，用户根本无法输入。
     * 改成竖向堆叠后，控件始终占满卡片可用宽度。</p>
     */
    public static VBox settingRowStacked(String labelKey, Region control, String hint) {
        Label label = i18n(labelKey, "muted");
        control.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(6, label, control);
        box.setFillWidth(true);
        if (hint != null && !hint.isBlank()) {
            Label hintLabel = label(hint, "mute-2");
            hintLabel.setWrapText(true);
            box.getChildren().add(hintLabel);
        }
        return box;
    }

    // ==================================================================
    // 微动效
    // ==================================================================

    /** 悬浮时轻微上浮，松开还原。 */
    public static void hoverLift(Region node, double distance) {
        TranslateTransition up = new TranslateTransition(HOVER_IN, node);
        up.setToY(-distance);
        up.setInterpolator(Interpolator.EASE_OUT);
        TranslateTransition down = new TranslateTransition(HOVER_OUT, node);
        down.setToY(0);
        down.setInterpolator(Interpolator.EASE_OUT);
        node.setOnMouseEntered(event -> {
            down.stop();
            up.playFromStart();
        });
        node.setOnMouseExited(event -> {
            up.stop();
            down.playFromStart();
        });
    }

    /** 按压时的轻微缩放反馈。 */
    public static void pressFeedback(Node node) {
        TypeSafe<ScaleTransition> holder = new TypeSafe<>();
        node.setOnMousePressed(event -> {
            ScaleTransition scale = new ScaleTransition(Duration.millis(90), node);
            scale.setToX(0.97);
            scale.setToY(0.97);
            holder.value = scale;
            scale.playFromStart();
        });
        node.setOnMouseReleased(event -> {
            if (holder.value != null) {
                ScaleTransition back = new ScaleTransition(Duration.millis(120), node);
                back.setToX(1.0);
                back.setToY(1.0);
                back.playFromStart();
            }
        });
    }

    /** 用于在 lambda 中持有可变引用的极小容器（避免为一次性场景定义字段）。 */
    private static final class TypeSafe<T> {
        private T value;
    }

    // ==================================================================
    // 格式化
    // ==================================================================

    /** 绝对时间。 */
    public static String formatDateTime(long timestampMillis) {
        return timestampMillis <= 0 ? "—" : DATE_TIME.format(Instant.ofEpochMilli(timestampMillis));
    }

    /** 相对时间，例如「3 分钟前」。 */
    public static String formatRelative(long timestampMillis) {
        if (timestampMillis <= 0) {
            return I18n.t("home.stats.never");
        }
        long delta = System.currentTimeMillis() - timestampMillis;
        if (delta < 0) {
            delta = 0;
        }
        long minutes = delta / 60_000L;
        if (minutes < 1) {
            return "刚刚";
        }
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " 小时前";
        }
        long days = hours / 24;
        if (days < 30) {
            return days + " 天前";
        }
        return formatDateTime(timestampMillis);
    }

    /** 时长，例如「2 小时 13 分」。 */
    public static String formatDuration(long seconds) {
        if (seconds <= 0) {
            return "—";
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        if (hours > 0) {
            return hours + " 小时 " + minutes + " 分";
        }
        if (minutes > 0) {
            return minutes + " 分钟";
        }
        return seconds + " 秒";
    }

    /** 大数值缩写，例如 23.7万。 */
    public static String formatCount(long value) {
        if (value < 10_000) {
            return String.valueOf(value);
        }
        if (value < 100_000_000) {
            return String.format("%.1f万", value / 10_000.0);
        }
        return String.format("%.2f亿", value / 100_000_000.0);
    }

    public static String humanSize(long bytes) {
        return com.misaka.launcher.core.util.FileUtils.humanSize(bytes);
    }
}
