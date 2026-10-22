package com.misaka.launcher.app;

import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.log.LogManager;
import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动入口包装类。
 * <p>JavaFX 位于 classpath（非 module-path）时，直接以继承 {@link Application} 的类作为主类
 * 会触发 JVM 的模块校验失败（{@code JavaFX runtime components are missing}）。
 * 因此统一经由本类间接启动，保持打包方式与运行方式在所有平台一致。</p>
 * <p>同时提供若干非图形化入口，便于构建流水线做启动自检：</p>
 * <ul>
 *   <li>{@code --version}：打印版本后退出；</li>
 *   <li>{@code --dump-info}：完成引导（配置、日志、国际化、路径解析）并打印摘要后退出，
 *       可用于验证安装包在无图形环境下仍能正常初始化。</li>
 * </ul>
 */
public final class MainLauncher {

    private MainLauncher() {
    }

    public static void main(String[] args) {
        if (args != null && args.length > 0) {
            switch (args[0]) {
                case "--version", "-v" -> {
                    // 与界面、jar 清单同一个来源（见 AppInfo），不再另立常量
                    System.out.println("Misaka Launcher " + AppInfo.version());
                    return;
                }
                case "--dump-info" -> {
                    System.exit(dumpInfo());
                    return;
                }
                default -> {
                    // 其余参数原样交给 JavaFX
                }
            }
        }
        Application.launch(MisakaLauncherApp.class, args);
    }

    /**
     * 引导自检：完整走一遍配置、日志、国际化与路径解析，输出摘要。
     *
     * @return 进程退出码，0 表示正常
     */
    private static int dumpInfo() {
        try {
            LauncherContext context = LauncherContext.boot();
            Logger log = LoggerFactory.getLogger(MainLauncher.class);
            LauncherConfig config = context.config();

            System.out.println("version          = " + AppInfo.version());
            System.out.println("paths            = " + context.paths().describe());
            System.out.println("language         = " + I18n.languageTag() + " (" + I18n.t("app.name") + ")");
            System.out.println("theme            = " + config.theme);
            System.out.println("instances        = " + config.instances.size());
            for (LauncherConfig.InstanceEntry entry : config.instances) {
                System.out.println("  - instance     = " + entry.name + " | version=" + entry.versionId
                        + " | dir=" + entry.gameDir);
            }
            System.out.println("accounts         = " + config.accounts.size());
            for (LauncherConfig.AccountEntry entry : config.accounts) {
                System.out.println("  - account      = " + entry.username + " | type=" + entry.type);
            }
            System.out.println("javaRuntimes     = " + config.java.runtimes.size());
            System.out.println("downloadSource   = " + config.download.source);
            System.out.println("syncServer       = " + config.sync.serverAddress + ":" + config.sync.serverPort);
            System.out.println("logFile          = " + LogManager.launcherLogFile());

            log.info("自检完成");
            context.shutdown();
            return 0;
        } catch (Throwable t) {
            System.err.println("自检失败: " + t);
            t.printStackTrace(System.err);
            return 1;
        }
    }
}
