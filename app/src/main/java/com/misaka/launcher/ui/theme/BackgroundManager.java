package com.misaka.launcher.ui.theme;

import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.config.PortablePaths;
import com.misaka.launcher.core.util.FileUtils;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 背景管理：与内容层完全解耦的独立渲染层。
 * <p>可实时调节的能力（对齐 HMCL 的可调背景体验）：</p>
 * <ul>
 *   <li>类型：纯色 / 渐变 / 单图 / 文件夹轮播；</li>
 *   <li>模糊半径、明暗叠加强度、整体不透明度；</li>
 *   <li>轮播间隔与随机顺序。</li>
 * </ul>
 * <p>渐变模式不是简单的两点线性渐变，而是「斜向主渐变 + 两处柔光」的复合绘制，
 * 这样即使没有任何图片也能得到有层次的观感。</p>
 */
public final class BackgroundManager {

    private static final Logger log = LoggerFactory.getLogger(BackgroundManager.class);

    private static final List<String> IMAGE_EXTENSIONS = List.of("png", "jpg", "jpeg", "webp", "bmp", "gif");

    private final StackPane layer;
    private final PortablePaths paths;

    private final Region baseLayer = new Region();
    private final ImageView imageView = new ImageView();
    private final Region dimLayer = new Region();
    private final Region overlayLayer = new Region();

    private LauncherConfig.BackgroundSettings settings;
    private Timeline rotationTimeline;
    private List<Path> rotationFiles = List.of();
    private int rotationIndex;

    public BackgroundManager(StackPane layer, PortablePaths paths, LauncherConfig.BackgroundSettings settings) {
        this.layer = layer;
        this.paths = paths;
        this.settings = settings;

        // 背景必须完全穿透鼠标事件，否则会挡住内容区的交互
        layer.setMouseTransparent(true);
        layer.setPickOnBounds(true);

        imageView.setPreserveRatio(false);
        imageView.setSmooth(true);
        imageView.fitWidthProperty().bind(layer.widthProperty());
        imageView.fitHeightProperty().bind(layer.heightProperty());
        // 背景层绝不能参与布局尺寸计算 —— 必须 unmanaged。
        // ImageView 是可调整大小的节点，其 min/pref 宽度**等于 fitWidth**，而 fitWidth 又绑定在
        // layer 的当前宽度上，于是形成自锁：背景层的最小宽度 == 它当前的宽度。
        // 后果是窗口放大之后最小宽度跟着变大，再往回缩就被卡住、右侧控件被裁掉
        // （用户报的「能放大不能缩小」，且只在放大过一次之后才出现）。
        // unmanaged 后 StackPane 不再量它、也不影响自身的 min/pref；位置保持 (0,0)，
        // 尺寸仍由上面的绑定决定，因此仍然能够整层铺满。
        imageView.setManaged(false);

        dimLayer.setMouseTransparent(true);
        overlayLayer.setMouseTransparent(true);

        layer.getChildren().setAll(baseLayer, imageView, dimLayer, overlayLayer);
    }

