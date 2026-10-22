package com.misaka.launcher.core.sync;

import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.task.CancelledException;
import com.misaka.launcher.core.task.ProgressListener;
import com.misaka.launcher.core.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 同步执行器：按计划删除与下载，并在完成后复查。
 * <p>执行顺序与并发参数严格对齐原客户端：<b>先删除、后下载</b>，默认 6 并发、单文件 3 次重试。</p>
 */
public final class SyncEngine {

    private static final Logger log = LoggerFactory.getLogger(SyncEngine.class);

    private final SyncProtocolClient client;
    private final LauncherConfig.SyncSettings settings;

    public SyncEngine(SyncProtocolClient client, LauncherConfig.SyncSettings settings) {
        this.client = client;
        this.settings = settings;
    }

    public SyncProtocolClient client() {
        return client;
    }

    // ==================================================================
    // 只检查
    // ==================================================================

    /** 仅检查并返回计划，不做任何修改。 */
    public SyncPlan check(Path syncRoot, ProgressListener listener) throws IOException {
        listener.onStage(I18n.t("sync.stage.scan"));
        Map<String, LocalFileInfo> localFiles = SyncPlanner.scanLocal(syncRoot,
                settings.directories, settings.ignoredFiles);
        listener.onMessage("扫描到 " + localFiles.size() + " 个本地文件");

        listener.onStage(I18n.t("sync.stage.config"));
        ServerSyncConfig serverConfig = client.fetchConfig();

        listener.onStage(I18n.t("sync.stage.plan"));
        List<ServerFileInfo> serverFiles = client.fetchFileList();
        listener.onMessage("服务器共有 " + serverFiles.size() + " 个文件");

        return SyncPlanner.plan(syncRoot, settings.directories, serverFiles, serverConfig,
                localFiles, settings.allowDeleteLocal);
    }

    // ==================================================================
    // 执行
    // ==================================================================

    /** 执行同步（不含复查）。 */
    public SyncResult sync(Path syncRoot, ProgressListener listener) throws IOException {
        long start = System.currentTimeMillis();

        listener.onStage(I18n.t("sync.stage.scan"));
        Map<String, LocalFileInfo> localFiles = SyncPlanner.scanLocal(syncRoot,
                settings.directories, settings.ignoredFiles);
        listener.onMessage("扫描到 " + localFiles.size() + " 个本地文件");

        listener.onStage(I18n.t("sync.stage.config"));
        ServerSyncConfig serverConfig = client.fetchConfig();

        listener.onStage(I18n.t("sync.stage.plan"));
        List<ServerFileInfo> serverFiles = client.fetchFileList();
        SyncPlan plan = SyncPlanner.plan(syncRoot, settings.directories, serverFiles, serverConfig,
                localFiles, settings.allowDeleteLocal);

        if (!plan.hasChanges()) {
            listener.onMessage(I18n.t("sync.noChanges"));
            return new SyncResult(plan.totalFiles(), plan.upToDateCount(), 0, 0, 0, 0, 0L, -1,
                    System.currentTimeMillis() - start, List.of());
        }

        List<SyncResult.Failure> failures = Collections.synchronizedList(new ArrayList<>());

        // ---- 先删除 ----
        int deleted = 0;
        if (!plan.toDelete().isEmpty()) {
            listener.onStage(I18n.t("sync.stage.delete"));
            for (String fileKey : plan.toDelete()) {
                listener.throwIfCancelled();
                try {
                    Path target = SyncPlanner.resolveTarget(syncRoot.toAbsolutePath().normalize(), fileKey);
                    if (Files.deleteIfExists(target)) {
                        deleted++;
                        listener.onMessage("已删除 " + fileKey);
                    }
                } catch (IOException e) {
                    // 与原客户端一致：单个文件删除失败不中断流程，仅记录
                    failures.add(new SyncResult.Failure(fileKey, "删除失败: " + e.getMessage()));
                    log.warn("删除失败 {}：{}", fileKey, e.getMessage());
                }
            }
            listener.onMessage("已删除 " + deleted + " 个多余文件");
        }

        // ---- 后下载 ----
        int downloaded = 0;
        int updated = 0;
        long transferred = 0L;
        if (!plan.toDownload().isEmpty()) {
            listener.onStage(I18n.t("sync.stage.download"));
            DownloadOutcome outcome = downloadAll(plan, localFiles, listener, failures);
            downloaded = outcome.downloaded();
            updated = outcome.updated();
            transferred = outcome.bytes();
        }

        long elapsed = System.currentTimeMillis() - start;
        SyncResult result = new SyncResult(plan.totalFiles(), plan.upToDateCount(),
                downloaded, updated, deleted, failures.size(), transferred, -1, elapsed,
                List.copyOf(failures));
        log.info("同步完成：{}（耗时 {} ms）", result.summary(), elapsed);

        // ---- 可选复查 ----
        if (settings.recheckAfterSync) {
            return recheck(syncRoot, listener, result);
        }
        return result;
    }

