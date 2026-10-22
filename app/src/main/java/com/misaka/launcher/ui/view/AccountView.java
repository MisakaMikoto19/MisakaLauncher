package com.misaka.launcher.ui.view;

import com.misaka.launcher.core.auth.AuthService;
import com.misaka.launcher.core.auth.DeviceCodeFlow;
import com.misaka.launcher.core.auth.PlayerHeads;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.ui.component.Dialogs;
import com.misaka.launcher.ui.component.Icons;
import com.misaka.launcher.ui.component.MicrosoftLoginDialog;
import com.misaka.launcher.ui.component.Toast;
import com.misaka.launcher.ui.component.Ui;
import com.misaka.launcher.ui.shell.AppShell;
import com.misaka.launcher.ui.shell.TaskCenter;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 账户视图。
 *
 * <p><b>只有一种登录方式：微软正版。</b> 离线账户与第三方皮肤站入口已移除 ——
 * 它们不经过微软 OAuth，也不校验是否拥有 Java 版，属于「不拥有游戏也能进」的通道。</p>
 *
 * <p>微软登录优先走系统浏览器的授权码流；设备码流程作为兜底保留（无默认浏览器、
 * 远程会话等拉不起浏览器的环境），因此内联展示短码并提供复制按钮，
 * 而不是弹一个转圈的对话框让用户不明所以。</p>
 */
public final class AccountView extends ScrollPane {

    private static final Logger log = LoggerFactory.getLogger(AccountView.class);

    private final AppShell shell;

    private final ListView<LauncherConfig.AccountEntry> accountList = new ListView<>();
    private final StackPane avatarHolder = new StackPane();
    private final Label usernameLabel = Ui.label("—", "card-title");
    private final Label typeLabel = Ui.label("", "badge");
    private final Label uuidLabel = Ui.label("", "mute-2");
    private final Label lastLoginLabel = Ui.label("", "mute-2");

    /** 设备码对话框；登录有结论后要主动关掉，避免留一个过了期的码在屏幕上。 */
    private javafx.stage.Stage deviceCodeDialog;
    /** 正在跑的设备码登录任务：取消按钮与关窗都要能停掉它。 */
    private TaskCenter.TaskHandle deviceCodeTask;

    /** 默认头像模型切换（Steve / Alex）；在 {@link #buildSkinModelRow()} 中创建。 */
    private Button skinSteve;
    private Button skinAlex;

    public AccountView(AppShell shell) {
        this.shell = shell;

        VBox content = new VBox(16, buildAccountSection(), buildAddSection());
        Ui.mountScrollable(this, content, Ui.CONTENT_MAX_WIDTH);

        // 头像加载完成后由缓存广播回来：与侧边栏同一帧更新，不再有先后
        shell.services().avatars().subscribe(this::refreshSelectedAvatar);

        refresh();
    }

    // ==================================================================
    // 账户列表与详情
    // ==================================================================

