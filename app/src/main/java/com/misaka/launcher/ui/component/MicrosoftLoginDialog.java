package com.misaka.launcher.ui.component;

import com.misaka.launcher.core.auth.AuthService;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.ui.shell.AppShell;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
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

import java.util.concurrent.atomic.AtomicReference;

/**
 * 「添加微软账户」对话框：浏览器授权码登录。
 *
 * <p><b>为什么不用设备码流</b>：设备码要求用户自己抄一段短码、再打开另一个页面输入，
 * 手机上还得分两步。浏览器流的体验是「点登录 → 浏览器里输密码（含两步验证）→ 自动回到启动器」，
 * 密码只出现在微软自己的页面上。设备码流保留为接口上的兜底（浏览器拉不起来时仍可用）。</p>
 *
 * <p><b>不向用户要 Client ID</b>：应用是本启动器自有的 public client，写死在
 * {@code LauncherConfig.AuthSettings.BUILT_IN_MICROSOFT_CLIENT_ID} 里 ——
 * 玩家点一下就登录，不需要知道也不需要注册任何 Azure 应用（与其它启动器一致）。</p>
 */
public final class MicrosoftLoginDialog {

    private static final Logger log = LoggerFactory.getLogger(MicrosoftLoginDialog.class);

    private static final String LOGIN_TASK = "account.microsoft";

    private MicrosoftLoginDialog() {
    }

    /**
     * 打开对话框并阻塞，直到用户完成登录或取消。
     *
     * @return 登录成功得到的新账户；取消/关闭时返回 {@code null}
     */
    public static LauncherConfig.AccountEntry show(AppShell shell) {
        Stage stage = new Stage();
        stage.initOwner(shell.stage());
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(I18n.t("account.microsoft.dialogTitle"));

        Label status = Ui.label(I18n.t("account.microsoft.idle"), "mute-2");
        status.setWrapText(true);
        status.setMaxWidth(Double.MAX_VALUE);

        Button login = Ui.primaryButton("account.microsoft.login", event -> {
        });
        Button copy = Ui.ghostButton("account.microsoft.copyLink", event -> {
        });
        Button cancel = Ui.ghostButton("action.cancel", event -> {
        });

        // 会话与授权地址：取消要能唤醒等待方，复制链接要用到地址
        AtomicReference<AuthService.BrowserLoginSession> session = new AtomicReference<>();
        AtomicReference<LauncherConfig.AccountEntry> created = new AtomicReference<>();

        login.setOnAction(event -> {
            status.setText(I18n.t("account.microsoft.preparing"));
            status.getStyleClass().removeAll("warn-callout");
            status.getStyleClass().add("mute-2");
            login.setDisable(true);
            copy.setDisable(true);
            created.set(null);

            shell.tasks().run(LOGIN_TASK, listener -> {
                AuthService.BrowserLoginSession current = shell.services().auth().beginBrowserLogin();
                session.set(current);
                // 拉起浏览器必须在 FX 线程（Desktop.browse 也要求非 headless 的 AWT 环境）
                Platform.runLater(() -> {
                    if (openUrl(shell, current.authorizeUrl(), true)) {
                        status.setText(I18n.t("account.microsoft.opened"));
                    } else {
                        // 拉不起浏览器（无默认浏览器 / headless）：把链接放到剪贴板并说清楚
                        copyToClipboard(current.authorizeUrl());
                        status.setText(I18n.t("account.microsoft.browserFailed"));
                    }
                    copy.setDisable(false);
                });
                LauncherConfig.AccountEntry entry = shell.services().auth()
                        .completeBrowserLogin(current, listener::onMessage);
                created.set(entry);
            }, result -> {
                if (result.success()) {
                    stage.close();
                } else if (result.failed()) {
                    login.setDisable(false);
                    status.setText(breakForDisplay(I18n.t("account.microsoft.failed", String.valueOf(
                            result.error() == null ? "" : result.error().getMessage()))));
                    markWarning(status);
                }
            });
        });

        copy.setOnAction(event -> {
            AuthService.BrowserLoginSession current = session.get();
            if (current != null) {
                copyToClipboard(current.authorizeUrl());
                shell.toast().show(I18n.t("toast.copied"), Toast.Kind.SUCCESS);
            }
        });

        cancel.setOnAction(event -> {
            AuthService.BrowserLoginSession current = session.get();
            if (current != null) {
                // 唤醒被阻塞的等待线程，后台任务随之结束（否则它会一直占着回环端口）
                current.close();
            }
            stage.close();
        });

        VBox content = new VBox(12,
                Ui.callout(I18n.t("account.microsoft.hint"), false),
                status,
                new HBox(8, login, copy, cancel));
        content.getStyleClass().add("glass-card");
        content.setPrefWidth(560);

        StackPane root = new StackPane(content);
        root.setPadding(new Insets(18));
        root.setStyle("-fx-background-color: -c-bg-alt;");

        stage.setScene(new Scene(root));
        // 鐙珛绐楀彛鏈夎嚜宸辩殑 Scene锛屼笉浼氱户鎵夸富绐楀彛鐨勫瓧浣揱r
        com.misaka.launcher.ui.theme.Fonts.applyToScene(stage.getScene());
        stage.setResizable(false);
        // 统一装修：应用图标 + 主窗口样式表（否则标题栏是系统默认图标、外观也不是产品视觉）
        Dialogs.style(stage, shell);
        // 直接关窗（X）也要释放会话，否则本地端口会一直被占住
        stage.setOnHidden(event -> {
            AuthService.BrowserLoginSession current = session.get();
            if (current != null) {
                current.close();
            }
        });

        stage.showAndWait();
        return created.get();
    }

    /**
     * 把错误信息断成多行再显示。
     *
     * <p>JSON 错误体里没有空格，JavaFX 的 {@code wrapText} 找不到断行点，整段会溢出被裁掉 ——
     * 现场就变成「只看到 HTTP 403 {」，恰恰是最需要看的那部分看不到。</p>
     */
    private static String breakForDisplay(String message) {
        return message == null ? "" : message
                .replace(",\"", ",\n\"")
                .replace("{\"", "{\n\"");
    }

    private static void markWarning(Label label) {
        label.getStyleClass().removeAll("mute-2", "info-callout");
        if (!label.getStyleClass().contains("warn-callout")) {
            label.getStyleClass().add("warn-callout");
        }
    }

    private static void copyToClipboard(String url) {
        ClipboardContent content = new ClipboardContent();
        content.putString(url);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /**
     * 拉起系统浏览器。
     *
     * @param notify 失败时是否提示用户（注册链接失败无需打扰）
     * @return 是否成功交给浏览器
     */
    private static boolean openUrl(AppShell shell, String url, boolean notify) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                return true;
            }
        } catch (Exception e) {
            log.warn("拉起浏览器失败: {}", e.getMessage());
        }
        if (notify) {
            shell.toast().show(I18n.t("account.microsoft.browserFailed"), Toast.Kind.ERROR);
        }
        return false;
    }
}
