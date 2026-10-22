package com.misaka.launcher.ui.component;

import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.update.UpdateInfo;
import com.misaka.launcher.core.update.ZooKeeperUpdateClient;
import com.misaka.launcher.ui.shell.AppShell;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * 「发现新版本」对话框。
 *
 * <p>交互上刻意保持三点：</p>
 * <ul>
 *   <li><b>默认不动手</b>：三个选项里「稍后」是关闭窗口时的默认结果，
 *       点叉号或按 ESC 都不会偷偷开始下载 —— 更新会替换启动器文件，必须用户明确同意；</li>
 *   <li><b>「跳过此版本」只跳过这一个版本</b>：服务端再发新版照常提示，
 *       否则一次「跳过」会变成永久静音，用户永远收不到更新；</li>
 *   <li><b>更新说明可选中复制</b>：用只读文本域而不是标签，方便用户把变更内容贴出去求助。</li>
 * </ul>
 */
public final class UpdateDialog {

    /** 用户的选择。 */
    public enum Choice {
        /** 立即下载并启动新版。 */
        UPDATE,
        /** 稍后再说（本次不再提示）。 */
        LATER,
        /** 跳过这个版本。 */
        SKIP
    }

    /** 与崩溃诊断对话框保持一致的内容宽度，两个弹窗观感统一。 */
    private static final double CONTENT_WIDTH = 620;

    /** 更新说明区域高度：够看几行，又不至于把对话框撑满屏幕。 */
    private static final double NOTES_HEIGHT = 180;

    private UpdateDialog() {
    }

    /**
     * 打开对话框并阻塞，直到用户做出选择。
     *
     * @param shell 主窗口外壳（用于取父窗口与样式表）
     * @param info  检查结果，必须包含最新版本
     * @return 用户的选择；直接关闭一律视为 {@link Choice#LATER}
     */
    public static Choice show(AppShell shell, UpdateInfo info) {
        Stage stage = new Stage();
        stage.initOwner(shell.stage());
        stage.initModality(Modality.WINDOW_MODAL);
        // 标题带上产品显示名称：ZooKeeper 上登记的就是这个名字，
        // 用户一看就知道这个更新属于哪个产品（服务地址可自定义，可能同时挂多个产品）
        stage.setTitle(ZooKeeperUpdateClient.DISPLAY_NAME + " · " + I18n.t("update.dialog.title"));

        Choice[] choice = {Choice.LATER};

        Label title = Ui.label(I18n.t("update.dialog.title"), "page-title");
        Label meta = Ui.label(I18n.t("update.dialog.version", info.currentLabel(), info.latestLabel(),
                info.channel() == null || info.channel().isBlank() ? "stable" : info.channel()), "mute-2");

        Label notesTitle = Ui.sectionTitle(I18n.t("update.dialog.notes"));
        TextArea notes = new TextArea(info.notes() == null || info.notes().isBlank()
                ? I18n.t("update.dialog.noNotes") : info.notes());
        notes.setEditable(false);
        notes.setWrapText(true);
        notes.setPrefHeight(NOTES_HEIGHT);
        notes.setMaxWidth(Double.MAX_VALUE);

        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.getChildren().addAll(
                Ui.primaryButton("update.dialog.update", event -> {
                    choice[0] = Choice.UPDATE;
                    stage.close();
                }),
                Ui.ghostButton("update.dialog.later", event -> {
                    choice[0] = Choice.LATER;
                    stage.close();
                }),
                Ui.ghostButton("update.dialog.skip", event -> {
                    choice[0] = Choice.SKIP;
                    stage.close();
                }),
                Ui.grow());

        Label skipHint = Ui.label(I18n.t("update.dialog.skipHint"), "mute-2");
        skipHint.setWrapText(true);

        VBox content = new VBox(12, title, meta, notesTitle, notes, skipHint, actions);
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
        return choice[0];
    }
}