    private Node buildAccountSection() {
        accountList.setPrefHeight(150);
        accountList.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(LauncherConfig.AccountEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label name = Ui.label(item.username, "section-title");
                Label type = Ui.badge(I18n.t(com.misaka.launcher.core.auth.AccountManager
                        .typeI18nKey(item.type)), "badge-info");
                HBox row = new HBox(8, name, type);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
                setText(null);
            }
        });
        accountList.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, value) -> showDetails(value));

        Button use = Ui.ghostButton("account.current", event -> useSelected(), Icons.create(Icons.PLAY, 13));
        Button remove = Ui.dangerButton("action.delete", event -> removeSelected());

        Node details = buildDetails();
        HBox body = new HBox(16, new VBox(10, accountList, new HBox(8, use, remove)), details);
        HBox.setHgrow(body.getChildren().get(0), Priority.ALWAYS);
        return Ui.glassCard(12, Ui.cardHeader("account.title"), body);
    }

    private Node buildDetails() {
        avatarHolder.setMinSize(84, 84);
        avatarHolder.setPrefSize(84, 84);
        avatarHolder.setMaxSize(84, 84);

        VBox info = new VBox(6, usernameLabel, typeLabel, uuidLabel, lastLoginLabel);
        HBox header = new HBox(14, avatarHolder, info);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(12, header, Ui.divider(), buildSkinModelRow(), Ui.muted(""));
        box.setPrefWidth(360);
        return box;
    }

    /**
     * 皮肤模型切换（Steve / Alex）。
     *
     * <p>它决定「没有真实皮肤时显示哪张默认头像」：有真实皮肤（来自该账号在官方服务上的
     * 皮肤）时这个开关不影响头像，但仍会随账户保存下来。</p>
     */
    private Node buildSkinModelRow() {
        skinSteve = Ui.pill(I18n.t("account.skin.default"), true,
                event -> applySkinModel(PlayerHeads.MODEL_DEFAULT));
        skinAlex = Ui.pill(I18n.t("account.skin.slim"), false,
                event -> applySkinModel(PlayerHeads.MODEL_SLIM));
        HBox row = new HBox(6, skinSteve, skinAlex);
        row.setAlignment(Pos.CENTER_LEFT);
        return Ui.settingRow("account.skin.model", row, I18n.t("account.skin.tip"));
    }

    /** 手动切换默认头像模型；只改这一条账户，不切换当前账户。 */
    private void applySkinModel(String model) {
        LauncherConfig.AccountEntry entry = accountList.getSelectionModel().getSelectedItem();
        if (entry == null || model.equals(PlayerHeads.normalizeModel(entry.skinModel))) {
            return;
        }
        entry.skinModel = model;
        // 用 update 而不是 add：后者会把该账户设为当前账户，等于偷偷换了登录身份
        shell.services().accounts().update(entry);
        showDetails(entry);
        shell.refreshShell();
        shell.toast().show(I18n.t("account.skin.applied"), Toast.Kind.SUCCESS);
    }

    /** 同步两个模型按钮的选中态。 */
    private void refreshSkinModel(LauncherConfig.AccountEntry entry) {
        boolean slim = PlayerHeads.MODEL_SLIM.equals(PlayerHeads.normalizeModel(entry.skinModel));
        markSelected(skinSteve, !slim);
        markSelected(skinAlex, slim);
    }

    private static void markSelected(Button button, boolean selected) {
        button.getStyleClass().remove("selected");
        if (selected) {
            button.getStyleClass().add("selected");
        }
    }

    private void showDetails(LauncherConfig.AccountEntry entry) {
        if (entry == null) {
            avatarHolder.getChildren().clear();
            usernameLabel.setText("—");
            typeLabel.setText("");
            uuidLabel.setText("");
            lastLoginLabel.setText("");
            return;
        }
        usernameLabel.setText(entry.username);
        typeLabel.setText(I18n.t(com.misaka.launcher.core.auth.AccountManager.typeI18nKey(entry.type)));
        uuidLabel.setText(entry.uuid);
        lastLoginLabel.setText(entry.lastLoginAt > 0 ? Ui.formatDateTime(entry.lastLoginAt) : "");
        refreshSkinModel(entry);
        loadAvatar(entry);
    }

    /**
     * 刷新头像。
     *
     * <p>走全局 {@link com.misaka.launcher.app.AvatarCache}：侧边栏与这里共用同一次加载，
     * 完成后由缓存广播回来两侧同帧替换（此前两处各自请求、更新有先后且都偏慢）。
     * 未就绪时先用默认 Steve / Alex 占位，避免「首字母 → 真实头像」的跳变。</p>
     */
    private void loadAvatar(LauncherConfig.AccountEntry entry) {
        Image image = shell.services().avatars().request(entry);
        avatarHolder.getChildren().setAll(Ui.playerHead(image, entry.skinModel, 84));
    }

    /** 头像就绪后的广播回调：只更新当前选中的那一条，避免把其它账户的头像画上来。 */
    private void refreshSelectedAvatar() {
        LauncherConfig.AccountEntry entry = accountList.getSelectionModel().getSelectedItem();
        if (entry != null) {
            loadAvatar(entry);
        }
    }

    private void useSelected() {
        LauncherConfig.AccountEntry selected = accountList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        shell.services().accounts().switchTo(selected.id);
        shell.refreshShell();
        shell.toast().show(I18n.t("account.current") + "：" + selected.username, Toast.Kind.SUCCESS);
    }

    private void removeSelected() {
        LauncherConfig.AccountEntry selected = accountList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        if (!Dialogs.confirm(shell, I18n.t("account.delete.confirmTitle"),
                I18n.t("account.delete.confirm", selected.username), "action.delete")) {
            return;
        }
        shell.services().accounts().remove(selected.id);
        refresh();
        shell.refreshShell();
    }

    // ==================================================================
    // 添加账户
    // ==================================================================

    private Node buildAddSection() {
        // 只保留微软正版登录：应用是启动器自有的 public client（见
        // LauncherConfig.AuthSettings.BUILT_IN_MICROSOFT_CLIENT_ID），点一下就能登录
        Button addMicrosoft = Ui.ghostButton("account.add.microsoft", event -> loginMicrosoft(),
                Icons.create(Icons.ACCOUNT, 14));
        // 设备码作为兜底入口保留：极少数环境拉不起浏览器（无默认浏览器 / 远程会话）
        Button addMicrosoftDeviceCode =
                Ui.ghostButton("account.microsoft.useDeviceCode",
                        event -> loginMicrosoftByDeviceCode(), Icons.create(Icons.ACCOUNT, 14));
        VBox microsoftCard = Ui.glassCard(10,
                Ui.i18n("account.microsoft", "card-title"),
                Ui.callout(I18n.t("account.microsoft.ready"), false),
                Ui.muted(I18n.t("account.microsoft.onlyWay")),
                new HBox(8, addMicrosoft, addMicrosoftDeviceCode));
        microsoftCard.setMaxWidth(520);
        return microsoftCard;
    }

    /**
     * 微软正版登录：浏览器授权码流。
     *
     * <p>点「登录」拉起系统浏览器，用户在里面输密码（含两步验证），
     * 浏览器跳回本地回环地址后启动器自动完成剩余链路 —— 密码全程不经过启动器。</p>
     */
    private void loginMicrosoft() {
        // 开源版本不内置微软应用 ID（只有编译期一处入口），先给明确提示，
        // 而不是让用户拉起浏览器、走完授权、再在换 MC 令牌时撞一个英文错误
        if (!shell.services().auth().clientIdConfigured()) {
            shell.toast().show(I18n.t("account.microsoft.noClientId"), Toast.Kind.WARN);
            return;
        }
        LauncherConfig.AccountEntry entry = MicrosoftLoginDialog.show(shell);
        if (entry == null) {
            return;
        }
        try {
            shell.services().accounts().add(entry);
            shell.toast().show(I18n.t("account.microsoft.success") + "：" + entry.username,
                    Toast.Kind.SUCCESS);
            refresh();
            shell.refreshShell();
        } catch (Exception e) {
            shell.toast().show(String.valueOf(e.getMessage()), Toast.Kind.ERROR);
        }
    }

    /** 微软设备码登录（兜底）：浏览器拉不起来时用，先取码并展示给用户，再在后台轮询。 */
    private void loginMicrosoftByDeviceCode() {
        if (!shell.services().auth().clientIdConfigured()) {
            shell.toast().show(I18n.t("account.microsoft.noClientId"), Toast.Kind.WARN);
            return;
        }
        TaskCenter.TaskHandle handle = shell.tasks().run("account.microsoft", listener -> {
            DeviceCodeFlow flow = shell.services().auth().beginMicrosoftLogin();
            Platform.runLater(() -> showDeviceCode(flow));
            // 传 listener 而不是 listener::onMessage：设备码轮询要能读到「已取消」
            LauncherConfig.AccountEntry entry = shell.services().auth().completeMicrosoftLogin(
                    flow, listener);
            shell.services().accounts().add(entry);
        }, result -> {
            // 后台那件事已经有结论了：设备码对话框留着只会挡路
            closeDeviceCodeDialog();
            if (result.cancelled()) {
                // 用户自己按的停止，不该再弹一条"登录失败"
                shell.toast().show(I18n.t("account.microsoft.cancelled"));
                return;
            }
            if (result.success()) {
                shell.toast().show(I18n.t("account.microsoft.success"), Toast.Kind.SUCCESS);
                refresh();
                shell.refreshShell();
            } else if (result.failed()) {
                shell.toast().show(I18n.t("account.microsoft.failed", String.valueOf(result.error() == null
                        ? "" : result.error().getMessage())), Toast.Kind.ERROR);
            }
        });
        deviceCodeTask = handle;
    }

    /** 关掉设备码对话框（登录有结论、或用户取消时调用）。 */
    private void closeDeviceCodeDialog() {
        if (deviceCodeDialog != null) {
            deviceCodeDialog.close();
            deviceCodeDialog = null;
        }
    }

    /** 展示设备码：用户需要在浏览器里输入短码。 */
    private void showDeviceCode(DeviceCodeFlow flow) {
        Label code = Ui.mono(flow.userCode(), "card-value");
        Label url = Ui.label(flow.verificationUri(), "accent-text");
        Button copy = Ui.ghostButtonText(I18n.t("action.copy"), event -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(flow.userCode());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            shell.toast().show(I18n.t("toast.copied"), Toast.Kind.SUCCESS);
        });
        Button open = Ui.ghostButtonText(I18n.t("account.microsoft.openBrowser"),
                event -> openUrl(flow.verificationUri()));

        Button cancelLogin = Ui.ghostButtonText(I18n.t("account.microsoft.cancelLogin"),
                event -> cancelDeviceCodeLogin());

        // 走统一的二级窗口：Alert 是系统默认样式，没有应用图标、配色也和主界面不一致
        VBox body = new VBox(10,
                Ui.i18n("account.microsoft.deviceCode"), code,
                Ui.i18n("account.microsoft.verifyUrl"), url,
                new HBox(8, copy, open),
                Ui.muted(I18n.t("account.microsoft.waiting")));
        javafx.stage.Stage dialog = Dialogs.info(shell, I18n.t("account.add.microsoft"), body, cancelLogin);
        dialog.setOnHidden(event -> {
            // 直接关窗（X）等同于放弃：不取消的话后台还会一直轮询到设备码过期
            TaskCenter.TaskHandle task = deviceCodeTask;
            if (task != null && task.isRunning()) {
                shell.tasks().cancel(task);
            }
            if (deviceCodeDialog == dialog) {
                deviceCodeDialog = null;
            }
        });
        deviceCodeDialog = dialog;
    }

    /** 放弃设备码登录：取消后台轮询并关掉对话框。 */
    private void cancelDeviceCodeLogin() {
        TaskCenter.TaskHandle task = deviceCodeTask;
        if (task != null) {
            shell.tasks().cancel(task);
        }
        closeDeviceCodeDialog();
    }

    private void openUrl(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception e) {
            // AWT 在部分环境中不可用（例如缺少 java.desktop 或 headless），退回系统命令
            shell.toast().show(url);
        }
    }

    // ==================================================================

    public void refresh() {
        var entries = shell.services().accounts().list();
        accountList.getItems().setAll(entries);
        LauncherConfig.AccountEntry current = shell.services().accounts().current();
        if (current != null && entries.contains(current)) {
            accountList.getSelectionModel().select(current);
        } else if (!entries.isEmpty()) {
            accountList.getSelectionModel().selectFirst();
        }
    }
}
