package com.misaka.launcher.core.update;

import com.misaka.launcher.core.util.HashUtils;
import com.misaka.launcher.core.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ZooKeeper 更新客户端。
 *
 * <p>用本地 {@link HttpServer} 假服务端覆盖真实协议里最容易出错的三处：
 * 「服务端把原因写在 200 的 body 里」、「下载地址是相对路径」、
 * 「分块下载要逐块校验、且断点续传时能跳过已校验通过的块」。</p>
 */
class ZooKeeperUpdateClientTest {

    /** 假服务端也接真实的产品标识：客户端回显校验（见 check 实现）要能对上。 */
    private static final String PRODUCT = ZooKeeperUpdateClient.PRODUCT;
    /** 分块下载有体积门槛（小文件分块反而更慢），测试要跨过它才能验证并行分支。 */
    private static final int BIG_SIZE = 9 * 1024 * 1024;

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    /** 收到的请求连同请求头，用来断言「下载归属」真的发出去了。 */
    private final List<Received> received = new CopyOnWriteArrayList<>();

    /** 一次请求：路径 + 请求头（键大小写不敏感，便于按 HTTP 头名直接取）。 */
    private record Received(String path, Map<String, String> headers) {
    }

    private byte[] payload = new byte[0];
    private String fileName = "MisakaLauncher-1.0.1-any.jar";
    private String filePlatform = "any";
    private String fileArch = "any";
    private int chunkSize;
    private boolean hasUpdate = true;
    private String serverError;
    private String shaOverride;
    private int corruptChunk = -1;
    /** 最近一次检查请求的请求体（断言产品标识与平台/架构确实发出去了）。 */
    private volatile String lastCheckBody = "";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // ------------------------------------------------------------------
    // 检查
    // ------------------------------------------------------------------

    @Test
    void checkShouldResolveRelativeDownloadUrl() throws IOException {
        tinyPayload();

        ZooKeeperUpdateClient.CheckResult result = client().check("1.0.0", "stable");

        assertTrue(result.ok(), result.error());
        UpdateInfo info = result.info();
        assertNotNull(info);
        assertTrue(info.hasUpdate());
        assertEquals("1.0.1.0", info.latestVersion());
        assertEquals("1.0.1", info.latestLabel());
        // 服务端未配置 base_url 时只返回裸路径，客户端必须自己补全
        assertEquals(baseUrl() + "/api/v1/download/" + PRODUCT + "/1.0.1/" + fileName, info.downloadUrl());
        assertEquals(HashUtils.sha256Hex(payload), info.sha256());
        assertEquals(payload.length, info.fileSize());
        assertEquals(fileName, info.targetFileName());
        // 变更日志单独一个接口
        assertEquals("修复了小问题", client().fetchNotes(info.notesUrl()));
    }

    @Test
    void noUpdateShouldYieldNoDownloadUrl() throws IOException {
        tinyPayload();
        hasUpdate = false;

        UpdateInfo info = client().check("1.0.1", "stable").info();

        assertNotNull(info);
        assertFalse(info.hasUpdate());
        assertFalse(info.downloadable());
    }

    @Test
    void errorBodyShouldBecomeFailure() throws IOException {
        tinyPayload();
        hasUpdate = false;
        serverError = "产品不存在";

        ZooKeeperUpdateClient.CheckResult result = client().check("1.0.0", "stable");

        // 这类情况下 HTTP 状态码是 200 —— 只看状态码会把「产品没登记」当成「已是最新」
        assertFalse(result.ok());
        assertEquals("产品不存在", result.error());
    }

    @Test
    void shouldPickCurrentPlatformFile() throws IOException {
        String manifest = "{\"files\":["
                + "{\"name\":\"win.exe\",\"platform\":\"windows\",\"arch\":\"x64\"},"
                + "{\"name\":\"linux.jar\",\"platform\":\"linux\",\"arch\":\"x64\"},"
                + "{\"name\":\"any.jar\",\"platform\":\"any\",\"arch\":\"any\"}]}";
        var node = JsonUtils.read(manifest);

        assertEquals("linux.jar", ZooKeeperUpdateClient.selectFile(node, "linux", "x64")
                .get("name").asText());
        assertEquals("win.exe", ZooKeeperUpdateClient.selectFile(node, "windows", "x64")
                .get("name").asText());
        // 平台对不上时退化到 any/any，而不是硬塞一个别的平台的可执行文件
        assertEquals("any.jar", ZooKeeperUpdateClient.selectFile(node, "darwin", "arm64")
                .get("name").asText());
    }

