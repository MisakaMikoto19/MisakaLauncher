package com.misaka.launcher.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.misaka.launcher.core.BuildInfo;
import com.misaka.launcher.core.task.CancelledException;
import com.misaka.launcher.core.task.ProgressListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 JDK 内置 {@link HttpClient} 的 HTTP 工具。
 * <p>刻意不引入第三方 HTTP 库：减少运行时依赖，同时便于后续按需裁剪运行时镜像。
 * 所有下载均实现「.part 临时文件 → 哈希校验 → 原子改名」，并在服务端支持时断点续传。</p>
 */
public final class HttpUtils {

    private static final Logger log = LoggerFactory.getLogger(HttpUtils.class);

    /** 默认 User-Agent；Modrinth 等 API 要求提供可识别的 UA。版本号取自构建期（见 BuildInfo）。 */
    public static final String DEFAULT_USER_AGENT =
            "MisakaLauncher/" + BuildInfo.version() + " (+https://misakalauncher.org/)";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration META_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(300);
    private static final int BUFFER_SIZE = 128 * 1024;

    /**
     * 正文「停滞」阈值：连续这么久没收到任何新字节，就判定这条连接已经死了。
     *
     * <p>存在的理由：JDK {@link HttpClient} <b>没有流读超时</b> —— {@code timeout()} 只约束
     * 响应头到达时间。一旦服务端「连接建立了、响应头也给了，但正文字节迟迟不来」
     * （半开连接、镜像节点挂住、被中间设备静默丢包），{@code in.read()} 会永久阻塞：
     * 读循环里的取消检查永远轮询不到，批量下载的 {@code future.get()} 也永远不返回。
     * 用户看到的就是「下载一直下不下来、点停止没用、只能杀进程」。</p>
     */
    private static final Duration READ_STALL_TIMEOUT = Duration.ofSeconds(60);

    /** 停滞检查的轮询间隔，同时决定「取消」在流式下载中的生效粒度。 */
    private static final long STALL_CHECK_INTERVAL_MILLIS = 500L;

