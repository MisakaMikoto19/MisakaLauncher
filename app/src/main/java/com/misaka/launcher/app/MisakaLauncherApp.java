package com.misaka.launcher.app;

import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.ui.component.AppIcon;
import com.misaka.launcher.ui.shell.MainWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaFX 应用入口。
 * <p>生命周期分工：</p>
 * <ul>
 *   <li>{@link #init()}：在 FX 线程启动前完成引导（配置 / 日志 / 路径 / 语言），
 *       这一步可能读盘与创建目录，放在这里不会阻塞界面渲染；</li>
 *   <li>{@link #start(Stage)}：构建主窗口；</li>
 *   <li>{@link #stop()}：保存窗口状态、关闭线程池与日志。</li>
 * </ul>
 */
public class MisakaLauncherApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(MisakaLauncherApp.class);

    private LauncherContext context;
    private MainWindow mainWindow;

    @Override
    public void init() {
        context = LauncherContext.boot();
        log.info("JavaFX 应用初始化完成");
    }

    @Override
    public void start(Stage stage) {
        // 未捕获异常不应导致启动器整体退出：记录并向用户提示即可
        Thread.currentThread().setUncaughtExceptionHandler((thread, error) ->
                log.error("界面线程出现未捕获异常", error));

        try {
            // 必须在 show() 之前：任务栏条目在窗口首次显示时就由系统创建，
            // 之后补图标不会刷新已存在的条目
            AppIcon.apply(stage);
            mainWindow = new MainWindow(stage, context);
            mainWindow.show();
        } catch (RuntimeException e) {
            log.error("主窗口构建失败", e);
            Platform.exit();
        }
    }

    @Override
    public void stop() {
        try {
            if (mainWindow != null) {
                mainWindow.shutdown();
            } else if (context != null) {
                context.shutdown();
            }
        } catch (RuntimeException e) {
            log.warn("关闭时发生异常: {}", e.getMessage());
        }
        // 确保后台线程不会阻止进程退出
        Platform.exit();
    }

    /** 语言变更后需要刷新界面文案（供设置页调用）。 */
    public static void onLanguageChanged() {
        log.info("界面语言已切换为 {}", I18n.languageTag());
    }
}