    /**
     * 完整流程：同步 + 复查。
     * <p>与 {@link #sync} 的区别仅在于是否强制复查：{@code sync} 由
     * {@code settings.recheckAfterSync} 决定，本方法始终复查，
     * 供界面上的「检查并同步」按钮使用。</p>
     */
    public SyncResult syncWithRecheck(Path syncRoot, ProgressListener listener) throws IOException {
        SyncResult result = sync(syncRoot, listener);
        if (result.remainingChanges() >= 0) {
            return result;
        }
        return recheck(syncRoot, listener, result);
    }

    /** 复查：重新拉取与重新判定，仅在结果中补充 remainingChanges。 */
    private SyncResult recheck(Path syncRoot, ProgressListener listener, SyncResult previous) throws IOException {
        listener.onStage(I18n.t("sync.stage.recheck"));
        try {
            Map<String, LocalFileInfo> localFiles = SyncPlanner.scanLocal(syncRoot,
                    settings.directories, settings.ignoredFiles);
            ServerSyncConfig serverConfig = client.fetchConfig();
            List<ServerFileInfo> serverFiles = client.fetchFileList();
            SyncPlan rechecked = SyncPlanner.plan(syncRoot, settings.directories, serverFiles, serverConfig,
                    localFiles, settings.allowDeleteLocal);

            int remaining = rechecked.toDownload().size()
                    + (settings.allowDeleteLocal ? rechecked.toDelete().size() : 0);
            listener.onMessage("复查完成，仍有 " + remaining + " 项待处理");
            return new SyncResult(previous.totalFiles(), rechecked.upToDateCount(),
                    previous.downloaded(), previous.updated(), previous.deleted(),
                    previous.failed(), previous.bytes(), remaining, previous.elapsedMillis(),
                    previous.failures());
        } catch (IOException e) {
            // 复查失败不影响同步结论
            log.warn("同步复查失败：{}", e.getMessage());
            listener.onMessage("复查失败：" + e.getMessage());
            return previous;
        }
    }

    // ==================================================================
    // 并发下载
    // ==================================================================

    private record DownloadOutcome(int downloaded, int updated, long bytes) {
    }

    /**
     * 等待单个同步任务，期间轮询取消信号。
     *
     * <p>不用无超时的 {@code future.get()}：只要有一个 worker 卡在网络上，等待方就再也
     * 回不来，「停止」按钮随之失效（真实故障：同步偶发卡住、只能杀进程）。</p>
     */
    private static void awaitFuture(java.util.concurrent.Future<?> future, ProgressListener listener)
            throws InterruptedException, java.util.concurrent.ExecutionException {
        while (true) {
            try {
                future.get(200, TimeUnit.MILLISECONDS);
                return;
            } catch (java.util.concurrent.TimeoutException retry) {
                listener.throwIfCancelled();
            }
        }
    }

