package com.misaka.launcher.ui.theme;

import com.misaka.launcher.app.LauncherContext;
import com.misaka.launcher.core.config.LauncherConfig;
import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 主题管理：加载基础样式与主题样式，并在切换时只替换样式表。
 * <p>自定义主题通过根节点的内联 looked-up colors 覆盖实现，
 * 因此「强调色」这类单点定制不需要另写一份 CSS。</p>
 */
public final class ThemeManager {

    private static final Logger log = LoggerFactory.getLogger(ThemeManager.class);

    private static final String BASE_STYLESHEET = "/css/base.css";

    /** 暗色主题下整窗外壳遮罩的默认强度（压住背景图，保证文字可读）。 */
    private static final double DARK_VEIL_ALPHA = 0.72;

    /** 浅色主题下同上的默认强度：浅底盖不住彩图，需要更强。 */
    private static final double LIGHT_VEIL_ALPHA = 0.86;

    private final Scene scene;
    private Theme current = Theme.DARK;

    /** 自定义强调色（{@code #RRGGBB}）；空表示用主题自带的。 */
    private String customAccent = "";

    /**
     * 整窗外壳遮罩的强度系数：1 = 主题默认强度，0 = 完全不遮罩（背景图本色显示）。
     */
    private double shellVeilFactor = 1.0;

    public ThemeManager(Scene scene) {
        this.scene = scene;
    }

    /** 按配置初始化：读取主题与自定义强调色。 */
    public void initialize() {
        LauncherConfig config = LauncherContext.get().config();
        apply(Theme.fromId(config.theme), config.themeAccent);
    }

    /** 切换主题并持久化。 */
    public void apply(Theme theme, String accent) {
        if (theme == null) {
            theme = Theme.DARK;
        }
        this.current = theme;

        List<String> sheets = new ArrayList<>();
        sheets.add(resource(BASE_STYLESHEET));
        sheets.add(resource(theme.stylesheet()));
        scene.getStylesheets().setAll(sheets);

        applyAccent(accent);

        LauncherConfig config = LauncherContext.get().config();
        config.theme = theme.id();
        if (accent != null && !accent.isBlank()) {
            config.themeAccent = accent;
        }
        log.info("主题已切换为 {}，强调色 {}", theme.id(), config.themeAccent);
    }

    /** 仅调整强调色（自定义主题的核心操作），并在自定义主题下覆盖主题自带的颜色。 */
    public void applyAccent(String accent) {
        this.customAccent = accent == null ? "" : accent;
        applyRootStyle();
    }

    /**
     * 调整整窗外壳（{@code .window-shell}）的遮罩强度。
     *
     * <p>这层遮罩压在背景图之上，是「玻璃拟态」的来源。但它此前是一个写死的常量，
     * 于是把「覆盖层透明度」拉到 100%（期望：除控件外完整显示背景图本色）时，
     * 背景图上依旧压着一层 72% 的主题色 —— 用户看到的正是「即使透明 100% 也还是被染色」。</p>
     *
     * @param factor 1 = 主题默认强度；0 = 完全不遮罩（背景图本色显示）
     */
    public void applyShellVeil(double factor) {
        this.shellVeilFactor = Math.max(0, Math.min(1, factor));
        applyRootStyle();
    }

    /**
     * 重写根节点的内联 looked-up colors。
     *
     * <p>强调色与整窗遮罩都在这里下发：内联样式是<b>整体替换</b>的，
     * 分两次 {@code setStyle} 会互相覆盖，因此必须在一处拼装。</p>
     */
    private void applyRootStyle() {
        javafx.scene.Parent root = scene.getRoot();
        if (root == null) {
            return;
        }
        StringBuilder style = new StringBuilder();
        // 字体：CSS 的家族列表无法表达「本机装没装」，这里挑一个确实存在的 CJK 家族
        // 用内联样式覆盖，否则 Linux 上中文会整片显示成方块
        style.append(Fonts.rootStyleFragment());
        if (!customAccent.isBlank()) {
            appendAccentPalette(style, customAccent);
        }
        // 遮罩颜色随主题走：浅色主题用浅底 + 更强的不透明度，暗色主题用深底
        double base = current.isLight() ? LIGHT_VEIL_ALPHA : DARK_VEIL_ALPHA;
        int[] veil = veilColor();
        style.append("-c-shell-veil: ")
                .append(rgba(veil[0], veil[1], veil[2], base * shellVeilFactor)).append(';');
        root.setStyle(style.toString());
    }

    /**
     * 整窗遮罩的底色。
     *
     * <p>没有自定义强调色时用主题自带的中性色（原来就是这样）；有强调色时<b>按色相染色</b> ——
     * 否则换再鲜艳的强调色，那层盖在背景图上的"玻璃"仍然是一块灰/白，用户会觉得
     * 「主题色根本没盖到这里」。</p>
     *
     * <p>混合比例刻意保守（浅色 12% / 暗色 20%）：遮罩面积是整窗，比例一大就会
     * 把背景图和文字一起糊成彩色玻璃。</p>
     */
    private int[] veilColor() {
        boolean light = current.isLight();
        int[] neutral = light ? new int[]{246, 247, 251} : new int[]{9, 12, 18};
        int[] accent = parseHex(customAccent);
        if (accent == null) {
            return neutral;
        }
        double amount = light ? 0.15 : 0.22;
        return new int[]{
                (int) Math.round(neutral[0] + (accent[0] - neutral[0]) * amount),
                (int) Math.round(neutral[1] + (accent[1] - neutral[1]) * amount),
                (int) Math.round(neutral[2] + (accent[2] - neutral[2]) * amount)};
    }

