package com.misaka.launcher.core.download;

import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.task.CancelledException;
import com.misaka.launcher.core.task.ProgressListener;
import com.misaka.launcher.core.util.HashUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下载引擎端到端验证：使用进程内 HTTP 桩服务，完全离线可测。
 * <p>覆盖：并发下载与哈希校验、幂等跳过、失败重试、断点续传、取消与进度单位选择。</p>
 */
class DownloadManagerTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String base;
    private final Map<String, byte[]> payloads = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();
    private final Map<String, String> lastRangeHeader = new ConcurrentHashMap<>();
    private final Set<String> failFirstRequest = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // 测试
    // ------------------------------------------------------------------

    @Test
    void shouldDownloadInParallelAndVerifyHash() throws Exception {
        DownloadManager manager = manager(4);
        List<DownloadItem> items = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            items.add(itemFor("/file" + i + ".bin", ("content-" + i).repeat(50).getBytes(StandardCharsets.UTF_8)));
        }

        DownloadReport report = manager.downloadAll(items, ProgressListener.NOOP);

        assertEquals(3, report.total());
        assertEquals(3, report.downloaded());
        assertEquals(0, report.failed());
        assertTrue(report.allSucceeded());
        assertTrue(report.bytes() > 0);
        for (DownloadItem item : items) {
            assertTrue(Files.isRegularFile(item.target()), "文件应已落盘: " + item.target());
            assertEquals(item.checksum(), HashUtils.sha1(item.target()), "落盘内容哈希应与下载条目的期望值一致");
        }
    }

    @Test
    void existingFileShouldSkipWithoutRequest() throws Exception {
        DownloadManager manager = manager(2);
        DownloadItem item = itemFor("/same.bin", "payload".repeat(30).getBytes(StandardCharsets.UTF_8));

        DownloadReport first = manager.downloadAll(List.of(item), ProgressListener.NOOP);
        assertEquals(1, first.downloaded());
        int requestsAfterFirst = requestCounts.get("/same.bin").get();

        DownloadReport second = manager.downloadAll(List.of(item), ProgressListener.NOOP);
        assertEquals(1, second.skipped(), "第二次应判定为已是最新并跳过");
        assertEquals(0, second.downloaded());
        assertEquals(0L, second.bytes());
        assertEquals(requestsAfterFirst, requestCounts.get("/same.bin").get(), "跳过不应产生任何网络请求");
    }

    @Test
    void hashMismatchShouldFailWithoutLeftovers() throws Exception {
        DownloadManager manager = manager(1);
        DownloadItem item = itemFor("/bad.bin", "abcdef".repeat(20).getBytes(StandardCharsets.UTF_8));
        DownloadItem broken = item.withChecksum("0".repeat(40), item.size());

        DownloadReport report = manager.downloadAll(List.of(broken), ProgressListener.NOOP);

        assertEquals(1, report.failed());
        assertTrue(report.hasRequiredFailure());
        assertFalse(Files.exists(item.target()), "校验失败的文件不应落地");
        assertFalse(Files.exists(item.target().resolveSibling(item.target().getFileName() + ".part")),
                "校验失败后应清理临时文件");
    }

    @Test
    void firstFailureShouldRetryAndSucceed() throws Exception {
        String path = "/flaky.bin";
        failFirstRequest.add(path);
        DownloadManager manager = manager(1);
        DownloadItem item = itemFor(path, "flaky-payload".repeat(20).getBytes(StandardCharsets.UTF_8));

        DownloadReport report = manager.downloadAll(List.of(item), ProgressListener.NOOP);

        assertTrue(report.allSucceeded(), "重试后应成功");
        assertEquals(2, requestCounts.get(path).get(), "应有两次请求：一次失败一次成功");
        assertTrue(Files.isRegularFile(item.target()));
    }

    @Test
    void existingPartialFileShouldResume() throws Exception {
        byte[] content = "0123456789".repeat(100).getBytes(StandardCharsets.UTF_8);
        DownloadItem item = itemFor("/resume.bin", content);

        // 预置前半段临时文件，模拟上次中断
        Path part = item.target().resolveSibling(item.target().getFileName() + ".part");
        Files.createDirectories(item.target().getParent());
        Files.write(part, java.util.Arrays.copyOf(content, content.length / 2));

        DownloadManager manager = manager(1);
        DownloadReport report = manager.downloadAll(List.of(item), ProgressListener.NOOP);

        assertTrue(report.allSucceeded());
        assertArrayEquals(content, Files.readAllBytes(item.target()), "续传后内容必须完整");
        assertTrue(lastRangeHeader.getOrDefault("/resume.bin", "").startsWith("bytes="),
                "应携带 Range 头，实际: " + lastRangeHeader.get("/resume.bin"));
    }

    @Test
    void userCancelShouldThrowCancelledException() throws Exception {
        DownloadManager manager = manager(1);
        DownloadItem item = itemFor("/cancel.bin", "x".repeat(1000).getBytes(StandardCharsets.UTF_8));

        ProgressListener cancelling = new ProgressListener() {
            @Override
            public boolean isCancelled() {
                return true;
            }
        };
        assertThrows(CancelledException.class, () -> manager.downloadAll(List.of(item), cancelling));
    }

    @Test
    void unknownLengthShouldReportItemProgress() throws Exception {
        DownloadManager manager = manager(1);
        DownloadItem item = itemFor("/unknown.bin", "y".repeat(500).getBytes(StandardCharsets.UTF_8));
        DownloadItem unknownSize = new DownloadItem(item.target(), item.url(), "", -1L,
                DownloadTargetType.OTHER, Map.of(), Map.of(), true);

        AtomicReference<long[]> last = new AtomicReference<>(new long[]{0, 0});
        ProgressListener capture = new ProgressListener() {
            @Override
            public void onProgress(long completed, long total) {
                last.set(new long[]{completed, total});
            }
        };

        DownloadReport report = manager.downloadAll(List.of(unknownSize), capture);

        assertTrue(report.allSucceeded());
        assertEquals(1L, last.get()[1], "长度未知时应以条目总数作为进度总量");
        assertEquals(1L, last.get()[0]);
    }

    @Test
    void preflightFailureShouldSkipBadSourceForWholeBatch() throws Exception {
        // 资产上千个：逐文件试坏源要白等上千次；预检失败必须让整批直接改走健康源
        HttpServer bad = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger badHits = new AtomicInteger();
        bad.createContext("/", exchange -> {
            badHits.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        bad.setExecutor(Executors.newFixedThreadPool(2));
        bad.start();
        try {
            String badBase = "http://127.0.0.1:" + bad.getAddress().getPort();
            LauncherConfig.DownloadSettings settings = new LauncherConfig.DownloadSettings();
            settings.concurrency = 4;
            settings.retries = 3;
            settings.timeoutSeconds = 10;
            SourceResolver resolver = new SourceResolver(settings, List.of(
                    stubSource("bad", 5, badBase),
                    stubSource("good", 10, base)));
            DownloadManager manager = new DownloadManager(resolver, settings);

            List<DownloadItem> items = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                byte[] content = ("preflight-" + i).getBytes(StandardCharsets.UTF_8);
                String path = "/preflight" + i + ".bin";
                payloads.put(path, content);
                items.add(new DownloadItem(tempDir.resolve("out-preflight" + i + ".bin"),
                        base + path, HashUtils.sha1(content), content.length,
                        DownloadTargetType.OTHER, Map.of(), Map.of(), true));
            }

            DownloadReport report = manager.downloadAll(items, ProgressListener.NOOP);

            assertTrue(report.allSucceeded(), "全部文件应经健康源下载成功");
            assertEquals(40, report.downloaded());
            assertEquals(1, badHits.get(), "仅预检探测命中一次坏源；40 个文件不应再逐个尝试");
        } finally {
            bad.stop(0);
        }
    }

    @Test
    void filesFailingFirstRoundShouldBeRetried() throws Exception {
        // 国内到官方库的连接会随机被重置：同一文件下一轮往往就成功。
        // 少量文件首轮失败就整体判定失败，代价是让用户重下几百 MB
        HttpServer flaky = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        Map<String, byte[]> bodies = new java.util.concurrent.ConcurrentHashMap<>();
        flaky.createContext("/", exchange -> {
            // 前 6 次请求全部失败：恰好让两个文件（各 3 次尝试）都在首轮耗尽预算
            if (hits.incrementAndGet() <= 6) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            byte[] data = bodies.get(exchange.getRequestURI().getPath());
            if (data == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
            exchange.close();
        });
        flaky.setExecutor(Executors.newFixedThreadPool(2));
        flaky.start();
        try {
            String flakyBase = "http://127.0.0.1:" + flaky.getAddress().getPort();
            LauncherConfig.DownloadSettings settings = new LauncherConfig.DownloadSettings();
            settings.source = LauncherConfig.DownloadSettings.SOURCE_OFFICIAL;
            settings.concurrency = 1;
            settings.retries = 3;
            settings.timeoutSeconds = 10;
            DownloadManager manager = new DownloadManager(new SourceResolver(settings), settings);

            List<DownloadItem> items = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                byte[] content = ("retry-" + i).getBytes(StandardCharsets.UTF_8);
                bodies.put("/retry" + i + ".bin", content);
                items.add(new DownloadItem(tempDir.resolve("out-retry" + i + ".bin"),
                        flakyBase + "/retry" + i + ".bin", HashUtils.sha1(content), content.length,
                        DownloadTargetType.OTHER, Map.of(), Map.of(), true));
            }

            DownloadReport report = manager.downloadAll(items, ProgressListener.NOOP);

            assertTrue(report.allSucceeded(),
                    "首轮全部失败后应由第二轮补下成功：" + report.failures());
            assertEquals(2, report.downloaded());
        } finally {
            flaky.stop(0);
        }
    }

    @Test
    void emptyListShouldReturnImmediately() {
        DownloadManager manager = manager(2);
        DownloadReport report = manager.downloadAll(List.of(), ProgressListener.NOOP);
        assertEquals(0, report.total());
        assertTrue(report.allSucceeded());
    }

    @Test
    void repeatedFailuresShouldTripCircuitBreaker() throws Exception {
        // 模拟 BMCLAPI 资产 CDN 不可达：坏源每个文件都先试一次、失败再落到健康源，
        // 没有熔断时上千个资产就要白等上千次超时
        HttpServer bad = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger badHits = new AtomicInteger();
        bad.createContext("/", exchange -> {
            badHits.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        bad.setExecutor(Executors.newFixedThreadPool(2));
        bad.start();
        try {
            String badBase = "http://127.0.0.1:" + bad.getAddress().getPort();
            LauncherConfig.DownloadSettings settings = new LauncherConfig.DownloadSettings();
            settings.concurrency = 1;
            settings.retries = 3;
            settings.timeoutSeconds = 10;
            SourceResolver resolver = new SourceResolver(settings, List.of(
                    stubSource("bad", 5, badBase),
                    stubSource("good", 10, base)));
            DownloadManager manager = new DownloadManager(resolver, settings);

            List<DownloadItem> items = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                byte[] content = ("payload-" + i).getBytes(StandardCharsets.UTF_8);
                String path = "/breaker" + i + ".bin";
                payloads.put(path, content);
                items.add(new DownloadItem(tempDir.resolve("out-breaker" + i + ".bin"),
                        base + path, HashUtils.sha1(content), content.length,
                        DownloadTargetType.OTHER, Map.of(), Map.of(), true));
            }

            DownloadReport report = manager.downloadAll(items, ProgressListener.NOOP);

            assertTrue(report.allSucceeded(), "全部文件应经健康源下载成功");
            assertEquals(5, report.downloaded());
            assertEquals(3, badHits.get(),
                    "坏源连续失败 3 次后应熔断：其余文件不应再逐个尝试坏源");
        } finally {
            bad.stop(0);
        }
    }

    @Test
    void failFastShouldTryEachCandidateOnce() throws Exception {
        // 预置场景（Forge 安装器要用的运行库）：镜像上没有的构件必须立刻放弃、
        // 换下一个镜像。默认策略的「候选 × retries 次 + 退避」会让 14 个不存在的
        // 构件白等 139 秒（实测），而安装器本来就能自己生成它们。
        HttpServer missing = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        missing.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        missing.setExecutor(Executors.newFixedThreadPool(2));
        missing.start();
        try {
            String base = "http://127.0.0.1:" + missing.getAddress().getPort();
            LauncherConfig.DownloadSettings settings = new LauncherConfig.DownloadSettings();
            settings.source = LauncherConfig.DownloadSettings.SOURCE_OFFICIAL;
            settings.concurrency = 1;
            settings.retries = 3;
            settings.timeoutSeconds = 10;
            DownloadManager manager = new DownloadManager(new SourceResolver(settings), settings);
            DownloadItem item = new DownloadItem(tempDir.resolve("out-fast.bin"),
                    base + "/nope.jar", "", -1L, DownloadTargetType.OTHER, Map.of(), Map.of(), true);

            DownloadReport fast = manager.downloadAllFast(List.of(item), ProgressListener.NOOP);

            assertEquals(1, fast.failed());
            assertEquals(1, hits.get(), "快速失败模式：每个候选只请求一次");

            // 对照：默认策略会反复重试（同一份候选、同一个不存在的文件）
            manager.downloadAll(List.of(item), ProgressListener.NOOP);
            assertTrue(hits.get() > 1, "默认模式应当重试；实际请求数 " + hits.get());
        } finally {
            missing.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // 测试基础设施
    // ------------------------------------------------------------------

    private DownloadManager manager(int concurrency) {
        LauncherConfig.DownloadSettings settings = new LauncherConfig.DownloadSettings();
        // 使用官方源模式：不做 URL 改写，候选即本地桩地址
        settings.source = LauncherConfig.DownloadSettings.SOURCE_OFFICIAL;
        settings.concurrency = concurrency;
        settings.retries = 3;
        settings.timeoutSeconds = 10;
        return new DownloadManager(new SourceResolver(settings), settings);
    }

    /** 桩下载源：把任何官方地址改写到指定基址（用于构造多候选场景）。 */
    private DownloadSource stubSource(String id, int priority, String targetBase) {
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

    private DownloadItem itemFor(String path, byte[] content) {
        payloads.put(path, content);
        requestCounts.computeIfAbsent(path, k -> new AtomicInteger());
        return new DownloadItem(tempDir.resolve("out" + path.replace('/', '_')),
                base + path, HashUtils.sha1(content), content.length, DownloadTargetType.OTHER,
                Map.of(), Map.of(), true);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requestCounts.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();

        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range != null) {
            lastRangeHeader.put(path, range);
        }

        byte[] data = payloads.get(path);
        if (data == null) {
            respond(exchange, 404, "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (failFirstRequest.contains(path) && requestCounts.get(path).get() == 1) {
            respond(exchange, 500, "boom".getBytes(StandardCharsets.UTF_8));
            return;
        }

        if (range != null && range.startsWith("bytes=")) {
            long start = Long.parseLong(range.substring("bytes=".length()).replace("-", "").trim());
            if (start >= data.length) {
                respond(exchange, 416, new byte[0]);
                return;
            }
            byte[] slice = java.util.Arrays.copyOfRange(data, (int) start, data.length);
            exchange.getResponseHeaders().add("Content-Range",
                    "bytes " + start + "-" + (data.length - 1) + "/" + data.length);
            respond(exchange, 206, slice);
            return;
        }

        respond(exchange, 200, data);
    }

    private void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        try {
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } finally {
            exchange.close();
        }
    }
}
