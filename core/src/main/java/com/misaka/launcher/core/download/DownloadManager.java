package com.misaka.launcher.core.download;

import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.task.CancelledException;
import com.misaka.launcher.core.task.ProgressListener;
import com.misaka.launcher.core.util.ByteRateLimiter;
import com.misaka.launcher.core.util.HashUtils;
import com.misaka.launcher.core.util.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多线程下载引擎。
 * <p>职责边界：</p>
 * <ul>
 *   <li>并发与调度（固定线程池，默认 8 并发）；</li>
 *   <li>幂等：目标文件已存在且哈希匹配则直接跳过，不发任何请求；</li>
 *   <li>降级：单个条目失败后按候选地址轮换重试（镜像 → 镜像 → 官方）；</li>
 *   <li>进度聚合：把并行条目的字节增量汇总为单一进度流，并按阈值节流上报；</li>
 *   <li>限速：可选的全局限速器，多线程共享同一预算。</li>
 * </ul>
 * <p>失败策略：单条失败不中断整批，最终由 {@link DownloadReport} 统一汇报；
 * 由调用方决定必需项失败是否终止流程（保证「能玩」优先）。</p>
 */
public final class DownloadManager {

    private static final Logger log = LoggerFactory.getLogger(DownloadManager.class);

    private static final long PROGRESS_THROTTLE_MILLIS = 100L;

    private final SourceResolver resolver;
    private final int concurrency;
    private final int retries;
    private final Duration requestTimeout;
    private final ByteRateLimiter rateLimiter;
    /** 源健康状态；与 MetaFetcher 共享同一个实例，一次发现全链路受益。 */
    private final SourceGate gate;

    public DownloadManager(SourceResolver resolver, LauncherConfig.DownloadSettings settings) {
        this(resolver, settings, new SourceGate());
    }

    public DownloadManager(SourceResolver resolver, LauncherConfig.DownloadSettings settings, SourceGate gate) {
        this.resolver = resolver;
        this.concurrency = Math.max(1, settings.concurrency);
        this.retries = Math.max(1, settings.retries);
        // 该超时约束的是「响应头到达时间」，大文件正文传输不受其限制
        this.requestTimeout = Duration.ofSeconds(Math.max(5, settings.timeoutSeconds));
        this.rateLimiter = ByteRateLimiter.ofKiloBytesPerSecond(settings.speedLimitKbps);
        this.gate = gate == null ? new SourceGate() : gate;
    }

    public SourceResolver resolver() {
        return resolver;
    }

    /**
     * 共享的限速器。
     * <p>供绕过本管理器直接调用 {@code HttpUtils.download} 的场景复用
     * （例如 Java 归档下载：其候选地址由发行版接口给出，不经下载源改写）。</p>
     */
    public ByteRateLimiter rateLimiter() {
        return rateLimiter;
    }

    // ------------------------------------------------------------------
    // 批量下载
    // ------------------------------------------------------------------

    /**
     * 按批量大小决定并发数。
     *
     * <p><b>为什么要自适应</b>：资源文件动辄上千个、单个几十 KB，总耗时几乎完全由
     * 「每请求往返 × 文件数 ÷ 线程数」决定，带宽反而用不满。配置里的并发是按
     * 「下载大文件」的直觉给的（默认 8），到资源阶段就成了瓶颈 —— PCL2 这类启动器
     * 默认就是几十个线程。这里只在<b>文件数够多</b>时上调；少量大文件的批量仍按用户配置，
     * 免得把带宽打散到几十条连接上。</p>
     */
    private int effectiveConcurrency(int pendingCount) {
        int configured = concurrency <= 0 ? 1 : concurrency;
        int adaptive;
        if (pendingCount >= HUGE_BATCH_ITEMS) {
            adaptive = HUGE_BATCH_CONCURRENCY;
        } else if (pendingCount >= LARGE_BATCH_ITEMS) {
            adaptive = LARGE_BATCH_CONCURRENCY;
        } else {
            return configured;
        }
        return Math.min(MAX_CONCURRENCY, Math.max(configured, adaptive));
    }

    /**
     * 下载一批文件。
     *
     * @param items    待下载条目
     * @param listener 进度回调；取消标志会被检查
     * @return 汇总结果
     * @throws CancelledException 用户取消时抛出
     */
    public DownloadReport downloadAll(List<DownloadItem> items, ProgressListener listener) {
        return downloadAll(items, listener, retries, requestTimeout, true);
    }

