package com.misaka.launcher.ui.component;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 轻提示。
 * <p>设计取舍：不做模态弹窗。启动器里绝大多数反馈（设置已保存、已复制、安装完成）
 * 都不需要打断用户，轻提示既不丢信息也不挡操作。</p>
 */
public final class Toast {

    /** 提示类型，仅影响描边颜色。 */
    public enum Kind {
        INFO(""),
        SUCCESS("toast-success"),
        /** 警告：流程完成但有需要留意的项（例如同步存在失败文件）。 */
        WARN("toast-warn"),
        ERROR("toast-error");

        private final String styleClass;

        Kind(String styleClass) {
            this.styleClass = styleClass;
        }
    }

    private static final int MAX_VISIBLE = 4;
    private static final Duration VISIBLE = Duration.seconds(3.2);

    private final StackPane host;
    private final VBox container;
    private final Deque<Region> visible = new ArrayDeque<>();

    public Toast(StackPane host) {
        this.host = host;
        this.container = new VBox(8);
        this.container.setAlignment(Pos.BOTTOM_RIGHT);
        this.container.setPickOnBounds(false);
        this.container.setMouseTransparent(true);
        this.container.setPadding(new Insets(0, 20, 78, 0));
        this.container.setMaxWidth(Region.USE_PREF_SIZE);
        StackPane.setAlignment(container, Pos.BOTTOM_RIGHT);
        host.getChildren().add(container);
    }

    public void show(String message) {
        show(message, Kind.INFO);
    }

    /** 展示一条提示；超出上限时先移除最早的。 */
    public void show(String message, Kind kind) {
        if (message == null || message.isBlank()) {
            return;
        }
        while (visible.size() >= MAX_VISIBLE) {
            Region oldest = visible.pollFirst();
            if (oldest != null) {
                container.getChildren().remove(oldest);
            }
        }

        Label label = new Label(message);
        label.getStyleClass().add("toast");
        if (kind != null && !kind.styleClass.isBlank()) {
            label.getStyleClass().add(kind.styleClass);
        }
        label.setWrapText(true);
        label.setMaxWidth(420);

        container.getChildren().add(label);
        visible.add(label);

        // 右侧滑入 + 淡入，停留后淡出并移除
        label.setOpacity(0);
        label.setTranslateX(36);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(180), label);
        fadeIn.setToValue(1);
        TranslateTransition slideIn = new TranslateTransition(Duration.millis(200), label);
        slideIn.setToX(0);
        PauseTransition stay = new PauseTransition(VISIBLE);
        FadeTransition fadeOut = new FadeTransition(Duration.millis(260), label);
        fadeOut.setToValue(0);
        TranslateTransition slideOut = new TranslateTransition(Duration.millis(260), label);
        slideOut.setToX(24);

        SequentialTransition sequence = new SequentialTransition(
                new javafx.animation.ParallelTransition(fadeIn, slideIn),
                stay,
                new javafx.animation.ParallelTransition(fadeOut, slideOut));
        sequence.setOnFinished(event -> {
            container.getChildren().remove(label);
            visible.remove(label);
        });
        sequence.play();
    }
}
