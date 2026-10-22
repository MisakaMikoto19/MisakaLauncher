package com.misaka.launcher.ui.component;

import javafx.scene.Group;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/**
 * 矢量图标。
 * <p>用 {@link SVGPath} 而非图标字体：</p>
 * <ul>
 *   <li>不依赖系统是否安装了特定字体（中文环境常见图标字体缺失导致显示为方块）；</li>
 *   <li>描边颜色可用 CSS 变量控制，因此主题切换与强调色变更会自动生效；</li>
 *   <li>矢量在任何缩放下都清晰，无需准备多套位图。</li>
 * </ul>
 * <p>路径均按 24×24 画布设计，通过缩放适配实际尺寸。</p>
 */
public final class Icons {

    /** 主页。 */
    public static final String HOME =
            "M3.5 10.6 L12 3.4 L20.5 10.6 L20.5 20.2 L14.3 20.2 L14.3 14.2 L9.7 14.2 L9.7 20.2 L3.5 20.2 Z";

    /** 实例（立方体，呼应 Minecraft 的方块）。 */
    public static final String INSTANCE =
            "M12 2.6 L20.4 7.2 L20.4 16.8 L12 21.4 L3.6 16.8 L3.6 7.2 Z M12 2.6 L12 12 L20.4 16.8 M12 12 L3.6 16.8";

    /** 下载（向下箭头落入托盘）。 */
    public static final String DOWNLOAD =
            "M12 3.2 L12 14.4 M7.6 10 L12 14.4 L16.4 10 M4.2 16.8 L4.2 20 L19.8 20 L19.8 16.8";

    /** 账户。 */
    public static final String ACCOUNT =
            "M12 11.6 A4.1 4.1 0 1 0 12 3.4 A4.1 4.1 0 0 0 12 11.6 Z "
                    + "M4.6 20.6 C4.6 16.6 7.9 14.6 12 14.6 C16.1 14.6 19.4 16.6 19.4 20.6";

    /** 设置（滑杆意象，比齿轮更易用少量路径表达且视觉更现代）。 */
    public static final String SETTINGS =
            "M4 7 L20 7 M4 12 L20 12 M4 17 L20 17 "
                    + "M9.2 7 m-2.1 0 a2.1 2.1 0 1 0 4.2 0 a2.1 2.1 0 1 0 -4.2 0 "
                    + "M15.4 12 m-2.1 0 a2.1 2.1 0 1 0 4.2 0 a2.1 2.1 0 1 0 -4.2 0 "
                    + "M8 17 m-2.1 0 a2.1 2.1 0 1 0 4.2 0 a2.1 2.1 0 1 0 -4.2 0";

    /** 日志（文档 + 文本行）。 */
    public static final String LOGS =
            "M6.2 3.4 L14.6 3.4 L18.4 7.2 L18.4 20.6 L6.2 20.6 Z "
                    + "M14.6 3.4 L14.6 7.2 L18.4 7.2 M9.2 11.4 L15.4 11.4 M9.2 14.6 L15.4 14.6 M9.2 17.6 L13.2 17.6";

    /**
     * 语言（地球 + 经纬线）。
     *
     * <p>标题栏的语言入口在它之上再叠「A / 文」两个小字，表达“多语言”，
     * 单靠地球容易被读成“网络/浏览器”。</p>
     */
    public static final String LANGUAGE =
            "M12 3.2 A8.8 8.8 0 1 0 12 20.8 A8.8 8.8 0 0 0 12 3.2 Z "
                    + "M3.4 12 L20.6 12 "
                    + "M12 3.2 C15.4 6.6 15.4 17.4 12 20.8 "
                    + "M12 3.2 C8.6 6.6 8.6 17.4 12 20.8";

    /** 启动（播放三角）。 */
    public static final String PLAY = "M7.6 4.6 L19.4 12 L7.6 19.4 Z";

    /** 停止。 */
    public static final String STOP = "M6.6 6.6 L17.4 6.6 L17.4 17.4 L6.6 17.4 Z";

    /** 刷新。 */
    public static final String REFRESH =
            "M20 12 A8 8 0 1 1 17.2 6.1 M20 3.4 L20 7.6 L15.8 7.6";

    /** 文件夹。 */
    public static final String FOLDER =
            "M3.6 6.4 L9.6 6.4 L11.6 8.8 L20.4 8.8 L20.4 18.6 L3.6 18.6 Z";

    /** 图片。 */
    public static final String IMAGE =
            "M3.6 5.4 L20.4 5.4 L20.4 18.6 L3.6 18.6 Z M3.6 15.2 L8.6 11 L13 14.6 L16.2 12 L20.4 15.6 "
                    + "M15.6 9.4 m-1.3 0 a1.3 1.3 0 1 0 2.6 0 a1.3 1.3 0 1 0 -2.6 0";

