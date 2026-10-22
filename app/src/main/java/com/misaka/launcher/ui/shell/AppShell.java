package com.misaka.launcher.ui.shell;

import com.misaka.launcher.app.LauncherServices;
import com.misaka.launcher.app.update.LauncherUpdater;
import com.misaka.launcher.ui.component.Toast;
import com.misaka.launcher.ui.theme.BackgroundManager;
import com.misaka.launcher.ui.theme.ThemeManager;
import javafx.stage.Stage;

/**
 * 主窗口对页面暴露的能力。
 * <p>页面只依赖这个接口，而不是直接依赖 MainWindow —— 这样页面之间不会互相引用，
 * 也不会因为窗口结构变化而需要改动视图；测试时还可以用桩实现替换。</p>
 */
public interface AppShell {

    /** 核心服务集合。 */
    LauncherServices services();

    /** 轻提示。 */
    Toast toast();

    /** 任务中心（后台执行 + 状态栏进度联动）。 */
    TaskCenter tasks();

    /**
     * 启动器自更新流程（后台检查 / 下载 / 启动新版）。
     * <p>检查在后台线程做，结果回到 JavaFX 线程后才弹对话框。</p>
     */
    LauncherUpdater updates();

    /**
     * 是否有游戏进程正在运行。
     * <p>更新启动器要退出当前进程，而退出路径会顺带结束游戏进程，
     * 所以更新前必须先问这一句，不能拿玩家的一局游戏换一次更新。</p>
     */
    default boolean gameRunning() {
        return false;
    }

    /**
     * 启动器自更新所处的阶段（见 {@link TaskCenter.SelfUpdatePhase}）。
     * <p>页面据此把「启动游戏 / 安装版本 / 同步模组」等入口置灰：更新结束时启动器会退出自己，
     * 正在写盘或正在跑的游戏都会被一起带走。</p>
     */
    default TaskCenter.SelfUpdatePhase selfUpdatePhase() {
        return TaskCenter.SelfUpdatePhase.IDLE;
    }

    /**
     * 自更新是否正在进行。
     * <p>页面里判断入口可用性用这个，而不是自己比较阶段 —— 「更新中」的定义只保留一处。</p>
     */
    default boolean selfUpdating() {
        return selfUpdatePhase() != TaskCenter.SelfUpdatePhase.IDLE;
    }

    /** 切换页面。 */
    void navigate(String pageId);

    /**
     * 标记页面需要重建。
     * <p>用于「切换实例」这类会改变页面数据源的场景：下次进入该页时会重新构建，
     * 而不是继续显示旧实例的数据。</p>
     */
    void invalidatePage(String pageId);

    /**
     * 打开设置页并定位到指定分页，例如 {@code "theme"}。
     * <p>供标题栏的「背景」快捷入口等直接跳到具体分页，避免让用户自己再找一遍。</p>
     *
     * @param tabId 分页标识；不存在时仅打开设置页
     */
    void openSettings(String tabId);

    /**
     * 把窗口恢复为默认尺寸并居中。
     * <p>无边框窗口被拉伸或拖偏后，边缘缩放热区可能落到屏幕外而无法抓回，
     * 因此需要这个显式恢复入口（设置页「重置窗口尺寸」按钮）。</p>
     */
    void resetWindowSize();

    /** 当前页面 id。 */
    String currentPage();

    Stage stage();

    ThemeManager themeManager();

    BackgroundManager backgroundManager();

    /**
     * 刷新外壳上的状态摘要（标题栏胶囊、状态栏信息）。
     * <p>实例、账户或设置变更后必须调用，否则展示会与实际配置不一致。</p>
     */
    void refreshShell();

    /**
     * 语言变更后重建静态文案。
     * <p>JavaFX 没有「自动重译」机制，已创建的标签需要显式刷新；
     * 页面本身在下次进入时会重建，因此这里只需处理外壳。</p>
     */
    void refreshTexts();

    /**
     * 状态栏主按钮的动作。
     * <p>启动流水线就绪后由 {@code LaunchPipeline} 注入；在此之前返回 null，
     * 状态栏会给出提示而不是静默无响应。</p>
     */
    Runnable launchHandler();

    /** 状态栏「停止」按钮的动作；未启动任何游戏时为 null。 */
    Runnable stopHandler();

    /** 便捷方法：后台执行并在结束后给出提示与回调。 */
    default void runTask(String key, TaskCenter.TaskBody body) {
        tasks().run(key, body, null);
    }
}