    /** 停滞看门狗：单线程守护线程池，所有下载共用（每个下载只挂一个定时任务，不占线程）。 */
    private static final ScheduledExecutorService STALL_WATCHER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "http-stall-watchdog");
                thread.setDaemon(true);
                return thread;
            });

    /** HTTP 206：与 {@code HttpURLConnection.HTTP_PARTIAL} 等价，避免引入无关依赖。 */
    private static final int HTTP_PARTIAL_CONTENT = 206;
    /** HTTP 416：请求区间越界，续传场景下可能意味着临时文件已达目标长度。 */
    private static final int HTTP_RANGE_NOT_SATISFIABLE = 416;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            // 部分国内镜像对 HTTP/2 支持不完整，固定使用 HTTP/1.1 以获得一致的连通性
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private HttpUtils() {
    }

    public static HttpClient client() {
        return CLIENT;
    }

    // ------------------------------------------------------------------
    // 请求头构造
    // ------------------------------------------------------------------

    /** 把 {@code k1,v1,k2,v2} 形式的变参转成有序 Map。 */
    public static Map<String, String> headers(String... keyValuePairs) {
        Map<String, String> map = new LinkedHashMap<>();
        if (keyValuePairs != null) {
            for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
                map.put(keyValuePairs[i], keyValuePairs[i + 1]);
            }
        }
        return map;
    }

    /**
     * 构造请求，默认附带 User-Agent，允许通过传入头覆盖。
     */
    public static HttpRequest.Builder request(String url, Duration timeout, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept-Encoding", "identity");
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return builder;
    }

    // ------------------------------------------------------------------
    // 文本 / JSON 获取
    // ------------------------------------------------------------------

    /** GET 文本，失败抛出 IOException（含非 2xx 状态码）。 */
    public static String getString(String url, Map<String, String> headers) throws IOException {
        return getString(url, headers, META_TIMEOUT);
    }

    /** GET 文本，可自定义超时。 */
    public static String getString(String url, Map<String, String> headers, Duration timeout) throws IOException {
        try {
            HttpResponse<String> response = CLIENT.send(
                    request(url, timeout, headers).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(url, response.statusCode());
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断: " + url, e);
        }
    }

    public static String getString(String url) throws IOException {
        return getString(url, Map.of());
    }

    /** GET JSON 并解析为树。 */
    public static JsonNode getJson(String url, Map<String, String> headers) throws IOException {
        String body = getString(url, headers);
        try {
            return JsonUtils.read(body);
        } catch (IOException e) {
            throw new IOException("响应不是合法 JSON: " + url, e);
        }
    }

    public static JsonNode getJson(String url) throws IOException {
        return getJson(url, Map.of());
    }

    /** GET 二进制内容（用于图标、头像等小体积资源）。 */
    public static byte[] getBytes(String url, Map<String, String> headers) throws IOException {
        try {
            HttpResponse<byte[]> response = CLIENT.send(
                    request(url, META_TIMEOUT, headers).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            ensureSuccess(url, response.statusCode());
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断: " + url, e);
        }
    }

    public static byte[] getBytes(String url) throws IOException {
        return getBytes(url, Map.of());
    }

    // ------------------------------------------------------------------
    // 通用请求（认证流程使用）
    // ------------------------------------------------------------------

    /**
     * 底层请求结果。
     * <p>刻意<b>不</b>因非 2xx 抛异常：认证类接口（设备码轮询、Xbox / XSTS 错误码）
     * 需要通过响应体中的错误字段做分支处理，抛异常会丢失这些信息。</p>
     *
     * @param status HTTP 状态码
     * @param body   响应文本
     */
    public record Result(int status, String body) {

        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        /** 解析响应体为 JSON 树。 */
        public JsonNode json() throws IOException {
            return JsonUtils.read(body);
        }

        /** 截断后的响应摘要，用于错误信息与日志（避免把超长响应体写进日志）。 */
        public String summary() {
            if (body == null) {
                return "HTTP " + status;
            }
            String trimmed = body.strip();
            return "HTTP " + status + " " + (trimmed.length() > 300 ? trimmed.substring(0, 300) + "…" : trimmed);
        }
    }

    /**
     * 发送请求并返回状态码与响应文本。
     *
     * @param method      HTTP 方法（GET / POST）
     * @param url         目标地址
     * @param headers     附加请求头
     * @param body        请求体；GET 或空体传 null
     * @param contentType 请求体类型；为空则按 JSON 处理
     */
    public static Result send(String method,
                              String url,
                              Map<String, String> headers,
                              String body,
                              String contentType,
                              Duration timeout) throws IOException {
        HttpRequest.Builder builder = request(url, timeout, headers);
        String upper = method == null ? "GET" : method.toUpperCase(java.util.Locale.ROOT);
        if ("GET".equals(upper) || "HEAD".equals(upper)) {
            builder.method(upper, HttpRequest.BodyPublishers.noBody());
        } else {
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            builder.method(upper, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<String> response = CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Result(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断: " + url, e);
        }
    }

    /**
     * HEAD 探测结果：只保留判断「远端是否变了」所需的两项。
     *
     * @param status        HTTP 状态码
     * @param contentLength 响应体长度；服务器未给出时为 -1
     * @param lastModified  {@code Last-Modified} 头；没有时为空串
     */
    public record Head(int status, long contentLength, String lastModified) {

        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * 发 HEAD 请求，取远端文件的长度与修改时间。
     *
     * <p>用途：模组的「官方地址」更新 —— 这类来源没有版本接口，只能靠
     * {@code Content-Length} / {@code Last-Modified} 判断是否发布过新文件。
     * 只读响应头，不下载正文，因此比「先下下来比哈希」便宜得多。</p>
     *
     * @throws IOException 网络失败或对方不支持 HEAD（405/501）时
     */
    public static Head head(String url, Duration timeout) throws IOException {
        HttpRequest.Builder builder = request(url, timeout, Map.of());
        builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
        try {
            HttpResponse<Void> response = CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.discarding());
            long length = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            String modified = response.headers().firstValue("Last-Modified").orElse("");
            return new Head(response.statusCode(), length, modified);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断: " + url, e);
        }
    }

    /** 表单编码常量，供认证接口复用。 */
    public static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";
    /** JSON 编码常量。 */
    public static final String JSON_CONTENT_TYPE = "application/json";

    /** 把键值对编码为 {@code application/x-www-form-urlencoded} 字符串。 */
    public static String formEncode(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(java.net.URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(),
                            StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    /**
     * 下载文件到目标路径。
     * <p>行为约定：</p>
     * <ol>
     *   <li>若目标文件已存在且 SHA-1 匹配，直接跳过（幂等，避免重复下载）；</li>
     *   <li>若 {@code dest.part} 存在且服务端支持 Range，则续传；否则从头下载；</li>
     *   <li>下载完成后校验 SHA-1/MD5，校验失败删除临时文件并抛错；</li>
     *   <li>通过 {@code expectedSize} 可对进度总量做预判，未知时总量传 -1。</li>
     * </ol>
     *
     * @param expectedChecksum 期望校验值（十六进制），可为 null（无校验）；
     *                         算法由长度自动识别（64=SHA-256 / 40=SHA-1 / 32=MD5）
     * @param expectedSize 期望字节数，未知传 -1
     * @param headers      附加请求头
     * @param timeout      响应头到达超时；正文字节流不受该超时约束（大文件场景的必要设计）
     * @param limiter      全局限速器，null 表示不限速
     */
    public static void download(URI uri,
                                Path dest,
                                ProgressListener listener,
                                String expectedChecksum,
                                long expectedSize,
                                Map<String, String> headers,
                                Duration timeout,
                                ByteRateLimiter limiter) throws IOException {

        listener.throwIfCancelled();
        ByteRateLimiter effectiveLimiter = limiter == null ? ByteRateLimiter.UNLIMITED : limiter;

        if (Files.isRegularFile(dest)) {
            try {
                if (HashUtils.verifyAuto(dest, expectedChecksum)) {
                    listener.onProgress(expectedSize > 0 ? expectedSize : Files.size(dest),
                            expectedSize > 0 ? expectedSize : Files.size(dest));
                    return;
                }
            } catch (IOException ignored) {
                // 校验失败视为需要重新下载
            }
        }

        FileUtils.ensureParent(dest);
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        long existing = Files.isRegularFile(part) ? Files.size(part) : 0L;
        if (expectedSize > 0 && existing > expectedSize) {
            // 上次中断留下了异常长度的临时文件，重新下载
            Files.deleteIfExists(part);
            existing = 0L;
        }

        try {
            HttpResponse<InputStream> response = sendDownload(uri, headers, existing, expectedSize, timeout);
            int status = response.statusCode();

            // 416 且本地临时文件长度恰好等于目标长度：说明上次其实已下载完成，
            // 直接进入校验阶段，无需再传输正文
            boolean alreadyComplete = status == HTTP_RANGE_NOT_SATISFIABLE
                    && existing > 0 && expectedSize > 0 && existing == expectedSize;
            // 206 Partial Content：服务端接受 Range，从断点续写；200 则从头覆盖写
            boolean resume = !alreadyComplete && existing > 0 && status == HTTP_PARTIAL_CONTENT;

            long written;
            if (alreadyComplete) {
                closeQuietly(response.body());
                written = existing;
            } else {
                if (status < 200 || status >= 300) {
                    // 失败响应：必须先关闭正文把连接归还连接池，否则会泄漏句柄
                    closeQuietly(response.body());
                    ensureSuccess(uri.toString(), status);
                }
                written = resume ? existing : 0L;
                InputStream body = response.body();
                ReadStallGuard guard = new ReadStallGuard(body, listener, uri);
                try (InputStream in = body;
                     OutputStream rawOut = openPart(part, resume)) {
                    BufferedOutputStream out = new BufferedOutputStream(rawOut, BUFFER_SIZE);
                    byte[] buffer = new byte[BUFFER_SIZE];
                    long total = expectedSize > 0 ? expectedSize : -1;
                    listener.onProgress(written, total);
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        listener.throwIfCancelled();
                        guard.onBytes();
                        effectiveLimiter.consume(read);
                        out.write(buffer, 0, read);
                        written += read;
                        listener.onProgress(written, total);
                    }
                    out.flush();
                    // 流被看门狗关掉时，某些实现对 read() 返回 EOF 而非抛异常 ——
                    // 这里补一次判定，避免把「被中断的半截文件」当成正常结束
                    if (guard.interrupted()) {
                        throw guard.explain(new IOException("下载连接被中断：" + uri));
                    }
                } catch (IOException e) {
                    // read() 是被看门狗关流打断的：把原因说清楚（停滞 / 已取消）
                    throw guard.explain(e);
                } finally {
                    guard.close();
                }
            }

            // 校验优先级：哈希 > 长度。
            // 哈希是内容层面的强保证；而长度是弱保证 —— 部分第三方来源声明的长度
            // 与实际并不完全一致（重压缩、多语言变体等），若长度优先会误杀正确文件。
            boolean hashVerified = false;
            if (expectedChecksum != null && !expectedChecksum.isBlank()) {
                if (HashUtils.verifyAuto(part, expectedChecksum)) {
                    hashVerified = true;
                } else {
                    String actual = HashUtils.hashAuto(part, expectedChecksum);
                    Files.deleteIfExists(part);
                    throw new IOException("下载文件校验失败：" + uri + "（算法 "
                            + HashUtils.algorithmFor(expectedChecksum) + "，期望 " + expectedChecksum
                            + "，实际 " + actual + "）");
                }
            }
            if (!hashVerified && expectedSize > 0 && written != expectedSize) {
                Files.deleteIfExists(part);
                throw new IOException("下载长度不符：期望 " + expectedSize + " 字节，实际 " + written
                        + " 字节，来源 " + uri);
            }
            if (hashVerified && expectedSize > 0 && written != expectedSize) {
                // 哈希通过说明内容正确，此处仅提示来源元数据不准，不影响使用
                org.slf4j.LoggerFactory.getLogger(HttpUtils.class)
                        .warn("来源声明的长度与实际不一致（已通过哈希校验，继续使用）：{} 声明 {} 实际 {}",
                                uri, expectedSize, written);
            }

            FileUtils.move(part, dest);
        } catch (IOException e) {
            // 保留 .part 以便下次续传；仅在哈希/长度错误时内部已删除
            throw e;
        }
    }

    public static void download(URI uri, Path dest, ProgressListener listener, String expectedChecksum, long expectedSize,
                                Map<String, String> headers) throws IOException {
        download(uri, dest, listener, expectedChecksum, expectedSize, headers, DOWNLOAD_TIMEOUT, null);
    }

    public static void download(URI uri, Path dest, ProgressListener listener, String expectedChecksum, long expectedSize)
            throws IOException {
        download(uri, dest, listener, expectedChecksum, expectedSize, Map.of());
    }

    public static void download(String url, Path dest, ProgressListener listener, String expectedChecksum, long expectedSize)
            throws IOException {
        download(URI.create(url), dest, listener, expectedChecksum, expectedSize, Map.of());
    }

    /** 打开临时文件：续写用 APPEND，全新下载用截断覆盖，避免残留旧字节。 */
    private static OutputStream openPart(Path part, boolean append) throws IOException {
        return append
                ? Files.newOutputStream(part, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                StandardOpenOption.CREATE)
                : Files.newOutputStream(part, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.CREATE);
    }

    private static void closeQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // 关闭失败不影响主流程
            }
        }
    }

    /**
     * 正文读取的停滞看门狗（理由见 {@link #READ_STALL_TIMEOUT}）。
     *
     * <p>独立定时任务周期性检查「是否还在收到新字节」与「用户是否已请求取消」，
     * 判定异常时<b>主动关闭正文流</b> —— 阻塞中的 {@code read()} 会立即抛
     * {@link IOException} 退出，把这次下载拉回「可超时、可取消、可换源重试」的正常路径。</p>
     */
    private static final class ReadStallGuard implements AutoCloseable {

        private final InputStream stream;
        private final URI uri;
        private final AtomicLong lastByteAt = new AtomicLong(System.currentTimeMillis());
        private final AtomicBoolean stalled = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final ScheduledFuture<?> task;

        ReadStallGuard(InputStream stream, ProgressListener listener, URI uri) {
            this.stream = stream;
            this.uri = uri;
            this.task = STALL_WATCHER.scheduleWithFixedDelay(() -> {
                if (!active.get()) {
                    return;
                }
                if (listener.isCancelled()) {
                    cancelled.set(true);
                    log.debug("下载被用户取消，断开连接：{}", uri);
                    // 触发一次就够：关流之后没必要再反复判定
                    active.set(false);
                    closeQuietly(stream);
                    return;
                }
                long silent = System.currentTimeMillis() - lastByteAt.get();
                if (silent >= READ_STALL_TIMEOUT.toMillis()) {
                    stalled.set(true);
                    log.warn("下载停滞 {} 秒未收到任何数据，主动断开以便换源重试：{}",
                            silent / 1000L, uri);
                    active.set(false);
                    closeQuietly(stream);
                }
            }, STALL_CHECK_INTERVAL_MILLIS, STALL_CHECK_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        }

        /** 每写入一段数据调用一次，告诉看门狗「这条连接还活着」。 */
        void onBytes() {
            lastByteAt.set(System.currentTimeMillis());
        }

        /** 是否因停滞或取消被主动断开（供读循环结束后补判，防止把半截文件当正常结束）。 */
        boolean interrupted() {
            return stalled.get() || cancelled.get();
        }

        /**
         * 把看门狗造成的 IOException 翻译成能说明白原因的异常。
         *
         * @return 与看门狗无关时原样返回 {@code cause}
         * @throws CancelledException 用户取消导致的断开：按「已取消」上报，而不是「失败」
         */
        private IOException explain(IOException cause) {
            if (cancelled.getAndSet(false)) {
                throw new CancelledException("下载已取消：" + uri);
            }
            if (stalled.getAndSet(false)) {
                return new IOException("下载停滞超过 " + READ_STALL_TIMEOUT.toSeconds()
                        + " 秒（连接已建立但收不到数据），已断开：" + uri, cause);
            }
            return cause;
        }

        @Override
        public void close() {
            active.set(false);
            task.cancel(false);
        }
    }

    private static HttpResponse<InputStream> sendDownload(URI uri, Map<String, String> headers, long existing,
                                                          long expectedSize, Duration timeout)
            throws IOException {
        HttpRequest.Builder builder = request(uri.toString(), timeout, headers).GET();
        if (existing > 0 && (expectedSize <= 0 || existing < expectedSize)) {
            builder.header("Range", "bytes=" + existing + "-");
        }
        try {
            return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断: " + uri, e);
        }
    }

    // ------------------------------------------------------------------
    // 连通性与延迟探测（下载源择优使用）
    // ------------------------------------------------------------------

    /**
     * 探测 URL 可用性并测量延迟。
     *
     * @param timeoutMillis 超时毫秒数
     * @return 往返毫秒数；失败返回 -1
     */
    public static long probe(String url, long timeoutMillis) {
        long start = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .header("Range", "bytes=0-0")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                in.read();
            }
            // 同样只认 2xx：能秒回 302 但跟随过去拿不到内容的源不算「可达」
            if (isSuccess(response.statusCode())) {
                return (System.nanoTime() - start) / 1_000_000L;
            }
            return -1L;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return -1L;
        }
    }

    /** 探测延迟并返回毫秒数，失败为 -1；语义同 {@link #probe}，提供更直观的方法名。 */
    public static long latencyMillis(String url, long timeoutMillis) {
        return probe(url, timeoutMillis);
    }

    /**
     * 预检专用客户端：3 秒连接超时 —— 坏源必须在预检阶段就快速失败，
     * 否则一次不可达的 TCP 连接就要陪它等满正常下载的 15 秒。
     */
    private static final HttpClient PROBE_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /**
     * 批量下载前的快速预检：只确认「能否拿到响应头」。
     *
     * <p>与 {@link #probe} 的区别在连接超时（3 秒 vs 15 秒）：
     * 镜像的存储节点可能整体不可达（例如 BMCLAPI 的资产 302 到社区节点），
     * 预检失败要让整批下载立刻切到其他源，而不是每个文件各等一次超时。</p>
     *
     * @return 到响应头的毫秒数；失败返回 -1
     */
    public static long probeFast(String url, long timeoutMillis) {
        long start = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<Void> response = PROBE_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            // 只认 2xx：客户端已自动跟随跳转，残余的 3xx 说明跳转链没有产出内容
            // （BMCLAPI 存储故障时正是「秒回 302、跟随过去拿不到东西」），
            // 把 3xx 当可达会让"坏源"永远通不过熔断
            if (isSuccess(response.statusCode())) {
                return (System.nanoTime() - start) / 1_000_000L;
            }
            return -1L;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return -1L;
        }
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private static void ensureSuccess(String url, int status) throws IOException {
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " <- " + url);
        }
    }
}
