package com.misaka.launcher.core.meta;

import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.download.DownloadSource;
import com.misaka.launcher.core.download.DownloadTargetType;
import com.misaka.launcher.core.download.SourceGate;
import com.misaka.launcher.core.download.SourceResolver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 元数据抓取的源降级验证（离线，用进程内桩服务）。
 *
 * <p>复现的是 BMCLAPI 存储故障的真实形态：<b>秒回 302 但跳转后拿不到内容</b>。
 * 这种情况必须快速判定为不可用并降级 —— 否则界面上的「获取版本」会长时间空转甚至失败。</p>
 */
class MetaFetcherTest {

    private HttpServer mirror;
    private HttpServer official;
    private String mirrorBase;
    private String officialBase;
    /** 镜像被请求的次数：健康检查通过后就不该再被碰。 */
    private final AtomicInteger mirrorHits = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        mirror = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mirror.createContext("/", this::handleMirror);
        mirror.setExecutor(Executors.newFixedThreadPool(2));
        mirror.start();
        mirrorBase = "http://127.0.0.1:" + mirror.getAddress().getPort();

        official = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        official.createContext("/", exchange -> respond(exchange, 200, "{\"versions\":[]}"));
        official.setExecutor(Executors.newFixedThreadPool(2));
        official.start();
        officialBase = "http://127.0.0.1:" + official.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (mirror != null) {
            mirror.stop(0);
        }
        if (official != null) {
            official.stop(0);
        }
    }

    /** 镜像故障形态：直接返回 302，没有 Location、也没有内容。 */
    private void handleMirror(HttpExchange exchange) throws IOException {
        mirrorHits.incrementAndGet();
        try (var ignored = exchange.getRequestBody()) {
            // 丢弃请求体
        }
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (var ignored = exchange.getRequestBody()) {
            // 丢弃请求体
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    @Test
    void redirectOnlyMirrorShouldDegradeOnce() throws Exception {
        LauncherConfig.DownloadSettings settings = new LauncherConfig.DownloadSettings();
        settings.source = LauncherConfig.DownloadSettings.SOURCE_AUTO;
        SourceResolver resolver = new SourceResolver(settings, List.of(
                stubSource("mirror", 5, mirrorBase),
                stubSource("official", 10, officialBase)));
        MetaFetcher fetcher = new MetaFetcher(resolver, Duration.ofSeconds(5), new SourceGate());

        String officialUrl = officialBase + "/mc/game/version_manifest_v2.json";

        String first = fetcher.fetchString(officialUrl, DownloadTargetType.VERSION_MANIFEST);
        assertTrue(first.contains("versions"), "应从官方源拿到内容：" + first);
        assertEquals(1, mirrorHits.get(), "镜像只应被预检命中一次，随即被熔断");

        String second = fetcher.fetchString(officialUrl, DownloadTargetType.VERSION_MANIFEST);
        assertTrue(second.contains("versions"), "第二次也应成功");
        assertEquals(1, mirrorHits.get(), "已熔断的镜像不应再被触碰");
    }

    @Test
    void singleFlakeShouldNotFailFetch() throws Exception {
        // 模拟「握手被随机重置」：第一次请求直接断开，第二次正常
        AtomicInteger attempts = new AtomicInteger();
        HttpServer flaky = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        flaky.createContext("/", exchange -> {
            if (attempts.incrementAndGet() <= 1) {
                exchange.close();
                return;
            }
            respond(exchange, 200, "{\"versions\":[]}");
        });
        flaky.setExecutor(Executors.newFixedThreadPool(2));
        flaky.start();
        try {
            String flakyBase = "http://127.0.0.1:" + flaky.getAddress().getPort();
            LauncherConfig.DownloadSettings settings = new LauncherConfig.DownloadSettings();
            settings.source = LauncherConfig.DownloadSettings.SOURCE_AUTO;
            SourceResolver resolver = new SourceResolver(settings,
                    List.of(stubSource("flaky", 5, flakyBase)));
            MetaFetcher fetcher = new MetaFetcher(resolver, Duration.ofSeconds(5), new SourceGate());

            String body = fetcher.fetchString(flakyBase + "/mc/game/version_manifest_v2.json",
                    DownloadTargetType.VERSION_MANIFEST);

            assertTrue(body.contains("versions"), "抖动后重试应当成功：" + body);
            assertEquals(2, attempts.get(), "应在一次失败后重试");
        } finally {
            flaky.stop(0);
        }
    }

    /** 桩源：把官方地址改写到指定基址。 */
    private static DownloadSource stubSource(String id, int priority, String targetBase) {
        return new DownloadSource() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String name() {
                return id;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public String probeUrl() {
                return null;
            }

            @Override
            public String rewrite(String officialUrl, DownloadTargetType type, Map<String, String> context) {
                return targetBase + URI.create(officialUrl).getPath();
            }
        };
    }
}
