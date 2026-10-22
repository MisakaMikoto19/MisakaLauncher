package com.misaka.launcher.ui.shell;

import com.misaka.launcher.app.LaunchPipeline;
import com.misaka.launcher.app.LauncherContext;
import com.misaka.launcher.app.LauncherServices;
import com.misaka.launcher.app.update.LauncherUpdater;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.util.OsUtils;
import com.misaka.launcher.ui.component.Toast;
import com.misaka.launcher.ui.theme.BackgroundManager;
import com.misaka.launcher.ui.theme.ThemeManager;
import com.misaka.launcher.ui.view.AccountView;
import com.misaka.launcher.ui.view.DownloadView;
import com.misaka.launcher.ui.view.HomeView;
import com.misaka.launcher.ui.view.InstanceView;
import com.misaka.launcher.ui.view.LogView;
import com.misaka.launcher.ui.view.ModsView;
import com.misaka.launcher.ui.view.ResourcePacksView;
import com.misaka.launcher.ui.view.SettingsView;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.SetChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.geometry.Pos;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 主窗口：把背景层、外壳（标题栏 / 导航 / 内容 / 状态栏）、提示层组装起来。
 *
 * <p>结构上刻意保持三层分离：</p>
 * <ol>
 *   <li><b>背景层</b>：不参与交互（{@code mouseTransparent}），可独立调节；</li>
 *   <li><b>内容层</b>：外壳与页面，全部位于玻璃拟态容器内；</li>
 *   <li><b>提示层</b>：轻提示永远位于最上，不被内容遮挡。</li>
 * </ol>
 *
 * <p>窗口使用无边框 + 透明样式以获得圆角与投影；在部分 Linux 桌面环境下
 * 透明窗口表现不佳，此时退化为不透明方角窗口，避免出现黑边或无法拖动。</p>
 */
