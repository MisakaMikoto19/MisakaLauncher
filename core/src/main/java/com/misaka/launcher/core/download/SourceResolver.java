package com.misaka.launcher.core.download;

import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.util.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 下载源择优器。
 * <p>两种工作模式：</p>
 * <ul>
 *   <li>{@code auto}（默认）：<b>官方源优先</b>，官方地址不可达时才降级到镜像；顺序是确定的，
 *       测速结果只作诊断展示、不参与重排（见 {@link #refreshOrder()}）；</li>
 *   <li>手动指定（{@code official} / {@code bmclapi} / {@code custom}）：
 *       用户所选源优先，其余源按默认优先级作为降级链，保证不会因为单一源不可用而整体失败；
 *       其中 {@code official} 是严格模式 —— 只用官方、不降级（便于排查镜像问题）。</li>
 * </ul>
 */
public final class SourceResolver {

    private static final Logger log = LoggerFactory.getLogger(SourceResolver.class);

    /** 单源探测超时（毫秒）。镜像不可达时应快速失败并降级，不能拖慢整体启动。 */
    private static final long PROBE_TIMEOUT_MILLIS = 3000L;

    private final LauncherConfig.DownloadSettings settings;
    private final List<DownloadSource> allSources;
    private final String mode;

    private volatile List<DownloadSource> ordered;
    private volatile Map<String, Long> latencies;

    public SourceResolver(LauncherConfig.DownloadSettings settings) {
        this(settings, defaultSources(settings));
    }

    /**
     * 使用显式源列表构造。
     * <p>两个用途：其一是单测中注入本地桩源，避免任何真实网络访问；
     * 其二供上层实现「只用某个源」的严格模式。</p>
     *
     * @param settings 下载设置（并发、超时、测速缓存等）
     * @param sources  按默认优先级排列的源；至少应包含一个恒等映射的源，
     *                 否则改写失败时无兜底地址可用
     */
    public SourceResolver(LauncherConfig.DownloadSettings settings, List<DownloadSource> sources) {
        this.settings = settings;
        this.mode = settings.source == null ? LauncherConfig.DownloadSettings.SOURCE_AUTO : settings.source;
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("下载源列表不能为空");
        }
        List<DownloadSource> sorted = new ArrayList<>(sources);
        sorted.sort(Comparator.comparingInt(DownloadSource::priority));
        this.allSources = List.copyOf(sorted);

        this.latencies = new LinkedHashMap<>(settings.sourceLatency == null ? Map.of() : settings.sourceLatency);
        this.ordered = manualOrder();
    }

    /**
     * 默认源集合。
     *
     * <p>顺序由各源的 {@code priority()} 决定（这里加入的顺序无关）：官方源最优先，
     * 镜像排在最后；配置了自定义源时它排在最前（用户显式指定了镜像地址，应当被使用）。</p>
     */
    private static List<DownloadSource> defaultSources(LauncherConfig.DownloadSettings settings) {
        List<DownloadSource> sources = new ArrayList<>();
        if (settings.customSourceUrl != null && !settings.customSourceUrl.isBlank()) {
            sources.add(CustomSource.reverseProxy(settings.customSourceUrl));
        }
        sources.add(new OfficialSource());
        sources.add(new BmclapiSource());
        return sources;
    }

    public String mode() {
        return mode;
    }

    /** 参与的全部源（按默认优先级）。 */
    public List<DownloadSource> allSources() {
        return allSources;
    }

    /** 当前生效的源顺序。 */
    public List<DownloadSource> orderedSources() {
        return ordered;
    }

    /** 最近一次测速结果（源 id → 往返毫秒；未测或不可达不在其中）。 */
    public Map<String, Long> latencies() {
        return Map.copyOf(latencies);
    }

    /**
     * 按配置计算源顺序（自动模式同样走这里）。
     * <p>用户显式指定的源排在首位，其后按默认优先级补齐，形成降级链；
     * 自动模式下没有「指定源」，于是完全按默认优先级 —— 官方在镜像之前。</p>
     */
    private List<DownloadSource> manualOrder() {
        List<DownloadSource> result = new ArrayList<>();
        DownloadSource preferred = switch (mode) {
            case LauncherConfig.DownloadSettings.SOURCE_OFFICIAL -> sourceById(OfficialSource.ID);
            case LauncherConfig.DownloadSettings.SOURCE_BMCLAPI -> sourceById(BmclapiSource.ID);
            case LauncherConfig.DownloadSettings.SOURCE_CUSTOM -> sourceById(CustomSource.ID);
            default -> null;
        };
        if (preferred != null) {
            result.add(preferred);
            if (mode.equals(LauncherConfig.DownloadSettings.SOURCE_OFFICIAL)) {
                // 明确指定官方源时不降级，尊重用户意图（例如用于排查镜像）、
                return List.copyOf(result);
            }
        }
        for (DownloadSource source : allSources) {
            if (!result.contains(source)) {
                result.add(source);
            }
        }
        return List.copyOf(result);
    }

    private DownloadSource sourceById(String id) {
        return allSources.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * 探测各源延迟（阻塞操作，请放在后台线程调用）。
     *
     * <p>测速结果只作为诊断信息展示与持久化，<b>不用于重排</b>：源顺序必须是确定性的
     * —— 默认官方优先、镜像兜底；用户在设置里选了镜像就镜像优先。若让某一次测速的
     * 偶然结果来排序，同一份配置在不同网络下会得到不同的下载路径，
     * 排查「为什么这台机器下不来」时就没有共同前提了。</p>
     *
     * <p>各源并行探测，总耗时约等于最慢源的单次往返。</p>
     */
    public void refreshOrder() {
        if (!LauncherConfig.DownloadSettings.SOURCE_AUTO.equals(mode)) {
            ordered = manualOrder();
            return;
        }
        List<DownloadSource> probeTargets = allSources.stream()
                .filter(s -> s.probeUrl() != null)
                .toList();
        if (probeTargets.isEmpty()) {
            ordered = manualOrder();
            return;
        }

        Map<String, Long> measured = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(probeTargets.size(), r -> {
            Thread thread = new Thread(r, "misaka-source-probe");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (DownloadSource source : probeTargets) {
                futures.add(CompletableFuture.runAsync(() -> {
                    long ms = HttpUtils.latencyMillis(source.probeUrl(), PROBE_TIMEOUT_MILLIS);
                    synchronized (measured) {
                        measured.put(source.id(), ms);
                    }
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdownNow();
        }

        Map<String, Long> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : measured.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                merged.put(entry.getKey(), entry.getValue());
                log.info("下载源测速：{} = {} ms", entry.getKey(), entry.getValue());
            } else {
                log.warn("下载源不可达：{}", entry.getKey());
            }
        }
        this.latencies = merged;
        persistLatencies(merged);

        // 测速只诊断、不排序：默认官方优先，镜像兜底
        this.ordered = manualOrder();
        log.info("下载源顺序（默认官方优先）：{}", ordered.stream().map(DownloadSource::id).toList());
    }

    /** 立即重排（不发起网络请求）。顺序是确定性的：默认官方优先、镜像兜底。 */
    public void applyCachedOrder() {
        ordered = manualOrder();
    }

    private void persistLatencies(Map<String, Long> measured) {
        if (settings.sourceLatency == null) {
            settings.sourceLatency = new LinkedHashMap<>();
        }
        settings.sourceLatency.clear();
        settings.sourceLatency.putAll(measured);
    }

    /**
     * 为一个下载条目给出有序候选地址列表。
     * <p>始终保证官方地址出现在最后，作为最终兜底。</p>
     */
    public List<String> candidates(DownloadItem item) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (DownloadSource source : ordered) {
            String rewritten = source.rewrite(item.url(), item.type(), item.context());
            if (rewritten != null && !rewritten.isBlank()) {
                result.add(rewritten);
            }
        }
        result.add(item.url());
        return List.copyOf(result);
    }

    /** 等待探测任务使用的线程池优雅退出（仅测试使用）。 */
    static void awaitTermination(ExecutorService pool, long millis) {
        try {
            pool.awaitTermination(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
