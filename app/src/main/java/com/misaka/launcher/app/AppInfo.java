package com.misaka.launcher.app;

import com.misaka.launcher.core.BuildInfo;
import com.misaka.launcher.core.i18n.I18n;

/**
 * 应用元信息。
 *
 * <p>版本号不在本类里判断：一律转给 core 的 {@link BuildInfo#version()}，它读的是构建期由
 * {@code gradle.properties} 的 {@code misaka.version} 生成的资源。这样界面（标题栏、侧栏、
 * 状态栏、设置页）与 {@code --version}、User-Agent 用的是同一个值，升版本号只改一处。</p>
 */
public final class AppInfo {

    private AppInfo() {
    }

    /** 版本号，例如 {@code 1.0.0}。 */
    public static String version() {
        return BuildInfo.version();
    }

    /**
     * 带应用名与版本号的完整标题，例如 {@code Misaka Launcher v1.0.0}。
     * <p>版本号一律带 {@code v} 前缀：界面上（标题栏、侧边栏底部、底部状态栏）
     * 显示的是同一个口径，不出现「有的带 v、有的不带」。</p>
     */
    public static String title() {
        return I18n.t("app.name") + " v" + version();
    }
}
