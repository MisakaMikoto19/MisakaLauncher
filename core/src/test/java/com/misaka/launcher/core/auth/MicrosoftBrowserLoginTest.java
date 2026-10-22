package com.misaka.launcher.core.auth;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 浏览器授权码登录的本地环回部分验证（完全离线）。
 *
 * <p>这些是「点登录之后」最容易悄悄坏掉的地方：回调收不到码、错误信息读不懂、
 * 授权地址里参数拼错 —— 任一环节出错，用户看到的都只是微软页面上的报错。</p>
 */
class MicrosoftBrowserLoginTest {

    /** 用真实 HTTP 请求模拟浏览器回调。 */
    private static String call(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        return response.body();
    }

    @Test
    void shouldExtractCodeFromCallback() throws Exception {
        try (LoopbackCallbackServer server = new LoopbackCallbackServer()) {
            String redirect = server.redirectUri();
            assertTrue(redirect.startsWith("http://localhost:"), "回环地址要用 localhost 写法：" + redirect);

            // 浏览器带回授权码
            String page = call(redirect + "?code=THE-CODE&state=x");
            assertTrue(page.contains("登录成功"), "应给用户一个明确的成功页面：" + page);

            assertEquals("THE-CODE", server.awaitCode(Duration.ofSeconds(5), null));
        }
    }

    @Test
    void deniedAuthorizationShouldProduceReadableReason() throws Exception {
        try (LoopbackCallbackServer server = new LoopbackCallbackServer()) {
            // 先把等待方放出去，避免回调到达时无人接收
            call(server.redirectUri() + "?error=access_denied&error_description=user%20cancelled");

            AuthException error = assertThrows(AuthException.class,
                    () -> server.awaitCode(Duration.ofSeconds(5), null));
            assertTrue(error.getMessage().contains("user cancelled"),
                    "应带上微软给的原因：" + error.getMessage());
        }
    }

    @Test
    void cancelShouldWakeUpWaiter() throws Exception {
        LoopbackCallbackServer server = new LoopbackCallbackServer();
        Thread waiter = new Thread(() -> assertThrows(AuthException.class,
                () -> server.awaitCode(Duration.ofSeconds(10), null)));
        waiter.setDaemon(true);
        waiter.start();
        Thread.sleep(300);

        server.cancel();
        waiter.join(3000);

        assertFalse(waiter.isAlive(), "取消后等待方应立刻返回，而不是继续挂着");
        server.close();
    }

    @Test
    void queryParsingShouldBeTolerant() {
        Map<String, String> parsed = LoopbackCallbackServer.parseQuery("code=a%2Bb&empty=&flag&code=dup");
        assertEquals("a+b", parsed.get("code"), "百分号编码应解码，重复键取第一个");
        assertEquals("", parsed.get("empty"));
        assertEquals("", parsed.get("flag"));
        assertTrue(LoopbackCallbackServer.parseQuery(null).isEmpty());
        assertTrue(LoopbackCallbackServer.parseQuery("").isEmpty());
    }

    @Test
    void authorizeUrlShouldCarryAllKeyParameters() {
        String url = AuthService.authorizeUrl("client-123", "http://localhost:4567", "CHALLENGE");

        assertTrue(url.startsWith("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?"),
                url);
        assertTrue(url.contains("client_id=client-123"), url);
        assertTrue(url.contains("response_type=code"), url);
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A4567"), url);
        assertTrue(url.contains("code_challenge=CHALLENGE"), url);
        assertTrue(url.contains("code_challenge_method=S256"), url);
        assertTrue(url.contains("scope=XboxLive.signin"), url);
        // 多人共用电脑时不该默默沿用上一次的登录态
        assertTrue(url.contains("prompt=select_account"), url);
    }

    @Test
    void PKCE挑战应符合RFC7636示例() {
        // RFC 7636 附录 B 的标准向量：算错就无法完成令牌交换
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                AuthService.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));

        String verifier = AuthService.pkceVerifier();
        assertTrue(verifier.length() >= 43 && verifier.length() <= 128,
                "PKCE 校验串长度须在 43~128 之间：" + verifier.length());
        assertTrue(verifier.matches("[A-Za-z0-9\\-._~]+"), "只能用 RFC 7636 的非保留字符：" + verifier);
        assertFalse(AuthService.pkceVerifier().equals(AuthService.pkceVerifier()),
                "每次登录都应生成新的校验串");
    }
}