    /** 应用当前设置（可随时调用，用于实时预览）。 */
    public void apply() {
        stopRotation();
        String mode = settings.mode == null ? LauncherConfig.BackgroundSettings.MODE_GRADIENT : settings.mode;

        switch (mode) {
            case LauncherConfig.BackgroundSettings.MODE_SOLID -> {
                baseLayer.setBackground(solidBackground(parseColor(settings.baseColor, Color.web("#0B0E14"))));
                imageView.setImage(null);
                imageView.setVisible(false);
            }
            case LauncherConfig.BackgroundSettings.MODE_IMAGE -> {
                // 图片加载失败时的兜底：先垫上渐变底，成功加载后立刻撤掉（见 applyEffects）。
                // 图片本身成功显示时**不再垫任何底色** —— 垫了就会被"洗"成一层雾
                baseLayer.setBackground(presetBackground());
                applyImage(loadImage(settings.imagePath));
                if (isImageVisible()) {
                    baseLayer.setBackground(null);
                }
            }
            case LauncherConfig.BackgroundSettings.MODE_FOLDER -> {
                baseLayer.setBackground(presetBackground());
                rotationFiles = scanFolder(settings.folderPath);
                if (rotationFiles.isEmpty()) {
                    imageView.setImage(null);
                    imageView.setVisible(false);
                } else {
                    if (settings.shuffle) {
                        List<Path> shuffled = new ArrayList<>(rotationFiles);
                        Collections.shuffle(shuffled);
                        rotationFiles = shuffled;
                    }
                    rotationIndex = 0;
                    applyImage(loadImage(rotationFiles.get(0).toString()));
                    if (isImageVisible()) {
                        baseLayer.setBackground(null);
                    }
                    startRotation();
                }
            }
            default -> {
                baseLayer.setBackground(presetBackground());
                imageView.setImage(null);
                imageView.setVisible(false);
            }
        }

        applyEffects();
        // 背景"设了没反应"是最难排查的一类问题（加载失败与否界面完全一样），
        // 因此每次应用都记一条可核对的日志
        log.info("背景已应用：{}（可见 {}）", describeState(mode), imageView.isVisible());
    }

    /** 重新扫描轮播文件夹（用户在设置里换了目录后调用）。 */
    public void refresh() {
        apply();
    }

    /** 更新设置引用（设置页改动后由界面调用）。 */
    public void setSettings(LauncherConfig.BackgroundSettings settings) {
        this.settings = settings;
    }

    /**
     * 当前是否真的有一张图铺在背景上。
     *
     * <p>供界面在「选好图片之后」给出确定反馈：以前加载失败是静默的，
     * 用户无法区分「没生效」与「太淡看不出」。</p>
     */
    public boolean isImageVisible() {
        return imageView.getImage() != null && imageView.isVisible();
    }

    public void dispose() {
        stopRotation();
    }

    // ------------------------------------------------------------------
    // 渲染细节
    // ------------------------------------------------------------------

    private void applyImage(Image image) {
        if (image == null || image.isError()) {
            imageView.setImage(null);
            imageView.setVisible(false);
            return;
        }
        imageView.setImage(image);
        imageView.setVisible(true);
    }

    /** 内置默认壁纸的资源名（与 {@code LauncherServices} 释放到 backgrounds/ 的是同一张）。 */
    private static final String BUNDLED_WALLPAPER_RESOURCE = "/background/mid-autumn-1920x1080.png";

    /**
     * 直接加载打包在应用内的默认壁纸。
     *
     * <p>从类路径读而不依赖 backgrounds/ 里的副本：即使释放失败（目录只读等），
     * 背景也不该是空白。路径为空或文件缺失时都退到这里。</p>
     */
    private Image loadBundledWallpaper() {
        try (java.io.InputStream in = BackgroundManager.class.getResourceAsStream(BUNDLED_WALLPAPER_RESOURCE)) {
            if (in == null) {
                log.warn("内置默认壁纸缺失（打包遗漏？）：{}", BUNDLED_WALLPAPER_RESOURCE);
                return null;
            }
            Image image = new Image(in);
            if (image.isError() || image.getWidth() <= 0) {
                log.warn("内置默认壁纸无法解码：{}", image.getException());
                return null;
            }
            log.info("已使用内置默认壁纸（{}x{}）", (int) image.getWidth(), (int) image.getHeight());
            return image;
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("加载内置默认壁纸失败: {}", e.getMessage());
            return null;
        }
    }

    /** 供日志/诊断：当前背景模式与实际生效的参数。 */
    private String describeState(String mode) {
        String source = switch (mode) {
            case LauncherConfig.BackgroundSettings.MODE_IMAGE -> "单图 " + settings.imagePath;
            case LauncherConfig.BackgroundSettings.MODE_FOLDER ->
                    "轮播 " + settings.folderPath + "（" + rotationFiles.size() + " 张）";
            case LauncherConfig.BackgroundSettings.MODE_SOLID -> "纯色 " + settings.baseColor;
            default -> "渐变";
        };
        if (isImageVisible()) {
            return source + "，模糊 " + (int) clamp(settings.blur, 0, 40)
                    + "px，覆盖层透明度 " + Math.round(coverOpacity() * 100) + "%（100% = 完整显示背景图）";
        }
        return source + "，模糊 " + (int) clamp(settings.blur, 0, 40)
                + "，压暗 " + (int) clamp(settings.dim, 0, 80)
                + "%，叠加不透明度 " + Math.round(clamp(settings.opacity, 0, 100)) + "%";
    }

