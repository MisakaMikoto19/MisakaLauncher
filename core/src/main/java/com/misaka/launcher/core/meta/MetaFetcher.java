package com.misaka.launcher.core.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.misaka.launcher.core.download.DownloadItem;
import com.misaka.launcher.core.download.DownloadTargetType;
import com.misaka.launcher.core.download.SourceGate;
import com.misaka.launcher.core.download.SourceResolver;
import com.misaka.launcher.core.task.CancelledException;
import com.misaka.launcher.core.util.HttpUtils;
import com.misaka.launcher.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据抓取器。
 * <p>与 {@link com.misaka.launcher.core.download.DownloadManager} 的区别在于：
 * 本类只取「小体积文本 / JSON」，不落盘，但同样走多源降级链。</p>
 *
 * <p>与下载共用同一个 {@link SourceGate}：镜像存储故障时（表现为「秒回 302、
 * 跟随跳转后拿不到内容」），版本清单抓取会先被 3 秒预检拦下并熔断该源，
 * 因此界面上的「版本获取」不会长时间空转、更不会直接失败。</p>
 */
public final class MetaFetcher {

    private static final Logger log = LoggerFactory.getLogger(MetaFetcher.class);

    /** 元数据预检超时：坏源必须快速现形，否则「获取版本」会长时间空转。 */
    private static final long PROBE_TIMEOUT_MILLIS = 3_000L;
    /**
     * 单个候选地址的尝试次数。
     * <p>国内网络到部分域名的 TLS 握手会被随机重置（实测 piston-meta / bmclapi
     * 同一脚本连跑几次时好时坏），一次失败并不代表源不可用 ——
     * 只试一次会让「获取版本清单」这类单点操作直接失败。</p>
     */
    private static final int ATTEMPTS_PER_CANDIDATE = 3;
    /** 重试间隔。 */
    private static final long RETRY_DELAY_MILLIS = 400L;
    /** 单次尝试超过该耗时（毫秒）就判定为「慢失败」，不再重试 —— 多半是整体不可达，重试徒增等待。 */
    private static final long SLOW_FAILURE_MILLIS = 6_000L;

    private final SourceResolver resolver;
    private final Duration timeout;
    private final SourceGate gate;

    public MetaFetcher(SourceResolver resolver) {
        this(resolver, Duration.ofSeconds(20), new SourceGate());
    }

    public MetaFetcher(SourceResolver resolver, Duration timeout) {
        this(resolver, timeout, new SourceGate());
    }

    /** 与下载共用健康状态时使用（由 {@code LauncherServices} 注入同一个门）。 */
    public MetaFetcher(SourceResolver resolver, SourceGate gate) {
        this(resolver, Duration.ofSeconds(20), gate);
    }

    public MetaFetcher(SourceResolver resolver, Duration timeout, SourceGate gate) {
        this.resolver = resolver;
        this.timeout = timeout;
        this.gate = gate == null ? new SourceGate() : gate;
    }

    public SourceResolver resolver() {
        return resolver;
    }

