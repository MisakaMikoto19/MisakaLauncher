package com.misaka.launcher.ui.component;

import com.misaka.launcher.ui.shell.AppShell;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * 二级窗口（对话框）的统一入口。
 *
 * <p><b>为什么要有它</b>：之前每个弹窗各写一遍「设 owner / 模态 / 标题 / 拷样式表」，
 * 于是出现了两类毛病：三处漏了 {@link AppIcon}（标题栏左上角是系统默认图标），
 * 两处用 {@code Alert} 实现的弹窗连配色和按钮都是系统默认的，和主界面不是一套视觉。
 * 统一收到这里之后，新建弹窗只要走 {@link #build} 就自动带图标与主题。</p>
 *
 * <p>刻意不接受 {@code Stage} 之外的复用：图标必须在 {@code show()} 之前注册，
 * 任务栏/标题栏条目在窗口首次显示时就由系统创建，之后再补不会刷新。</p>
 */
public final class Dialogs {

    private static final double DEFAULT_WIDTH = 460;

    private Dialogs() {
    }

    /**
     * 给已有窗口补上「应用图标 + 主窗口样式表」。
     *
     * @param stage 已经 {@code setScene} 过、尚未 {@code show()} 的窗口
     */
    public static void style(Stage stage, AppShell shell) {
        AppIcon.apply(stage);
        Scene scene = stage.getScene();
        if (scene != null && shell.stage().getScene() != null) {
            // 沿用主窗口样式表：否则弹窗会是 JavaFX 默认外观，与产品不是一套视觉
            scene.getStylesheets().setAll(shell.stage().getScene().getStylesheets());
        }
    }

    /**
     * 建一个已装修、但还没显示的模态窗口。
     *
     * @param title   已本地化的窗口标题
     * @param content 卡片内容；会自动套上 {@code glass-card} 与深色背景
     * @param width   卡片宽度；{@code <= 0} 用默认宽度
     */
    public static Stage build(AppShell shell, String title, Region content, double width) {
        Stage stage = new Stage();
        stage.initOwner(shell.stage());
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(title);

        content.getStyleClass().add("glass-card");
        content.setPrefWidth(width <= 0 ? DEFAULT_WIDTH : width);

        StackPane root = new StackPane(content);
        root.setPadding(new Insets(18));
        // 深色底：首帧绘出来之前不会闪一下白
        root.setStyle("-fx-background-color: -c-bg-alt;");

        stage.setScene(new Scene(root));
        // 鐙珛绐楀彛鏈夎嚜宸辩殑 Scene锛屼笉浼氱户鎵夸富绐楀彛鐨勫瓧浣揱r
        com.misaka.launcher.ui.theme.Fonts.applyToScene(stage.getScene());
        stage.setResizable(false);
        style(stage, shell);
        return stage;
    }

    /**
     * 单按钮提示框（不阻塞）。
     *
     * <p>用来替掉 {@code Alert}：{@code Alert} 走的是系统默认对话框样式，
     * 既没有应用图标，也和主界面配色对不上。</p>
     *
     * <p>返回窗口本身，让调用方能在「后台那件事做完了」时把它关掉 ——
     * 例如设备码登录：码还有效、但已经登录成功，对话框就该自己消失。</p>
     */
    public static Stage info(AppShell shell, String title, Region body, Node... leadingButtons) {
        Button close = Ui.primaryButton("action.confirm", event -> {
        });
        HBox buttons = new HBox(8, leadingButtons);
        buttons.getChildren().addAll(Ui.grow(), close);
        VBox card = new VBox(12,
                Ui.sectionTitle(title),
                body,
                buttons);
        Stage stage = build(shell, title, card, 0);
        close.setOnAction(event -> stage.close());
        stage.show();
        return stage;
    }

    /**
     * 确认框。
     *
     * @param okKey 确认按钮的文案键（危险操作用 {@code action.delete} 之类，与操作同名）
     * @return 用户是否点了确认
     */
    public static boolean confirm(AppShell shell, String title, String message, String okKey) {
        boolean[] confirmed = {false};
        Label text = Ui.label(message, "mute-2");
        text.setWrapText(true);
        text.setMaxWidth(Double.MAX_VALUE);

        Button cancel = Ui.ghostButton("action.cancel", event -> {
        });
        Button ok = Ui.dangerButton(okKey, event -> {
        });
        VBox card = new VBox(12,
                Ui.sectionTitle(title),
                text,
                new HBox(8, Ui.grow(), cancel, ok));

        Stage stage = build(shell, title, card, 520);
        cancel.setOnAction(event -> stage.close());
        ok.setOnAction(event -> {
            confirmed[0] = true;
            stage.close();
        });
        stage.showAndWait();
        return confirmed[0];
    }
}
