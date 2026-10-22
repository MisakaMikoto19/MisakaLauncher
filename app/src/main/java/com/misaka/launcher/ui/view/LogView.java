package com.misaka.launcher.ui.view;

import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.log.LogCleaner;
import com.misaka.launcher.core.log.LogEntry;
import com.misaka.launcher.core.log.LogFiles;
import com.misaka.launcher.core.log.LogManager;
import com.misaka.launcher.ui.component.Icons;
import com.misaka.launcher.ui.component.Toast;
import com.misaka.launcher.ui.component.Ui;
import com.misaka.launcher.ui.shell.AppShell;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 日志视图。
 * <p>两类日志分开呈现：</p>
 * <ul>
 *   <li><b>启动器日志</b>：内存环形缓冲 + 文件落盘，支持实时追加与级别筛选；</li>
 *   <li><b>游戏日志</b>：每次启动一个文件，按文件浏览 —— 游戏输出量极大，
 *       全部塞进内存既不必要也影响性能。</li>
 * </ul>
 */
public final class LogView extends ScrollPane {

    private static final Logger log = LoggerFactory.getLogger(LogView.class);

    /** 界面最多保留的行数：再多会明显拖慢 TextArea 渲染。 */
    private static final int MAX_LINES = 2500;

    /**
     * 界面最多保留的字符数（估算值）。
     * <p>只封顶行数是不够的：安装器转发出来的行里有 {@code Args:}/{@code Classpath:} 这类
     * 数百字符的长行，2500 行最长能到 1MB，而 TextArea 每次文本变更都要整份重排，
     * 代价随字符数线性增长。字符数与行数一起封顶，单次重排代价才真的有界。</p>
     */
    private static final int MAX_CHARS = 300_000;

    /**
     * 单次刷屏最多处理的行数。
     * <p>安装器一秒能打上千行，一轮事件循环里全写完等于把 FX 线程独占到日志打完为止；
     * 分批处理后，剩余行留在队列里等下一轮，界面始终有机会响应输入与重绘。</p>
     */
    private static final int FLUSH_BATCH = 300;

    /**
     * 连续写入这么多行后整表重写一次。
     * <p>按行追加只会让 TextArea 里的文本单调增长，滑出缓冲区的旧行必须靠整表重写
     * 才能真正丢掉；间隔过小又会把省下来的开销重新花在重写上。</p>
     */
    private static final int REBUILD_AFTER_LINES = 1000;

    private final AppShell shell;

    private final ComboBox<String> sourceCombo = Ui.comboBox(
            List.of(I18n.t("logs.launcher"), I18n.t("logs.game")), I18n.t("logs.launcher"), this::onSourceChanged);
    private final ComboBox<String> levelCombo = Ui.comboBox(
            List.of(I18n.t("logs.level.all"), "INFO", "WARN", "ERROR"), I18n.t("logs.level.all"), null);
    private final TextField filterField = Ui.searchField("logs.filter", value -> rebuild());
    private final CheckBox autoScroll = Ui.checkBox("logs.autoScroll", true, null);
    private final TextArea logArea = new TextArea();
    private final ListView<Path> gameLogList = new ListView<>();
    private final Label statusLabel = Ui.label("", "mute-2");

    private final Deque<LogEntry> buffer = new ArrayDeque<>();
    /** 待落屏的行：发布线程只入队，FX 线程按批消费（见 {@link #flushPending()}）。 */
    private final Queue<LogEntry> pending = new ConcurrentLinkedQueue<>();
    /** 是否已排过一次刷屏任务；用它把日志洪水折叠成每轮事件循环一次文本变更。 */
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    /** 缓冲区已占用的估算字符数，配合 {@link #MAX_CHARS} 裁剪。 */
    private int bufferChars;
    /** 自上次整表重写以来追加的行数。 */
    private int linesSinceRebuild;
    private AutoCloseable subscription;
    private boolean showingGameLogs;
    /** 鼠标悬停在日志区内时暂停自动滚动（用户可能正在回看历史），移开后恢复。 */
    private boolean hoverPaused;

