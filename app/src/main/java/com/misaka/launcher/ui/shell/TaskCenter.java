package com.misaka.launcher.ui.shell;

import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.task.CancelledException;
import com.misaka.launcher.core.task.ProgressListener;
import com.misaka.launcher.core.task.ProgressListeners;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyListProperty;
import javafx.beans.property.ReadOnlyListWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 任务中心：所有耗时操作的统一入口。
 *
 * <p>承担四件事：</p>
 * <ol>
 *   <li>把 core 层的阻塞调用丢到后台线程池，保证界面永不卡死；</li>
 *   <li>把 core 的进度回调切回 JavaFX 线程并做节流，避免高频重绘；</li>
 *   <li><b>逐任务追踪进度</b>：每个任务有自己的句柄（标题 / 消息 / 进度），
 *       并行任务互不覆盖 —— 这参考 IntelliJ 的运行组件：状态栏只在「恰好一个任务」时
 *       镜像它的进度，多于一个时退化为持续加载（波浪），逐个进度在展开面板里查看；</li>
 *   <li>暴露「忙碌 / 阶段文本 / 进度 / 任务列表」供状态栏绑定展示。</li>
 * </ol>
 *
 * <p>为什么不能像旧版那样让所有任务共用一个进度属性：并行下载与 Java 安装同时跑时，
 * 两者会互相覆盖对方的进度文本与百分比，用户看到的数字一直在跳、且不知道属于谁。</p>
 */
public final class TaskCenter {

    private static final Logger log = LoggerFactory.getLogger(TaskCenter.class);

    /** 后台任务体；通过 {@link ProgressListener} 上报进度，可抛出任意异常。 */
    public interface TaskBody {
        void run(ProgressListener listener) throws Exception;
    }

    /**
     * 任务结果。
     *
     * @param success   是否成功
     * @param cancelled 是否被用户取消
     * @param error     失败原因；成功时为 null
     */
    public record TaskResult(boolean success, boolean cancelled, Throwable error) {

        public boolean failed() {
            return !success && !cancelled;
        }
    }

    /**
     * 任务状态。
     *
     * <p>刻意区分 {@link #CANCELLED} 与 {@link #FAILED}：前者是用户自己停的（不该显示成错误、
     * 也不该弹报错），后者只有重试才有意义 —— 界面上的提示与配色完全不同。</p>
     */
    public enum TaskState {
        RUNNING, DONE, CANCELLED, FAILED
    }

    /**
     * 启动器自更新所处的阶段。
     *
     * <p>它不是「又一个忙碌标志」，而是<b>独占标志</b>：非 {@link #IDLE} 时不允许启动游戏、
     * 安装版本、同步模组等任何会写盘或改实例的操作（见 {@link #runTask}）。
     * 理由是更新结束时启动器要退出自己，而退出路径会结束游戏进程、并把还在跑的后台任务
     * 拦腰砍断 —— 写盘写一半留下的半成品，比干脆不做更糟。</p>
     */
    public enum SelfUpdatePhase {
        /** 没有在更新。 */
        IDLE,
        /**
         * 正在下载更新包。
         * <p>此时用户可以停止更新继续用当前版本，所以停止入口保持可用。</p>
         */
        DOWNLOADING,
        /**
         * 正在替换启动器自身文件并拉起新版本。
         * <p>包已经下载校验完，停下来也没有完整可用的旧版本了，停止入口据此置灰。</p>
         */
        INSTALLING
    }

    /** 「已结束但保留在详情面板里」的任务上限（保留是为了让用户能重试）。 */
    private static final int MAX_FINISHED_TASKS = 5;

    /**
     * 一个正在运行的任务的句柄。
     *
     * <p>进度取值与全局约定一致：{@code 0–1} 为确定进度，{@code -1} 表示不确定（波浪）。</p>
     */
    public static final class TaskHandle {