    @Test
    void foreignPlatformOnlyShouldPickNothing() throws IOException {
        String manifest = "{\"files\":[{\"name\":\"linux.jar\",\"platform\":\"linux\",\"arch\":\"x64\"}]}";
        var node = JsonUtils.read(manifest);

        // 服务端做平台回落，拿回来的可能就是「别的平台登记过」的那一份；
        // 只有一个文件也不能将就 —— 形态校验拦得住 .exe/.sh，拦不住跨平台的 .jar
        assertNull(ZooKeeperUpdateClient.selectFile(node, "windows", "x64"),
                "明确写着 linux 的包不该被 Windows 选中");
        assertNull(ZooKeeperUpdateClient.selectFile(node, "linux", "arm64"),
                "架构对不上同样不该选中");
        assertEquals("linux.jar", ZooKeeperUpdateClient.selectFile(node, "linux", "x64")
                .get("name").asText());
    }

    @Test
    void samePlatformShouldPreferMatchingShape() throws IOException {
        // 服务端唯一键是「产品+版本+平台+架构」，所以单文件 exe 与可执行 jar 只能挂在同一条记录下
        String manifest = "{\"files\":["
                + "{\"name\":\"App-1.0.1-windows-portable.exe\",\"platform\":\"windows\",\"arch\":\"x64\"},"
                + "{\"name\":\"App-1.0.1-windows-amd64.jar\",\"platform\":\"windows\",\"arch\":\"x64\"}]}";
        var node = JsonUtils.read(manifest);

        assertEquals("App-1.0.1-windows-amd64.jar",
                ZooKeeperUpdateClient.selectFile(node, "windows", "x64", ".jar").get("name").asText(),
                "可执行 jar 形态应挑 jar，否则要等下载完才在替换时报形态不符");
        assertEquals("App-1.0.1-windows-portable.exe",
                ZooKeeperUpdateClient.selectFile(node, "windows", "x64", ".exe").get("name").asText());
        assertEquals("App-1.0.1-windows-portable.exe",
                ZooKeeperUpdateClient.selectFile(node, "windows", "x64").get("name").asText(),
                "识别不出运行形态时保持原行为：第一个平台匹配的文件");
    }

    @Test
    void matchingShapeShouldOutrankLocalMismatch() throws IOException {
        // 精确匹配的包若形态不对，装上去会被 requireSameKind 拦掉；而 any/any 声明「不分平台」，
        // 且形态一致，是真正能就地替换自己的那个
        String manifest = "{\"files\":["
                + "{\"name\":\"App-any-tool.exe\",\"platform\":\"windows\",\"arch\":\"x64\"},"
                + "{\"name\":\"App-all-platforms.jar\",\"platform\":\"any\",\"arch\":\"any\"}]}";
        var node = JsonUtils.read(manifest);

        assertEquals("App-all-platforms.jar",
                ZooKeeperUpdateClient.selectFile(node, "windows", "x64", ".jar").get("name").asText());
        assertEquals("App-any-tool.exe",
                ZooKeeperUpdateClient.selectFile(node, "windows", "x64", ".exe").get("name").asText());
    }