    private DownloadOutcome downloadAll(SyncPlan plan,
                                        Map<String, LocalFileInfo> localFiles,
                                        ProgressListener listener,
                                        List<SyncResult.Failure> failures) {
        int concurrency = Math.max(1, settings.concurrency);
        int maxAttempts = Math.max(1, settings.retries);

        AtomicInteger downloaded = new AtomicInteger();
        AtomicInteger updated = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        AtomicInteger completed = new AtomicInteger();
        int total = plan.toDownload().size();

        ExecutorService pool = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "misaka-sync");
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>(total);
            for (SyncDownload task : plan.toDownload()) {
                futures.add(pool.submit(() -> {
                    try {
                        if (listener.isCancelled()) {
                            throw new CancelledException();
                        }
                        boolean isUpdate = localFiles.containsKey(task.fileKey());
                        boolean ok = downloadWithRetry(task, maxAttempts, listener, bytes);
                        if (ok) {
                            if (isUpdate) {
                                updated.incrementAndGet();
                            } else {
                                downloaded.incrementAndGet();
                            }
                            listener.onMessage((isUpdate ? "已更新 " : "已下载 ") + task.fileKey());
                        } else {
                            failures.add(new SyncResult.Failure(task.fileKey(), "下载失败（已重试 "
                                    + maxAttempts + " 次）"));
                        }
                    } catch (CancelledException e) {
                        throw e;
                    } finally {
                        int done = completed.incrementAndGet();
                        listener.onProgress(done, total);
                        // 同一组数字再以「项数」形式上报一次：进度条用它推百分比，
                        // 详情里用它显示 12/300 —— 两者粒度不同，不能互相推导
                        listener.onItemProgress(done, total);
                    }
                }));
            }

            // 与下载侧同样的道理：不做无限期盲等，等待期间轮询取消信号，
            // 否则「停止」在同步阶段会完全失效（真实故障）
            for (java.util.concurrent.Future<?> future : futures) {
                try {
                    awaitFuture(future, listener);
                } catch (java.util.concurrent.CancellationException ignored) {
                    // 取消时统一在下方处理
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancelledException("同步被中断");
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof CancelledException cancelled) {
                        throw cancelled;
                    }
                    log.debug("同步任务异常：{}", cause == null ? e.getMessage() : cause.getMessage());
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
        listener.onMessage("下载阶段完成：新增 " + downloaded.get() + "，更新 " + updated.get());
        return new DownloadOutcome(downloaded.get(), updated.get(), bytes.get());
    }

    /**
     * 带重试的单文件下载。
     * <p>与原客户端一致：失败后间隔 1 秒重试，最多 {@code retries} 次。</p>
     */
    private boolean downloadWithRetry(SyncDownload task, int maxAttempts, ProgressListener listener,
                                     AtomicLong bytesCounter) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (listener.isCancelled()) {
                throw new CancelledException();
            }
            try {
                FileUtils.ensureParent(task.target());
            } catch (IOException e) {
                log.warn("创建目标目录失败 {}：{}", task.target(), e.getMessage());
                return false;
            }

            long before = sizeOf(task.target());
            ProgressListener sub = new ProgressListener() {
                @Override
                public void onMessage(String message) {
                    listener.onMessage(message);
                }

                @Override
                public boolean isCancelled() {
                    return listener.isCancelled();
                }
            };
            if (client.download(task.serverPath(), task.target(), task.expectedMd5(), task.size(), sub)) {
                bytesCounter.addAndGet(Math.max(0L, sizeOf(task.target()) - before));
                return true;
            }
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancelledException("同步被中断");
                }
            }
        }
        return false;
    }

    private static long sizeOf(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 便捷方法：用当前配置构造客户端与引擎。 */
    public static SyncEngine of(LauncherConfig.SyncSettings settings) {
        SyncProtocolClient client = new SyncProtocolClient(settings.serverAddress, settings.serverPort,
                settings.metaTimeoutSeconds, settings.downloadTimeoutSeconds);
        return new SyncEngine(client, settings);
    }
}
