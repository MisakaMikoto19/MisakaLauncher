package com.misaka.launcher.core.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.misaka.launcher.core.config.ConfigStore;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.launch.AccountInfo;
import com.misaka.launcher.core.task.CancelledException;
import com.misaka.launcher.core.task.ProgressListener;
import com.misaka.launcher.core.util.HttpUtils;
import com.misaka.launcher.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 认证服务：<b>只支持微软正版账户</b>。
 *
 * <p>本类不再提供离线账户与第三方认证服务器（Yggdrasil / 皮肤站）的任何入口 ——
 * 只有走过 {@code login_with_xbox} 并确认账号确实拥有 Java 版的账户才能启动游戏。
 * 这不是可配置项，也不打算加回来。</p>
 *
 * <p>微软采用<b>设备代码流</b>：无需内嵌浏览器、无需回调地址，最适合桌面启动器。
 * 完整链路为：设备码 → 轮询令牌 → Xbox Live 认证 → XSTS 授权 → Minecraft 登录 → 查询档案。</p>
 * <p>安全约定：accessToken / refreshToken 只写回配置，绝不进入日志。</p>
 */
public final class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    // ---- 微软端点 ----
    private static final String MS_DEVICE_CODE =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String MS_TOKEN =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String MS_SCOPE = "XboxLive.signin offline_access";
    private static final String XBL_AUTH = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE = "https://api.minecraftservices.com/minecraft/profile";
    /** 浏览器授权码流的授权页（消费者账户端点）。 */
    private static final String MS_AUTHORIZE =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
    private static final String GRANT_AUTHORIZATION_CODE = "authorization_code";
    /** 等用户去浏览器里登录的上限：去忙别的事也来得及回来。 */
    private static final Duration BROWSER_LOGIN_TIMEOUT = Duration.ofMinutes(5);

    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(20);

    private final ConfigStore configStore;
    /** 生效的 Client ID：生产环境恒为编译期常量；测试可注入假值走到「已配置」的分支。 */
    private final String clientId;

    public AuthService(ConfigStore configStore) {
        this(configStore, LauncherConfig.AuthSettings.BUILT_IN_MICROSOFT_CLIENT_ID);
    }

    /**
     * 仅供测试：注入一个假 Client ID。
     *
     * <p>开源版本的常量是空的，设备码轮询、取消、换令牌这些逻辑在「未配置」时会被
     * 直接短路，无从覆盖。这个构造器<b>不是</b>配置入口 —— 包级可见，
     * 生产代码只有上面那一个调用点。</p>
     */
    AuthService(ConfigStore configStore, String clientId) {
        this.configStore = configStore;
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    private LauncherConfig.AuthSettings settings() {
        return configStore.get().auth;
    }

    // ==================================================================
    // 微软正版
    // ==================================================================

    /**
     * 开始微软登录：申请设备码。
     *
     * @throws AuthException 未配置 Client ID，或接口拒绝请求
     */
    public DeviceCodeFlow beginMicrosoftLogin() throws IOException {
        String clientId = requireClientId();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("scope", MS_SCOPE);

        HttpUtils.Result result = HttpUtils.send("POST", MS_DEVICE_CODE, Map.of(),
                HttpUtils.formEncode(form), HttpUtils.FORM_CONTENT_TYPE, AUTH_TIMEOUT);
        if (!result.isSuccess()) {
            throw microsoftError("申请设备码失败", result);
        }
        JsonNode json = result.json();
        DeviceCodeFlow flow = new DeviceCodeFlow(
                JsonUtils.text(json, "device_code", ""),
                JsonUtils.text(json, "user_code", ""),
                JsonUtils.text(json, "verification_uri", "https://microsoft.com/link"),
                JsonUtils.intValue(json, "expires_in", 900),
                Math.max(1, JsonUtils.intValue(json, "interval", 5)));
        log.info("已申请微软设备码，验证地址 {}，有效期 {} 秒", flow.verificationUri(), flow.expiresInSeconds());
        return flow;
    }

    /**
     * 等待用户完成授权并完成整个登录链路。
     * <p>本方法会阻塞，应放在后台线程执行。</p>
     *
     * @param progress 进度提示（用于界面展示「等待授权中…」）
     */
    public LauncherConfig.AccountEntry completeMicrosoftLogin(DeviceCodeFlow flow,
                                                             Consumer<String> progress) throws IOException {
        return completeMicrosoftLogin(flow, adapt(progress));
    }

    /**
     * 等待设备码授权并完成整个链路（**可取消**）。
     *
     * <p>设备码有效期 15 分钟，用户随时可能放弃。之前的实现只接受 {@code Consumer<String>}，
     * 拿不到取消标志，于是「点了停止」之后后台仍在每 5 秒轮询到过期为止 ——
     * 界面看着停了，实际没停，还可能在你已经去做别的事时突然登录成功。</p>
     *
     * @param listener 进度回调；其 {@link ProgressListener#isCancelled()} 会在每个轮询间隔被检查
     * @throws com.misaka.launcher.core.task.CancelledException 用户取消时
     */
    public LauncherConfig.AccountEntry completeMicrosoftLogin(DeviceCodeFlow flow,
                                                             ProgressListener listener) throws IOException {
        String microsoftToken = pollForMicrosoftToken(flow, listener);
        return finishMicrosoftLogin(microsoftToken, null, listener::onMessage);
    }

    /** 把只关心文案的旧调用点包成 {@link ProgressListener}（不可取消）。 */
    private static ProgressListener adapt(Consumer<String> progress) {
        return new ProgressListener() {
            @Override
            public void onMessage(String message) {
                if (progress != null) {
                    progress.accept(message);
                }
            }
        };
    }

    // ------------------------------------------------------------------
    // 微软正版：浏览器授权码流（推荐）
    // ------------------------------------------------------------------

    /**
     * 一次浏览器登录会话。
     *
     * @param authorizeUrl  要拉起的授权地址
     * @param redirectUri   本地回调地址（必须与 Azure 应用里登记的一致）
     * @param server        本地回环回调服务器
     * @param codeVerifier  PKCE 校验串（随令牌交换一起提交，不写日志）
     */
    public record BrowserLoginSession(String authorizeUrl,
                                      String redirectUri,
                                      LoopbackCallbackServer server,
                                      String codeVerifier) implements AutoCloseable {

        /** 放弃这次登录：唤醒等待方并关闭本地回调端口。 */
        @Override
        public void close() {
            server.close();
        }
    }

    /**
     * 开始浏览器登录：起本地回调服务并给出要拉起的授权地址。
     *
     * <p><b>为什么优先用它</b>：密码只出现在微软自己的页面上（含两步验证），
     * 启动器全程不接触密码；用户也不用抄短码或换设备。设备码流保留作为兜底。</p>
     *
     * @throws AuthException 未配置 Client ID
     */
    public BrowserLoginSession beginBrowserLogin() throws IOException {
        String clientId = requireClientId();
        LoopbackCallbackServer server = new LoopbackCallbackServer();
        try {
            String verifier = pkceVerifier();
            String url = authorizeUrl(clientId, server.redirectUri(), pkceChallenge(verifier));
            log.info("已生成微软授权地址（回调 {}），等待浏览器完成授权", server.redirectUri());
            return new BrowserLoginSession(url, server.redirectUri(), server, verifier);
        } catch (RuntimeException e) {
            // 生成失败也别把端口漏着
            server.close();
            throw e;
        }
    }

    /**
     * 等待浏览器回调并完成整个登录链路。
     * <p>本方法会阻塞，应放在后台线程执行。</p>
     */
    public LauncherConfig.AccountEntry completeBrowserLogin(BrowserLoginSession session,
                                                            Consumer<String> progress) throws IOException {
        try {
            String code = session.server().awaitCode(BROWSER_LOGIN_TIMEOUT, progress);
            if (progress != null) {
                progress.accept("已收到授权，正在换取令牌…");
            }
            JsonNode token = exchangeAuthorizationCode(code, session);
            String accessToken = JsonUtils.text(token, "access_token", "");
            String refreshToken = JsonUtils.text(token, "refresh_token", "");
            if (accessToken.isBlank()) {
                throw new AuthException("微软令牌响应缺少 access_token");
            }
            return finishMicrosoftLogin(accessToken, refreshToken.isBlank() ? null : refreshToken, progress);
        } finally {
            session.close();
        }
    }

    /** 用授权码换取微软令牌。 */
    private JsonNode exchangeAuthorizationCode(String code, BrowserLoginSession session) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", GRANT_AUTHORIZATION_CODE);
        form.put("client_id", requireClientId());
        form.put("code", code);
        // 必须与会话里提交过的完全一致，否则微软直接拒绝（invalid_grant）
        form.put("redirect_uri", session.redirectUri());
        form.put("code_verifier", session.codeVerifier());
        form.put("scope", MS_SCOPE);

        HttpUtils.Result result = HttpUtils.send("POST", MS_TOKEN, Map.of(),
                HttpUtils.formEncode(form), HttpUtils.FORM_CONTENT_TYPE, AUTH_TIMEOUT);
        if (!result.isSuccess()) {
            throw microsoftError("用授权码换取令牌失败", result);
        }
        return result.json();
    }

    /**
     * 拼授权地址（包级可见便于单测：这些参数错一个，用户就会在微软页面上看到报错）。
     */
    static String authorizeUrl(String clientId, String redirectUri, String codeChallenge) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", clientId);
        query.put("response_type", "code");
        query.put("redirect_uri", redirectUri);
        query.put("response_mode", "query");
        query.put("scope", MS_SCOPE);
        // 每次都让用户选账户：多人共用电脑时不该默默沿用上一次的登录态
        query.put("prompt", "select_account");
        query.put("code_challenge", codeChallenge);
        query.put("code_challenge_method", "S256");
        return MS_AUTHORIZE + "?" + HttpUtils.formEncode(query);
    }

    /** PKCE 校验串：43–128 位、取自 RFC 7636 的非保留字符集。 */
    static String pkceVerifier() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder builder = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    /** PKCE challenge = BASE64URL(SHA-256(verifier))，去掉填充。 */
    static String pkceChallenge(String verifier) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行时不支持 SHA-256", e);
        }
    }

    /** 轮询令牌接口，直到用户完成授权或超时。 */
    private String pollForMicrosoftToken(DeviceCodeFlow flow, ProgressListener listener) throws IOException {
        String clientId = requireClientId();
        long deadline = System.currentTimeMillis() + flow.expiresInSeconds() * 1000L;
        int interval = Math.max(1, flow.intervalSeconds());
        int attempt = 0;

        while (System.currentTimeMillis() < deadline) {
            // 每轮都先看有没有被取消：用户点了停止就不该再发请求
            listener.throwIfCancelled();
            attempt++;
            listener.onMessage("等待授权完成…（第 " + attempt + " 次检查）");
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            form.put("client_id", clientId);
            form.put("device_code", flow.deviceCode());

            HttpUtils.Result result = HttpUtils.send("POST", MS_TOKEN, Map.of(),
                    HttpUtils.formEncode(form), HttpUtils.FORM_CONTENT_TYPE, POLL_TIMEOUT);
            if (result.isSuccess()) {
                JsonNode json = result.json();
                String accessToken = JsonUtils.text(json, "access_token", "");
                if (accessToken.isBlank()) {
                    throw new AuthException("微软令牌响应缺少 access_token");
                }
                return accessToken;
            }

            String error = readError(result);
            switch (error) {
                case "authorization_pending" -> waitInterval(interval, listener);
                case "slow_down" -> {
                    interval += 5;
                    waitInterval(interval, listener);
                }
                case "expired_token" -> throw new AuthException("设备码已过期，请重新发起登录");
                case "authorization_declined", "access_denied" -> throw new AuthException("用户拒绝了本次授权");
                case "bad_verification_code" -> throw new AuthException("设备码无效，请重新发起登录");
                default -> throw microsoftError("等待授权时出错", result);
            }
        }
        throw new AuthException("等待授权超时，请重新发起登录");
    }

    /**
     * 用微软令牌完成后续链路（XBL → XSTS → Minecraft 登录 → 查询档案）。
     * <p>刷新登录时直接复用该方法。</p>
     *
     * @param microsoftAccessToken 微软 access_token（首次）或 refresh_token 换来的新 access_token
     * @param refreshToken         要保存的 refresh_token；为 null 表示不更新
     */
    private LauncherConfig.AccountEntry finishMicrosoftLogin(String microsoftAccessToken,
                                                             String refreshToken,
                                                             Consumer<String> progress) throws IOException {
        requireClientId();

        if (progress != null) {
            progress.accept("正在通过 Xbox Live 认证…");
        }
        String[] xbl = authenticateXboxLive(microsoftAccessToken);

        if (progress != null) {
            progress.accept("正在获取 XSTS 授权…");
        }
        String xstsToken = authorizeXsts(xbl[0], xbl[1]);

        if (progress != null) {
            progress.accept("正在登录 Minecraft…");
        }
        JsonNode minecraftLogin = loginWithXbox(xstsToken);
        String accessToken = JsonUtils.text(minecraftLogin, "access_token", "");
        long expiresInSeconds = JsonUtils.longValue(minecraftLogin, "expires_in", 86_400L);
        if (accessToken.isBlank()) {
            throw new AuthException("Minecraft 登录响应缺少 access_token");
        }

        if (progress != null) {
            progress.accept("正在读取游戏档案…");
        }
        JsonNode profile = fetchMinecraftProfile(accessToken);

        LauncherConfig.AccountEntry entry = new LauncherConfig.AccountEntry();
        entry.id = UUID.randomUUID().toString();
        entry.type = LauncherConfig.AccountEntry.TYPE_MICROSOFT;
        entry.username = JsonUtils.text(profile, "name", "");
        entry.uuid = JsonUtils.text(profile, "id", "");
        entry.accessToken = accessToken;
        entry.refreshToken = refreshToken == null ? "" : refreshToken;
        entry.userType = AccountInfo.TYPE_MSA;
        entry.xuid = xbl[1];
        entry.skinModel = readSkinModel(profile);
        entry.expiresAt = System.currentTimeMillis() + expiresInSeconds * 1000L;
        entry.lastLoginAt = System.currentTimeMillis();
        log.info("微软登录成功：{}（{}）", entry.username, entry.uuid);
        return entry;
    }

    /** 用 refresh_token 重新获取微软令牌并重建游戏令牌。 */
    public LauncherConfig.AccountEntry refreshMicrosoft(LauncherConfig.AccountEntry entry) throws IOException {
        if (entry.refreshToken == null || entry.refreshToken.isBlank()) {
            throw new AuthException("缺少刷新令牌，无法自动续期，请重新登录");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("client_id", requireClientId());
        form.put("refresh_token", entry.refreshToken);
        form.put("scope", MS_SCOPE);

        HttpUtils.Result result = HttpUtils.send("POST", MS_TOKEN, Map.of(),
                HttpUtils.formEncode(form), HttpUtils.FORM_CONTENT_TYPE, AUTH_TIMEOUT);
        if (!result.isSuccess()) {
            throw microsoftError("刷新微软令牌失败", result);
        }
        JsonNode json = result.json();
        String microsoftToken = JsonUtils.text(json, "access_token", "");
        String newRefresh = JsonUtils.text(json, "refresh_token", entry.refreshToken);
        if (microsoftToken.isBlank()) {
            throw new AuthException("刷新响应缺少 access_token");
        }
        return finishMicrosoftLogin(microsoftToken, newRefresh, null);
    }

    /**
     * Xbox Live 认证。返回 {@code [xblToken, userHash]}。
     *
     * <p>{@code RpsTicket} 要加 {@code d=} 前缀：我们用的是 v2.0 应用拿到的「直接」MSA 令牌，
     * 不加前缀 XBL 会当成无效令牌（这是与 v1.0 MBI_SSL 流程最容易混淆的一处）。</p>
     */
    private String[] authenticateXboxLive(String microsoftAccessToken) throws IOException {
        String body = JsonUtils.write(Map.of(
                "Properties", Map.of(
                        "AuthMethod", "RPS",
                        "SiteName", "user.auth.xboxlive.com",
                        "RpsTicket", "d=" + microsoftAccessToken),
                "RelyingParty", "http://auth.xboxlive.com",
                "TokenType", "JWT"));

        HttpUtils.Result result = HttpUtils.send("POST", XBL_AUTH,
                Map.of("Accept", "application/json"), body, HttpUtils.JSON_CONTENT_TYPE, AUTH_TIMEOUT);
        if (!result.isSuccess()) {
            throw new AuthException("Xbox Live 认证失败。该微软账户可能未注册 Xbox 服务，"
                    + "请先在 xbox.com 登录一次后重试。（" + result.summary() + "）");
        }
        JsonNode json = result.json();
        String token = JsonUtils.text(json, "Token", "");
        String userHash = userHashOf(json);
        if (token.isBlank() || userHash.isBlank()) {
            throw new AuthException("Xbox Live 响应缺少必需字段");
        }
        return new String[]{token, userHash};
    }

    /** 从 XBL/XSTS 的响应里取 {@code DisplayClaims.xui[0].uhs}。 */
    private static String userHashOf(JsonNode json) {
        JsonNode xui = json.path("DisplayClaims").path("xui");
        return xui.isArray() && !xui.isEmpty() ? JsonUtils.text(xui.get(0), "uhs", "") : "";
    }

    /** XSTS 授权。返回 {@code XBL3.0 x=<uhs>;<xsts>} 形式的身份令牌。 */
    private String authorizeXsts(String xblToken, String userHash) throws IOException {
        String body = JsonUtils.write(Map.of(
                "Properties", Map.of(
                        "SandboxId", "RETAIL",
                        "UserTokens", java.util.List.of(xblToken)),
                "RelyingParty", "rp://api.minecraftservices.com/",
                "TokenType", "JWT"));

        HttpUtils.Result result = HttpUtils.send("POST", XSTS_AUTH,
                Map.of("Accept", "application/json"), body, HttpUtils.JSON_CONTENT_TYPE, AUTH_TIMEOUT);
        if (!result.isSuccess()) {
            throw describeXstsError(result);
        }
        JsonNode json = result.json();
        String token = JsonUtils.text(json, "Token", "");
        if (token.isBlank()) {
            throw new AuthException("XSTS 授权响应缺少令牌");
        }
        // uhs 以 XSTS 响应为准，并与 XBL 的交叉核对（做法同 HMCL）：两者不一致说明令牌被替换
        // 或串号，此时拼出来的 identityToken 一定过不了 MC 那一跳
        String xstsUhs = userHashOf(json);
        if (!xstsUhs.isBlank() && !xstsUhs.equalsIgnoreCase(userHash)) {
            throw new AuthException("XSTS 返回的用户标识与 Xbox Live 不一致（"
                    + userHash + " / " + xstsUhs + "），请重新登录一次");
        }
        String identityUhs = xstsUhs.isBlank() ? userHash : xstsUhs;
        log.info("XSTS 令牌已获取（uhs={}）", identityUhs);
        return "XBL3.0 x=" + identityUhs + ";" + token;
    }

    /**
     * 用 XSTS 令牌登录 Minecraft 服务。
     *
     * <p><b>失败时要把现场记全</b>：这一步的 403 与 401 含义完全不同 ——
     * 401 是「令牌无效」（说明前面的链路有问题），而 403 是「请求被拒绝」，
     * 令牌往往是好的，挡在前面的是别的东西（本机的加速器 / 反向代理 / DNS 劫持
     * 都会在 {@code api.minecraftservices.com} 这一跳插进来），或者账号侧有额外限制。
     * 只回一句 403 用户没法自救，所以响应体与解析到的 IP 都要落到日志里。</p>
     */
    private JsonNode loginWithXbox(String identityToken) throws IOException {
        String body = JsonUtils.write(Map.of("identityToken", identityToken));
        HttpUtils.Result result = HttpUtils.send("POST", MC_LOGIN,
                Map.of("Accept", "application/json"), body, HttpUtils.JSON_CONTENT_TYPE, AUTH_TIMEOUT);
        if (!result.isSuccess()) {
            log.warn("Minecraft 登录被拒绝：{}；{} 解析到 {}", result.summary(), MC_LOGIN,
                    resolveHostHint(MC_LOGIN));
            String errorBody = result.body() == null ? "" : result.body().toLowerCase(java.util.Locale.ROOT);
            if (errorBody.contains("invalid app registration")) {
                // 与账号、网络、代理都无关：微软在这一跳要求调用方应用**已在 Minecraft 服务侧登记**，
                // 只把 Azure 应用注册好、只开公共客户端流都不够。此刻 MS → XBL → XSTS 已经全部成功，
                // 所以提示必须指向「应用登记」，不能把人引去查网络
                throw new AuthException("内置的微软应用未通过 Minecraft 服务登记（Invalid app registration），"
                        + "不能用于正版登录。需在 https://aka.ms/AppRegInfo 完成登记，"
                        + "并换用登记过的应用 ID。");
            }
            String hint = result.status() == 403
                    ? "（403 通常不是账号问题：本机的游戏加速器 / 反向代理 / DNS 劫持会拦下"
                    + " api.minecraftservices.com，请先关掉这类工具或把该域名排除后重试）"
                    : "";
            throw new AuthException("Minecraft 登录被拒绝：" + result.summary() + hint);
        }
        return result.json();
    }

    /** 诊断用：目标域名当前解析到的地址（劫持时这里会是个意料之外的 IP）。 */
    private static String resolveHostHint(String url) {
        try {
            return java.net.InetAddress.getByName(java.net.URI.create(url).getHost()).getHostAddress();
        } catch (Exception e) {
            return "（解析失败：" + e.getClass().getSimpleName() + "）";
        }
    }

    /** 查询游戏档案；404 表示该账户不拥有 Java 版。 */
    private JsonNode fetchMinecraftProfile(String accessToken) throws IOException {
        HttpUtils.Result result = HttpUtils.send("GET", MC_PROFILE,
                Map.of("Authorization", "Bearer " + accessToken, "Accept", "application/json"),
                null, null, AUTH_TIMEOUT);
        if (result.status() == 404) {
            throw new AuthException("该微软账户未拥有 Minecraft Java 版");
        }
        if (!result.isSuccess()) {
            throw new AuthException("查询游戏档案失败：" + result.summary());
        }
        return result.json();
    }

    /** 从档案中读取皮肤模型（default / slim）。 */
    private static String readSkinModel(JsonNode profile) {
        JsonNode skins = profile.get("skins");
        if (skins != null && skins.isArray() && !skins.isEmpty()) {
            String variant = JsonUtils.text(skins.get(0), "variant", "CLASSIC");
            return "SLIM".equalsIgnoreCase(variant) ? "slim" : "default";
        }
        return "default";
    }

    // ==================================================================
    // 错误信息
    // ==================================================================

    /** 读取 OAuth 错误码。 */
    private static String readError(HttpUtils.Result result) {
        try {
            return JsonUtils.text(result.json(), "error", "");
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 把微软的错误响应转成可操作的中文提示。
     * <p>最常见的两类问题是「未配置 Client ID」与「Client ID 不被接受」，
     * 后者对应 {@code AADSTS700016}。</p>
     */
    private AuthException microsoftError(String prefix, HttpUtils.Result result) {
        String error = readError(result);
        String description = "";
        try {
            description = JsonUtils.text(result.json(), "error_description", "");
        } catch (IOException ignored) {
            // 忽略
        }
        if (description.contains("AADSTS700016") || "unauthorized_client".equals(error)) {
            return new AuthException(prefix + "：所配置的微软 Client ID 不被接受。"
                    + "请在 Azure 门户注册一个应用（账户类型选「仅个人 Microsoft 账户」，"
                    + "启用「允许公共客户端流」），并把应用 ID 填入设置中的「微软 Client ID」。");
        }
        String detail = description.isBlank() ? result.summary() : description;
        return new AuthException(prefix + "：" + detail);
    }

    private AuthException describeXstsError(HttpUtils.Result result) {
        String xerr = "";
        try {
            JsonNode json = result.json();
            xerr = JsonUtils.text(json, "XErr", "");
        } catch (IOException ignored) {
            // 忽略
        }
        return switch (xerr) {
            case "2148916233" -> new AuthException("该微软账户还没有 Xbox 账户，"
                    + "请先在 xbox.com 登录一次完成创建后重试");
            case "2148916235" -> new AuthException("Xbox Live 在您所在的地区不可用");
            case "2148916236", "2148916237" -> new AuthException("该账户需要通过年龄验证（成人确认）");
            case "2148916238" -> new AuthException("该账户是儿童账户，需要先由家长将其加入家庭组");
            default -> new AuthException("XSTS 授权失败：" + result.summary());
        };
    }

    /**
     * 生效的 Client ID：只有编译期常量一处来源。
     *
     * <p><b>刻意不读配置</b>：开源版本把 Client ID 留空，由定制者在编译前填写。
     * 允许运行时覆盖的后果是任何人都能把发行版换成别人的应用登记 —— 那正是
     * Mojang 判定滥用、连带封禁一整批应用的原因，所以这里只认源码里的值。</p>
     *
     * <p>留空时返回空串，由 {@link #clientIdConfigured()} 供界面判断是否展示正版登录。</p>
     */
    public String effectiveClientId() {
        return clientId;
    }

    /** 本构建是否配置了可用的微软应用 ID（开源版本默认 false）。 */
    public boolean clientIdConfigured() {
        return !effectiveClientId().isBlank();
    }

    private String requireClientId() throws AuthException {
        String clientId = effectiveClientId();
        if (clientId.isBlank()) {
            throw new AuthException(I18n.t("account.microsoft.noClientId"));
        }
        // 记下这次到底用的是哪个应用：换 id 时，「日志里写的是不是我填的那个」是第一现场
        log.info("微软登录使用应用 {}", maskClientId(clientId));
        return clientId;
    }

    /** 只露前 8 位：够识别是哪个应用，又不至于被人从日志里整段抄走。 */
    private static String maskClientId(String clientId) {
        return clientId.length() <= 8 ? clientId : clientId.substring(0, 8) + "…";
    }

    /**
     * 轮询间隔内的小步等待：每 250ms 检查一次取消。
     *
     * <p>不能直接 {@code Thread.sleep(interval)}：设备码的间隔是 5 秒起、收到
     * {@code slow_down} 后还会加到十几秒，这段时间里点「停止」会完全没反应 ——
     * 用户只能得出「取消按钮没用」的结论（真实反馈）。</p>
     */
    private static void waitInterval(int seconds, ProgressListener listener) throws IOException {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            listener.throwIfCancelled();
            long leftMillis = Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L);
            try {
                Thread.sleep(Math.min(250L, leftMillis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
        }
        listener.throwIfCancelled();
    }
}