        private final ReadOnlyStringWrapper title;
        private final ReadOnlyStringWrapper message;
        private final ReadOnlyDoubleWrapper progress;
        private final ReadOnlyBooleanWrapper running;
        /**
         * 计数进度的原始数值，用于界面显示 {@code 12/300}。
         *
         * <p>必须与 {@link #progress} 并存：后者是「当前这一步的百分比」（按字节算），
         * 归一到 0–1 之后原始数字就丢了，而用户真正想知道的往往是「还剩几个文件」。</p>
         */
        private final ReadOnlyIntegerWrapper completedItems = new ReadOnlyIntegerWrapper(0);
        private final ReadOnlyIntegerWrapper totalItems = new ReadOnlyIntegerWrapper(0);
        /** 终态；{@link #RUNNING} 表示尚未结束。 */
        private final ReadOnlyObjectWrapper<TaskState> state =
                new ReadOnlyObjectWrapper<>(TaskState.RUNNING);
        /**
         * 重新执行本任务的入口，由 {@link TaskCenter} 在创建句柄时注入。
         *
         * <p>把任务体留在句柄里，用户才能在「停错了」或「失败了」之后一键重来，
         * 而不必自己回想刚才点的是哪个按钮、参数是什么。</p>
         */
        private Runnable restartAction;
        /**
         * 本任务自己的取消标志。
         *
         * <p>刻意不做成全局开关：全局开关会被「后启动的任务」清零，并发时先前的取消
         * 意图会凭空消失；任务侧也无法判断这个信号到底是不是给自己的
         * （真实故障：下载卡住时点「停止」毫无反应）。</p>
         */
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        /** 是否为「启动游戏」任务：状态栏据此把它与下载/安装区分开。 */
        private final boolean launch;
        /** 本任务正在改动的实例 id；与实例无关的任务（取版本清单等）为 null。 */
        private final String instanceId;

        private TaskHandle(String title, boolean launch, String instanceId) {
            this.title = new ReadOnlyStringWrapper(title);
            this.message = new ReadOnlyStringWrapper("");
            this.progress = new ReadOnlyDoubleWrapper(-1);
            this.running = new ReadOnlyBooleanWrapper(true);
            this.launch = launch;
            this.instanceId = instanceId;
        }

        public boolean isLaunch() {
            return launch;
        }

        /** 请求取消本任务。耗时循环会在下一个检查点退出。 */
        void requestCancel() {
            cancelled.set(true);
        }

        /** 本任务是否已被请求取消（由 {@link ProgressListener#isCancelled()} 轮询）。 */
        public boolean isCancelled() {
            return cancelled.get();
        }

        /** 本任务正在改动的实例 id；为 null 表示与具体实例无关。 */
        public String instanceId() {
            return instanceId;
        }

        public String getTitle() {
            return title.get();
        }

        public ReadOnlyStringProperty titleProperty() {
            return title.getReadOnlyProperty();
        }

        public String getMessage() {
            return message.get();
        }

        public ReadOnlyStringProperty messageProperty() {
            return message.getReadOnlyProperty();
        }

        public double getProgress() {
            return progress.get();
        }

        public ReadOnlyDoubleProperty progressProperty() {
            return progress.getReadOnlyProperty();
        }

        public boolean isRunning() {
            return running.get();
        }

        public ReadOnlyBooleanProperty runningProperty() {
            return running.getReadOnlyProperty();
        }

        /** 已完成项数（配合 {@link #totalItemsProperty()} 显示 {@code 12/300}）。 */
        public int getCompletedItems() {
            return completedItems.get();
        }

        public ReadOnlyIntegerProperty completedItemsProperty() {
            return completedItems.getReadOnlyProperty();
        }

        /** 总项数；&lt;= 0 表示本任务没有「项数」概念，界面不展示计数。 */
        public int getTotalItems() {
            return totalItems.get();
        }

        public ReadOnlyIntegerProperty totalItemsProperty() {
            return totalItems.getReadOnlyProperty();
        }

