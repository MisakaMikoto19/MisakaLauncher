package com.misaka.launcher.ui.theme;

/**
 * 内置主题。
 * <p>颜色不在 Java 里硬编码，而是全部定义在 CSS 的 looked-up colors 中
 * （{@code -c-bg}、{@code -c-accent} 等），切换主题只需替换根样式表 ——
 * 界面节点无需重建，切换是瞬时的。</p>
 */
public enum Theme {

    /** 暗色（默认）：深空底色 + 紫青渐变。 */
    DARK("dark", "/css/dark.css", "settings.theme.dark"),

    /** 浅色：保持同一套主色以维持品牌一致性。 */
    LIGHT("light", "/css/light.css", "settings.theme.light"),

    /** 自定义：以暗色为基底，允许用户指定强调色。 */
    CUSTOM("custom", "/css/dark.css", "settings.theme.custom");

    private final String id;
    private final String stylesheet;
    private final String i18nKey;

    Theme(String id, String stylesheet, String i18nKey) {
        this.id = id;
        this.stylesheet = stylesheet;
        this.i18nKey = i18nKey;
    }

    public String id() {
        return id;
    }

    /** 类路径资源形式的主样式表。 */
    public String stylesheet() {
        return stylesheet;
    }

    public String i18nKey() {
        return i18nKey;
    }

    /** 是否为浅色系（用于选择图标与叠加强度）。 */
    public boolean isLight() {
        return this == LIGHT;
    }

    public static Theme fromId(String id) {
        if (id != null) {
            for (Theme theme : values()) {
                if (theme.id.equalsIgnoreCase(id)) {
                    return theme;
                }
            }
        }
        return DARK;
    }
}