    private void applyEffects() {
        if (isImageVisible()) {
            applyImageEffects();
        } else {
            applySolidEffects();
        }

        // 渐变遮罩（顶部/底部压暗，保证标题栏与状态栏文字可读）。
        // 强度由「覆盖层透明度」统一控制：100% = 遮罩完全透明（背景完整显示），
        // 0% = 遮罩最深；勾选框仍是硬开关
        double scrim = settings.showOverlayGradient ? (1 - coverOpacity()) : 0;
        if (scrim > 0.01) {
            LinearGradient gradient = new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.color(0, 0, 0, 0.35 * scrim)),
                    new Stop(0.35, Color.color(0, 0, 0, 0.0)),
                    new Stop(1, Color.color(0, 0, 0, 0.4 * scrim)));
            overlayLayer.setBackground(solidBackground(gradient));
        } else {
            overlayLayer.setBackground(null);
        }
    }

    /**
     * 图片模式的特效：<b>毛玻璃与压暗直接作用在图片上</b>。
     *
     * <p><b>为什么不能再垫底色、叠黑色层</b>（旧实现，用户一句话点破）：
     * 「并不是有了背景图以后再叠加白色底色，而是直接对背景图进行毛玻璃效果和半透明」。
     * 旧做法是图片下面垫一层不透明底色、上面叠黑色压暗层，再把整个 stack 半透明化 ——
     * 在浅色主题下，半透明的图叠在不透明的浅底上只会得到一团雾，
     * 无论滑杆怎么调都像「背景图没生效」。现在图片<b>就是不透明的最底层</b>，
     * 可读性靠「把图本身调暗」来保证，任何主题下图片都清晰可辨。</p>
     */
    private void applyImageEffects() {
        javafx.scene.effect.Effect effect = null;

        // 「覆盖层透明度」是图片模式唯一的压暗旋钮：100% = 图片原样显示（除 UI 控件外），
        // 越低图片越暗（保证标题/状态栏文字可读）。以前的「明暗」「不透明度」也压暗图片，
        // 三个旋钮叠在一起谁也说不清哪个在起作用 —— 已统一到这一个
        double cover = coverOpacity();
        if (cover < 0.99) {
            javafx.scene.effect.ColorAdjust adjust = new javafx.scene.effect.ColorAdjust();
            adjust.setBrightness(-(1 - cover) * 0.5);
            effect = adjust;
        }

        double blur = clamp(settings.blur, 0, 40);
        if (blur > 0.5) {
            javafx.scene.effect.BoxBlur boxBlur = new javafx.scene.effect.BoxBlur(blur, blur, 3);
            boxBlur.setInput(effect);
            effect = boxBlur;
        }
        imageView.setEffect(effect);

        // 不再叠黑色压暗层（它属于"叠底色"的旧思路）
        dimLayer.setBackground(null);
        // 整层不再做半透明：否则图片会与主题底色混色，浅色主题下必被洗白
        layer.setOpacity(1.0);
    }

    private void applySolidEffects() {
        double blur = clamp(settings.blur, 0, 40);
        imageView.setEffect(blur > 0.5 ? new javafx.scene.effect.BoxBlur(blur, blur, 3) : null);

        double dim = clamp(settings.dim, 0, 80) / 100.0;
        dimLayer.setBackground(solidBackground(Color.color(0, 0, 0, dim)));

        layer.setOpacity(clamp(clamp(settings.opacity, 0, 100) / 100.0, 0.30, 1.0));
    }

    /**
     * 覆盖层透明度（0~1）。
     *
     * <p>100% = 覆盖层（压暗与渐变遮罩）完全透明，除 UI 控件外完整显示背景图；
     * 0% = 覆盖层最不透明，背景被压到最暗（文字可读性最好）。
     * 这是图片模式唯一的压暗旋钮 —— 以前「明暗」「不透明度」「压暗层不透明度」
     * 三个旋钮都在压暗图片且互相叠加，用户无法判断哪个在起作用（报障原话：
     * 「压暗层的不透明度指的是什么？」）。</p>
     */
    private double coverOpacity() {
        return clamp(settings.overlayOpacity, 0, 100) / 100.0;
    }

    /**
     * 整窗外壳（{@code .window-shell}）遮罩的强度，0~1；0 = 完全不遮罩。
     *
     * <p>背景图上其实压着<b>两层</b>东西：这一层（外壳自身的背景色）与图片自身的压暗效果。
     * 上面 {@link #applyImageEffects()} 已经把第二层交给「覆盖层透明度」了，
     * 但第一层此前是 CSS 里写死的 {@code rgba(9,12,18,0.72)} —— 于是把这个滑杆拉到 100%
     * （期望：除 UI 控件外完整显示背景图本色）时，图上仍旧压着一层 72% 的主题色。
     * 用户报障原话：「即使透明 100%，也不是完全显示背景图本色，还是会有主题色遮罩存在」。</p>
     *
     * <p>因此这里把同一个旋钮接到外壳遮罩上：滑杆 100% → 系数 0（图本色显示），
     * 滑杆 0% → 系数 1（遮罩最深，文字最好读）。</p>
     */
    public double shellVeilFactor() {
        return 1 - coverOpacity();
    }

    /**
     * 复合渐变背景：斜向主渐变 + 左上/右下两处柔光。
     * <p>层叠多个 {@link BackgroundFill} 是 JavaFX 原生支持的，无需额外节点或 Canvas。</p>
     */
    private Background presetBackground() {
        Color start = parseColor(settings.gradientStart, Color.web("#1B1140"));
        Color end = parseColor(settings.gradientEnd, Color.web("#06131F"));
        Color base = parseColor(settings.baseColor, Color.web("#0B0E14"));

        LinearGradient main = new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, start), new Stop(0.55, base), new Stop(1, end));

        // 光晕颜色由渐变起点推导：用户改渐变色调时，光晕会自动协调，无需额外配置项
        Color glow = glowFrom(start);
        RadialGradient glowA = new RadialGradient(0, 0, 0.22, 0.15, 0.7, true, CycleMethod.NO_CYCLE,
                new Stop(0, withAlpha(glow, 0.30)),
                new Stop(1, Color.TRANSPARENT));

        RadialGradient glowB = new RadialGradient(0, 0, 0.85, 0.9, 0.65, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.color(0.13, 0.83, 0.93, 0.22)),
                new Stop(1, Color.TRANSPARENT));

        return new Background(
                new BackgroundFill(glowB, CornerRadii.EMPTY, Insets.EMPTY),
                new BackgroundFill(glowA, CornerRadii.EMPTY, Insets.EMPTY),
                new BackgroundFill(main, CornerRadii.EMPTY, Insets.EMPTY));
    }

    private static Background solidBackground(Paint paint) {
        return new Background(new BackgroundFill(paint, CornerRadii.EMPTY, Insets.EMPTY));
    }

    /** 返回类型刻意用 {@link Color} 而非 {@link Paint}：调用方需要继续做颜色运算。 */
    private static Color withAlpha(Color color, double alpha) {
        return Color.color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /**
     * 由渐变起点推导光晕色。
     * <p>做法是把色相稍作偏移并提高饱和与亮度，从而既保持同色系又不会与底色糊在一起。</p>
     */
    private static Color glowFrom(Color base) {
        Color shifted = base.deriveColor(28, 1.35, 1.6, 1);
        // 深色底上纯黑起点会让光晕不可见，这里兜底到品牌紫
        return shifted.getBrightness() < 0.18 ? Color.web("#7C5CFF") : shifted;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Color parseColor(String value, Color fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Color.web(value.trim());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private Image loadImage(String pathValue) {
        if (pathValue == null || pathValue.isBlank()) {
            // 路径为空 = 回到默认，而不是「什么都没有」：用户清空路径后应该看到内置壁纸
            //（旧行为会让背景彻底空白，看起来就是"默认壁纸没内置"）
            return loadBundledWallpaper();
        }
        Path path = resolveImagePath(pathValue);
        if (path == null || !Files.isRegularFile(path)) {
            log.warn("背景图片不存在，改用内置默认壁纸: {}", pathValue);
            return loadBundledWallpaper();
        }
        // 用字节流而不是 file: URL：路径里带空格、中文、'#'、'%' 等字符时，
        // URL 形式要经过百分号编解码，字节流没有这一层（实测中文路径两者都行，
        // 但流式少一个出错环节，代价只是多一次本地读）
        try (java.io.InputStream in = Files.newInputStream(path)) {
            Image image = new Image(in);
            if (image.isError() || image.getWidth() <= 0) {
                // 以前这里静默返回 null —— 用户看到的就是"设了背景图没反应"，日志里还什么都没有
                log.warn("背景图片无法解码（格式不受支持？）：{}（{} KB）异常={}",
                        path, Files.size(path) / 1024, image.getException());
                return null;
            }
            log.info("背景图片已加载：{}（{}x{}）", path,
                    (int) image.getWidth(), (int) image.getHeight());
            return image;
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("加载背景图片失败 {}: {}", path, e.getMessage());
            return null;
        }
    }

    /** 支持绝对路径、启动器数据目录内的相对路径，以及用户主目录的 {@code ~} 简写。 */
    private Path resolveImagePath(String pathValue) {
        String value = pathValue.trim();
        if (value.startsWith("~")) {
            value = System.getProperty("user.home", ".") + value.substring(1);
        }
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = paths.backgroundsDir().resolve(value);
        }
        return path;
    }

    private List<Path> scanFolder(String folderValue) {
        if (folderValue == null || folderValue.isBlank()) {
            return List.of();
        }
        Path folder = resolveImagePath(folderValue);
        if (!Files.isDirectory(folder)) {
            log.warn("轮播文件夹不存在: {}", folder);
            return List.of();
        }
        List<Path> files = FileUtils.listFilesRecursively(folder).stream()
                .filter(path -> IMAGE_EXTENSIONS.contains(FileUtils.extensionOf(path).toLowerCase(Locale.ROOT)))
                .sorted()
                .toList();
        log.info("背景轮播已加载 {} 张图片（来自 {}）", files.size(), folder);
        return files;
    }

    // ------------------------------------------------------------------
    // 轮播
    // ------------------------------------------------------------------

    private void startRotation() {
        int interval = settings.rotateIntervalSeconds;
        if (interval <= 0 || rotationFiles.size() <= 1) {
            return;
        }
        rotationTimeline = new Timeline(new KeyFrame(Duration.seconds(interval), event -> nextImage()));
        rotationTimeline.setCycleCount(Timeline.INDEFINITE);
        rotationTimeline.play();
    }

    private void nextImage() {
        if (rotationFiles.isEmpty()) {
            return;
        }
        rotationIndex = (rotationIndex + 1) % rotationFiles.size();
        Image next = loadImage(rotationFiles.get(rotationIndex).toString());
        if (next == null) {
            return;
        }
        // 淡出 → 换图 → 淡入：避免硬切换的突兀感
        FadeTransition fadeOut = new FadeTransition(Duration.millis(320), imageView);
        fadeOut.setFromValue(1.0);
        fadeOut.setToValue(0.0);
        fadeOut.setOnFinished(event -> {
            imageView.setImage(next);
            FadeTransition fadeIn = new FadeTransition(Duration.millis(480), imageView);
            fadeIn.setFromValue(0.0);
            fadeIn.setToValue(1.0);
            fadeIn.play();
        });
        fadeOut.play();
    }

    private void stopRotation() {
        if (rotationTimeline != null) {
            rotationTimeline.stop();
            rotationTimeline = null;
        }
    }
}