    /** 搜索。 */
    public static final String SEARCH =
            "M10.8 4.2 A6.6 6.6 0 1 0 10.8 17.4 A6.6 6.6 0 1 0 10.8 4.2 Z M15.6 15.6 L20.2 20.2";

    /** 关闭。 */
    public static final String CLOSE = "M6.4 6.4 L17.6 17.6 M17.6 6.4 L6.4 17.6";

    /** 最小化。 */
    public static final String MINIMIZE = "M5.6 12 L18.4 12";

    /** 最大化。 */
    public static final String MAXIMIZE = "M5.8 5.8 L18.2 5.8 L18.2 18.2 L5.8 18.2 Z";

    /** 还原。 */
    public static final String RESTORE =
            "M8.2 8.2 L8.2 5.8 L18.2 5.8 L18.2 15.8 L15.8 15.8 M5.8 8.2 L15.8 8.2 L15.8 18.2 L5.8 18.2 Z";

    /** 调色板。 */
    public static final String PALETTE =
            "M12 3.6 A8.4 8.4 0 1 0 12 20.4 C13.6 20.4 14.4 19.4 14.4 18.2 C14.4 17.2 13.8 16.6 13.8 15.8 "
                    + "C13.8 14.8 14.6 14.2 15.6 14.2 L17 14.2 C19 14.2 20.4 12.8 20.4 10.8 "
                    + "C20.4 6.8 16.6 3.6 12 3.6 Z "
                    + "M7.8 11.4 m-1.3 0 a1.3 1.3 0 1 0 2.6 0 a1.3 1.3 0 1 0 -2.6 0 "
                    + "M11.4 8 m-1.3 0 a1.3 1.3 0 1 0 2.6 0 a1.3 1.3 0 1 0 -2.6 0";

    /** 加号。 */
    public static final String PLUS = "M12 6 L12 18 M6 12 L18 12";

    /** 垃圾桶。 */
    public static final String TRASH =
            "M5.4 7.4 L18.6 7.4 M9.6 7.4 L9.6 5.2 L14.4 5.2 L14.4 7.4 "
                    + "M7 7.4 L7.8 19.4 L16.2 19.4 L17 7.4 M10.4 10.6 L10.4 16.4 M13.6 10.6 L13.6 16.4";

    /** 向下箭头（用于下拉/快速切换入口）。 */
    /** 模组：方块的三个可见面（"可拼装的组件"语义，与实例/下载一眼区分）。 */
    public static final String MODS = "M12 2.8 L20.4 7.4 L20.4 16.6 L12 21.2 L3.6 16.6 L3.6 7.4 Z"
            + " M12 11.6 L20.4 7.4 M12 11.6 L3.6 7.4 M12 11.6 L12 21.2";

    public static final String CHEVRON_DOWN = "M6.4 9.6 L12 15.2 L17.6 9.6";
    public static final String CHEVRON_UP = "M6.4 14.4 L12 8.8 L17.6 14.4";

    private Icons() {
    }

    /**
     * 生成指定尺寸的描边图标。
     *
     * @param pathData 24×24 路径数据
     * @param size     期望边长（像素）
     * @param styleClasses 附加样式类，例如 {@code nav-glyph}
     */
    public static Group create(String pathData, double size, String... styleClasses) {
        SVGPath path = new SVGPath();
        path.setContent(pathData);
        path.setFill(null);
        path.setStrokeLineCap(StrokeLineCap.ROUND);
        path.setStrokeLineJoin(StrokeLineJoin.ROUND);
        path.getStyleClass().add("icon-stroke");
        for (String styleClass : styleClasses) {
            if (styleClass != null && !styleClass.isBlank()) {
                path.getStyleClass().add(styleClass);
            }
        }
        // 线宽随尺寸等比缩放，保证小图标不糊、大图标不细
        path.setStrokeWidth(Math.max(1.15, size / 15.0));

        Group group = new Group(path);
        double scale = size / 24.0;
        group.setScaleX(scale);
        group.setScaleY(scale);
        // 注意：不要 setManaged(false)。Group 的布局边界会包含子节点的变换，
        // 因此放大后的 Group 尺寸即为 size，放进 StackPane 可被正确居中；
        // 若设为不受管理，父容器会按未缩放的 24×24 计算布局导致图标偏移。
        return group;
    }

    /**
     * 生成填充图标（用于播放按钮等实心图形）。
     */
    public static Group createFilled(String pathData, double size, String... styleClasses) {
        SVGPath path = new SVGPath();
        path.setContent(pathData);
        path.getStyleClass().add("icon-fill");
        for (String styleClass : styleClasses) {
            if (styleClass != null && !styleClass.isBlank()) {
                path.getStyleClass().add(styleClass);
            }
        }
        Group group = new Group(path);
        double scale = size / 24.0;
        group.setScaleX(scale);
        group.setScaleY(scale);
        return group;
    }
}
