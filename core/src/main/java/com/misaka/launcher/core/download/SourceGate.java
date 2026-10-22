package com.misaka.launcher.core.download;

import com.misaka.launcher.core.util.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 下载源健康门：快速预检 + 熔断。
 *
 * <p><b>为什么需要它</b>：镜像（BMCLAPI）的存储后端一旦整体故障，
 * 它的入口仍会<b>秒回 302</b>，只有跟随跳转后的地址才真正不可达。
 * 不做识别的话会付出两种代价：</p>
 * <ul>
 *   <li>下载：每个文件都在坏源上白等一个连接超时（15 秒）—— 资产上千个时直接不可用；</li>
 *   <li>元数据：版本清单抓取会卡到超时甚至失败，界面表现就是「版本列表刷不出来」。</li>
 * </ul>
 *
 * <p><b>两个机制</b>：</p>
 * <ol>
 *   <li><b>预检</b>：用 3 秒探针把「跟随跳转后是否真的拿到内容」问清楚。
 *       只认 2xx —— 残余的 3xx 说明跳转链没有产出内容，必须按失败处理
 *       （这是最初版本把 3xx 当可达、导致熔断永远打不开的根因）；</li>
 *   <li><b>熔断</b>：连续失败（或预检失败）达到阈值后，一段时间内所有调用方
 *       直接跳过该源；成功则清零，避免偶发抖动误伤。</li>
 * </ol>
 *
 * <p>本类由 {@code LauncherServices} 持有一个实例并注入 {@link DownloadManager}
 * 与 {@code MetaFetcher}，两者共享同一份健康状态 —— 下载阶段发现镜像挂了，
 * 元数据抓取立刻受益（反之亦然）。</p>
 */
public final class SourceGate {

    private static final Logger log = LoggerFactory.getLogger(SourceGate.class);

    /** 连续失败多少次后熔断一个源。 */
    private static final int BREAKER_THRESHOLD = 3;
    /** 熔断持续时长；到期后放行一次探测（半开），失败则再次熔断。 */
    private static final long BREAKER_COOLDOWN_MILLIS = 60_000L;
    /** 最近一次确认健康的有效期：期内无需重复预检。 */
    private static final long HEALTHY_TTL_MILLIS = 60_000L;

    private final ConcurrentHashMap<String, Health> health = new ConcurrentHashMap<>();

    /** 该源当前是否处于熔断中。 */
    public boolean isBroken(String url) {
        Health entry = health.get(keyOf(url));
        return entry != null && entry.openUntilMillis > System.currentTimeMillis();
    }

    /** 登记一次成功：清零失败计数。 */
    public void markSuccess(String url) {
        Health entry = health.computeIfAbsent(keyOf(url), key -> new Health());
        entry.consecutiveFailures.set(0);
        entry.openUntilMillis = 0L;
        entry.healthyUntilMillis = System.currentTimeMillis() + HEALTHY_TTL_MILLIS;
    }

    /** 登记一次失败；达到阈值即熔断。 */
    public void markFailure(String url) {
        Health entry = health.computeIfAbsent(keyOf(url), key -> new Health());
        entry.healthyUntilMillis = 0L;
        int failures = entry.consecutiveFailures.incrementAndGet();
        if (failures >= BREAKER_THRESHOLD && entry.openUntilMillis <= System.currentTimeMillis()) {
            entry.consecutiveFailures.set(0);
            open(url, failures + " 次连续失败");
        }
    }

    /** 直接熔断（用于预检失败这类「一次失败即定论」的场景）。 */
    public void trip(String url, String reason) {
        health.computeIfAbsent(keyOf(url), key -> new Health()).consecutiveFailures.set(0);
        open(url, reason);
    }

    /**
     * 需要时预检该源；返回「现在是否可以用它」。
     *
     * <p>近期已确认健康（{@value #HEALTHY_TTL_MILLIS} 毫秒内）的源不再重复探测，
     * 因此正常情况下的额外开销接近于零；只有状态未知或已过期的源才会付出一次探测。</p>
     *
     * @param timeoutMillis 探测超时；坏源必须快速失败
     */
    public boolean probeIfNeeded(String url, long timeoutMillis) {
        String key = keyOf(url);
        Health entry = health.get(key);
        long now = System.currentTimeMillis();
        if (entry != null) {
            if (entry.openUntilMillis > now) {
                return false;
            }
            if (entry.healthyUntilMillis > now) {
                return true;
            }
        }

        long elapsed = HttpUtils.probeFast(url, timeoutMillis);
        if (elapsed < 0) {
            trip(url, "预检 " + timeoutMillis + " 毫秒内未拿到内容");
            return false;
        }
        markSuccess(url);
        log.info("源预检通过：{}（{} ms）", url, elapsed);
        return true;
    }

    private void open(String url, String reason) {
        Health entry = health.computeIfAbsent(keyOf(url), key -> new Health());
        entry.openUntilMillis = System.currentTimeMillis() + BREAKER_COOLDOWN_MILLIS;
        log.warn("源 {} 熔断 {} 秒（{}）；期间其候选直接跳过，不再逐个等待超时",
                keyOf(url), BREAKER_COOLDOWN_MILLIS / 1000, reason);
    }

    /**
     * 健康度的键：{@code host:port}。
     * <p>不能用裸主机名 —— 同一台机器上的多个源（测试桩、反向代理）会共享状态，
     * 健康源的一次成功就会把坏源的失败计数清零，熔断永远打不开。</p>
     */
    private static String keyOf(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return url;
            }
            int port = uri.getPort();
            return port == -1 ? host : host + ":" + port;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    /** 按 host:port 聚合的健康状态。 */
    private static final class Health {
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        volatile long openUntilMillis;
        volatile long healthyUntilMillis;
    }
}
