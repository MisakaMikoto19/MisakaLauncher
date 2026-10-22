package com.misaka.launcher.ui.component;

import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.log.CrashAdvisor;
import com.misaka.launcher.ui.shell.AppShell;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 「游戏异常退出」诊断对话框。
 *
 * <p><b>为什么需要它</b>：过去崩溃只给一句「退出码 1」加一个日志路径，
 * 用户既不知道发生了什么、也不知道该做什么 —— 而真正的原因（显存不足、模组冲突、
 * Java 版本不符）埋在几十万行的崩溃报告与游戏输出里。这里把 {@link CrashAdvisor}
 * 的结论直接摆到眼前，并给出可以照做的下一步。</p>
 *
 * <p><b>刻意不展示崩溃报告原文</b>：那是给整合包作者看的，几百 KB 的堆栈堆进对话框
 * 只会把用户劝退；需要原文的人点「打开崩溃报告」即可。复制出去的也仅是按当前语言
 * 渲染后的结论与建议，不含原始日志（日志里可能回显启动参数）。</p>
 */
public final class CrashReportDialog {

    private static final Logger log = LoggerFactory.getLogger(CrashReportDialog.class);

    /** 对话框内文字的最大宽度：太宽会让人读串行，且与主窗口的卡片观感不一致。 */
    private static final double CONTENT_WIDTH = 620;

    private CrashReportDialog() {
    }

    /**
     * 打开对话框并阻塞，直到用户关闭。
     *
     * @param shell        主窗口外壳
     * @param instanceName 出现问题的实例名（展示用）
     * @param exitCode     游戏进程退出码
     * @param crashReport  本次新产生的崩溃报告；没有则为 {@code null}
     * @param gameLog      本次游戏输出日志；没有则为 {@code null}
     * @param diagnosis    {@link CrashAdvisor} 的判定结果
     */
    public static void show(AppShell shell, String instanceName, int exitCode,
                            Path crashReport, Path gameLog, CrashAdvisor.Diagnosis diagnosis) {
        Stage stage = new Stage();
        stage.initOwner(shell.stage());
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(I18n.t("crash.title"));

        Label title = Ui.label(I18n.t("crash.title"), "page-title");
        Label meta = Ui.label(I18n.t("crash.meta", instanceName, String.valueOf(exitCode)), "mute-2");

        VBox adviceBox = new VBox(8);
        for (CrashAdvisor.Advice advice : diagnosis.advice()) {
            adviceBox.getChildren().add(adviceItem(advice));
        }

        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_LEFT);
        if (crashReport != null) {
            Path report = crashReport;
            actions.getChildren().add(Ui.primaryButton("crash.openReport",
                    event -> openFile(shell, report)));
        } else {
            // 报告不存在时不留一颗点了没反应的按钮：直接说明这次没生成
            Label missing = Ui.label(I18n.t("crash.reportMissing"), "mute-2");
            missing.setWrapText(true);
            missing.setMaxWidth(220);
            actions.getChildren().add(missing);
        }
        Path logDir = gameLog == null ? null : gameLog.getParent();
        if (logDir != null) {
            Path dir = logDir;
            actions.getChildren().add(Ui.ghostButton("crash.openLogs",
                    event -> openFolder(shell, dir)));
        }
        actions.getChildren().add(Ui.ghostButton("crash.copy", event -> {
            copyToClipboard(describe(instanceName, exitCode, crashReport, diagnosis));
            shell.toast().show(I18n.t("crash.copied"), Toast.Kind.SUCCESS);
        }));
        if (diagnosis.graphicsRelated()) {
            // 与显卡相关的结论，最可能需要动的是启动设置里的「独显优先」与资源包/光影
            actions.getChildren().add(Ui.ghostButton("crash.openSettings",
                    event -> shell.openSettings("settings.launch")));
        }
        actions.getChildren().add(Ui.grow());
        actions.getChildren().add(Ui.ghostButton("crash.close", event -> stage.close()));

        VBox content = new VBox(12, title, meta,
                Ui.callout(I18n.t("crash.hint"), true),
                adviceBox, actions);
        content.getStyleClass().add("glass-card");
        content.setPrefWidth(CONTENT_WIDTH);

        StackPane root = new StackPane(content);
        root.setPadding(new Insets(18));
        root.setStyle("-fx-background-color: -c-bg-alt;");

        stage.setScene(new Scene(root));
        // 鐙珛绐楀彛鏈夎嚜宸辩殑 Scene锛屼笉浼氱户鎵夸富绐楀彛鐨勫瓧浣揱r
        com.misaka.launcher.ui.theme.Fonts.applyToScene(stage.getScene());
        stage.setResizable(false);
        // 统一装修：应用图标 + 主窗口样式表
        Dialogs.style(stage, shell);
        stage.showAndWait();
    }

    /** 单条建议：一句话结论 + 具体怎么做。 */
    private static Node adviceItem(CrashAdvisor.Advice advice) {
        Label title = Ui.label(I18n.t(advice.titleKey()), "section-title");
        title.setWrapText(true);
        Label detail = Ui.label(I18n.t(advice.detailKey()), "mute-2");
        detail.setWrapText(true);
        detail.setMaxWidth(Double.MAX_VALUE);
        VBox item = new VBox(4, title, detail);
        item.getStyleClass().add("advice-item");
        item.setMaxWidth(Double.MAX_VALUE);
        return item;
    }

    /**
     * 组装可复制的诊断文本。
     *
     * <p>只含结论、建议与文件路径，<b>不含</b>原始日志 —— 游戏输出可能回显启动参数，
     * 而用户往往会把这段文本贴到公开的求助帖里。</p>
     */
    private static String describe(String instanceName, int exitCode, Path crashReport,
                                   CrashAdvisor.Diagnosis diagnosis) {
        StringBuilder sb = new StringBuilder(I18n.t("crash.title")).append('\n');
        sb.append(I18n.t("crash.meta", instanceName, String.valueOf(exitCode))).append('\n');
        if (crashReport != null) {
            sb.append(I18n.t("crash.openReport")).append(": ").append(crashReport).append('\n');
        }
        for (CrashAdvisor.Advice advice : diagnosis.advice()) {
            sb.append("- ").append(I18n.t(advice.titleKey())).append('\n');
            sb.append("  ").append(I18n.t(advice.detailKey())).append('\n');
        }
        return sb.toString();
    }

    private static void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** 用系统默认程序打开文件；失败时退化为把路径提示给用户，至少他还能自己去找。 */
    private static void openFile(AppShell shell, Path file) {
        try {
            java.awt.Desktop.getDesktop().open(file.toFile());
        } catch (Exception e) {
            log.warn("打开崩溃报告失败：{}", e.getMessage());
            shell.toast().show(file.toString(), Toast.Kind.INFO);
        }
    }

    private static void openFolder(AppShell shell, Path dir) {
        try {
            java.awt.Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            log.warn("打开日志目录失败：{}", e.getMessage());
            shell.toast().show(dir.toString(), Toast.Kind.INFO);
        }
    }
}
