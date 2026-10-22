package com.misaka.launcher.core.auth;

import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.task.CancelledException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 浏览器登录用的本地回环回调服务器。
 *
 * <p><b>为什么需要它</b>：正版登录的顺畅做法是「点登录 → 拉起系统浏览器 → 用户在网页里
 * 输密码（含两步验证）→ 浏览器跳回本地地址 → 启动器拿到授权码」。
 * 这样密码只出现在微软自己的页面上，启动器永远不接触密码；相比设备码流程，
 * 用户不需要抄短码、也不需要在另一台设备上操作。</p>
 *
 * <p><b>回环地址的选择</b>：只监听 {@link InetAddress#getLoopbackAddress() 回环地址}
 * （不暴露到局域网），端口交给系统分配（{@code 0}）。微软对回环重定向地址
 * <b>忽略端口差异</b>，因此用户只需在 Azure 应用里登记一次 {@code http://localhost}，
 * 之后每次端口不同也能正常回调 —— 这也是各家启动器的通行做法。</p>
 *
 * <p>注册应用时请把重定向地址填成 <b>{@code http://localhost}</b>，
 * 并在「身份验证」里启用<b>公共客户端流</b>。</p>
 */
public final class LoopbackCallbackServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LoopbackCallbackServer.class);

    /** 轮询间隔：等待回调期间用它兼顾「尽快返回」与「不空转」。 */
    private static final long POLL_MILLIS = 250L;

    private final HttpServer server;
    private final CompletableFuture<String> result = new CompletableFuture<>();

    public LoopbackCallbackServer() throws IOException {
        // 明确绑 127.0.0.1，而不是 InetAddress.getLoopbackAddress()：
        // 双栈机器上后者可能只监听 ::1，而浏览器访问 http://localhost:port 通常先试 IPv4，
        // 于是回调连不上、登录卡死。绑 IPv4 回环、URL 仍写 localhost（微软要求的回环写法）
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 挂在根路径：微软把授权码回跳到「http://localhost:<port>」，路径为空；
        // 浏览器还会顺带请求 /favicon.ico，这些一并由同一个处理器兜住
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "misaka-msauth");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    /**
     * 回调地址（要登记到 Azure 应用里，且必须与令牌交换时提交的一致）。
     *
     * <p>不带路径：与登记 {@code http://localhost} 的写法完全对应。</p>
     */
    public String redirectUri() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /**
     * 阻塞等待浏览器回调，返回授权码。
     *
     * @param timeout  等待上限（用户可能去忙别的了）
     * @param progress 进度提示；可为 null
     * @throws AuthException 用户拒绝授权、回调带回错误，或等待超时
     */
    public String awaitCode(Duration timeout, Consumer<String> progress) throws IOException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        int tick = 0;
        while (System.currentTimeMillis() < deadline) {
            try {
                return result.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                tick++;
                // 每 4 秒刷一次提示即可，避免状态栏刷屏
                if (progress != null && tick % 16 == 0) {
                    long left = Math.max(0, (deadline - System.currentTimeMillis()) / 1000);
                    progress.accept("等待浏览器完成授权…（剩余 " + left + " 秒）");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AuthException("等待授权被中断");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof CancelledException cancelled) {
                    // 用户取消要原样上抛：包成 AuthException 就会被当成登录失败
                    throw cancelled;
                }
                if (cause instanceof AuthException auth) {
                    throw auth;
                }
                throw new AuthException("等待授权失败：" + (cause == null ? "" : cause.getMessage()));
            }
        }
        throw new AuthException("等待授权超时（浏览器里是否完成了登录？）");
    }

    /**
     * 主动放弃等待（用户点了取消、或窗口被关闭）。
     *
     * <p>用 {@link CancelledException} 而不是 {@code AuthException}：前者会被任务中心判成
     * 「用户取消」（不弹报错、不写失败日志），后者会显示成一次失败 —— 用户自己按的取消
     * 不该被当成错误。</p>
     */
    public void cancel() {
        result.completeExceptionally(new CancelledException());
    }

    @Override
    public void close() {
        cancel();
        server.stop(0);
    }

    // ------------------------------------------------------------------
    // 回调处理
    // ------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String code = query.get("code");
            String error = query.get("error");
            if (code != null && !code.isBlank()) {
                respond(exchange, 200, successPage());
                // 成功页先发出去，再唤醒等待方：用户看到"可以关掉这个页面"与登录完成是同一瞬间
                result.complete(code);
                return;
            }
            if (error != null && !error.isBlank()) {
                String description = query.getOrDefault("error_description", "");
                // 页面上不该出现 access_denied 这种原始代码：用同一句人话喂给页面与异常
                String reason = describeError(error, description);
                respond(exchange, 200, failurePage(reason));
                result.completeExceptionally(new AuthException(reason));
                return;
            }
            // 其它请求（favicon 等）不该打断流程
            respond(exchange, 404, "<html><body>Not found</body></html>");
        } catch (RuntimeException e) {
            log.debug("处理回环回调出错: {}", e.getMessage());
            respond(exchange, 500, "<html><body>Error</body></html>");
        }
    }

    private static String describeError(String error, String description) {
        if (description != null && !description.isBlank()) {
            return "授权失败：" + description;
        }
        return switch (error) {
            case "access_denied" -> "已取消授权（在微软页面上点了拒绝或返回）";
            default -> "授权失败：" + error;
        };
    }

    private static void respond(HttpExchange exchange, int status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        // 回调页只该看一次：不缓存；万一将来在页面里加了外链，也别把带 code 的地址当 referrer 传出去
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.getResponseHeaders().add("Referrer-Policy", "no-referrer");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** 解析查询串；重复键取第一个。 */
    static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            values.putIfAbsent(decode(key), decode(value));
        }
        return values;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static String successPage() {
        return page(true, "登录成功", "已收到微软授权，启动器正在完成剩余步骤。",
                "看到账户出现在列表里之后，可以直接关闭这个页面。");
    }

    private static String failurePage(String detail) {
        return page(false, "登录未完成", detail, "请回到启动器重试；原因也会写进启动器日志。");
    }

    /**
     * 浏览器回调页。
     *
     * <p><b>这是启动器唯一会出现在用户浏览器里的界面</b>，所以按产品页面来做：</p>
     * <ul>
     *   <li>自包含：不引用任何外部字体/图片/CDN —— 登录刚结束时网络状况不可控，
     *       也不能让一个「本地回调页」去请求外网；</li>
     *   <li>跟随系统深浅色（{@code prefers-color-scheme}），因为浏览器主题与启动器主题无关；</li>
     *   <li>成功页顺手把地址栏里的 {@code ?code=…} 抹掉并尝试自动关窗 —— 授权码只该出现一次。</li>
     * </ul>
     *
     * <p>占位符用 {@link String#replace} 而不是 {@code formatted}：样式表里大量 {@code %}，
     * 用格式化就得逐个转义成 {@code %%}，以后改一次样式错一次。</p>
     */
    private static String page(boolean success, String title, String detail, String hint) {
        return PAGE_TEMPLATE
                .replace("{{TONE}}", success ? "ok" : "warn")
                .replace("{{ICON}}", success ? ICON_CHECK : ICON_WARN)
                .replace("{{TITLE}}", escape(title))
                .replace("{{DETAIL}}", escape(detail))
                .replace("{{HINT}}", escape(hint))
                .replace("{{BRAND}}", escape(I18n.t("app.name")))
                .replace("{{SCRIPT}}", success ? SUCCESS_SCRIPT : "");
    }

    /** 打勾路径：{@code stroke-dasharray} 做描边动画，不依赖任何图片资源。 */
    private static final String ICON_CHECK = "<path class=\"mark\" d=\"M4.6 12.7l4.9 4.9L19.4 7.5\"/>";

    /** 感叹号路径：与打勾共用同一套描边动画。 */
    private static final String ICON_WARN =
            "<path class=\"mark\" d=\"M12 6.1v7.6\"/><path class=\"mark dot\" d=\"M12 17.7h.01\"/>";

    /**
     * 成功页的小脚本：清掉地址栏里的授权码，再试着关掉这个窗口。
     *
     * <p>由外部程序拉起的浏览器窗口通常<b>不允许</b>脚本自行关闭，所以这里只「尝试」——
     * 关不掉就靠页面上的文字提示，用户不会卡住。</p>
     */
    private static final String SUCCESS_SCRIPT = """
            <script>
              if (location.search) history.replaceState(null, '', location.pathname);
              setTimeout(function () { try { window.close(); } catch (e) {} }, 2200);
            </script>
            """;

    /** 页面骨架：两套主题变量 + 一张玻璃卡片 + 描边动画图标。 */
    private static final String PAGE_TEMPLATE = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="robots" content="noindex">
            <title>{{TITLE}}</title>
            <style>
              :root {
                color-scheme: dark light;
                --bg: #0A0D14;
                --card: rgba(255,255,255,.05);
                --border: rgba(255,255,255,.10);
                --fg: #E7EAF3;
                --muted: #98A2B8;
                --ok: #34D399;
                --warn: #FB923C;
                --glow: rgba(124,92,255,.26);
                --shadow: 0 30px 70px rgba(0,0,0,.42);
              }
              @media (prefers-color-scheme: light) {
                :root {
                  --bg: #F4F6FB;
                  --card: #FFFFFF;
                  --border: rgba(17,24,39,.10);
                  --fg: #111827;
                  --muted: #5C6478;
                  --glow: rgba(107,77,255,.16);
                  --shadow: 0 24px 60px rgba(17,24,39,.12);
                }
              }
              * { box-sizing: border-box; }
              html, body { height: 100%; }
              body {
                margin: 0; padding: 24px;
                display: grid; place-items: center;
                background:
                  radial-gradient(920px 520px at 50% -12%, var(--glow), transparent 68%),
                  radial-gradient(760px 460px at 108% 112%, var(--glow), transparent 66%),
                  var(--bg);
                color: var(--fg);
                font-family: system-ui, -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif;
                -webkit-font-smoothing: antialiased;
              }
              .card {
                width: min(430px, 100%);
                padding: 42px 36px 30px;
                text-align: center;
                background: var(--card);
                border: 1px solid var(--border);
                border-radius: 22px;
                box-shadow: var(--shadow);
                -webkit-backdrop-filter: blur(14px);
                backdrop-filter: blur(14px);
                animation: rise .5s cubic-bezier(.2,.8,.2,1) both;
              }
              @keyframes rise {
                from { opacity: 0; transform: translateY(14px); }
                to { opacity: 1; transform: none; }
              }
              .badge {
                width: 68px; height: 68px; margin: 0 auto 22px;
                border-radius: 50%;
                display: grid; place-items: center;
              }
              .badge.ok {
                background: rgba(52,211,153,.14);
                box-shadow: inset 0 0 0 1px rgba(52,211,153,.30);
                color: var(--ok);
              }
              .badge.warn {
                background: rgba(251,146,60,.14);
                box-shadow: inset 0 0 0 1px rgba(251,146,60,.30);
                color: var(--warn);
              }
              svg { overflow: visible; }
              .mark {
                fill: none; stroke: currentColor; stroke-width: 2.6;
                stroke-linecap: round; stroke-linejoin: round;
                stroke-dasharray: 34; stroke-dashoffset: 34;
                animation: draw .55s .15s ease forwards;
              }
              .dot { stroke-dasharray: 3; stroke-dashoffset: 3; }
              @keyframes draw { to { stroke-dashoffset: 0; } }
              h1 {
                margin: 0 0 10px;
                font-size: 23px; font-weight: 600; letter-spacing: .2px;
              }
              .detail {
                margin: 0;
                font-size: 14.5px; line-height: 1.75;
                color: var(--muted);
                word-break: break-word;
              }
              .hint {
                margin: 20px 0 0; padding-top: 18px;
                border-top: 1px solid var(--border);
                font-size: 13px; line-height: 1.7;
                color: var(--muted); opacity: .85;
              }
              .brand {
                margin-top: 24px;
                font-size: 11.5px; letter-spacing: .8px;
                color: var(--muted); opacity: .55;
              }
            </style>
            </head>
            <body>
              <main class="card">
                <div class="badge {{TONE}}">
                  <svg width="32" height="32" viewBox="0 0 24 24" aria-hidden="true">{{ICON}}</svg>
                </div>
                <h1>{{TITLE}}</h1>
                <p class="detail">{{DETAIL}}</p>
                <p class="hint">{{HINT}}</p>
                <div class="brand">{{BRAND}} · 微软正版登录</div>
              </main>
            {{SCRIPT}}
            </body>
            </html>
            """;

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