    /**
     * 「快速失败」批量下载：每个候选只试一次、单次请求超时更短、不做退避。
     *
     * <p>用于<b>预置 / 探测型</b>场景 —— 拿不到就换下一个，重试毫无意义。</p>
     *
     * <p>为什么必须区分：默认策略下每个条目会尝试 {@code 候选数 × retries} 次并逐次退避，
     * 对「镜像上根本不存在」的文件（例如 Forge 安装器自己生成的 {@code client-….jar}、
     * {@code forge-…-client.jar}）就是纯等待 —— 实测 14 个这样的文件让预置白花了 139 秒
     * （4 个镜像各试一轮，每轮 30+ 秒），而安装器本来就能自己搞定它们。</p>
     */
    public DownloadReport downloadAllFast(List<DownloadItem> items, ProgressListener listener) {
        return downloadAll(items, listener, 1, FAST_REQUEST_TIMEOUT, false);
    }

    private DownloadReport downloadAll(List<DownloadItem> items, ProgressListener listener,
                                       int attemptsPerItem, Duration requestTimeout, boolean backoff) {
        long startMillis = System.currentTimeMillis();
        if (items == null || items.isEmpty()) {
            return new DownloadReport(0, 0, 0, 0, 0L, 0L, List.of());
        }

        List<DownloadItem> pending = new ArrayList<>();
        long preDoneBytes = 0L;
        int skipped = 0;
        for (DownloadItem item : items) {
            listener.throwIfCancelled();
            if (isUpToDate(item)) {
                skipped++;
                if (item.size() > 0) {
                    preDoneBytes += item.size();
                } else {
                    preDoneBytes += sizeOf(item.target());
                }
            } else {
                pending.add(item);
            }
        }

        if (!pending.isEmpty()) {
            listener.onMessage("共 " + items.size() + " 个文件，跳过 " + skipped + " 个已是最新，待下载 "
                    + pending.size() + " 个");
        }

        // 预检是「一次探测换来整批不踩坏源」的优化，只在正常模式下做；
        // 快速失败模式本身就要求立刻切换，多一次探测纯属浪费
        if (backoff) {
            preflight(pending);
        }

        Aggregator aggregator = new Aggregator(items, preDoneBytes, listener);
        aggregator.emit(true);

        if (pending.isEmpty()) {
            return new DownloadReport(items.size(), 0, skipped, 0, 0L,
                    System.currentTimeMillis() - startMillis, List.of());
        }

        AtomicInteger downloaded = new AtomicInteger();
        AtomicLong transferredBytes = new AtomicLong();
        List<DownloadReport.Failure> failures = java.util.Collections.synchronizedList(new ArrayList<>());

        int threads = effectiveConcurrency(pending.size());
        if (threads != concurrency) {
            // 这一行是「装版本为什么快/慢」的第一现场：并发到底用了多少
            log.info("本批 {} 个文件，并发由 {} 上调至 {}", pending.size(), concurrency, threads);
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "misaka-download");
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<Future<?>> futures = new ArrayList<>(pending.size());
            for (DownloadItem item : pending) {
                futures.add(pool.submit(() -> {
                    try {
                        boolean ok = downloadWithFailover(item, aggregator::subListener,
                                transferredBytes::addAndGet, attemptsPerItem, requestTimeout, backoff);
                        if (ok) {
                            downloaded.incrementAndGet();
                        } else {
                            failures.add(new DownloadReport.Failure(item.target(), item.url(), item.required(),
                                    "所有候选地址均失败"));
                        }
                    } catch (CancelledException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        failures.add(new DownloadReport.Failure(item.target(), item.url(), item.required(),
                                String.valueOf(e.getMessage())));
                    } finally {
                        aggregator.fileFinished();
                    }
                }));
            }

            // 逐个等待，但**不做无限期盲等**：worker 可能卡在网络上（已由 HttpUtils 的
            // 停滞看门狗兜底），等待方自己也要能响应取消 —— 否则用户点了「停止」
            // 界面依然一动不动（真实故障：模组同步偶发卡住、停止按钮无效只能杀进程）
            for (Future<?> future : futures) {
                try {
                    awaitFuture(future, listener);
                } catch (java.util.concurrent.CancellationException ignored) {
                    // 取消流程统一在下面处理
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancelledException("下载被中断");
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof CancelledException cancelled) {
                        throw cancelled;
                    }
                    log.warn("下载任务异常: {}", cause == null ? e.getMessage() : cause.getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (listener.isCancelled()) {
            throw new CancelledException();
        }

        // 第二轮补下：失败项多半是随机网络抖动（TLS 被重置、瞬时超时），
        // 同一文件下一轮往往就成功。批量安装里剩几个文件失败就整体判定失败，
        // 代价是重下几百 MB —— 补一轮远比让用户重来划算。
        // 快速失败模式不补：那里的失败通常是「镜像上根本没有这个文件」，重试同样拿不到。
        if (!failures.isEmpty() && backoff) {
            retryFailures(pending, failures, aggregator, transferredBytes, downloaded, listener);
        }

        long elapsed = System.currentTimeMillis() - startMillis;
        DownloadReport report = new DownloadReport(items.size(), downloaded.get(), skipped, failures.size(),
                transferredBytes.get(), elapsed, List.copyOf(failures));
        log.info("下载完成：共 {}，下载 {}，跳过 {}，失败 {}，传输 {} 字节，耗时 {} ms",
                report.total(), report.downloaded(), report.skipped(), report.failed(), report.bytes(), elapsed);
        return report;
    }

    /**
     * 对首轮失败的文件再补一轮（串行，避免同时把压力重新加到一个刚恢复的源上）。
     *
     * @param pending 首轮参与下载的条目（用于按目标路径找回原始 DownloadItem）
     */
    private void retryFailures(List<DownloadItem> pending, List<DownloadReport.Failure> failures,
                               Aggregator aggregator, AtomicLong transferredBytes,
                               AtomicInteger downloaded, ProgressListener listener) {
        Map<Path, DownloadItem> byTarget = new HashMap<>();

        for (DownloadItem item : pending) {
            byTarget.put(normalize(item.target()), item);
        }

        List<DownloadItem> retry = new ArrayList<>();
        for (DownloadReport.Failure failure : List.copyOf(failures)) {
            DownloadItem item = byTarget.get(normalize(failure.target()));
            if (item != null) {
                retry.add(item);
            }
        }
        if (retry.isEmpty()) {
            return;
        }

        log.info("{} 个文件首轮失败，开始第二轮补下", retry.size());
        for (DownloadItem item : retry) {
            listener.throwIfCancelled();
            if (downloadWithFailover(item, aggregator::subListener, transferredBytes::addAndGet)) {
                downloaded.incrementAndGet();
                failures.removeIf(failure -> normalize(failure.target()).equals(normalize(item.target())));
            }
        }
        log.info("第二轮补下结束，仍有 {} 个文件失败", failures.size());
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------
    // 单文件下载（带候选地址轮换）
    // ------------------------------------------------------------------

    /**
     * 下载单个文件，失败时按候选地址轮换重试。
     *
     * <p>失败处理包含两层：</p>
     * <ol>
     *   <li><b>候选轮换</b>：镜像失败立即换下一个候选（官方兜底在最后）；</li>
     *   <li><b>源熔断</b>：某主机连续失败达到阈值后暂停使用一段时间。
     *       这是「大量小文件」场景（资产动辄上千个）的关键 —— 否则镜像挂掉时
     *       每个文件都要在坏源上白等一次连接超时（15 秒），整体耗时不可接受。</li>
     * </ol>
     *
     * @param listenerFactory  为每次尝试创建独立的子进度监听器（各尝试的增量需独立统计）
     * @param transferredSink  实际网络传输字节的累计入口；含失败尝试中已读取的部分
     * @return 是否成功
     */
    public boolean downloadWithFailover(DownloadItem item,
                                        java.util.function.Supplier<ProgressListener> listenerFactory,
                                        java.util.function.LongConsumer transferredSink) {
        return downloadWithFailover(item, listenerFactory, transferredSink, retries, requestTimeout, true);
    }

    /**
     * 带策略的单文件下载。
     *
     * @param attemptsPerItem 每个可用候选的尝试次数（≥1）
     * @param timeout         单次请求超时（约束响应头到达时间）
     * @param backoff         失败之间是否退避等待；快速失败模式传 {@code false}
     */
    private boolean downloadWithFailover(DownloadItem item,
                                         java.util.function.Supplier<ProgressListener> listenerFactory,
                                         java.util.function.LongConsumer transferredSink,
                                         int attemptsPerItem, Duration timeout, boolean backoff) {
        List<String> candidates = resolver.candidates(item);
        // 熔断中的源排到最后而不是直接删除：全部候选都熔断时仍然要给它们机会
        List<String> orderedCandidates = new ArrayList<>(candidates.size());
        for (String url : candidates) {
            if (!gate.isBroken(url)) {
                orderedCandidates.add(url);
            }
        }
        // 熔断中的源在冷却期内不参与重试（这正是熔断的意义，否则每个文件仍会陪它等一次超时）；
        // 只有全部候选都处于熔断时才退回去用它们 —— 总比连一次尝试机会都没有要好
        if (orderedCandidates.isEmpty()) {
            orderedCandidates.addAll(candidates);
        }
        if (orderedCandidates.isEmpty()) {
            return false;
        }

        // 预算按「可用候选数 × attemptsPerItem」给足：镜像挂掉后只剩官方一个候选时，
        // 不能因此把总尝试次数缩到 attemptsPerItem —— 国内到官方库的连接本来就会随机被重置
        // （实测日志：熔断镜像后 66 个库文件里挂了 6 个，全是握手被中断）
        int attempts = Math.max(attemptsPerItem, orderedCandidates.size() * attemptsPerItem);

        IOException lastError = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            String url = orderedCandidates.get(attempt % orderedCandidates.size());
            TransferTrackingListener tracking = new TransferTrackingListener(listenerFactory.get());
            if (tracking.isCancelled()) {
                throw new CancelledException();
            }
            try {
                HttpUtils.download(URI.create(url), item.target(), tracking, item.checksum(), item.size(),
                        item.headers(), timeout, rateLimiter);
                transferredSink.accept(tracking.transferred());
                gate.markSuccess(url);
                if (attempt > 0) {
                    log.info("下载成功（第 {} 次尝试，源 {}）：{}", attempt + 1, url, item.displayName());
                }
                return true;
            } catch (CancelledException e) {
                throw e;
            } catch (IOException e) {
                transferredSink.accept(tracking.transferred());
                gate.markFailure(url);
                lastError = e;
                log.debug("下载失败（尝试 {}/{}，地址 {}）：{}", attempt + 1, attempts, url, e.getMessage());
                if (backoff) {
                    backoff(attempt, tracking);
                }
            }
        }
        log.warn("下载最终失败：{} <- {}（{}）", item.displayName(), item.url(),
                lastError == null ? "未知原因" : lastError.getMessage());
        return false;
    }

    // ==================================================================
    // 源健康度（预检 + 熔断）：状态由共享的 SourceGate 持有
    // ==================================================================

    /** 大批量下载才值得预检：小批量即使撞上坏源，浪费也可控。 */
    private static final int PREFLIGHT_MIN_ITEMS = 32;

    // ---- 自适应并发（见 effectiveConcurrency） ----
    /** 超过这个文件数就算「小文件批」，往返延迟会主导总耗时。 */
    private static final int LARGE_BATCH_ITEMS = 150;
    private static final int LARGE_BATCH_CONCURRENCY = 16;
    /** 资源全量下载的量级（上千个）。 */
    private static final int HUGE_BATCH_ITEMS = 600;
    private static final int HUGE_BATCH_CONCURRENCY = 24;
    /** 上限：再多也不划算，反而容易被镜像限流。 */
    private static final int MAX_CONCURRENCY = 32;
    /** 预检超时；坏源要在这一步就现形，不能等正常下载的 15 秒。 */
    private static final long PREFLIGHT_TIMEOUT_MILLIS = 3_000L;
    /** 重试退避：第 n 次失败后等待 300ms * (n+1)，封顶 1.5s。 */
    private static final long BACKOFF_BASE_MILLIS = 300L;
    /** 快速失败模式的单次请求超时：拿不到就换下一个，不值得久等。 */
    private static final Duration FAST_REQUEST_TIMEOUT = Duration.ofSeconds(6);
    private static final long BACKOFF_CAP_MILLIS = 1_500L;

    /**
     * 大批量下载前的预检：探测镜像是否真的能拿到内容。
     *
     * <p>背景：BMCLAPI 的资产请求 302 到一批社区存储节点，节点整体不可达时
     * 「主站探活」测不出来（302 秒回），但每个文件都会在死节点上白等一个连接超时。
     * 资产动辄上千个，这就是「下载巨慢且频繁重试」的来源。</p>
     *
     * <p>做法：对批量的首个<b>镜像</b>候选（即与官方原始地址不同的那条）做一次 3 秒预检，
     * 失败即熔断该源，整批直接改走其他源 —— 总代价只有 3 秒。健康的源在
     * {@link SourceGate#probeIfNeeded} 的健康期内不会重复探测。</p>
     */
    private void preflight(List<DownloadItem> pending) {
        if (pending.size() < PREFLIGHT_MIN_ITEMS) {
            return;
        }
        for (DownloadItem item : pending) {
            // 默认源顺序是「官方在前、镜像兜底」，所以候选里与官方原始地址不同的那条才是镜像；
            // 不能再用「候选第 0 条」——那个位置现在是官方地址，探测它没有诊断价值
            String mirror = null;
            for (String candidate : resolver.candidates(item)) {
                if (!item.url().equals(candidate)) {
                    mirror = candidate;
                    break;
                }
            }
            if (mirror == null) {
                continue;
            }
            gate.probeIfNeeded(mirror, PREFLIGHT_TIMEOUT_MILLIS);
            // 同一批的镜像相同，探测一次即可
            return;
        }
    }

    /** 尝试之间的退避等待，避免对限流中的源立刻重试（可取消）。 */
    private void backoff(int attempt, ProgressListener listener) {
        long millis = Math.min(BACKOFF_CAP_MILLIS, BACKOFF_BASE_MILLIS * (attempt + 1L));
        if (millis <= 0) {
            return;
        }
        if (listener.isCancelled()) {
            throw new CancelledException();
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException("下载被中断");
        }
    }

    /**
     * 统计「本次实际从网络读取的字节数」。
     * <p>{@code HttpUtils} 上报的是文件内绝对偏移（续传时起点不为 0），
     * 因此需要记录首次上报值作为基线，用终值减基线得到真实流量。</p>
     */
    private static final class TransferTrackingListener implements ProgressListener {

        private final ProgressListener delegate;
        private boolean started = false;
        private long baseline = 0L;
        private long last = 0L;

        TransferTrackingListener(ProgressListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onProgress(long completed, long total) {
            if (!started) {
                started = true;
                baseline = completed;
            }
            last = completed;
            delegate.onProgress(completed, total);
        }

        @Override
        public void onStage(String stage) {
            delegate.onStage(stage);
        }

        @Override
        public void onMessage(String message) {
            delegate.onMessage(message);
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        long transferred() {
            return started ? Math.max(0L, last - baseline) : 0L;
        }
    }

    /** 下载单个文件；失败直接抛出异常（不轮换之外的额外重试）。 */
    public void download(DownloadItem item, ProgressListener listener) throws IOException {
        List<String> candidates = resolver.candidates(item);
        IOException lastError = null;
        for (String url : candidates) {
            listener.throwIfCancelled();
            try {
                HttpUtils.download(URI.create(url), item.target(), listener, item.checksum(), item.size(),
                        item.headers(), requestTimeout, rateLimiter);
                return;
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw lastError == null ? new IOException("没有可用的下载地址: " + item.url()) : lastError;
    }

    /**
     * 等待单个下载任务，期间轮询取消信号。
     *
     * <p>刻意不用无超时的 {@code future.get()}：只要有一个 worker 回不来，等待方就永远
     * 卡在这里，用户点「停止」也不会有任何反应（真实故障）。这里以 200ms 为粒度轮询，
     * 取消请求最迟 200ms 生效；worker 侧另有 {@code HttpUtils} 的停滞看门狗保证它会退出。</p>
     */
    private static void awaitFuture(Future<?> future, ProgressListener listener)
            throws InterruptedException, java.util.concurrent.ExecutionException {
        while (true) {
            try {
                future.get(200, TimeUnit.MILLISECONDS);
                return;
            } catch (java.util.concurrent.TimeoutException retry) {
                // 还没结束：趁等待的空隙检查一次取消
                listener.throwIfCancelled();
            }
        }
    }

    // ------------------------------------------------------------------
    // 幂等判定
    // ------------------------------------------------------------------

    /**
     * 判断目标文件是否已是最新。
     * <p>先比长度快速排除，再比哈希，避免对每次安装都做全量哈希计算带来的浪费。</p>
     */
    public static boolean isUpToDate(DownloadItem item) {
        Path target = item.target();
        try {
            if (!Files.isRegularFile(target)) {
                return false;
            }
            if (item.size() > 0 && Files.size(target) != item.size()) {
                return false;
            }
            if (item.checksum() == null || item.checksum().isBlank()) {
                // 无校验信息时，只要文件存在且长度匹配即认为可用
                return true;
            }
            return HashUtils.sha1(target).equalsIgnoreCase(item.checksum());
        } catch (IOException e) {
            return false;
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------
    // 进度聚合
    // ------------------------------------------------------------------

    /**
     * 把并行条目的进度汇总成单一进度流。
     * <p>单位选择：若全部条目长度已知则按字节；否则退化为按条目计数，
     * 避免出现「进度条长时间不动」的观感问题。</p>
     */
    private static final class Aggregator {

        private final int totalFiles;
        private final long totalBytes;
        private final boolean useBytes;
        private final ProgressListener delegate;

        private final AtomicLong doneBytes;
        private final AtomicInteger doneFiles = new AtomicInteger();
        private final AtomicLong lastEmitMillis = new AtomicLong();

        Aggregator(List<DownloadItem> items, long preDoneBytes, ProgressListener delegate) {
            this.totalFiles = items.size();
            long sum = 0L;
            boolean allKnown = true;
            for (DownloadItem item : items) {
                if (item.size() > 0) {
                    sum += item.size();
                } else {
                    allKnown = false;
                }
            }
            this.totalBytes = sum;
            this.useBytes = allKnown && sum > 0;
            this.doneBytes = new AtomicLong(Math.min(preDoneBytes, sum));
            this.delegate = delegate;
            // 一开始就把总数告诉界面：否则「共 300 个文件」要等第一个下完才出现，
            // 而用户在这段最安静的时间最需要知道到底有多少活要干
            delegate.onItemProgress(doneFiles.get(), totalFiles);
        }

        /** 为每个条目生成独立的子监听器（各自累计增量）。 */
        ProgressListener subListener() {
            return new ProgressListener() {
                private long lastReported = 0L;

                @Override
                public void onProgress(long completed, long total) {
                    long delta = completed - lastReported;
                    lastReported = completed;
                    if (delta > 0) {
                        doneBytes.addAndGet(delta);
                        emit(false);
                    }
                }

                @Override
                public void onMessage(String message) {
                    delegate.onMessage(message);
                }

                @Override
                public boolean isCancelled() {
                    return delegate.isCancelled();
                }
            };
        }

        void fileFinished() {
            int done = doneFiles.incrementAndGet();
            // 计数进度（12/300）不参与节流：它每个文件才变一次，本身就是低频事件，
            // 而「还剩几个文件」恰恰是用户最想知道的信息，不值得为省几次刷新把它丢掉
            delegate.onItemProgress(done, totalFiles);
            // 最后一个条目结束时强制上报：否则终态进度可能被节流窗口丢弃，
            // 界面上会永远停在 99% 之类的位置
            emit(done >= totalFiles);
        }

        void emit(boolean force) {
            long now = System.currentTimeMillis();
            long last = lastEmitMillis.get();
            if (!force && now - last < PROGRESS_THROTTLE_MILLIS) {
                return;
            }
            if (!lastEmitMillis.compareAndSet(last, now) && !force) {
                return;
            }
            if (useBytes) {
                delegate.onProgress(Math.min(doneBytes.get(), totalBytes), totalBytes);
            } else {
                delegate.onProgress(doneFiles.get(), totalFiles);
            }
        }
    }

    /** 便于外部读取当前限速器状态（界面展示用）。 */
    public Map<String, Object> speedInfo() {
        return Map.of("unlimited", rateLimiter.isUnlimited(), "concurrency", concurrency);
    }
}
