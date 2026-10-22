package com.misaka.launcher.core.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.task.CancelledException;
import com.misaka.launcher.core.task.ProgressListener;
import com.misaka.launcher.core.util.ArchUtils;
import com.misaka.launcher.core.util.FileUtils;
import com.misaka.launcher.core.util.HashUtils;
import com.misaka.launcher.core.util.HttpUtils;
import com.misaka.launcher.core.util.JsonUtils;
import com.misaka.launcher.core.util.NetDiagnostics;
import com.misaka.launcher.core.util.OsUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ZooKeeper 更新服务客户端。
 *
 * <p>对应服务端（自建 ZooKeeper）的三个公开接口：</p>
 * <ul>
 *   <li>{@code POST /api/v1/updates/check} —— 批量检查新版本（本类只查自己一个产品）；</li>
 *   <li>{@code GET /api/v1/changelog/{product}?version=} —— 取更新说明；</li>
 *   <li>{@code GET /api/v1/download/{product}/{version}/{file}}（含 {@code /chunks[/n]}）—— 下载更新包。</li>
 * </ul>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>是否更新以服务端为准</b>：服务端返回的 {@code has_update} 直接采用，本地不重算；</li>
 *   <li><b>相对地址必须解析</b>：服务端未配置 {@code base_url} 时只返回 {@code /api/v1/...} 裸路径；</li>
 *   <li><b>分块并行下载 + 逐块校验</b>：服务端给了分块信息就走 {@code /chunks/{i}}，
 *       每块 SHA-256 校验通过才写盘，已存在且校验通过的块直接跳过（真断点续传）；
 *       拿不到分块信息时退回整包下载（{@link HttpUtils#download} 自带续传与校验）；</li>
 *   <li><b>只重试值得重试的</b>：429 与 5xx 才重试，4xx 立即失败（与官方 SDK 一致）。</li>
 * </ul>
 */
public final class ZooKeeperUpdateClient {

    private static final Logger log = LoggerFactory.getLogger(ZooKeeperUpdateClient.class);

    // ------------------------------------------------------------------
    // 产品信息：硬编码在这里，不开放成配置项
    //
    // 这两个值必须与服务端 ZooKeeper 上登记的产品完全一致。之所以写死在代码里而不是让用户填：
    // 产品键填错的后果是「检查更新永远返回产品不存在」，而界面上看不出是填错还是服务端没登记；
    // 写死之后一个产品只对应一个键，发布与对接两侧都只有一处需要确认（见 docs/ZooKeeper接入说明.md）。
    // ------------------------------------------------------------------

    /**
     * 产品标识：ZooKeeper 上登记的产品 key，<b>区分大小写</b>。
     *
     * <p><b>改名迁移</b>：旧版的键是 {@code MisakaMinecraftLauncher}（含商标，已弃用）。
     * 换键之后，服务端要按下面的顺序操作，否则会出现「老客户端收不到更新」：</p>
     * <ol>
     *   <li>在 ZooKeeper 上登记新产品 {@code MisakaLauncher}，把新版发布或同步到它下面；</li>
     *   <li>旧键 {@code MisakaMinecraftLauncher} <b>继续保留一段时间</b>：仍跑旧版本的客户端
     *       只认旧键，把同一份包也同步到旧键下，它们才会升上来；</li>
     *   <li>确认现场客户端都升到新版后，再停用旧键。</li>
     * </ol>
     */
    public static final String PRODUCT = "MisakaLauncher";

    /** 产品显示名称：ZooKeeper 上登记的名称，用于日志与更新对话框。 */
    public static final String DISPLAY_NAME = "Misaka Launcher";

    // ------------------------------------------------------------------
    // 下载归属：总是带上账号，口令可有可无
    //
    // ZooKeeper 的下载明细里，归属有两个来源（见 docs/api.md §1.6「下载归属（自称用户名）」）：
    //   * 已校验凭据：账号口令（HTTP Basic）或 API Key → verified=true；
    //   * 客户端自称的用户名 → verified=false，管理台显示「未验证」。
    // 后者只影响统计、不参与鉴权，因此**没配口令也不会记成匿名**，管理台的
    // 「账号下载排行」照样能看到这个账号下载了哪个文件。
    //
    // 自称用户名有三种载体：HTTP Basic 的用户名部分、请求头 X-ZK-User、查询参数 ?user=。
    // 本项目固定用 X-ZK-User：**Basic 只有配上真口令才会被服务端接受**——
    // middleware.PublicAuth 只要看到 Authorization: Basic 就一定会去校验账号口令
    // （service.UserService.Authenticate 对空口令直接判失败），所以「Basic 用户名 + 空口令」
    // 不是退化成自称归属，而是下载直接 401。别把这里改回 Basic。
    //
    // 想拿到 verified=true 的归属时，再配 DOWNLOAD_USER 的口令（内置值或设置页）。
    // 口令写错同样是 401，而不是退化成未验证。
    // ------------------------------------------------------------------

    /**
     * 下载归属账号名：与 ZooKeeper「用户管理」里该账号的用户名逐字一致。
     * <p>产品改名后这里也跟着改名，所以服务端需要有一个同名的账号（或把旧账号改名）；
     * 否则下载明细里的归属会指向一个不存在的账号（仍记成未验证，不影响下载本身）。</p>
     */
    public static final String DOWNLOAD_USER = "MisakaLauncher";

    /**
     * 自称用户名的请求头。
     *
     * <p>与 {@code middleware.ClaimedUserHeader} 必须逐字一致：服务端只在下载链上读它，
     * 且**不校验、不参与鉴权**，仅用于把下载记到该账号名下（{@code verified=false}）。</p>
     */
    public static final String CLAIMED_USER_HEADER = "X-ZK-User";

    /**
     * 内置下载口令：与上面账号的口令逐字一致。
     *
     * <p>留空表示不做「已校验」归属——此时下载仍会以 {@link #DOWNLOAD_USER} 自称，
     * 记成 {@code verified=false}，而不是匿名。配了口令则走 HTTP Basic，
     * 管理台里显示为已验证的归属。需要临时改用别的账号时，可在设置里填「下载口令」覆盖本值。</p>
     */
    private static final String BUILT_IN_DOWNLOAD_PASSWORD = "";

    /** 通道取值，与服务端 catalog 一致。 */
    public static final String CHANNEL_STABLE = "stable";
    public static final String CHANNEL_BETA = "beta";
    public static final String CHANNEL_DEV = "dev";
    public static final String CHANNEL_ALPHA = "alpha";

    /** 界面下拉用的通道顺序。 */
    public static final List<String> CHANNELS = List.of(CHANNEL_STABLE, CHANNEL_BETA, CHANNEL_DEV, CHANNEL_ALPHA);

    /** 可并行下载的块数上限：再多也只是把自建服务器的带宽占满，对端到端时间没有帮助。 */
    private static final int MAX_THREADS = 8;

    /** 单块超过该体积时才走并行分块（小包分块反而更慢，还要多发一次索引请求）。 */
    private static final long PARALLEL_THRESHOLD_BYTES = 8L * 1024 * 1024;

    /** 连接级失败后重试前的间隔：够让对端/系统的解析结果换个顺序，又不至于让用户干等。 */
    private static final long RETRY_DELAY_MILLIS = 400L;

    /** 续传时愿意先校验的已有数据上限，超出直接重下更省事。 */
    private static final long MAX_RESUME_PROBE_BYTES = 32L * 1024 * 1024;

    private final String serverUrl;
    private final String apiKey;
    private final String downloadPassword;
    private final Duration metaTimeout;
    private final Duration downloadTimeout;
    private final int threads;

    /**
     * 不带下载口令：下载仍会以 {@link #DOWNLOAD_USER} 自称（未验证归属），不会记成匿名。
     *
     * @param serverUrl   服务根地址，例如 {@code http://127.0.0.1:8080}
     * @param apiKey      可选的 API Key（{@code zk_...}）；为空表示不使用密钥
     * @param timeoutSeconds 元数据请求超时（秒）
     * @param threads     并行下载块数（1~8）
     */
    public ZooKeeperUpdateClient(String serverUrl, String apiKey, int timeoutSeconds, int threads) {
        this(serverUrl, apiKey, timeoutSeconds, threads, null);
    }

    /**
     * @param downloadPassword 下载账号口令，用来把归属升级成「已校验」；为空时使用内置值，
     *                         内置值也为空则只带自称用户名（{@code verified=false}）
     */
    public ZooKeeperUpdateClient(String serverUrl, String apiKey, int timeoutSeconds, int threads,
                                String downloadPassword) {
        this.serverUrl = normalizeBase(serverUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        String configured = downloadPassword == null ? "" : downloadPassword.trim();
        this.downloadPassword = configured.isEmpty() ? BUILT_IN_DOWNLOAD_PASSWORD : configured;
        this.metaTimeout = Duration.ofSeconds(Math.max(3, timeoutSeconds));
        this.downloadTimeout = Duration.ofMinutes(30);
        this.threads = Math.max(1, Math.min(MAX_THREADS, threads));
    }

    /**
     * 规范化服务地址。
     * <p>用户粘贴时常见的写法都要能接受：末尾斜杠、缺协议、带空格。</p>
     */
    static String normalizeBase(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            return "";
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        return value;
    }

    /** 服务根地址（已规范化）；为空表示未配置。 */
    public String baseUrl() {
        return serverUrl;
    }

    public boolean configured() {
        return !serverUrl.isBlank();
    }

    // ------------------------------------------------------------------
    // 平台标识：ZooKeeper 用 windows/linux/darwin + x64/arm64
    // ------------------------------------------------------------------

    /** 当前平台的 ZooKeeper 取值。 */
    public static String currentPlatform() {
        return switch (OsUtils.current()) {
            case WINDOWS -> "windows";
            case LINUX -> "linux";
            case MACOS -> "darwin";
            case UNKNOWN -> "any";
        };
    }

    /** 当前架构的 ZooKeeper 取值。注意服务端用 {@code x64}，不是 {@code amd64}。 */
    public static String currentArch() {
        return switch (ArchUtils.id()) {
            case "x64" -> "x64";
            case "x86" -> "x86";
            case "aarch64" -> "arm64";
            case "arm" -> "arm";
            default -> "any";
        };
    }

    // ------------------------------------------------------------------
    // 接口
    // ------------------------------------------------------------------

    /** 健康检查；失败返回 false（供设置页「测试连接」使用，不抛异常）。 */
    public boolean health() {
        if (!configured()) {
            return false;
        }
        try {
            return sendWithRetry("GET", serverUrl + "/api/v1/health", authHeaders(), null, null, metaTimeout)
                    .isSuccess();
        } catch (IOException e) {
            log.debug("更新服务器健康检查失败: {}", NetDiagnostics.describe(e));
            return false;
        }
    }

    /**
     * 发送请求；连接级失败时换一条全新连接重试一次。
     *
     * <p>为什么需要它：JDK 的 {@code HttpClient} 在建连失败时会在
     * {@code PlainHttpConnection.checkRetryConnect} 里对一条<b>已经关闭</b>的通道再连一次，
     * 于是真正的失败原因被 {@code ClosedChannelException} 顶掉，最外层只剩一个没有
     * message 的 {@code ConnectException}（现场表现为「更新失败：null」）。
     * 换一条新连接往往就成功 —— 除了绕开这个缺陷，Windows 对有多条 A 记录的域名
     * 是轮转返回的，第二次很可能落到另一个（可用的）地址上。</p>
     *
     * <p>只重试一次、且只对连接级失败重试：HTTP 4xx / 请求超时再试一遍只是让用户多等。</p>
     */
    private HttpUtils.Result sendWithRetry(String method, String url, Map<String, String> headers,
                                           String body, String contentType, Duration timeout) throws IOException {
        try {
            return HttpUtils.send(method, url, headers, body, contentType, timeout);
        } catch (IOException e) {
            if (!NetDiagnostics.connectLevelFailure(e)) {
                throw e;
            }
            log.warn("{} 连接失败（{}），换一条连接重试一次", url, NetDiagnostics.describe(e));
            sleepQuietly();
            return HttpUtils.send(method, url, headers, body, contentType, timeout);
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(RETRY_DELAY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        }
    }

    /** 一次检查的结果；{@code error} 非空表示这次没得到结论（产品不存在、通道下没有版本…）。 */
    public record CheckResult(UpdateInfo info, String error) {

        public boolean ok() {
            return error == null || error.isBlank();
        }

        public boolean hasUpdate() {
            return ok() && info != null && info.hasUpdate();
        }

        static CheckResult failure(String message) {
            return new CheckResult(null, message);
        }
    }

    /**
     * 检查新版本。
     *
     * <p>产品标识固定为 {@link #PRODUCT}，调用方只需要给出本地版本与通道；
     * 请求里同时带上当前平台与架构（{@link #currentPlatform()} / {@link #currentArch()}），
     * 服务端据此返回适用于本操作系统的更新包。</p>
     *
     * @param localVersion 本地版本，可以是 {@code 1.0.0} 或 {@code 1.0.0.0}（服务端会归一化）
     * @param channel      通道；为空或 {@code latest}/{@code default} 表示服务端默认通道
     */
    public CheckResult check(String localVersion, String channel) throws IOException {
        if (!configured()) {
            return CheckResult.failure(I18n.t("update.error.notConfigured"));
        }
        String product = PRODUCT;
        ObjectNode item = JsonUtils.newObject();
        item.put("product", product);
        item.put("version", LauncherVersion.normalize(localVersion).isEmpty()
                ? String.valueOf(localVersion) : LauncherVersion.normalize(localVersion));
        if (channel != null && !channel.isBlank()) {
            item.put("channel", channel);
        }
        item.put("platform", currentPlatform());
        item.put("arch", currentArch());
        ArrayNode body = JsonUtils.newArray();
        body.add(item);

        HttpUtils.Result result = sendWithRetry("POST", serverUrl + "/api/v1/updates/check", authHeaders(),
                JsonUtils.write(body), HttpUtils.JSON_CONTENT_TYPE, metaTimeout);
        if (!result.isSuccess()) {
            return CheckResult.failure(describeFailure(result, I18n.t("update.error.checkFailed")));
        }
        JsonNode root = JsonUtils.read(result.body());
        JsonNode first = root.path("results").path(0);
        if (first.isMissingNode() || first.isNull()) {
            return CheckResult.failure(I18n.t("update.error.emptyResult"));
        }
        String error = JsonUtils.text(first, "error", "");
        if (!error.isBlank()) {
            // 服务端把「产品不存在 / 已停用 / 该通道没有版本」放在 200 的 body 里，必须识别
            return CheckResult.failure(error);
        }
        // 服务端回显的产品键应当就是请求的那一个；不一致多半是 ZooKeeper 上登记了同名不同大小写的产品，
        // 这类问题只看「有没有新版」是发现不了的（会一路正常，直到换机器才发现装的是别的产品）
        String returnedProduct = JsonUtils.text(first, "product", "");
        if (!returnedProduct.isBlank() && !PRODUCT.equalsIgnoreCase(returnedProduct)) {
            log.warn("服务端回显的产品标识是 {}，与本地硬编码的 {} 不一致，请核对 ZooKeeper 上的登记",
                    returnedProduct, PRODUCT);
        }
        if (!JsonUtils.boolValue(first, "has_update", false)) {
            return new CheckResult(UpdateInfo.none(product, localVersion), "");
        }
        // 按当前操作系统 + 架构挑包：服务端只负责按平台回落，最终挑哪一个由这里决定
        String platform = currentPlatform();
        String arch = currentArch();
        JsonNode manifest = first.path("manifest");
        JsonNode file = selectFile(manifest, platform, arch, runningSuffix());
        if (file == null) {
            log.warn("清单里没有适用于 {}/{} 的文件（共 {} 个），本次无法更新",
                    platform, arch, manifest.path("files").size());
            return CheckResult.failure(I18n.t("update.error.noArtifact", platform, arch));
        }
        String downloadUrl = JsonUtils.text(first, "download_url", "");
        if (downloadUrl.isBlank()) {
            downloadUrl = JsonUtils.text(file, "download_url", "");
        }
        UpdateInfo info = new UpdateInfo(
                JsonUtils.text(first, "product", product),
                localVersion,
                true,
                JsonUtils.text(first, "latest_version", ""),
                JsonUtils.text(first, "channel", channel),
                JsonUtils.text(first, "released_at", ""),
                "",
                resolve(JsonUtils.text(first, "notes_url", "")),
                resolve(downloadUrl),
                JsonUtils.text(file, "name", ""),
                JsonUtils.longValue(file, "size", 0L),
                JsonUtils.text(file, "sha256", "").toLowerCase(Locale.ROOT),
                JsonUtils.longValue(file, "chunk_size", 0L),
                JsonUtils.intValue(file, "chunk_count", 0));
        log.info("发现新版本：{} → 适用于 {}/{} 的包 {}（{} 字节）", info.describe(), platform, arch,
                info.targetFileName(), info.fileSize());
        return new CheckResult(info, "");
    }

    /** 取更新说明（变更日志里对应版本的那一条）；失败返回空串，不影响更新流程。 */
    public String fetchNotes(String notesUrl) {
        if (notesUrl == null || notesUrl.isBlank()) {
            return "";
        }
        try {
            HttpUtils.Result result = HttpUtils.send("GET", resolve(notesUrl), authHeaders(), null, null,
                    metaTimeout);
            if (!result.isSuccess()) {
                return "";
            }
            JsonNode items = JsonUtils.read(result.body()).path("items");
            if (items.isArray() && !items.isEmpty()) {
                return JsonUtils.text(items.get(0), "notes", "");
            }
            return "";
        } catch (IOException e) {
            log.debug("读取更新说明失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 下载更新包到 {@code dest}。
     *
     * @param info     检查结果（提供地址、大小、SHA-256 与分块参数）
     * @param dest     目标文件；下载过程写 {@code *.part}，校验通过后才改名落盘
     * @param listener 进度回调（字节粒度）
     */
    public void download(UpdateInfo info, Path dest, ProgressListener listener) throws IOException {
        if (!info.downloadable()) {
            throw new IOException(I18n.t("update.error.noDownloadUrl"));
        }
        boolean big = info.fileSize() >= PARALLEL_THRESHOLD_BYTES && info.chunkCount() > 1;
        if (big) {
            JsonNode index = fetchChunkIndex(info);
            if (index != null && downloadChunks(info, index, dest, listener)) {
                return;
            }
        }
        downloadWhole(info, dest, listener);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private void downloadWhole(UpdateInfo info, Path dest, ProgressListener listener) throws IOException {
        listener.onStage(I18n.t("update.stage.download"));
        HttpUtils.download(URI.create(resolve(info.downloadUrl())), dest, listener, info.sha256(),
                info.fileSize(), authHeaders(), downloadTimeout, null);
    }

    /**
     * 并行分块下载。
     *
     * @return 是否完成；返回 false 表示分块信息不可用，调用方应退回整包下载
     */
    private boolean downloadChunks(UpdateInfo info, JsonNode index, Path dest, ProgressListener listener)
            throws IOException {
        long size = JsonUtils.longValue(index, "size", info.fileSize());
        JsonNode chunks = index.path("chunks");
        if (size <= 0 || !chunks.isArray() || chunks.isEmpty()) {
            return false;
        }
        listener.onStage(I18n.t("update.stage.download"));
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        FileUtils.ensureParent(dest);
        // 刻意保留上次留下的 .part：每块都要过 SHA-256 才敢用，块大小或文件变了会全部校验失败，
        // 等价于重下 —— 因此「复用」不会带来正确性风险，却能让中断过的更新接着下完

        AtomicLong done = new AtomicLong();
        List<ChunkTask> tasks = new ArrayList<>();
        for (JsonNode chunk : chunks) {
            int index0 = JsonUtils.intValue(chunk, "index", tasks.size());
            long offset = JsonUtils.longValue(chunk, "offset", -1L);
            long length = JsonUtils.longValue(chunk, "length", -1L);
            if (offset < 0 || length <= 0 || offset + length > size) {
                log.warn("分块信息异常，退回整包下载：index={} offset={} length={}", index0, offset, length);
                return false;
            }
            tasks.add(new ChunkTask(index0, offset, length, JsonUtils.text(chunk, "sha256", "")));
        }
        String chunksUrl = resolve(info.downloadUrl()) + "/chunks";
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, tasks.size()), runnable -> {
            Thread thread = new Thread(runnable, "misaka-update-chunk");
            thread.setDaemon(true);
            return thread;
        });
        try (FileChannel channel = FileChannel.open(part, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            List<Future<?>> futures = new ArrayList<>(tasks.size());
            for (ChunkTask task : tasks) {
                futures.add(pool.submit(() -> {
                    listener.throwIfCancelled();
                    if (isChunkValid(channel, task)) {
                        // 断点续传：这块已经落盘且校验通过，跳过
                        report(listener, done.addAndGet(task.length()), size);
                        return null;
                    }
                    byte[] bytes = fetchChunk(chunksUrl, task.index());
                    if (bytes.length != task.length()) {
                        throw new IOException(I18n.t("update.error.chunkSize", task.index(),
                                bytes.length, task.length()));
                    }
                    if (!task.sha256().isBlank()
                            && !task.sha256().equalsIgnoreCase(HashUtils.sha256Hex(bytes))) {
                        throw new IOException(I18n.t("update.error.chunkHash", task.index()));
                    }
                    writeAt(channel, bytes, task.offset());
                    report(listener, done.addAndGet(task.length()), size);
                    return null;
                }));
            }
            awaitAll(futures);
        } finally {
            pool.shutdownNow();
        }
        listener.onStage(I18n.t("update.stage.verify"));
        if (!info.sha256().isBlank() && !HashUtils.verifyAuto(part, info.sha256())) {
            Files.deleteIfExists(part);
            throw new IOException(I18n.t("update.error.packageHash"));
        }
        FileUtils.move(part, dest);
        return true;
    }

    private void awaitAll(List<Future<?>> futures) throws IOException {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(I18n.t("update.error.interrupted"), e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof CancelledException cancelled) {
                    throw cancelled;
                }
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IOException(I18n.t("update.error.chunkFailed", String.valueOf(cause)), cause);
            }
        }
    }

    private static void writeAt(FileChannel channel, byte[] bytes, long offset) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long position = offset;
        while (buffer.hasRemaining()) {
            position += channel.write(buffer, position);
        }
    }

    /** 已有的分块是否可用（大小够、校验通过）。 */
    private static boolean isChunkValid(FileChannel channel, ChunkTask task) {
        try {
            if (task.length() > MAX_RESUME_PROBE_BYTES || channel.size() < task.offset() + task.length()) {
                return false;
            }
            if (task.sha256().isBlank()) {
                return false;
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) task.length());
            long position = task.offset();
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, position);
                if (read <= 0) {
                    return false;
                }
                position += read;
            }
            return task.sha256().equalsIgnoreCase(HashUtils.sha256Hex(buffer.array()));
        } catch (IOException e) {
            return false;
        }
    }

    private static void report(ProgressListener listener, long done, long total) {
        listener.onProgress(done, total);
    }

    private byte[] fetchChunk(String chunksUrl, int index) throws IOException {
        return fetchBytes(chunksUrl + "/" + index, downloadTimeout, 3);
    }

    /** 分块索引；失败返回 null（调用方退回整包下载，不让一次索引请求失败毁掉整个更新）。 */
    private JsonNode fetchChunkIndex(UpdateInfo info) {
        try {
            HttpUtils.Result result = HttpUtils.send("GET", resolve(info.downloadUrl()) + "/chunks",
                    authHeaders(), null, null, metaTimeout);
            if (!result.isSuccess()) {
                log.debug("分块索引不可用（{}），改为整包下载", result.summary());
                return null;
            }
            return JsonUtils.read(result.body());
        } catch (IOException e) {
            log.debug("分块索引请求失败，改为整包下载: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 带重试的二进制 GET。
     * <p>只重试 429／5xx／网络异常：4xx 是「请求本身不对」，重试没有意义。</p>
     */
    private byte[] fetchBytes(String url, Duration timeout, int attempts) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= Math.max(1, attempts); attempt++) {
            try {
                HttpRequest request = HttpUtils.request(url, timeout, authHeaders()).GET().build();
                HttpResponse<byte[]> response = HttpUtils.client().send(request,
                        HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                if (status == 429 || status >= 500) {
                    last = new IOException(I18n.t("update.error.serverBusy", status));
                    sleep(attempt * 400L, response.headers().firstValue("Retry-After").orElse(""));
                    continue;
                }
                throw new IOException(I18n.t("update.error.http", status));
            } catch (IOException e) {
                last = e;
                sleep(attempt * 400L, "");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(I18n.t("update.error.interrupted"), e);
            }
        }
        throw last == null ? new IOException(I18n.t("update.error.chunkFailed", "")) : last;
    }

    private static void sleep(long millis, String retryAfter) {
        long wait = millis;
        try {
            if (retryAfter != null && !retryAfter.isBlank()) {
                wait = Math.max(wait, Long.parseLong(retryAfter.trim()) * 1000L);
            }
        } catch (NumberFormatException ignored) {
            // 服务端给了非数字的 Retry-After，按默认退避
        }
        try {
            Thread.sleep(Math.min(wait, 10_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 把相对地址解析为绝对地址；已经是绝对的则原样返回，空值仍为空。 */
    public String resolve(String url) {
        if (url == null) {
            return "";
        }
        String value = url.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        return serverUrl + value;
    }

    /**
     * 更新服务的请求头。
     *
     * <p><b>自称用户名永远带上</b>（{@link #CLAIMED_USER_HEADER}）：不校验口令的部署下，
     * 服务端据此把这次下载记到 {@link #DOWNLOAD_USER} 名下（{@code verified=false}），
     * 于是没配口令也不再是一串 {@code (anonymous)}。</p>
     *
     * <p>凭据是可选的，只用来把归属升级成「已验证」：配了口令走 HTTP Basic（服务端优先采信，
     * 覆盖自称值）；没口令但有 API Key 时带 API Key（同样只记 api_key_id，账号列仍是匿名）。</p>
     */
    private Map<String, String> authHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(CLAIMED_USER_HEADER, DOWNLOAD_USER);
        if (!downloadPassword.isBlank()) {
            String raw = DOWNLOAD_USER + ":" + downloadPassword;
            headers.put("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        } else if (!apiKey.isBlank()) {
            headers.put("X-API-Key", apiKey);
        }
        return headers;
    }

    private String describeFailure(HttpUtils.Result result, String prefix) {
        String reason = switch (result.status()) {
            case 401, 403 -> I18n.t("update.error.unauthorized");
            case 404 -> I18n.t("update.error.notFound");
            case 429 -> I18n.t("update.error.rateLimited");
            default -> I18n.t("update.error.http", result.status());
        };
        return prefix + "：" + reason;
    }

    /**
     * 从清单里挑出适用于当前操作系统与架构的文件（不看形态）。
     */
    static JsonNode selectFile(JsonNode manifest, String platform, String arch) {
        return selectFile(manifest, platform, arch, "");
    }

    /**
     * 从清单里挑出适用于当前操作系统与架构的文件，并优先挑与当前运行形态一致的那个。
     *
     * <p>挑选顺序：形态一致且平台/架构精确匹配 → 形态一致但靠 {@code any} 兼容
     * → 平台/架构精确匹配 → {@code any} 兼容的兜底。</p>
     *
     * <p>为什么形态（后缀）要参与挑选：服务端的唯一键是「产品 + 版本 + 平台 + 架构」，
     * 所以同一个 {@code (windows,x64)} 下，单文件 {@code .exe} 与可执行 {@code .jar} 只能挂在
     * 同一条版本记录里，只按平台挑会随机挑中另一种形态 —— 那要等下载完整个包、走到
     * {@code SelfUpdateInstaller.requireSameKind} 才报「形态不符」。{@code any} 兼容的包之所以
     * 可以越过精确匹配优先，是因为 {@code any} 本就声明「不分平台」，且形态一致才能就地替换自己。</p>
     *
     * <p><b>明确写着别的平台/架构的文件一律不要</b>，哪怕整个版本只有这一个文件：
     * 服务端会做平台回落（{@code (platform,arch)} → {@code (platform,any)} → {@code (any,any)}
     * → 该通道最新版本），拿回来的清单可能只是「别的平台登记过」的那一份。此时宁可报
     * 「没有适用于本平台的更新包」，也不能把别的系统的包装上去 —— 形态校验只是最后一道防线，
     * 对跨平台的 {@code .jar} 根本拦不住（Linux 的 jar 在 Windows 上照样能覆盖自身）。</p>
     *
     * @param preferredSuffix 当前运行形态的后缀（{@code .exe} / {@code .sh} / {@code .jar}），
     *                        传空串表示不做形态偏好
     */
    static JsonNode selectFile(JsonNode manifest, String platform, String arch, String preferredSuffix) {
        JsonNode files = manifest.path("files");
        if (!files.isArray() || files.isEmpty()) {
            return null;
        }
        JsonNode exact = null;
        JsonNode fallback = null;
        JsonNode exactPreferred = null;
        JsonNode fallbackPreferred = null;
        for (JsonNode file : files) {
            if (!declaredFor(file, platform, arch)) {
                continue;
            }
            boolean preferred = !preferredSuffix.isEmpty()
                    && JsonUtils.text(file, "name", "").toLowerCase(Locale.ROOT).endsWith(preferredSuffix);
            if (matchesExactly(file, platform, arch)) {
                if (exact == null) {
                    exact = file;
                }
                if (preferred && exactPreferred == null) {
                    exactPreferred = file;
                }
            } else {
                if (fallback == null) {
                    fallback = file;
                }
                if (preferred && fallbackPreferred == null) {
                    fallbackPreferred = file;
                }
            }
        }
        if (exactPreferred != null) {
            return exactPreferred;
        }
        // 形态一致优先于平台精确：精确匹配但形态不符的包，装上去照样会被 requireSameKind 拦掉
        // （还白下了整个包），而 any/any 本就声明「不分平台」
        if (fallbackPreferred != null) {
            return fallbackPreferred;
        }
        return exact != null ? exact : fallback;
    }

    /**
     * 当前运行形态对应的更新包后缀。
     *
     * <p>识别不出形态时返回空串（例如从 IDE 里跑），此时挑包只看平台，形态问题交给替换前的
     * {@code requireSameKind} 兜底。</p>
     */
    private static String runningSuffix() {
        return switch (SelfUpdateInstaller.locate().form()) {
            case WINDOWS_EXE -> ".exe";
            case UNIX_SCRIPT -> ".sh";
            case JAR -> ".jar";
            case UNSUPPORTED -> "";
        };
    }

    /** 文件声明的平台/架构能否用在本机（未声明的字段视为 {@code any}）。 */
    private static boolean declaredFor(JsonNode file, String platform, String arch) {
        String filePlatform = JsonUtils.text(file, "platform", "any");
        String fileArch = JsonUtils.text(file, "arch", "any");
        boolean platformOk = "any".equalsIgnoreCase(filePlatform) || filePlatform.equalsIgnoreCase(platform);
        boolean archOk = "any".equalsIgnoreCase(fileArch) || fileArch.equalsIgnoreCase(arch);
        return platformOk && archOk;
    }

    /** 平台与架构都是精确匹配（不是靠 {@code any} 兜上来的）。 */
    private static boolean matchesExactly(JsonNode file, String platform, String arch) {
        return platform.equalsIgnoreCase(JsonUtils.text(file, "platform", "any"))
                && arch.equalsIgnoreCase(JsonUtils.text(file, "arch", "any"));
    }

    /** 一个待下载的分块。 */
    private record ChunkTask(int index, long offset, long length, String sha256) {
    }
}