public final class MainWindow implements AppShell {

    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);

    /** 窗口圆角半径，需与 base.css 中 .window-frame 的圆角保持一致。 */
    private static final double CORNER_RADIUS = 14;
    /**
     * 四周留给投影的边距。
     * <p>这段区域必须是<b>真正透明</b>的：投影要能落到桌面上，圆角处要能露出桌面。
     * 因此背景层必须画在圆角框架「内部」，而不是铺满整个场景。</p>
     */
    private static final double SHADOW_MARGIN = 12;
    /** 外壳内边距：标题栏 / 导航栏 / 内容区 / 状态栏共用同一条基线，避免各边对不齐。 */
    private static final double GUTTER = 12;
    /** 期望的最小窗口尺寸；实际值还会按屏幕可视区域再收敛一次。 */
    private static final double PREF_MIN_WIDTH = 1000;
    private static final double PREF_MIN_HEIGHT = 640;
    /** 初始尺寸占屏幕可视区域的上限比例，保证小屏（含高缩放）也能完整放下。 */
    private static final double SCREEN_FILL = 0.94;
    /** 拖动判定阈值（像素）：小于该位移视为点击，避免抖动吃掉双击最大化。 */
    private static final double DRAG_THRESHOLD = 4;
    /** 移动 / 缩放窗口时必须保留在屏幕内的标题栏可见长度，保证把手永远抓得到。 */
    private static final double TITLE_BAR_KEEP = 60;

    /** 页面顺序：同时决定「上次停留页」的白名单与 Ctrl+1..N 快捷键，须与侧边栏顺序一致。 */
    private static final List<String> PAGE_ORDER =
            List.of("home", "instances", "download", "mods", "accounts", "settings", "logs");

    /** 上次输出布局诊断时的窗口宽度（避免拖动时刷屏）。 */
    private double lastDiagnosedWidth = -1;
    /** 上次做全树布局测量的视口宽度（测量不便宜，宽度变化不足 8px 就跳过）。 */
    private double lastMeasuredWidth = -1;
    /** 上一次的溢出签名与连续出现次数（用于过滤布局中间态的误报）。 */
    private String lastOverflowSignature = "";
    private int overflowStrikes;

    private final Stage stage;
    private final LauncherContext context;
    private final LauncherServices services;

    private final StackPane root = new StackPane();
    private final StackPane backgroundLayer = new StackPane();
    /** 并行任务面板的悬浮层：面板从启动栏向上展开，盖在内容之上、提示之下。 */
    private final StackPane taskLayer = new StackPane();
    private final StackPane toastLayer = new StackPane();
    private final StackPane contentHost = new StackPane();
    private final BorderPane shell = new BorderPane();
    /** 圆角框架：承载投影与描边（不能裁剪，否则投影会被一并剪掉）。 */
    private final StackPane frame = new StackPane();
    /** 被圆角裁剪的内容容器：背景层 / 外壳 / 提示层都放在里面。 */
    private final StackPane frameClip = new StackPane();
    /** 圆角裁剪形状，尺寸跟随内容容器。 */
    private final Rectangle frameClipShape = new Rectangle();

    private final boolean transparentWindow;
    private double minWidth;
    private double minHeight;
    /** 是否已判定为一次真实拖动（用于抑制拖动结束后的点击行为）。 */
    private boolean dragging;

    private final ThemeManager themeManager;
    private final BackgroundManager backgroundManager;
    private final Toast toast;
    private final TaskCenter taskCenter = new TaskCenter();
    private final ViewRouter router;

    private final TitleBar titleBar;
    private final Sidebar sidebar;
    private final LaunchBar launchBar;

    private Runnable launchHandler;
    private Runnable stopHandler;
    private LaunchPipeline launchPipeline;
    /** 自更新流程；懒创建 —— 它要引用本窗口，字段初始化阶段构造会拿到未初始化的成员。 */
    private LauncherUpdater updater;

    // 窗口缩放状态
    private String resizeEdge = "";
    private double pressScreenX;
    private double pressScreenY;
    private double pressStageX;
    private double pressStageY;
    private double pressWidth;
    private double pressHeight;

    // 窗口拖动状态
    private double dragOffsetX;
    private double dragOffsetY;

    public MainWindow(Stage stage, LauncherContext context) {
        this.stage = stage;
        this.context = context;
        this.services = LauncherServices.create(context);

        LauncherConfig config = context.config();
        this.transparentWindow = supportsTransparentWindow();

        // ---- 尺寸：按屏幕可视区域推导 ----
        // 无边框窗口一旦超出屏幕，用户既拖不回来也缩不回去，因此初始尺寸与最小尺寸
        // 都必须相对屏幕收敛；小屏（含 150% 缩放）机器上尤其明显。
        Rectangle2D visual = Screen.getPrimary().getVisualBounds();
        this.minWidth = Math.min(PREF_MIN_WIDTH, Math.max(720, visual.getWidth() * 0.7));
        this.minHeight = Math.min(PREF_MIN_HEIGHT, Math.max(520, visual.getHeight() * 0.7));
        double initialWidth = clamp(config.ui.windowWidth, minWidth, visual.getWidth() * SCREEN_FILL);
        double initialHeight = clamp(config.ui.windowHeight, minHeight, visual.getHeight() * SCREEN_FILL);

        Scene scene = new Scene(root, initialWidth, initialHeight);
        // 透明窗口必须显式把场景填充设为透明。否则留给投影的那圈边距会露出 Scene 默认的
        // 白色填充，窗口四周出现一圈白边 —— 这是无边框窗口最常见的踩坑。
        scene.setFill(transparentWindow ? Color.TRANSPARENT : Color.web("#0B0E14"));
        stage.setScene(scene);
        stage.setTitle(I18n.t("app.name"));
        stage.setMinWidth(minWidth);
        stage.setMinHeight(minHeight);
        stage.initStyle(transparentWindow ? StageStyle.TRANSPARENT : StageStyle.UNDECORATED);

        this.themeManager = new ThemeManager(scene);
        this.toast = new Toast(toastLayer);
        this.router = new ViewRouter(contentHost);

        // ---- 组装层次 ----
        // root（透明填充）
        //   └ frame         投影 + 描边（不裁剪，投影才能落到桌面上）
        //       └ frameClip 圆角裁剪
        //           ├ backgroundLayer  背景画在圆角内部，而不是铺满整个场景
        //           ├ shell            外壳：标题栏 / 导航 / 内容 / 状态栏
        //           └ toastLayer       提示层也在圆角内部，避免溢出到桌面
        toastLayer.setPickOnBounds(false);
        toastLayer.setMouseTransparent(false);

        shell.getStyleClass().add("window-shell");
        if (!transparentWindow) {
            // 无透明支持时（部分 Linux 桌面）退化为不透明直角窗口，避免黑边与无法交互
            shell.setStyle("-fx-background-color: -c-bg;");
        }
        shell.setPadding(new Insets(GUTTER));

        frame.getStyleClass().add("window-frame");
        frameClipShape.widthProperty().bind(frameClip.widthProperty());
        frameClipShape.heightProperty().bind(frameClip.heightProperty());
        frameClip.setClip(frameClipShape);
        frameClip.getChildren().addAll(backgroundLayer, shell, taskLayer, toastLayer);
        frame.getChildren().add(frameClip);
        StackPane.setMargin(frame, new Insets(transparentWindow ? SHADOW_MARGIN : 0));
        root.getChildren().add(frame);

        this.titleBar = new TitleBar(this, stage);
        this.sidebar = new Sidebar(router, this);
        this.launchBar = new LaunchBar(this);

        // 并行任务面板挂在独立的悬浮层（内容之上、提示之下）：
        // 它要从启动栏「向上展开」，因此锚定底部并留出启动栏自身的高度
        Region taskPanel = launchBar.taskPanel();
        StackPane.setAlignment(taskPanel, Pos.BOTTOM_LEFT);
        StackPane.setMargin(taskPanel, new Insets(0, 12, 72, 12));
        taskLayer.getChildren().add(taskPanel);
        taskLayer.setPickOnBounds(false);

        shell.setTop(titleBar);
        shell.setLeft(sidebar);
        shell.setCenter(contentHost);
        shell.setBottom(launchBar);
        // 外壳已带统一内边距，这里只负责「侧边栏↔内容区」「内容区↔启动栏」的间距
        BorderPane.setMargin(sidebar, new Insets(0, GUTTER, 0, 0));
        BorderPane.setMargin(contentHost, new Insets(0, 0, GUTTER, 0));

        // ---- 布局自检 ----
        // 内容一旦宽于视口就会被 frameClip 的圆角裁剪切掉（用户看到的「控件丢失」），
        // 因此在这里主动发现并报告，而不是等用户反馈。
        // 全树测量不便宜，所以只在宽度明显变化时才做，避免拖动时逐帧卡顿。
        contentHost.widthProperty().addListener((observable, was, now) -> {
            if (Math.abs(now.doubleValue() - lastMeasuredWidth) < 8) {
                return;
            }
            lastMeasuredWidth = now.doubleValue();
            // 两层 runLater：本帧的 runLater 在布局**之前**执行，那时量到的还是旧尺寸，
            // 会把完全正常的状态误报成溢出（踩过这个坑）
            Platform.runLater(() -> Platform.runLater(this::verifyLayoutFits));
        });
        router.addListener(() -> Platform.runLater(this::verifyLayoutFits));

        // ---- 背景与主题 ----
        this.backgroundManager = new BackgroundManager(backgroundLayer, context.paths(), config.background);
        backgroundManager.apply();
        themeManager.initialize();
        // 整窗外壳遮罩的强度与「覆盖层透明度」同步：必须在 initialize() 之后 ——
        // 主题初始化会重写根节点的内联样式，那会把这里设的值冲掉
        themeManager.applyShellVeil(backgroundManager.shellVeilFactor());

        // ---- 页面 ----
        router.register("home", () -> new HomeView(this));
        router.register("instances", () -> new InstanceView(this));
        router.register("download", () -> new DownloadView(this));
        router.register("mods", () -> new ModsView(this));
        router.register("packs", () -> new ResourcePacksView(this));
        router.register("accounts", () -> new AccountView(this));
        router.register("settings", () -> new SettingsView(this));
        router.register("logs", () -> new LogView(this));

        // ---- 实例忙碌联动 ----
        // 「哪些实例正被后台任务改动」一变，启动栏与主页的启动入口就要重算可用性。
        // 监听只挂在这里：外壳是长生命周期对象，而页面（主页）会被反复重建 ——
        // 让页面各自去监听这个共享集合，会随重建不断累积监听器，最后变成内存泄漏。
        taskCenter.busyInstanceIds().addListener((SetChangeListener<String>) change -> {
            launchBar.refresh();
            if (router.currentNode() instanceof HomeView home) {
                home.refresh();
            }
        });

        // 替代 Alt+F4 / 任务栏关闭：替换自身文件的那几秒不能被中断，
        // 否则原文件已被改名、新文件还没写完 —— 用户手里就没有可用的启动器了。
        // 下载阶段随便关（.part 会留着下次续传），只有替换阶段需要拦
        stage.setOnCloseRequest(event -> {
            if (taskCenter.selfUpdatePhase() == TaskCenter.SelfUpdatePhase.INSTALLING) {
                event.consume();
                toast.show(I18n.t("update.gate.closing"), Toast.Kind.WARN);
            }
        });

        // ---- 自更新联动 ----
        // 自更新一开始，各页面的写盘 / 启动入口都要置灰。页面的可用性是在构建那一刻定下的，
        // 所以状态一变就把页面缓存清掉重建；停止更新或更新失败时同样走这里，把入口重新放开。
        taskCenter.selfUpdatePhaseProperty().addListener(
                (observable, was, now) -> onSelfUpdatePhaseChanged(was, now));

        installWindowInteractions(scene);
        // 关键：必须显式接线，否则自绘标题栏不会产生任何拖动行为，
        // 无边框窗口将完全无法移动
        installDrag(titleBar);
        installShortcuts(scene);
        applyMaximizeState(stage.isMaximized());
        stage.maximizedProperty().addListener((observable, was, now) -> applyMaximizeState(now));
        installResizeLogging();
        restoreWindowState();
    }

    /** 展示窗口并进入首页。 */
    public void show() {
        // 启动流水线在此注入：状态栏的主按钮与各页面的「启动」都会走它
        LaunchPipeline pipeline = new LaunchPipeline(this);
        setLaunchHandler(pipeline::launch);
        setStopHandler(pipeline::stop);
        this.launchPipeline = pipeline;

        stage.show();

        if (context.config().ui.maximized) {
            stage.setMaximized(true);
        } else {
            // 不持久化窗口位置，直接居中；多屏 / 虚拟显示环境下平台给出的默认位置
            // 可能落在屏幕外，因此这里再做一次越界校正
            stage.centerOnScreen();
            ensureOnScreen();
        }

        // 启动固定落在主页。
        //
        // 以前会恢复 ui.lastPage，结果是「上次在账户页 → 下次开屏高亮停在侧边栏的账户卡片上」，
        // 第一眼看到的不是「现在该做什么」，而是「上次停在哪」。开屏页应当是确定性的。
        router.show("home");
        refreshShell();
        logWindowGeometry();
        log.info("主窗口已就绪");

        // 界面已经可见，才去后台查新版本：检查是独立守护线程，慢或失败都不影响使用，
        // 有新版本才会弹对话框（见 LauncherUpdater#checkOnStartup）
        updates().checkOnStartup();
    }

    /**
     * 保证窗口完整落在屏幕可视区域内。
     * <p>多显示器、远程桌面与虚拟显示适配器并存的机器上，平台给出默认位置经常越界；
     * 而无边框窗口一旦越界，用户既看不到内容也无法把它拖回来。</p>
     */
    private void ensureOnScreen() {
        Rectangle2D visual = Screen.getPrimary().getVisualBounds();
        double width = stage.getWidth();
        double height = stage.getHeight();
        boolean outside = stage.getX() < visual.getMinX() - 1
                || stage.getY() < visual.getMinY() - 1
                || stage.getX() + width > visual.getMaxX() + 1
                || stage.getY() + height > visual.getMaxY() + 1;
        if (!outside) {
            return;
        }
        double targetWidth = Math.min(width, visual.getWidth());
        double targetHeight = Math.min(height, visual.getHeight());
        stage.setWidth(targetWidth);
        stage.setHeight(targetHeight);
        stage.setX(visual.getMinX() + (visual.getWidth() - targetWidth) / 2);
        stage.setY(visual.getMinY() + (visual.getHeight() - targetHeight) / 2);
        log.info("窗口越出屏幕可视区域，已按 {}x{} 重新居中", Math.round(targetWidth),
                Math.round(targetHeight));
    }

    /**
     * 布局自检：页面内容比视口宽时，多出来的控件会被圆角裁剪**直接切掉**，
     * 用户看到的就是「拖小窗口后控件丢失」。
     *
     * <p>这里在发生溢出时把「最小宽度最大的那个节点」写进日志 ——
     * 免得到时候还得靠肉眼一个个猜是谁把布局顶宽的。只在溢出且窗口宽度明显变化时输出，
     * 避免拖动过程中刷屏。</p>
     */
    private void verifyLayoutFits() {
        Node page = router.currentNode();
        if (page == null || contentHost.getWidth() <= 1) {
            return;
        }
        // 拖动过程中宽度连续变化，只在宽度明显改变时输出一次
        if (Math.abs(stage.getWidth() - lastDiagnosedWidth) < 8) {
            return;
        }

        // 1) 外壳整体：外壳宽于可用区域时，BorderPane 会把各区域压到最小宽度，
        //    整个界面被 StackPane 居中后两侧同时丢内容
        double available = frameClip.getWidth();
        double shellWidth = shell.getWidth();
        if (available > 1 && shellWidth > available + 1) {
            lastDiagnosedWidth = stage.getWidth();
            log.warn("外壳宽于可用区域（会被裁剪）：窗口 {}x{}，可用 {}px，外壳实际 {}px / 最小 {}px，最宽节点 = {}",
                    Math.round(stage.getWidth()), Math.round(stage.getHeight()),
                    Math.round(available), Math.round(shellWidth),
                    Math.round(shell.minWidth(-1)), widestNode(shell));
            return;
        }

        // 2) 子节点被挤出父容器可视区。这才是「控件丢失」的直接判据：
        //    容器的 minWidth 往往仍然很小（滚动条不会出现），但实际排布已经溢出了。
        String overflow = findOverflow(page);
        if (overflow.isEmpty()) {
            lastOverflowSignature = "";
            overflowStrikes = 0;
            return;
        }
        // 布局中间态会短暂出现「内容比视口宽」：连续两次都测到同一种溢出才算真问题，
        // 否则会把正常状态误报成溢出（踩过这个坑）。
        if (overflow.equals(lastOverflowSignature)) {
            overflowStrikes++;
        } else {
            lastOverflowSignature = overflow;
            overflowStrikes = 1;
        }
        if (overflowStrikes < 2) {
            return;
        }
        lastDiagnosedWidth = stage.getWidth();
        log.warn("有控件被挤出可视区（会被裁剪）：窗口 {}x{}, 视口 {}px{}",
                Math.round(stage.getWidth()), Math.round(stage.getHeight()),
                Math.round(contentHost.getWidth()), overflow);
        if (page instanceof javafx.scene.control.ScrollPane scrollPane
                && scrollPane.getContent() instanceof javafx.scene.layout.Region content) {
            log.warn("滚动容器状态：viewport={} fitToWidth={} hbarVisible={} vbarVisible={} | 内容 w={} pref={} min={} max={} | stack w={}",
                    scrollPane.getViewportBounds(),
                    scrollPane.isFitToWidth(),
                    scrollPane.lookup(".scroll-bar:horizontal") != null
                            && scrollPane.lookup(".scroll-bar:horizontal").isVisible(),
                    scrollPane.lookup(".scroll-bar:vertical") != null
                            && scrollPane.lookup(".scroll-bar:vertical").isVisible(),
                    Math.round(content.getWidth()), Math.round(content.prefWidth(-1)),
                    Math.round(content.minWidth(-1)), Math.round(content.maxWidth(-1)),
                    Math.round(page.getLayoutBounds().getWidth()));
        }
    }

    /**
     * 找出「被挤出父容器可视区」的子节点。
     *
     * <p>判据必须是<b>实际边界</b>而不是最小宽度：HBox 在空间不足时会把子节点按各自
     * 最小宽度排布，若子节点们的最小宽度之和仍超出容器，内容就会溢出容器右边界 ——
     * 这种溢出不会体现在 {@code minWidth} 上，滚动条也不会出现，控件就那么静静地被裁掉。
     * 这也是「拖小窗口后控件消失」的真正成因。</p>
     *
     * <p>自身可滚动的容器（ScrollPane / 文本控件 / ListView / ComboBox）跳过：
     * 它们的子节点宽于可视区属于设计如此，有滚动条或省略号兜底。</p>
     */
    private static String findOverflow(Node root) {
        StringBuilder report = new StringBuilder();
        java.util.ArrayDeque<Node> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int found = 0;
        while (!queue.isEmpty() && found < 3) {
            Node node = queue.poll();
            if (node instanceof javafx.scene.control.ScrollPane scrollPane) {
                if (scrollPane.getContent() != null) {
                    queue.add(scrollPane.getContent());
                }
                continue;
            }
            // 拆成两个 if：Java 的模式变量在「取反的或表达式」里不算确定匹配，
            // 写成一句会编译不过
            if (!(node instanceof javafx.scene.Parent parent)) {
                continue;
            }
            if (!(node instanceof javafx.scene.layout.Region region)) {
                continue;
            }
            if (isSelfScrollable(node)) {
                continue;
            }
            if (region.getWidth() > 1) {
                for (Node child : parent.getChildrenUnmodifiable()) {
                    if (!child.isManaged() || !child.isVisible()) {
                        continue;
                    }
                    double overflowX = child.getBoundsInParent().getMaxX() - region.getWidth();
                    if (overflowX > 2) {
                        found++;
                        report.append(String.format(
                                "%n   · 在 %s%s[%d] 内：%s%s 右缘 %.0f > 容器宽 %.0f（溢出 %.0f，该子节点最小宽 %.0f）",
                                region.getClass().getSimpleName(), region.getStyleClass(),
                                parent.getChildrenUnmodifiable().indexOf(child),
                                child.getClass().getSimpleName(), child.getStyleClass(),
                                child.getBoundsInParent().getMaxX(), region.getWidth(), overflowX,
                                child.minWidth(-1)));
                        break;
                    }
                }
            }
            queue.addAll(parent.getChildrenUnmodifiable());
        }
        return report.toString();
    }

    /**
     * 页面内容的真实最小宽度。
     * <p>{@code ScrollPane.minWidth()} 恒为 0（它自身可无限压缩、靠滚动条兜底），
     * 直接量会永远得到「没有溢出」的假结论，必须解包量它的内容。</p>
     */
    private static double pageContentMinWidth(Node page) {
        if (page instanceof javafx.scene.control.ScrollPane scrollPane
                && scrollPane.getContent() != null) {
            return scrollPane.getContent().minWidth(-1);
        }
        return page.minWidth(-1);
    }

    /** 自身可滚动 / 自带省略显示的容器：其子节点宽于可视区属于正常。 */
    private static boolean isSelfScrollable(Node node) {
        return node instanceof javafx.scene.control.TextInputControl
                || node instanceof javafx.scene.control.ListView
                || node instanceof javafx.scene.control.TreeView
                || node instanceof javafx.scene.control.TableView
                || node instanceof javafx.scene.control.ComboBoxBase
                || node instanceof javafx.scene.control.ScrollBar;
    }

    /** 找出「最小宽度最大」的节点（全树遍历），用于定位把布局顶宽的元凶。 */
    private static String widestNode(Node root) {
        Node widest = root;
        double widestWidth = root.minWidth(-1);
        java.util.ArrayDeque<Node> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            double width = node.minWidth(-1);
            if (width > widestWidth) {
                widestWidth = width;
                widest = node;
            }
            if (node instanceof javafx.scene.Parent parent) {
                queue.addAll(parent.getChildrenUnmodifiable());
            }
        }
        return widest.getClass().getSimpleName() + widest.getStyleClass()
                + " min=" + Math.round(widestWidth);
    }

    /** 记录窗口几何信息，便于诊断「界面尺寸异常 / 元素被裁剪」这类问题。 */
    private void logWindowGeometry() {
        Screen screen = Screen.getPrimary();
        Rectangle2D visual = screen.getVisualBounds();
        // 刻意拆成两行：单行 10 个占位符一旦写错就会静默错位（曾把缩放值与最小尺寸对调）
        log.info("窗口几何：stage={}x{} @({}, {})，屏幕可视 {}x{}",
                Math.round(stage.getWidth()), Math.round(stage.getHeight()),
                Math.round(stage.getX()), Math.round(stage.getY()),
                Math.round(visual.getWidth()), Math.round(visual.getHeight()));
        log.info("窗口几何：输出缩放 {} x {}，最小尺寸 {} x {}",
                screen.getOutputScaleX(), screen.getOutputScaleY(),
                Math.round(minWidth), Math.round(minHeight));
        // 各区域的最小宽度：决定「窗口能缩到多窄而不丢控件」。
        // 一旦窗口窄于某个区域的最小宽度，该区域的内容就会被居中裁剪。
        Node page = router.currentNode();
        log.info("布局容量：外壳最小 {}px（可用 {}px）| 侧边栏 {} | 启动栏 {} | 内容区实际 {} | 页面 {} 内容最小 {}px",
                Math.round(shell.minWidth(-1)),
                Math.round(frameClip.getWidth() > 1 ? frameClip.getWidth() : stage.getWidth()),
                Math.round(sidebar.minWidth(-1)),
                Math.round(launchBar.minWidth(-1)),
                Math.round(contentHost.getWidth()),
                router.currentId(),
                page == null ? -1 : Math.round(pageContentMinWidth(page)));
        // 布局完成之后才能拿到外壳各区域的真实尺寸
        Platform.runLater(this::logShellGeometry);
    }

    /**
     * 记录外壳各区域的真实尺寸。
     * <p>排查「拉伸后布局错乱 / 界面显示不全」时，这是唯一不依赖截图的证据：
     * 无边框窗口走 D3D 合成，截图很容易受合成时序与 DPI 缩放干扰，
     * 而这里的数字直接来自布局结果，可用来判断界面究竟有没有跟着重排。</p>
     */
    private void logShellGeometry() {
        log.info("外壳尺寸：frame={}x{}，侧边栏 {}x{}，内容区 {}x{}，启动栏 {}x{}",
                Math.round(frame.getWidth()), Math.round(frame.getHeight()),
                Math.round(sidebar.getWidth()), Math.round(sidebar.getHeight()),
                Math.round(contentHost.getWidth()), Math.round(contentHost.getHeight()),
                Math.round(launchBar.getWidth()), Math.round(launchBar.getHeight()));
    }

    // ==================================================================
    // AppShell
    // ==================================================================

    @Override
    public LauncherServices services() {
        return services;
    }

    @Override
    public Toast toast() {
        return toast;
    }

    @Override
    public TaskCenter tasks() {
        return taskCenter;
    }

    @Override
    public LauncherUpdater updates() {
        if (updater == null) {
            updater = new LauncherUpdater(this);
        }
        return updater;
    }

    @Override
    public boolean gameRunning() {
        return launchPipeline != null && launchPipeline.isRunning();
    }

    @Override
    public TaskCenter.SelfUpdatePhase selfUpdatePhase() {
        return taskCenter.selfUpdatePhase();
    }

    @Override
    public void navigate(String pageId) {
        router.show(pageId);
        context.config().ui.lastPage = pageId;
    }

    @Override
    public void invalidatePage(String pageId) {
        // 失效当前页时路由会立即原地重建（见 ViewRouter.invalidate），
        // 这里不再需要「先跳默认页再跳回来」的绕路 —— 那条路因 currentId 被置空
        // 而永远不会执行，正是「游戏退出后界面空白」的根因
        router.invalidate(pageId);
    }

    @Override
    public String currentPage() {
        return router.currentId();
    }

    @Override
    public Stage stage() {
        return stage;
    }

    @Override
    public ThemeManager themeManager() {
        return themeManager;
    }

    @Override
    public BackgroundManager backgroundManager() {
        return backgroundManager;
    }

    @Override
    public Runnable launchHandler() {
        return launchHandler;
    }

    @Override
    public Runnable stopHandler() {
        return stopHandler;
    }

    // ==================================================================
    // 对外能力
    // ==================================================================

    /** 由启动流水线注入主按钮行为。 */
    public void setLaunchHandler(Runnable handler) {
        this.launchHandler = handler;
    }

    /** 由启动流水线注入停止行为。 */
    public void setStopHandler(Runnable handler) {
        this.stopHandler = handler;
    }

    /** 刷新所有依赖配置的展示（切换实例 / 账户 / 保存设置后调用）。 */
    public void refreshShell() {
        titleBar.refresh();
        sidebar.refresh();
        launchBar.refresh();
    }

    /** 语言切换后刷新静态文案。 */
    public void refreshTexts() {
        stage.setTitle(I18n.t("app.name"));
        titleBar.refreshTexts();
        sidebar.refreshTexts();
        launchBar.refreshTexts();
        // 页面正文也是构造时按当时的语言定下的：只刷外壳会出现
        // 「侧边栏变了、内容还是旧语言」—— 必须把页面缓存整体作废并原地重建当前页。
        String currentPage = router.currentId();
        router.invalidateAll();
        router.show(currentPage == null || currentPage.isBlank() ? router.defaultPage() : currentPage);
    }

    /**
     * 自更新阶段变化后刷新入口可用性。
     *
     * <p>页面缓存必须清掉：页面上的按钮在构建时就按当时的阶段定下了可用性，
     * 复用旧页面会让「更新中不该点」的入口重新变成可点。只有「在更新 / 不在更新」
     * 这一维翻转时才重建 —— {@code DOWNLOADING → INSTALLING} 只影响启动栏的停止按钮，
     * 把页面全部重建一遍纯属闪烁。</p>
     */
    private void onSelfUpdatePhaseChanged(TaskCenter.SelfUpdatePhase was, TaskCenter.SelfUpdatePhase now) {
        launchBar.refresh();
        if ((was == TaskCenter.SelfUpdatePhase.IDLE) == (now == TaskCenter.SelfUpdatePhase.IDLE)) {
            return;
        }
        String current = router.currentId();
        router.invalidateAll();
        if (current != null) {
            router.show(current);
        }
        log.info("自更新阶段 {} → {}：已重建页面以刷新入口可用性", was, now);
    }

    /**
     * 把窗口恢复为默认尺寸并居中。
     *
     * <p>无边框窗口被拉大或拖偏之后，边缘的缩放热区可能落到屏幕外而无法抓回，
     * 因此必须提供一个明确的恢复入口（设置页「重置窗口尺寸」按钮走这里）。</p>
     *
     * <p>同时要把尺寸写回配置：否则用户下次启动又会回到那个异常尺寸。</p>
     */
    @Override
    public void resetWindowSize() {
        if (stage.isMaximized()) {
            stage.setMaximized(false);
        }
        Rectangle2D visual = Screen.getPrimary().getVisualBounds();
        double width = clamp(LauncherConfig.UiSettings.DEFAULT_WIDTH, minWidth,
                visual.getWidth() * SCREEN_FILL);
        double height = clamp(LauncherConfig.UiSettings.DEFAULT_HEIGHT, minHeight,
                visual.getHeight() * SCREEN_FILL);

        stage.setWidth(width);
        stage.setHeight(height);
        stage.setX(visual.getMinX() + (visual.getWidth() - width) / 2);
        stage.setY(visual.getMinY() + (visual.getHeight() - height) / 2);

        LauncherConfig.UiSettings ui = context.config().ui;
        ui.windowWidth = width;
        ui.windowHeight = height;
        context.configStore().save();

        refreshShell();
        toast.show(I18n.t("settings.window.reset.done"), Toast.Kind.SUCCESS);
        log.info("窗口尺寸已重置为 {}x{} 并居中", Math.round(width), Math.round(height));
    }

    /**
     * 打开设置页并定位到指定分页。
     * <p>页面是懒创建 + 缓存的，刚切换过去时节点可能还没建立，
     * 因此把定位动作放到下一帧执行。</p>
     */
    @Override
    public void openSettings(String tabId) {
        navigate("settings");
        Platform.runLater(() -> {
            if (router.currentNode() instanceof SettingsView settings && tabId != null) {
                settings.showTab(tabId);
            }
        });
    }

    /** 保存窗口状态并在退出时释放资源。 */
    public void shutdown() {
        LauncherConfig config = context.config();
        if (!stage.isMaximized()) {
            config.ui.windowWidth = stage.getWidth();
            config.ui.windowHeight = stage.getHeight();
        }
        config.ui.maximized = stage.isMaximized();

        // 结束游戏进程：否则关闭启动器后会留下一个失去父进程的孤儿进程，
        // 用户既看不到它也无法从启动器停止它
        if (launchPipeline != null && launchPipeline.isRunning()) {
            launchPipeline.stop();
        }

        backgroundManager.dispose();
        taskCenter.shutdown();
        if (updater != null) {
            updater.shutdown();
        }
        context.shutdown();
        log.info("主窗口已关闭");
    }

    // ==================================================================
    // 窗口交互
    // ==================================================================

    /**
     * 是否使用透明窗口。
     * <p>Windows 与 macOS 支持良好；部分 Linux 桌面环境（尤其未启用合成器时）
     * 会出现黑边或无法交互，因此保守退化为不透明窗口。</p>
     */
    private static boolean supportsTransparentWindow() {
        return OsUtils.isWindows() || OsUtils.isMacOS();
    }

    private void installWindowInteractions(Scene scene) {
        scene.setOnMouseMoved(event -> scene.setCursor(resolveCursor(event.getSceneX(), event.getSceneY())));
        scene.setOnMouseExited(event -> scene.setCursor(Cursor.DEFAULT));

        scene.setOnMousePressed(event -> {
            resizeEdge = resolveEdge(event.getSceneX(), event.getSceneY());
            pressScreenX = event.getScreenX();
            pressScreenY = event.getScreenY();
            pressStageX = stage.getX();
            pressStageY = stage.getY();
            pressWidth = stage.getWidth();
            pressHeight = stage.getHeight();
        });

        scene.setOnMouseDragged(event -> {
            if (resizeEdge.isEmpty() || stage.isMaximized()) {
                return;
            }
            double deltaX = event.getScreenX() - pressScreenX;
            double deltaY = event.getScreenY() - pressScreenY;

            double newX = pressStageX;
            double newY = pressStageY;
            double newWidth = pressWidth;
            double newHeight = pressHeight;

            if (resizeEdge.contains("w")) {
                newWidth = Math.max(minWidth, pressWidth - deltaX);
                newX = pressStageX + (pressWidth - newWidth);
            }
            if (resizeEdge.contains("e")) {
                newWidth = Math.max(minWidth, pressWidth + deltaX);
            }
            if (resizeEdge.contains("n")) {
                newHeight = Math.max(minHeight, pressHeight - deltaY);
                newY = pressStageY + (pressHeight - newHeight);
            }
            if (resizeEdge.contains("s")) {
                newHeight = Math.max(minHeight, pressHeight + deltaY);
            }

            // 尺寸与位置都必须钳在屏幕可视区域内。
            // 无边框窗口的缩放热区位于窗口最外圈，一旦被拉到屏幕之外，热区也随之离开屏幕，
            // 用户就再也抓不到边缘把它拉回来 —— 这正是「拉伸后无法还原」的原因。
            Rectangle2D visual = Screen.getPrimary().getVisualBounds();
            newWidth = Math.min(newWidth, visual.getWidth());
            newHeight = Math.min(newHeight, visual.getHeight());
            newX = Math.max(visual.getMinX(), Math.min(newX, visual.getMaxX() - newWidth));
            newY = Math.max(visual.getMinY(), Math.min(newY, visual.getMaxY() - newHeight));

            // 注意顺序：先设尺寸再设位置，避免在缩小窗口时出现抖动
            stage.setWidth(newWidth);
            stage.setHeight(newHeight);
            stage.setX(newX);
            stage.setY(newY);
        });

        scene.setOnMouseReleased(event -> {
            if (!resizeEdge.isEmpty()) {
                resizeEdge = "";
                // 缩放结束后再校正一次：缩放过程中的钳制以按下时的屏幕信息为准，
                // 这里兜底处理缩放期间分辨率 / 显示器变化的情况
                ensureOnScreen();
            }
        });
    }

    /** 判断鼠标位于哪条边（组合字符串，例如 {@code ne}）。 */
    private String resolveEdge(double x, double y) {
        double width = stage.getWidth();
        double height = stage.getHeight();
        if (stage.isMaximized()) {
            return "";
        }
        boolean left = x <= SHADOW_MARGIN + 2;
        boolean right = x >= width - SHADOW_MARGIN - 2;
        boolean top = y <= SHADOW_MARGIN + 2;
        boolean bottom = y >= height - SHADOW_MARGIN - 2;

        StringBuilder edge = new StringBuilder(2);
        if (top) {
            edge.append('n');
        } else if (bottom) {
            edge.append('s');
        }
        if (left) {
            edge.append('w');
        } else if (right) {
            edge.append('e');
        }
        return edge.toString();
    }

    private Cursor resolveCursor(double x, double y) {
        String edge = resolveEdge(x, y);
        return switch (edge) {
            case "n", "s" -> Cursor.V_RESIZE;
            case "e", "w" -> Cursor.H_RESIZE;
            case "ne", "sw" -> Cursor.NE_RESIZE;
            case "nw", "se" -> Cursor.NW_RESIZE;
            default -> Cursor.DEFAULT;
        };
    }

    /**
     * 让指定节点承担窗口拖动（标题栏使用）。
     * <p>几个必须处理的细节：</p>
     * <ul>
     *   <li>用 {@code addEventHandler} 而不是 {@code setOnXxx}：标题栏内部可能还有别的
     *       处理器，直接覆盖会静默吞掉它们；</li>
     *   <li>按下位置若落在边缘缩放热区，则不拖动，避免与缩放互相打架；</li>
     *   <li>位移小于阈值视为点击，这样双击最大化不会被微抖动破坏；</li>
     *   <li>从最大化状态拖动时先还原，并按比例把鼠标映射回还原后的标题栏，
     *       否则窗口会「跳」到鼠标下方。</li>
     * </ul>
     */
    public void installDrag(Node node) {
        node.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            dragging = false;
            if (event.getButton() != MouseButton.PRIMARY || !resizeEdge.isEmpty()) {
                return;
            }
            dragOffsetX = event.getScreenX() - stage.getX();
            dragOffsetY = event.getScreenY() - stage.getY();
        });

        node.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            if (event.getButton() != MouseButton.PRIMARY || !resizeEdge.isEmpty()) {
                return;
            }
            // 阈值判断：拖动前的微小位移不应被当成拖动
            double deltaX = event.getScreenX() - (stage.getX() + dragOffsetX);
            double deltaY = event.getScreenY() - (stage.getY() + dragOffsetY);
            if (!dragging && Math.hypot(deltaX, deltaY) < DRAG_THRESHOLD) {
                return;
            }
            dragging = true;

            if (stage.isMaximized()) {
                double ratio = stage.getWidth() <= 0 ? 0.5
                        : Math.min(1, Math.max(0, event.getScreenX() / stage.getWidth()));
                stage.setMaximized(false);
                dragOffsetX = Math.min(stage.getWidth() * ratio, Math.max(0, stage.getWidth() - 60));
                dragOffsetY = Math.min(dragOffsetY, 16);
            }
            // 标题栏是无边框窗口唯一的拖动把手，必须保证它始终留在屏幕内：
            // 一旦把窗口拖到屏幕顶部之外，用户就再也抓不到把手把窗口拖回来，
            // 表现就是「拖动后上方 UI 消失」。
            Rectangle2D visual = Screen.getPrimary().getVisualBounds();
            double targetX = event.getScreenX() - dragOffsetX;
            double targetY = event.getScreenY() - dragOffsetY;
            double maxShiftX = Math.max(0, stage.getWidth() - TITLE_BAR_KEEP);
            stage.setX(Math.min(Math.max(targetX, visual.getMinX() - maxShiftX),
                    visual.getMaxX() - TITLE_BAR_KEEP));
            stage.setY(Math.min(Math.max(targetY, visual.getMinY()),
                    visual.getMaxY() - TITLE_BAR_KEEP));
        });

        node.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !dragging) {
                toggleMaximize();
            }
        });
    }

    /** 切换最大化并刷新外壳（按钮图标与边距）。 */
    private void toggleMaximize() {
        stage.setMaximized(!stage.isMaximized());
        refreshShell();
    }

    /**
     * 应用最大化状态。
     * <p>最大化时必须去掉投影留白与圆角：否则屏幕四边会出现一圈透明缝隙，
     * 内容还会被圆角切掉一块。</p>
     */
    private void applyMaximizeState(boolean maximized) {
        boolean rounded = transparentWindow && !maximized;
        StackPane.setMargin(frame, new Insets(rounded ? SHADOW_MARGIN : 0));
        frameClipShape.setArcWidth(rounded ? CORNER_RADIUS * 2 : 0);
        frameClipShape.setArcHeight(rounded ? CORNER_RADIUS * 2 : 0);
        frame.getStyleClass().remove("maximized");
        if (maximized) {
            frame.getStyleClass().add("maximized");
        }
    }

    /**
     * 收敛尺寸到 {@code [min, max]}。
     * <p>屏幕比期望下限还小时，以屏幕允许的上限为准 —— 能放下优先于守住最小尺寸。</p>
     */
    private static double clamp(double value, double min, double max) {
        double ceiling = Math.max(min, max);
        double floor = Math.min(min, ceiling);
        return Math.max(floor, Math.min(value, ceiling));
    }

    /**
     * 尺寸变化后（防抖 400ms）记录一次外壳尺寸。
     * <p>用户报过「拉伸后无法还原」，这类问题必须能自证：交付后只要让用户看日志里的
     * 「外壳尺寸」是否随窗口尺寸同步变化，就能区分「真的没重排」与「只是截图不可靠」。</p>
     */
    private void installResizeLogging() {
        PauseTransition debounce = new PauseTransition(Duration.millis(400));
        debounce.setOnFinished(event -> logShellGeometry());
        stage.widthProperty().addListener((observable, was, now) -> debounce.playFromStart());
        stage.heightProperty().addListener((observable, was, now) -> debounce.playFromStart());
    }

    private void installShortcuts(Scene scene) {
        var accelerators = scene.getAccelerators();
        accelerators.put(new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN), () -> {
            String current = router.currentId();
            if (current != null) {
                invalidatePage(current);
                router.show(current);
            }
        });
        accelerators.put(new KeyCodeCombination(KeyCode.ESCAPE), () -> {
            if (taskCenter.selfUpdatePhase() == TaskCenter.SelfUpdatePhase.INSTALLING) {
                // 替换自身文件的那几秒不能停（同启动栏的停止按钮）：停下来既回不到
                // 干净的旧版本，也拿不到新版本
                toast.show(I18n.t("update.gate.locked"), Toast.Kind.WARN);
                return;
            }
            if (taskCenter.busyProperty().get()) {
                taskCenter.cancel();
                toast.show(I18n.t("status.cancelled"));
            }
        });
        // Ctrl+1..7 快速切页（与导航栏顺序一致）
        // 不设 Ctrl+0：窗口越界已由「移动 / 缩放的屏幕内钳制」自动兜底，用户无需再记快捷键
        for (int i = 0; i < PAGE_ORDER.size() && i < 9; i++) {
            KeyCode code = KeyCode.valueOf("DIGIT" + (i + 1));
            String pageId = PAGE_ORDER.get(i);
            accelerators.put(new KeyCodeCombination(code, KeyCombination.CONTROL_DOWN),
                    () -> navigate(pageId));
        }
    }

    /** 恢复上次的窗口尺寸；同样按当前屏幕收敛，避免换显示器后尺寸超界。 */
    private void restoreWindowState() {
        LauncherConfig.UiSettings ui = context.config().ui;
        Rectangle2D visual = Screen.getPrimary().getVisualBounds();
        stage.setWidth(clamp(ui.windowWidth, minWidth, visual.getWidth() * SCREEN_FILL));
        stage.setHeight(clamp(ui.windowHeight, minHeight, visual.getHeight() * SCREEN_FILL));
    }
}