    @Test
    void checkRequestShouldCarryProductAndPlatform() throws IOException {
        tinyPayload();

        client().check("1.0.0", "stable");

        // 产品标识硬编码在客户端里；平台/架构由当前系统推导 ——
        // 服务端正是靠这两个字段挑出「适用于这个操作系统」的包
        assertEquals(ZooKeeperUpdateClient.PRODUCT, "MisakaLauncher");
        assertEquals(ZooKeeperUpdateClient.DISPLAY_NAME, "Misaka Launcher");
        assertTrue(lastCheckBody.contains("\"product\":\"" + ZooKeeperUpdateClient.PRODUCT + "\""),
                "请求体里应带产品标识：" + lastCheckBody);
        assertTrue(lastCheckBody.contains("\"platform\":\"" + ZooKeeperUpdateClient.currentPlatform() + "\""),
                "请求体里应带当前操作系统：" + lastCheckBody);
        assertTrue(lastCheckBody.contains("\"arch\":\"" + ZooKeeperUpdateClient.currentArch() + "\""),
                "请求体里应带当前架构：" + lastCheckBody);
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    @Test
    void fullDownloadShouldVerifyAndPersist(@TempDir Path dir) throws IOException {
        tinyPayload();
        Path dest = dir.resolve("update-" + fileName);

        client().download(client().check("1.0.0", "stable").info(), dest, nullListener());

        assertArrayEquals(payload, Files.readAllBytes(dest));
    }

    @Test
    void hashMismatchShouldNotPersist(@TempDir Path dir) throws IOException {
        tinyPayload();
        shaOverride = HashUtils.sha256Hex("这不是真实内容的哈希".getBytes(StandardCharsets.UTF_8));
        Path dest = dir.resolve("update-" + fileName);
        UpdateInfo info = client().check("1.0.0", "stable").info();

        assertThrows(IOException.class, () -> client().download(info, dest, nullListener()));
        assertFalse(Files.exists(dest), "校验失败的更新包绝不能落到目标路径");
    }

    @Test
    void chunkedDownloadShouldVerifyAndStitch(@TempDir Path dir) throws IOException {
        bigPayload();
        Path dest = dir.resolve("update-" + fileName);
        UpdateInfo info = client().check("1.0.0", "stable").info();

        client().download(info, dest, nullListener());

        assertArrayEquals(payload, Files.readAllBytes(dest));
        long chunkRequests = requests.stream().filter(path -> path.matches(".*/chunks/\\d+")).count();
        assertEquals(3, chunkRequests, "9 MB / 3 MB 应分成 3 块");
    }

    @Test
    void resumeShouldSkipVerifiedChunks(@TempDir Path dir) throws IOException {
        bigPayload();
        Path dest = dir.resolve("update-" + fileName);
        // 上次下载中断后留在磁盘上的 .part：内容完好，本次不应重新下载任何一块
        Files.write(dest.resolveSibling(dest.getFileName() + ".part"), payload);
        UpdateInfo info = client().check("1.0.0", "stable").info();

        client().download(info, dest, nullListener());

        assertArrayEquals(payload, Files.readAllBytes(dest));
        assertEquals(0, requests.stream().filter(path -> path.matches(".*/chunks/\\d+")).count(),
                "已校验通过的块不该重复下载：" + requests);
    }

    @Test
    void tamperedChunkShouldFail(@TempDir Path dir) throws IOException {
        bigPayload();
        corruptChunk = 1;
        Path dest = dir.resolve("update-" + fileName);
        UpdateInfo info = client().check("1.0.0", "stable").info();

        assertThrows(IOException.class, () -> client().download(info, dest, nullListener()));
        assertFalse(Files.exists(dest));
    }

    // ------------------------------------------------------------------
    // 假服务端
    // ------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.add(path);
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, String.join(", ", values)));
        received.add(new Received(path, headers));
        if (path.equals("/api/v1/updates/check")) {
            lastCheckBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, 200, checkResponse().getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (path.startsWith("/api/v1/changelog/")) {
            respond(exchange, 200, ("{\"total\":1,\"items\":[{\"version\":\"1.0.1.0\","
                    + "\"notes\":\"修复了小问题\"}]}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (path.endsWith("/chunks")) {
            respond(exchange, 200, chunkIndexResponse().getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (path.contains("/chunks/")) {
            int index = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
            int offset = index * chunkSize;
            int length = Math.min(chunkSize, payload.length - offset);
            byte[] slice = new byte[length];
            System.arraycopy(payload, offset, slice, 0, length);
            if (index == corruptChunk) {
                slice[0] = (byte) (slice[0] ^ 0xFF);
            }
            respond(exchange, 200, slice);
            return;
        }
        if (path.startsWith("/api/v1/download/")) {
            respond(exchange, 200, payload);
            return;
        }
        respond(exchange, 404, "{\"error\":{\"code\":\"not_found\"}}".getBytes(StandardCharsets.UTF_8));
    }

    private String checkResponse() {
        StringBuilder sb = new StringBuilder("{\"checked_at\":\"2026-10-13T00:00:00Z\",\"count\":1,\"results\":[{");
        sb.append("\"product\":\"").append(PRODUCT).append("\",\"current_version\":\"1.0.0\",");
        sb.append("\"has_update\":").append(hasUpdate);
        if (hasUpdate) {
            sb.append(",\"latest_version\":\"1.0.1.0\",\"channel\":\"stable\"");
            sb.append(",\"released_at\":\"2026-10-13T00:00:00Z\"");
            sb.append(",\"notes_url\":\"/api/v1/changelog/").append(PRODUCT).append("?version=1.0.1.0\"");
            sb.append(",\"download_url\":\"/api/v1/download/").append(PRODUCT).append("/1.0.1/")
                    .append(fileName).append("\"");
            sb.append(",\"manifest\":{\"schema\":1,\"product\":\"").append(PRODUCT)
                    .append("\",\"version\":\"1.0.1.0\",\"files\":[{")
                    .append("\"name\":\"").append(fileName).append("\",")
                    .append("\"platform\":\"").append(filePlatform).append("\",")
                    .append("\"arch\":\"").append(fileArch).append("\",")
                    .append("\"size\":").append(payload.length).append(',')
                    .append("\"sha256\":\"").append(expectedSha()).append("\",")
                    .append("\"chunk_size\":").append(chunkSize).append(',')
                    .append("\"chunk_count\":").append(chunkCount()).append(',')
                    .append("\"download_url\":\"/api/v1/download/").append(PRODUCT).append("/1.0.1/")
                    .append(fileName).append("\"}]}");
        }
        if (serverError != null) {
            sb.append(",\"error\":\"").append(serverError).append('"');
        }
        sb.append("}]}");
        return sb.toString();
    }

    private String chunkIndexResponse() {
        StringBuilder sb = new StringBuilder("{\"size\":").append(payload.length)
                .append(",\"chunk_size\":").append(chunkSize)
                .append(",\"chunk_count\":").append(chunkCount())
                .append(",\"chunks\":[");
        for (int index = 0; index < chunkCount(); index++) {
            int offset = index * chunkSize;
            int length = Math.min(chunkSize, payload.length - offset);
            if (index > 0) {
                sb.append(',');
            }
            sb.append("{\"index\":").append(index)
                    .append(",\"offset\":").append(offset)
                    .append(",\"length\":").append(length)
                    .append(",\"sha256\":\"").append(chunkSha(index, length)).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private String chunkSha(int index, int length) {
        int offset = index * chunkSize;
        byte[] slice = new byte[length];
        System.arraycopy(payload, offset, slice, 0, length);
        return HashUtils.sha256Hex(slice);
    }

    private String expectedSha() {
        return shaOverride != null ? shaOverride : HashUtils.sha256Hex(payload);
    }

    private int chunkCount() {
        return chunkSize <= 0 ? 1 : (payload.length + chunkSize - 1) / chunkSize;
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    // ------------------------------------------------------------------
    // 下载归属
    // ------------------------------------------------------------------

    @Test
    void downloadWithoutPasswordShouldSendClaimedUser(@TempDir Path dir) throws IOException {
        tinyPayload();
        Path dest = dir.resolve("update-" + fileName);

        client().download(client().check("1.0.0", "stable").info(), dest, nullListener());

        assertFalse(received.isEmpty());
        for (Received request : received) {
            assertEquals(ZooKeeperUpdateClient.DOWNLOAD_USER, request.headers().get("X-ZK-User"),
                    request.path() + " 没带自称用户名，这次下载在管理台就会记成 (anonymous)");
            // 服务端只要看到 Authorization: Basic 就一定会去校验账号口令（空口令直接判失败），
            // 所以「没口令」不能靠 Basic 冒充自称归属，否则下载会 401
            assertNull(request.headers().get("Authorization"),
                    request.path() + " 在没配口令时不该带 Basic 凭据");
        }
    }

    @Test
    void passwordShouldSwitchToBasicAuth() throws IOException {
        tinyPayload();

        new ZooKeeperUpdateClient(baseUrl(), "", 5, 4, "s3cret").check("1.0.0", "stable");

        Map<String, String> headers = headersOf("/api/v1/updates/check");
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                (ZooKeeperUpdateClient.DOWNLOAD_USER + ":s3cret").getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, headers.get("Authorization"), "配了口令就该升级成已验证归属");
        assertEquals(ZooKeeperUpdateClient.DOWNLOAD_USER, headers.get("X-ZK-User"),
                "自称用户名照旧带上：凭据被拒时也还有一条可读的归属线索");
    }

    @Test
    void apiKeyShouldBeSentWhenNoPassword() throws IOException {
        tinyPayload();

        new ZooKeeperUpdateClient(baseUrl(), "zk_deadbeef", 5, 4).check("1.0.0", "stable");

        Map<String, String> headers = headersOf("/api/v1/updates/check");
        assertEquals("zk_deadbeef", headers.get("X-API-Key"));
        assertNull(headers.get("Authorization"), "有 API Key 时不必再拼 Basic");
        assertEquals(ZooKeeperUpdateClient.DOWNLOAD_USER, headers.get("X-ZK-User"));
    }

    /** 指定路径最后一次请求的请求头；没收到则直接失败（避免断言悄悄落空）。 */
    private Map<String, String> headersOf(String path) {
        for (int i = received.size() - 1; i >= 0; i--) {
            if (path.equals(received.get(i).path())) {
                return received.get(i).headers();
            }
        }
        throw new AssertionError("假服务端没收到 " + path + "，实际收到 " + requests);
    }

    private void tinyPayload() {
        setPayload(new byte[512]);
    }

    /** 跨过 8 MB 的分块门槛：3 MB 一块，共 3 块。 */
    private void bigPayload() {
        setPayload(new byte[BIG_SIZE]);
        chunkSize = BIG_SIZE / 3;
    }

    private void setPayload(byte[] data) {
        // 固定内容，保证哈希稳定；纯 0 也够用，因为校验的是「字节一致」而不是「内容有意义」
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251);
        }
        payload = data;
        chunkSize = 0;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private ZooKeeperUpdateClient client() {
        return new ZooKeeperUpdateClient(baseUrl(), "", 5, 4);
    }

    private static com.misaka.launcher.core.task.ProgressListener nullListener() {
        return com.misaka.launcher.core.task.ProgressListener.NOOP;
    }
}