        public TaskState getState() {
            return state.get();
        }

        public ReadOnlyObjectProperty<TaskState> stateProperty() {
            return state.getReadOnlyProperty();
        }

        /** 本任务是否已结束（成功 / 被取消 / 失败都算）。 */
        public boolean isFinished() {
            return !running.get();
        }

        /** 是否可以重新执行（未运行且任务体还在）。 */
        public boolean canRestart() {
            return !running.get() && restartAction != null;
        }
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "misaka-task");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 正在运行的任务列表。
     * <p>只在 FX 线程读写（{@link #begin} / {@link #finish} 都包了 {@code Platform.runLater}），
     * 因此后台回调里不需要再加锁。</p>
     */
    private final ReadOnlyListWrapper<TaskHandle> tasks =
            new ReadOnlyListWrapper<>(FXCollections.observableArrayList());

    /**
     * 正在被后台任务改动的实例 id 集合（安装游戏 / 加载器 / 模组、同步模组等）。
     *
     * <p>启动入口据此把对应实例的启动按钮置灰，而<b>不影响其它实例</b>：
     * 判定是按实例 id 做的，切到别的已经装好的实例照样能启动。</p>
     */
    private final ObservableSet<String> busyInstanceIds =
            FXCollections.observableSet(new LinkedHashSet<>());

    private final ReadOnlyStringWrapper message = new ReadOnlyStringWrapper(I18n.t("status.ready"));
    private final ReadOnlyDoubleWrapper progress = new ReadOnlyDoubleWrapper(-1);
    private final ReadOnlyBooleanWrapper busy = new ReadOnlyBooleanWrapper(false);
    /** 是否有「启动游戏」任务在跑（下载/安装不算）。 */
    private final ReadOnlyBooleanWrapper launching = new ReadOnlyBooleanWrapper(false);
    /** 自更新所处的阶段；非 {@link SelfUpdatePhase#IDLE} 时其它任务一律被拒绝。 */
    private final ReadOnlyObjectWrapper<SelfUpdatePhase> selfUpdatePhase =
            new ReadOnlyObjectWrapper<>(SelfUpdatePhase.IDLE);

    private final AtomicInteger runningTasks = new AtomicInteger();

    public ObservableList<TaskHandle> getTasks() {
        return tasks.get();
    }

    public ReadOnlyListProperty<TaskHandle> tasksProperty() {
        return tasks.getReadOnlyProperty();
    }

    /**
     * 正在被后台任务改动的实例 id 集合。
     *
     * <p>供启动入口判断「这个实例现在能不能启动」：正在安装游戏 / 加载器 / 模组，
     * 或正在同步模组的实例必须先置灰 —— 改到一半启动必然失败。
     * 集合按实例 id 记录，因此互不牵连。</p>
     */
    public ObservableSet<String> busyInstanceIds() {
        return busyInstanceIds;
    }

    /** 该实例此刻是否正被后台任务改动（为空 / null 一律视为「不忙」）。 */
    public boolean isInstanceBusy(String instanceId) {
        return instanceId != null && !instanceId.isBlank() && busyInstanceIds.contains(instanceId);
    }

    public ReadOnlyStringProperty messageProperty() {
        return message.getReadOnlyProperty();
    }

    /** 进度：0–1；-1 表示不确定（转圈）。 */
    public ReadOnlyDoubleProperty progressProperty() {
        return progress.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty busyProperty() {
        return busy.getReadOnlyProperty();
    }

    /**
     * 是否有「启动游戏」任务在运行。
     * <p>与 {@link #busyProperty()} 的区别：下载、安装、扫描同样是忙碌，
     * 但它们不该让启动按钮显示成「启动中」——那会让用户以为游戏已经在起了。</p>
     */
    public ReadOnlyBooleanProperty launchingProperty() {
        return launching.getReadOnlyProperty();
    }

    /**
     * 自更新所处的阶段。
     * <p>供界面据此把写盘 / 启动入口置灰（见 {@code AppShell.selfUpdating()}），
     * 也供启动栏决定「停止」是否还可用。</p>
     */
    public ReadOnlyObjectProperty<SelfUpdatePhase> selfUpdatePhaseProperty() {
        return selfUpdatePhase.getReadOnlyProperty();
    }

    public SelfUpdatePhase selfUpdatePhase() {
        return selfUpdatePhase.get();
    }

    /** 自更新是否正在进行。 */
    public boolean isSelfUpdating() {
        return selfUpdatePhase.get() != SelfUpdatePhase.IDLE;
    }

    /**
     * 切换自更新阶段（由 {@code LauncherUpdater} 在更新流程里调用）。
     *
     * <p>允许从后台线程调用：更新任务在替换阶段是在工作线程上推进的，
     * 与本类的 {@link #cancel()} 同样自行切回 JavaFX 线程。</p>
     */
    public void setSelfUpdatePhase(SelfUpdatePhase phase) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> setSelfUpdatePhase(phase));
            return;
        }
        if (selfUpdatePhase.get() == phase) {
            return;
        }
        log.info("自更新阶段：{} → {}", selfUpdatePhase.get(), phase);
        selfUpdatePhase.set(phase);
    }

    /**
     * 是否有后台任务正在运行（含启动游戏、安装版本、同步模组、下载等）。
     *
     * <p>自更新开始前用它确认「没有别的活在跑」：更新结束时启动器要退出自己，
     * 会把没跑完的任务一起带走。面板里那些已结束（失败 / 已停止、等用户处理）的任务不算。</p>
     */
    public boolean hasRunningTasks() {
        for (TaskHandle handle : tasks.get()) {
            if (handle.isRunning()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行任务。
     *
     * @param titleKey 阶段文案的 i18n 键
     * @param body     任务体
     * @param onDone   结束回调（在 JavaFX 线程执行）；可为 null
     * @return 任务句柄；调用方需要「中途主动取消」时用它（例如设备码登录的取消按钮），
     *         不需要时忽略即可
     */
    public TaskHandle run(String titleKey, TaskBody body, Consumer<TaskResult> onDone) {
        return runTask(titleKey, body, onDone, false, null);
    }

    /** 执行「启动游戏」任务：状态栏会据此把启动按钮切换为「启动中」。 */
    public void runLaunch(String titleKey, TaskBody body, Consumer<TaskResult> onDone) {
        runTask(titleKey, body, onDone, true, null);
    }

    /**
     * 执行会改动某个实例的任务（安装游戏、安装 / 移除加载器、安装 / 更新模组、同步模组）。
     *
     * <p>与 {@link #run} 的唯一区别是声明了「正在动哪个实例」：这段时间里该实例的
     * 启动入口会被置灰（见 {@link #busyInstanceIds()}），因为它改到一半必然启动不起来。
     * 不传实例 id 就没有这层保护，因此会写盘的任务都应该走这个入口。</p>
     *
     * @param instanceId 目标实例 id；为 null / 空表示与具体实例无关
     */
    public void runForInstance(String instanceId, String titleKey, TaskBody body,
                               Consumer<TaskResult> onDone) {
        runTask(titleKey, body, onDone, false,
                instanceId == null || instanceId.isBlank() ? null : instanceId);
    }

    /**
     * 执行「启动器自更新」任务，并把句柄交给调用方。
     *
     * <p>与 {@link #run} 的两点区别：① 它是自更新期间<b>唯一</b>被允许启动的任务
     * （别的入口在这一阶段都被 {@link #runTask} 挡下）；② 调用方要拿句柄盯住它 ——
     * 长时间没有进展时自动停止（见 {@code LauncherUpdater} 里的停滞看门狗）。</p>
     */
    public TaskHandle runSelfUpdate(String titleKey, TaskBody body, Consumer<TaskResult> onDone) {
        return runTask(titleKey, body, onDone, false, null, true);
    }

    private TaskHandle runTask(String titleKey, TaskBody body, Consumer<TaskResult> onDone, boolean launch,
                               String instanceId) {
        return runTask(titleKey, body, onDone, launch, instanceId, false);
    }

    private TaskHandle runTask(String titleKey, TaskBody body, Consumer<TaskResult> onDone, boolean launch,
                               String instanceId, boolean selfUpdate) {
        if (!selfUpdate && isSelfUpdating()) {
            // 自更新期间不允许启动任何别的作业：更新结束时启动器会退出自己，
            // 把没写完的任务一起带走（安装到一半的游戏目录、同步了一半的模组）。
            // 界面上这些入口此刻都是置灰的，走到这里说明是竞态或新入口漏了置灰 ——
            // 静默拒绝并留下日志，而不是让它悄悄写坏盘
            log.warn("自更新进行中，拒绝启动任务：{}", titleKey);
            return null;
        }
        String title = I18n.t(titleKey);
        TaskHandle handle = new TaskHandle(title, launch, instanceId);
        // 注入「重新执行」入口：同一个任务体 + 同一份回调，重试才算真重试
        handle.restartAction = () -> runTask(titleKey, body, onDone, launch, instanceId);
        begin(handle);

        ProgressListener listener = createListener(handle);

        pool.submit(() -> {
            TaskResult result;
            try {
                body.run(listener);
                result = new TaskResult(true, false, null);
            } catch (CancelledException e) {
                result = new TaskResult(false, true, null);
                log.info("任务已取消：{}", title);
            } catch (Exception e) {
                result = new TaskResult(false, false, e);
                log.warn("任务失败：{} —— {}", title, e.toString());
            } catch (Throwable error) {
                // Error 也必须兜住：打包运行时缺模块（NoClassDefFoundError）、
                // 内存不足（OutOfMemoryError）这类问题都是 Error。
                // 不兜的话任务永远不会 finish —— 界面停在「处理中…」不动、日志里还什么都没有，
                // 排查成本极高（真实踩过：缺 jdk.httpserver 导致浏览器登录一直卡住）。
                result = new TaskResult(false, false, new IllegalStateException(
                        error.getClass().getSimpleName() + ": " + error.getMessage(), error));
                log.error("任务因错误中断：{} —— {}", title, error.toString(), error);
            }
            TaskResult finalResult = result;
            Platform.runLater(() -> {
                finish(handle, finalResult);
                if (onDone != null) {
                    onDone.accept(finalResult);
                }
            });
        });
        return handle;
    }

    /** 执行任务并仅在失败时回调（用于无需成功提示的场景）。 */
    public void runSilent(String titleKey, TaskBody body, Consumer<Throwable> onFailure) {
        run(titleKey, body, result -> {
            if (result.failed() && onFailure != null) {
                onFailure.accept(result.error());
            }
        });
    }

    /**
     * 请求停止所有正在运行的后台任务。
     *
     * <p>状态栏上的「停止」只有一颗按钮，它的语义就是「把正在忙的活停下来」，
     * 因此这里取消全部运行中的任务，而不是猜哪一个是「当前任务」。</p>
     *
     * <p>取消只是置位：耗时循环会在下一个检查点退出。core 侧的检查点现在覆盖了
     * 阻塞读流（{@code HttpUtils} 的停滞看门狗）与批量等待（轮询式 {@code future.get}），
     * 所以「点了没反应」不会再出现。</p>
     */
    public void cancel() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::cancel);
            return;
        }
        for (TaskHandle handle : tasks.get()) {
            if (!handle.isRunning()) {
                continue;
            }
            handle.requestCancel();
            // 立即给出可见反馈：否则用户点了「停止」之后界面毫无变化，
            // 只能得出「这个按钮没用」的结论（真实反馈）
            handle.message.set(I18n.t("status.cancelling"));
        }
        refreshAggregate();
    }

    /**
     * 取消单个任务（详情面板里的逐项停止）。
     *
     * <p>与总停止的区别：并行跑着「装游戏 + 同步模组」时，用户可能只想停掉其中一个。
     * 需在 JavaFX 线程调用。</p>
     */
    public void cancel(TaskHandle handle) {
        if (handle == null || !handle.isRunning()) {
            return;
        }
        handle.requestCancel();
        handle.message.set(I18n.t("status.cancelling"));
        refreshAggregate();
    }

    /**
     * 重新执行一个已结束的任务。
     *
     * <p>用句柄里保存的任务体重新走一遍 {@code runTask}，因此会生成一个新句柄 ——
     * 「重试」必须是一次真正的全新执行，而不是把旧句柄的状态重置回运行中
     *（旧句柄的失败信息、进度都该作为历史被替换掉）。需在 JavaFX 线程调用。</p>
     */
    public void restart(TaskHandle handle) {
        if (handle == null || handle.isRunning() || handle.restartAction == null) {
            return;
        }
        Runnable action = handle.restartAction;
        // 先摘掉旧句柄：否则面板上会同时出现「失败的那次」与「重试的这次」两行
        tasks.get().remove(handle);
        syncBusyInstances();
        refreshAggregate();
        log.info("重试任务：{}", handle.getTitle());
        action.run();
    }

    /** 从详情面板里移除一个已结束的任务（用户选择不再处理）。需在 JavaFX 线程调用。 */
    public void dismiss(TaskHandle handle) {
        if (handle == null || handle.isRunning()) {
            return;
        }
        tasks.get().remove(handle);
        syncBusyInstances();
        refreshAggregate();
    }

    /** 是否有任务已被请求取消。需在 JavaFX 线程调用。 */
    public boolean isCancellationRequested() {
        for (TaskHandle handle : tasks.get()) {
            if (handle.isCancelled()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------

    private void begin(TaskHandle handle) {
        int count = runningTasks.incrementAndGet();
        Platform.runLater(() -> {
            tasks.add(handle);
            syncBusyInstances();
            refreshAggregate();
        });
        log.debug("任务开始（并发 {}）：{}", count, handle.getTitle());
    }

    private void finish(TaskHandle handle, TaskResult result) {
        int count = runningTasks.decrementAndGet();
        // 先写终态再翻 running：界面上监听 runningProperty 的地方会在回调里读 state，
        // 顺序反了就会永远读到 RUNNING
        handle.state.set(result.cancelled() ? TaskState.CANCELLED
                : result.failed() ? TaskState.FAILED : TaskState.DONE);
        handle.running.set(false);
        if (result.failed() && result.error() != null) {
            // 把失败原因留在句柄上：详情面板要能直接看到「为什么失败」，
            // 而不是只有一句「失败」然后让用户去翻日志
            handle.message.set(I18n.t("task.failedWith", String.valueOf(result.error().getMessage())));
        }
        Platform.runLater(() -> {
            // 成功的任务直接消失；失败 / 被取消的留在面板里 —— 它们需要用户处理
            //（重试或忽略），凭空消失会让「刚才到底停没停成功」无从确认
            if (result.success()) {
                tasks.remove(handle);
            } else {
                trimFinishedHandles();
            }
            syncBusyInstances();
            refreshAggregate();
        });
        if (count <= 0) {
            runningTasks.set(0);
        }
        log.debug("任务结束（剩余 {}）：{}", count, handle.getTitle());
    }

    /** 限制「已结束但保留在面板里」的任务数量，避免面板退化成越来越长的历史记录。 */
    private void trimFinishedHandles() {
        List<TaskHandle> finished = new ArrayList<>();
        for (TaskHandle handle : tasks.get()) {
            if (handle.isFinished()) {
                finished.add(handle);
            }
        }
        for (int i = 0; i < finished.size() - MAX_FINISHED_TASKS; i++) {
            tasks.get().remove(finished.get(i));
        }
    }

    /**
     * 按当前任务列表重算「哪些实例正忙」。
     * <p>只在真的变了才改集合：这个集合被启动按钮监听，进度刷新期间若反复
     * 清空再填回，会把监听刷爆（进度回调是 100ms 一次的高频路径）。</p>
     */
    private void syncBusyInstances() {
        Set<String> current = new LinkedHashSet<>();
        for (TaskHandle handle : tasks.get()) {
            // 只看正在跑的：面板里可能留着已停止 / 已失败的任务，它们并没有在动这个实例
            if (handle.isRunning() && handle.instanceId() != null) {
                current.add(handle.instanceId());
            }
        }
        if (current.equals(new LinkedHashSet<>(busyInstanceIds))) {
            return;
        }
        busyInstanceIds.retainAll(current);
        busyInstanceIds.addAll(current);
    }

    /**
     * 汇总状态栏展示：恰好一个任务 → 直接镜像它；多于一个 → 只显示波浪与数量。
     *
     * <p>多任务时「故意不给百分比」：聚合多个任务的进度没有意义（权重未知），
     * 用户想看细节就展开面板，那里每个任务各自有自己的进度。</p>
     */
    private void refreshAggregate() {
        // 只统计正在跑的：面板里可能留着已停止 / 已失败的任务（供用户重试与查看原因），
        // 它们不该继续让状态栏显示「忙碌」
        List<TaskHandle> running = new ArrayList<>();
        for (TaskHandle handle : tasks.get()) {
            if (handle.isRunning()) {
                running.add(handle);
            }
        }
        launching.set(running.stream().anyMatch(TaskHandle::isLaunch));
        if (running.isEmpty()) {
            busy.set(false);
            message.set(I18n.t("status.ready"));
            progress.set(-1);
            return;
        }
        busy.set(true);
        if (running.size() == 1) {
            TaskHandle only = running.get(0);
            message.set(only.getMessage());
            progress.set(only.getProgress());
            return;
        }
        message.set(I18n.t("task.runningMany", running.size()));
        progress.set(-1);
    }

    /** 构造回调监听器：更新任务自己的句柄 + 切回 FX 线程 + 100ms 节流 + 合并取消信号。 */
    private ProgressListener createListener(TaskHandle handle) {
        ProgressListener target = new ProgressListener() {
            @Override
            public void onStage(String stage) {
                if (stage == null || stage.isBlank()) {
                    return;
                }
                Platform.runLater(() -> {
                    handle.message.set(stage);
                    handle.progress.set(-1);
                    refreshAggregate();
                });
            }

            @Override
            public void onItemProgress(int completedItems, int totalItems) {
                if (totalItems <= 0) {
                    return;
                }
                // 计数进度本身就是低频事件（每个文件一次），不必节流；
                // 也不必刷新聚合状态 —— 状态栏只显示百分比，不显示项数
                Platform.runLater(() -> {
                    handle.completedItems.set(completedItems);
                    handle.totalItems.set(totalItems);
                });
            }

            @Override
            public void onProgress(long completed, long total) {
                if (total <= 0) {
                    return;
                }
                double value = Math.max(0, Math.min(1.0, completed / (double) total));
                Platform.runLater(() -> {
                    handle.progress.set(value);
                    refreshAggregate();
                });
            }

            @Override
            public void onMessage(String stageMessage) {
                if (stageMessage == null || stageMessage.isBlank()) {
                    return;
                }
                Platform.runLater(() -> {
                    handle.message.set(stageMessage);
                    refreshAggregate();
                });
            }

            @Override
            public boolean isCancelled() {
                return handle.isCancelled();
            }
        };
        return ProgressListeners.throttled(target, 100L);
    }

    /** 关闭线程池（应用退出时调用）。 */
    public void shutdown() {
        pool.shutdownNow();
    }
}