    /**
     * 抓取文本内容，按候选地址依次尝试。
     *
     * @param officialUrl 官方地址（由下载源改写成镜像地址）
     * @param type        资源类型，参与改写
     * @param context     改写上下文
     * @param headers     附加请求头
     * @throws IOException 所有候选均失败时抛出，异常信息包含最后一次失败原因
     */
    public String fetchString(String officialUrl,
                              DownloadTargetType type,
                              Map<String, String> context,
                              Map<String, String> headers) throws IOException {
        DownloadItem probe = new DownloadItem(null, officialUrl, "", -1L, type,
                headers == null ? Map.of() : headers, context == null ? Map.of() : context, true);
        List<String> candidates = resolver.candidates(probe);

        IOException lastError = null;
        for (int index = 0; index < candidates.size(); index++) {
            String url = candidates.get(index);
            // 只探测「后面还有兜底候选」的地址：最后一个候选无论如何都得用，
            // 探测它纯属多一次请求（会白白消耗 GitHub 之类的匿名配额）
            if (hasFallback(candidates, index) && !gate.probeIfNeeded(url, PROBE_TIMEOUT_MILLIS)) {
                log.debug("元数据源预检未通过，跳过：{}", url);
                continue;
            }
            for (int attempt = 0; attempt < ATTEMPTS_PER_CANDIDATE; attempt++) {
                long started = System.nanoTime();
                try {
                    String body = HttpUtils.getString(url, headers == null ? Map.of() : headers, timeout);
                    if (body != null && !body.isBlank()) {
                        gate.markSuccess(url);
                        return body;
                    }
                    lastError = new IOException("响应内容为空: " + url);
                } catch (IOException e) {
                    lastError = e;
                }
                long cost = (System.nanoTime() - started) / 1_000_000L;
                log.debug("元数据抓取失败（第 {}/{} 次，{} ms）：{} -> {}", attempt + 1,
                        ATTEMPTS_PER_CANDIDATE, cost, url, lastError.getMessage());
                if (cost >= SLOW_FAILURE_MILLIS) {
                    break;
                }
                sleepQuietly();
            }
            gate.markFailure(url);
            log.debug("该源多次失败，改用下一个候选：{}", url);
        }
        throw new IOException("元数据抓取失败（已尝试 " + candidates.size() + " 个地址）: " + officialUrl,
                lastError);
    }

    public String fetchString(String officialUrl, DownloadTargetType type) throws IOException {
        return fetchString(officialUrl, type, Map.of(), Map.of());
    }

    /** 抓取并解析 JSON。 */
    public JsonNode fetchJson(String officialUrl,
                              DownloadTargetType type,
                              Map<String, String> context,
                              Map<String, String> headers) throws IOException {
        String body = fetchString(officialUrl, type, context, headers);
        try {
            return JsonUtils.read(body);
        } catch (IOException e) {
            throw new IOException("元数据不是合法 JSON: " + officialUrl, e);
        }
    }

    public JsonNode fetchJson(String officialUrl, DownloadTargetType type) throws IOException {
        return fetchJson(officialUrl, type, Map.of(), Map.of());
    }

    /**
     * 依次尝试一组候选地址，返回首个成功结果。
     * <p>用于「部分镜像才提供的接口」（例如 BMCLAPI 的加载器版本列表）：
     * 调用方按可用性顺序给出地址，本方法负责容错与降级。</p>
     *
     * @throws IOException 全部候选失败
     */
    public String fetchFirstAvailable(List<String> candidateUrls, Map<String, String> headers) throws IOException {
        IOException lastError = null;
        for (int index = 0; index < candidateUrls.size(); index++) {
            String url = candidateUrls.get(index);
            if (hasFallback(candidateUrls, index) && !gate.probeIfNeeded(url, PROBE_TIMEOUT_MILLIS)) {
                log.debug("候选地址预检未通过，跳过：{}", url);
                continue;
            }
            try {
                String body = HttpUtils.getString(url, headers == null ? Map.of() : headers, timeout);
                if (body != null && !body.isBlank()) {
                    gate.markSuccess(url);
                    return body;
                }
                lastError = new IOException("响应内容为空: " + url);
                gate.markFailure(url);
            } catch (IOException e) {
                lastError = e;
                gate.markFailure(url);
                log.debug("候选地址失败：{} -> {}", url, e.getMessage());
            }
        }
        throw new IOException("所有候选地址均不可用（" + candidateUrls.size() + " 个）", lastError);
    }

    /** 尝试抓取文本，失败返回 null（用于可选的校验信息，如 Maven 的 .sha1 边车文件）。 */
    public String tryFetchString(String url) {
        try {
            return HttpUtils.getString(url, Map.of(), timeout);
        } catch (IOException e) {
            return null;
        }
    }

    /** 该候选之后是否还有别的候选可用作兜底。 */
    private static boolean hasFallback(List<String> candidates, int index) {
        return index < candidates.size() - 1;
    }

    /** 重试前的短暂等待；被中断时直接抛出取消。 */
    private static void sleepQuietly() {
        try {
            Thread.sleep(RETRY_DELAY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException("元数据抓取被中断");
        }
    }

    /** 便捷构造上下文。 */
    public static Map<String, String> context(String key, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        if (key != null) {
            map.put(key, value);
        }
        return map;
    }
}