    /**
     * 由强调色派生出整套界面色。
     *
     * <p>只覆盖 {@code -c-accent} 远远不够：卡片描边（{@code -c-card-border}）、
     * 选中图标与文字（{@code -c-accent-2} / {@code -c-accent-text}）、侧边栏高亮渐变的第二色
     * 原先都是<b>写死的紫/青</b>，所以换了强调色只有个别元素变化，观感就是「主题色根本没生效」。
     * 这里一次性把它们都算出来，并用内联 looked-up colors 下发 —— 不需要重建节点。</p>
     */
    private void appendAccentPalette(StringBuilder style, String accent) {
        int[] rgb = parseHex(accent);
        if (rgb == null) {
            log.warn("无法解析强调色 {}，沿用主题自带配色", accent);
            return;
        }
        int r = rgb[0];
        int g = rgb[1];
        int b = rgb[2];
        boolean light = current.isLight();
        double luminance = luminance(r, g, b);

        // 图标/描边用的第二色：浅底上压暗、暗底上提亮，否则浅色强调色几乎看不见
        int[] strong = mix(r, g, b, light ? 0 : 255, light ? 0.30 : 0.16);
        // 文字用的强调色：再往对比方向多走一步，保证 13px 左右的小字可读
        int[] text = mix(r, g, b, light ? 0 : 255, light ? 0.44 : 0.20);
        // 强调色上的文字：按亮度自动选深/浅，浅紫配白字会糊成一片
        String onAccent = luminance > 0.62 ? "#12141A" : "#FFFFFF";

        style.append("-c-accent: ").append(hex(r, g, b)).append(';')
                .append("-c-accent-2: ").append(hex(strong[0], strong[1], strong[2])).append(';')
                .append("-c-accent-text: ").append(hex(text[0], text[1], text[2])).append(';')
                .append("-c-accent-soft: ").append(rgba(r, g, b, 0.18)).append(';')
                .append("-c-accent-veil: ").append(rgba(r, g, b, 0.10)).append(';')
                .append("-c-card-border: ").append(rgba(r, g, b, light ? 0.24 : 0.22)).append(';')
                .append("-c-card-border-strong: ").append(rgba(r, g, b, light ? 0.48 : 0.45)).append(';')
                .append("-c-on-accent: ").append(onAccent).append(';')
                .append("-c-on-accent-dim: ").append(luminance > 0.62
                        ? rgba(18, 20, 26, 0.72) : rgba(255, 255, 255, 0.78)).append(';');
    }

    /** 解析 {@code #RGB} / {@code #RRGGBB}；带 alpha 的 {@code #RRGGBBAA} 也接受（忽略 alpha）。 */
    private static int[] parseHex(String hex) {
        String value = hex.trim().replace("#", "");
        try {
            if (value.length() == 3) {
                return new int[]{
                        Integer.parseInt(value.substring(0, 1).repeat(2), 16),
                        Integer.parseInt(value.substring(1, 2).repeat(2), 16),
                        Integer.parseInt(value.substring(2, 3).repeat(2), 16)};
            }
            if (value.length() >= 6) {
                return new int[]{
                        Integer.parseInt(value.substring(0, 2), 16),
                        Integer.parseInt(value.substring(2, 4), 16),
                        Integer.parseInt(value.substring(4, 6), 16)};
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    /** 向 {@code target}（0 = 黑，255 = 白）混合，{@code amount} 为混合比例。 */
    private static int[] mix(int r, int g, int b, int target, double amount) {
        double k = Math.max(0, Math.min(1, amount));
        return new int[]{
                (int) Math.round(r + (target - r) * k),
                (int) Math.round(g + (target - g) * k),
                (int) Math.round(b + (target - b) * k)};
    }

    /** 相对亮度（0 = 黑，1 = 白），用于决定「强调色上该用黑字还是白字」。 */
    private static double luminance(int r, int g, int b) {
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
    }

    private static String hex(int r, int g, int b) {
        return String.format(java.util.Locale.ROOT, "#%02X%02X%02X", r, g, b);
    }

    private static String rgba(int r, int g, int b, double alpha) {
        return String.format(java.util.Locale.ROOT, "rgba(%d, %d, %d, %.3f)", r, g, b, alpha);
    }

    /** 保留 3 位小数即可，避免把一长串浮点噪声写进样式字符串。 */
    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    public Theme current() {
        return current;
    }

    /** 当前生效的主题样式表路径（供调试展示）。 */
    public List<String> stylesheets() {
        return List.copyOf(scene.getStylesheets());
    }

    private String resource(String path) {
        URL url = ThemeManager.class.getResource(path);
        if (url == null) {
            log.error("样式表缺失: {}（界面将回退为默认外观）", path);
            return "";
        }
        return url.toExternalForm();
    }
}