    public LogView(AppShell shell) {
        this.shell = shell;

        logArea.setEditable(false);
        // 必须换行：日志行可以很长，不换行会让内容宽度远超视口，
        // 于是在窄窗口下要么被裁掉、要么逼出横向滚动条
        logArea.setWrapText(true);
        logArea.getStyleClass().add("log-view");
        logArea.setPrefRowCount(26);
        VBox.setVgrow(logArea, Priority.ALWAYS);
        // 自动滚动默认开启（新日志一到就跳到最新一行）；鼠标悬停在日志区内时暂停，
        // 方便往上翻历史，移开后恢复并主动跳回最新处
        logArea.setOnMouseEntered(event -> hoverPaused = true);
        logArea.setOnMouseExited(event -> {
            hoverPaused = false;
            if (autoScroll.isSelected()) {
                scrollToEnd();
            }
        });

        gameLogList.setPrefHeight(360);
        gameLogList.setVisible(false);
        gameLogList.setManaged(false);
        gameLogList.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                HBox row = new HBox(10,
                        Ui.mono(item.getFileName().toString()),
                        Ui.label(Ui.humanSize(LogFiles.sizeOf(item)) + " · "
                                + Ui.formatRelative(LogFiles.lastModifiedMillis(item)), "mute-2"));
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
                setText(null);
            }
        });
        gameLogList.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, value) -> loadGameLog(value));

        VBox content = new VBox(12, buildToolbar(), logArea, gameLogList, buildFooter());
        Ui.mountScrollable(this, content, Ui.CONTENT_MAX_WIDTH);

        // 路由切页时整个节点会脱离场景图（ViewRouter.show 用 setAll 换掉唯一的子节点）：
        // 此时对 TextArea 的任何文本变更都是纯浪费 —— 成本随内容长度增长，而用户根本看不到，
        // 「装 Forge 时界面卡死」正发生在用户停在安装页、日志却照旧全量重排的场景下。
        // 因此不可见期间只记账不落屏，回到本页时用缓冲区重建一次。
        sceneProperty().addListener((observable, oldScene, scene) -> {
            if (scene != null) {
                rebuild();
            }
        });

        loadHistory();
        subscribe();
    }

    private Node buildToolbar() {
        Button clear = Ui.ghostButton("logs.clear", event -> {
            LogManager.clearBuffer();
            buffer.clear();
            pending.clear();
            bufferChars = 0;
            linesSinceRebuild = 0;
            logArea.clear();
        }, Icons.create(Icons.TRASH, 14));
        Button openFolder = Ui.ghostButton("logs.openFolder", event -> openLogsFolder(),
                Icons.create(Icons.FOLDER, 14));
        Button export = Ui.ghostButton("action.export", event -> exportLogs(),
                Icons.create(Icons.DOWNLOAD, 14));
        Button clean = Ui.ghostButton("settings.logs.cleanNow", event -> cleanNow(),
                Icons.create(Icons.REFRESH, 14));

        HBox row = new HBox(8,
                Ui.i18n("logs.title", "section-title"), Ui.spacer(8),
                sourceCombo, levelCombo, filterField, autoScroll, Ui.grow(),
                clean, export, openFolder, clear);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(filterField, Priority.SOMETIMES);
        filterField.setPrefWidth(180);
        return Ui.glassCard(0, row);
    }

    private Node buildFooter() {
        return new HBox(8, statusLabel, Ui.grow(),
                Ui.label(LogManager.launcherLogFile() == null ? ""
                        : LogManager.launcherLogFile().toString(), "mute-2"));
    }

    // ------------------------------------------------------------------

    private void loadHistory() {
        replaceBuffer(LogManager.history());
        rebuild();
    }

    private void subscribe() {
        subscription = LogManager.addListener(entry -> {
            // 回调来自日志发布线程（安装器转发的行就来自读取子进程输出的线程），
            // 这里只做两件廉价的事：入队、排一次刷屏。文本变更全部交给 FX 线程按批做。
            pending.add(entry);
            scheduleFlush();
        });
    }

    /**
     * 排一次刷屏任务（已排过就不再排）。
     * <p>用「合并调度」替代「一行一个 {@code Platform.runLater}」：后者会在日志洪水时
     * 往 FX 事件队列塞进数万个任务，界面连输入事件都插不进去。</p>
     */
    private void scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::flushPending);
        }
    }

    /**
     * 把积压的日志合并成尽可能少的文本变更。
     *
     * <p><b>「安装 Forge 时启动器卡死」的根因就在这里</b>：{@link TextArea} 每次文本变更都会
     * 把<em>全部</em>内容重新物化成字符串并整份重排（开启换行后还要重新断行），
     * 成本随内容长度线性增长。安装器在打补丁阶段会连打两万余行，逐行 append 就是两万次全量
     * 重排 —— 实测 FX 线程被 100% 占满数分钟，窗口长时间无响应且日志还在涨。
     * 改为按批追加后，文本变更次数从「行数」降到「事件循环轮数」；再叠加字符数封顶，
     * 单次重排的代价也有界。</p>
     *
     * <p>页面不在前台时只把行记进缓冲区、完全不碰 TextArea：安装过程中用户通常停在版本页，
     * 那些重排纯属浪费（返回本页时由 sceneProperty 监听重建）。</p>
     */
    private void flushPending() {
        flushScheduled.set(false);

        boolean visible = getScene() != null && !showingGameLogs;
        StringBuilder block = new StringBuilder(1024);
        int handled = 0;
        LogEntry entry;
        while (handled < FLUSH_BATCH && (entry = pending.poll()) != null) {
            handled++;
            addToBuffer(entry);
            if (!visible || !matches(entry)) {
                continue;
            }
            if (block.length() > 0) {
                block.append('\n');
            }
            block.append(entry.toLine());
        }

        if (visible && block.length() > 0) {
            logArea.appendText(logArea.getLength() > 0 ? "\n" + block : block.toString());
            statusLabel.setText(buffer.size() + " 行");
            if (autoScroll.isSelected() && !hoverPaused) {
                scrollToEnd();
            }
            linesSinceRebuild += handled;
            if (linesSinceRebuild >= REBUILD_AFTER_LINES) {
                // 整表重写一次，把已滑出窗口的旧行真正从 TextArea 里丢掉
                rebuild();
            }
        }

        if (!pending.isEmpty()) {
            // 还有积压，说明日志产出快于本轮的写入额度：留到下一轮继续，不占住当前时间片
            scheduleFlush();
        }
    }

    /** 重建缓冲区内容（例如初次加载历史）。 */
    private void replaceBuffer(List<LogEntry> entries) {
        buffer.clear();
        bufferChars = 0;
        for (LogEntry entry : entries) {
            buffer.addLast(entry);
            bufferChars += estimateChars(entry);
        }
        trimBuffer();
    }

    private void addToBuffer(LogEntry entry) {
        buffer.addLast(entry);
        bufferChars += estimateChars(entry);
        trimBuffer();
    }

    /** 按行数与字符数双重封顶裁剪缓冲区（只丢弃最旧的行）。 */
    private void trimBuffer() {
        while (buffer.size() > MAX_LINES || (bufferChars > MAX_CHARS && buffer.size() > 1)) {
            bufferChars -= estimateChars(buffer.removeFirst());
        }
        if (bufferChars < 0) {
            bufferChars = 0;
        }
    }

    /**
     * 估算一条日志落屏后的字符数。
     * <p>只用于裁剪，不需要精确 —— 量得准就得先渲染整行，反而把省下来的开销花回去了。
     * 正文与来源名都可能为空（{@code LogRecord} 允许），这里按 0 计。</p>
     */
    private static int estimateChars(LogEntry entry) {
        String message = entry.message();
        String logger = entry.loggerName();
        return (message == null ? 0 : message.length())
                + (logger == null ? 0 : logger.length()) + 32;
    }

    private boolean matches(LogEntry entry) {
        String level = levelCombo.getValue();
        if (level != null && !level.equals(I18n.t("logs.level.all"))) {
            if (entry.levelWeight() < weightOf(level)) {
                return false;
            }
        }
        String filter = filterField.getText();
        if (filter != null && !filter.isBlank()) {
            String needle = filter.toLowerCase(java.util.Locale.ROOT);
            return entry.message().toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || entry.loggerName().toLowerCase(java.util.Locale.ROOT).contains(needle);
        }
        return true;
    }

    private static int weightOf(String level) {
        return switch (level) {
            case "ERROR" -> 4;
            case "WARN" -> 3;
            default -> 2;
        };
    }

    private void rebuild() {
        StringBuilder builder = new StringBuilder(Math.min(bufferChars, MAX_CHARS) + 64);
        buffer.stream().filter(this::matches).forEach(entry -> builder.append(entry.toLine()).append('\n'));
        logArea.setText(builder.toString());
        statusLabel.setText(buffer.size() + " 行");
        linesSinceRebuild = 0;
        if (autoScroll.isSelected() && !hoverPaused) {
            scrollToEnd();
        }
    }

    private void scrollToEnd() {
        // 日志页整体还包在一层页面级 ScrollPane 里（工具栏 + 日志区 + 页脚一起滚）：
        // 只滚 TextArea 内部时，页面本身可能停在别处 —— 用户看到的就是「没停在最新」。
        // 两层都滚到底才能稳定停在最新一行
        logArea.setScrollTop(Double.MAX_VALUE);
        setVvalue(1.0);
    }

    private void onSourceChanged(String source) {
        showingGameLogs = source != null && source.equals(I18n.t("logs.game"));
        gameLogList.setVisible(showingGameLogs);
        gameLogList.setManaged(showingGameLogs);
        logArea.setVisible(!showingGameLogs);
        logArea.setManaged(!showingGameLogs);
        if (showingGameLogs) {
            refreshGameLogs();
        } else if (getScene() != null) {
            // 切回启动器日志：日志区里装的还是刚才那份游戏日志，必须重新渲染一遍
            // （游戏日志模式下 flushPending 按设计不往日志区写）
            rebuild();
        }
    }

    private void refreshGameLogs() {
        Path dir = shell.services().context().paths().gameLogsDir();
        List<Path> files = new ArrayList<>(LogFiles.listLogFiles(dir));
        files.sort((a, b) -> Long.compare(LogFiles.lastModifiedMillis(b), LogFiles.lastModifiedMillis(a)));
        gameLogList.getItems().setAll(files);
        statusLabel.setText(files.size() + " 个游戏日志文件");
        if (!files.isEmpty()) {
            gameLogList.getSelectionModel().selectFirst();
        }
    }

    /** 游戏日志可能很大，只读取末尾部分以免界面卡死。 */
    private void loadGameLog(Path file) {
        if (file == null) {
            return;
        }
        shell.tasks().runSilent("logs.game", listener -> {
            String content = readTail(file, 512 * 1024);
            Platform.runLater(() -> {
                logArea.setText(content);
                // 只读了末尾 512KB，直接滚到最新处
                scrollToEnd();
            });
        }, error -> log.debug("读取游戏日志失败: {}", error.getMessage()));
    }

    private static String readTail(Path file, int maxBytes) throws IOException {
        long size = Files.size(file);
        try (var channel = Files.newByteChannel(file)) {
            long start = Math.max(0, size - maxBytes);
            channel.position(start);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate((int) (size - start));
            channel.read(buffer);
            buffer.flip();
            return new String(buffer.array(), 0, buffer.limit(), StandardCharsets.UTF_8);
        }
    }

    private void openLogsFolder() {
        Path dir = shell.services().context().paths().logsDir();
        try {
            java.awt.Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            shell.toast().show(dir.toString());
        }
    }

    private void exportLogs() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(I18n.t("action.export"));
        chooser.setInitialFileName("misaka-logs.txt");
        java.io.File target = chooser.showSaveDialog(shell.stage());
        if (target == null) {
            return;
        }
        try {
            StringBuilder builder = new StringBuilder();
            buffer.forEach(entry -> builder.append(entry.toLine()).append('\n'));
            Files.writeString(target.toPath(), builder.toString(), StandardCharsets.UTF_8);
            shell.toast().show(I18n.t("logs.exported", target.getAbsolutePath()), Toast.Kind.SUCCESS);
        } catch (IOException e) {
            shell.toast().show(I18n.t("error.io", e.getMessage()), Toast.Kind.ERROR);
        }
    }

    private void cleanNow() {
        LauncherConfig.LogSettings settings = shell.services().config().log;
        LogCleaner.CleanResult result = LogCleaner.clean(
                shell.services().context().paths().logsDir(), settings, LogManager.launcherLogFile());
        shell.toast().show(I18n.t("settings.logs.cleanNow") + "：" + result.deletedFiles() + " 个文件，"
                + Ui.humanSize(result.freedBytes()), Toast.Kind.SUCCESS);
        refreshGameLogs();
    }

    /** 关闭订阅（页面被销毁时由主窗口调用；此处保留供后续扩展）。 */
    public void dispose() {
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception e) {
                log.debug("关闭日志订阅失败: {}", e.getMessage());
            }
            subscription = null;
        }
    }
}
