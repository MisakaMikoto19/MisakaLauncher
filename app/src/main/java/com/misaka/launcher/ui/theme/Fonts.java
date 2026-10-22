package com.misaka.launcher.ui.theme;

import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 界面字体选择。
 *
 * <p><b>为什么需要运行时挑</b>：base.css 里已经写了「CJK 优先」的家族列表，但 JavaFX 的
 * {@code -fx-font-family} 不做逐字回退 —— 列表里第一个存在的家族会被整体使用，若它没有
 * 中日韩字形，中文就会渲染成方块（Ubuntu 上最常见的现象：英文正常、中文全是 ▯）。</p>
 *
 * <p><b>为什么随包带一份字体</b>：英文环境的 Linux 默认不装 CJK 字体，此时任何代码都画不出
 * 汉字 —— 只能自己带。内置的 Noto Sans CJK SC（SIL OFL 1.1，见 {@code fonts/OFL.txt}）
 * 覆盖中日韩常用字，优先级最高，因此 Windows / macOS / 各种 Linux 上的观感也一致。</p>
 *
 * <p>选中的家族由 {@link ThemeManager} 写进根节点的内联 {@code -fx-font-family}：
 * 内联优先级高于样式表，且每次换主题都会重新拼接，不会被主题切换冲掉。</p>
 */
public final class Fonts {

    private static final Logger log = LoggerFactory.getLogger(Fonts.class);

    /** 随包分发的字体。改文件名时同步改这里。 */
    private static final String BUNDLED_FONT = "/fonts/NotoSansCJKsc-Regular.otf";

    /**
     * 系统字体候选，按「字形覆盖 + 观感」排序。
     *
     * <p>只在没有内置字体（打包漏了文件）时才会用到。简体优先；{@code Noto Sans CJK JP/KR}
     * 也列进来 —— 它们包含的汉字字形覆盖简体常用字，总比方块好。</p>
     */
    private static final List<String> CJK_CANDIDATES = List.of(
            "Noto Sans CJK SC",
            "Noto Sans SC",
            "Source Han Sans SC",
            "Source Han Sans CN",
            "Sarasa Gothic SC",
            "WenQuanYi Micro Hei",
            "WenQuanYi Zen Hei",
            "Droid Sans Fallback",
            "Microsoft YaHei UI",
            "Microsoft YaHei",
            "PingFang SC",
            "Hiragino Sans GB",
            "SimHei",
            "SimSun",
            "Noto Sans CJK JP",
            "Noto Sans CJK KR");

    private static boolean resolved;
    private static String family;

    private Fonts() {
    }

    /**
     * 注册内置字体并返回它的家族名；加载失败返回 {@code null}。
     *
     * <p>用 {@link Font#loadFont(InputStream, double)} 而不是把文件写在磁盘上再
     * {@code Font.loadFont(path)}：前者直接从 jar 读，不需要解压到临时目录。</p>
     */
    private static String loadBundled() {
        try (InputStream in = Fonts.class.getResourceAsStream(BUNDLED_FONT)) {
            if (in == null) {
                log.warn("内置字体缺失：{}（打包不完整？）", BUNDLED_FONT);
                return null;
            }
            long started = System.currentTimeMillis();
            Font font = Font.loadFont(in, 13);
            if (font == null) {
                log.warn("内置字体无法解析：{}", BUNDLED_FONT);
                return null;
            }
            log.info("已加载内置字体 {}（家族 {}，耗时 {} ms）",
                    BUNDLED_FONT, font.getFamily(), System.currentTimeMillis() - started);
            return font.getFamily();
        } catch (IOException | RuntimeException e) {
            log.warn("加载内置字体失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 最终使用的字体家族：内置优先，其次系统已装的 CJK 字体；都没有返回 {@code null}。
     *
     * <p>结果只解析一次：字体集合在运行期不会变，重复遍历 {@link Font#getFamilies()} 没有意义。</p>
     */
    public static synchronized String cjkFamily() {
        if (resolved) {
            return family;
        }
        resolved = true;

        String bundled = loadBundled();
        if (bundled != null && !bundled.isBlank()) {
            family = bundled;
            return family;
        }

        Set<String> installed = new HashSet<>(Font.getFamilies());
        for (String candidate : CJK_CANDIDATES) {
            if (installed.contains(candidate)) {
                family = candidate;
                log.info("内置字体不可用，改用系统字体 {}（共 {} 个已安装家族）",
                        candidate, installed.size());
                return family;
            }
        }

        log.warn("没有可用的中日韩字体，中文/日文/韩文会显示为方块。"
                + " 该构建疑似缺少内置字体（app/src/main/resources/fonts/）；"
                + " 或安装系统字体：Ubuntu/Debian: sudo apt install fonts-noto-cjk");
        return null;
    }

    /**
     * 供根节点内联样式使用的一段声明；无可用字体时返回空串。
     *
     * <p>用内联而不是改 CSS：CSS 的家族列表无法表达「这个家族在本机存不存在」，
     * 而内联可以精确指定一个已经验证可用（或已注册进 JVM）的家族。</p>
     */
    public static String rootStyleFragment() {
        String chosen = cjkFamily();
        if (chosen == null || chosen.isBlank()) {
            return "";
        }
        return "-fx-font-family: \"" + chosen + "\";";
    }

    /**
     * 给一个<b>独立场景</b>（对话框、弹层）补上字体。
     *
     * <p>为什么需要单独处理：JavaFX 的 Popup / Dialog 各自持有 Scene，<b>不会</b>继承主窗口
     * 根节点的内联样式。主界面中文正常、弹层里却是方块，就是这个原因 —— 弹层的
     * {@code .root} 只拿到样式表里的家族列表，而列表里的字体本机一个都没有。</p>
     */
    public static void applyToScene(javafx.scene.Scene scene) {
        if (scene == null) {
            return;
        }
        javafx.scene.Parent root = scene.getRoot();
        if (root == null) {
            return;
        }
        String chosen = cjkFamily();
        if (chosen == null || chosen.isBlank()) {
            return;
        }
        String existing = root.getStyle() == null ? "" : root.getStyle();
        // 已经带了字体声明就不要再叠加，避免样式串越来越长
        if (existing.contains("-fx-font-family")) {
            return;
        }
        root.setStyle("-fx-font-family: \"" + chosen + "\";" + existing);
    }
}
