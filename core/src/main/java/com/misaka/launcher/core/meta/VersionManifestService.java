package com.misaka.launcher.core.meta;

import com.misaka.launcher.core.config.PortablePaths;
import com.misaka.launcher.core.download.DownloadTargetType;
import com.misaka.launcher.core.model.VersionDetail;
import com.misaka.launcher.core.model.VersionManifest;
import com.misaka.launcher.core.util.FileUtils;
import com.misaka.launcher.core.util.HashUtils;
import com.misaka.launcher.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 版本元数据服务。
 * <p>两层缓存：</p>
 * <ul>
 *   <li>版本清单缓存 10 分钟，避免每次打开安装页都请求网络；</li>
 *   <li>版本 JSON 按版本永久缓存（内容由哈希保证不变），仅在文件缺失或强制刷新时重新抓取。</li>
 * </ul>
 * <p>抓取一律经 {@link MetaFetcher}，因此自带「镜像 → 官方」降级能力。</p>
 */
public final class VersionManifestService {

    private static final Logger log = LoggerFactory.getLogger(VersionManifestService.class);

    /** 官方版本清单地址。 */
    public static final String MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /** 清单缓存有效期。 */
    private static final long MANIFEST_TTL_MILLIS = 10L * 60L * 1000L;

    private final PortablePaths paths;
    private final MetaFetcher fetcher;
    /** 清单抓取地址；单测可注入，把整条抓取链指向必然失败的地址。 */
    private final String manifestUrl;

    private volatile VersionManifest cachedManifest;

    public VersionManifestService(PortablePaths paths, MetaFetcher fetcher) {
        this(paths, fetcher, MANIFEST_URL);
    }

    VersionManifestService(PortablePaths paths, MetaFetcher fetcher, String manifestUrl) {
        this.paths = paths;
        this.fetcher = fetcher;
        this.manifestUrl = manifestUrl;
    }

    public MetaFetcher fetcher() {
        return fetcher;
    }

    // ------------------------------------------------------------------
    // 版本清单
    // ------------------------------------------------------------------

    /**
     * 获取版本清单（带内存 + 磁盘缓存）。
     *
     * @param forceRefresh 忽略缓存强制重新抓取
     */
    public VersionManifest manifest(boolean forceRefresh) throws IOException {
        if (!forceRefresh && cachedManifest != null) {
            return cachedManifest;
        }
        Path cacheFile = manifestCacheFile();

        if (!forceRefresh && Files.isRegularFile(cacheFile)
                && System.currentTimeMillis() - Files.getLastModifiedTime(cacheFile).toMillis() < MANIFEST_TTL_MILLIS) {
            try {
                VersionManifest cached = JsonUtils.read(cacheFile, VersionManifest.class);
                cachedManifest = cached;
                log.debug("使用缓存的版本清单（{} 个版本）", cached.versions.size());
                return cached;
            } catch (IOException e) {
                log.warn("版本清单缓存损坏，重新抓取: {}", e.getMessage());
            }
        }

        try {
            String body = fetcher.fetchString(manifestUrl, DownloadTargetType.VERSION_MANIFEST);
            FileUtils.ensureParent(cacheFile);
            FileUtils.atomicWrite(cacheFile, body);
            VersionManifest manifest = JsonUtils.read(body, VersionManifest.class);
            cachedManifest = manifest;
            log.info("已获取版本清单：共 {} 个版本，最新正式版 {}", manifest.versions.size(), manifest.latest.release);
            return manifest;
        } catch (IOException e) {
            // 网络全挂时退回本地旧清单。清单只决定「有哪些版本、版本 JSON 从哪下载」，
            // 用 3 小时前的清单安装 1.20.1 与用刚抓的毫无区别；
            // 而直接抛错会让校验/安装整体卡死 —— 真实故障：晚间 BMCLAPI 与官方同时不可达，
            // 用户连续两次「校验文件」失败，磁盘上明明躺着几小时前的可用清单。
            VersionManifest stale = readStaleManifest(cacheFile);
            if (stale == null) {
                throw e;
            }
            long age = ageOfCache(cacheFile);
            log.warn("版本清单抓取失败（{}），改用本地缓存继续：约 {} 前抓取，共 {} 个版本",
                    e.getMessage(), describeAge(age), stale.versions.size());
            cachedManifest = stale;
            return stale;
        }
    }

    /** 读取过期的本地清单缓存；文件缺失或内容不可用时返回 null。 */
    private VersionManifest readStaleManifest(Path cacheFile) {
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }
        try {
            VersionManifest stale = JsonUtils.read(cacheFile, VersionManifest.class);
            if (stale.versions == null || stale.versions.isEmpty()) {
                return null;
            }
            return stale;
        } catch (IOException e) {
            log.warn("本地清单缓存也无法读取: {}", e.getMessage());
            return null;
        }
    }

    private static long ageOfCache(Path cacheFile) {
        try {
            return Math.max(0, System.currentTimeMillis() - Files.getLastModifiedTime(cacheFile).toMillis());
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String describeAge(long millis) {
        long minutes = Math.max(1, millis / 60_000L);
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        long hours = minutes / 60;
        return hours + " 小时" + (minutes % 60 > 0 ? " " + (minutes % 60) + " 分" : "");
    }

    public VersionManifest manifest() throws IOException {
        return manifest(false);
    }

    /** 磁盘上的清单缓存位置。 */
    public Path manifestCacheFile() {
        return paths.metaCacheDir().resolve("version_manifest_v2.json");
    }

    /** 按 id 查找版本引用。 */
    public VersionManifest.VersionRef findVersion(String versionId) throws IOException {
        return manifest().find(versionId);
    }

    /** 正式版列表（按发布时间倒序，与清单顺序一致）。 */
    public List<VersionManifest.VersionRef> releases(boolean forceRefresh) throws IOException {
        List<VersionManifest.VersionRef> result = new ArrayList<>();
        for (VersionManifest.VersionRef ref : manifest(forceRefresh).versions) {
            if (ref.isRelease()) {
                result.add(ref);
            }
        }
        return result;
    }

    /** 快照列表。 */
    public List<VersionManifest.VersionRef> snapshots(boolean forceRefresh) throws IOException {
        List<VersionManifest.VersionRef> result = new ArrayList<>();
        for (VersionManifest.VersionRef ref : manifest(forceRefresh).versions) {
            if (ref.isSnapshot()) {
                result.add(ref);
            }
        }
        return result;
    }

    /** 远古版本（Alpha/Beta）。 */
    public List<VersionManifest.VersionRef> oldVersions(boolean forceRefresh) throws IOException {
        List<VersionManifest.VersionRef> result = new ArrayList<>();
        for (VersionManifest.VersionRef ref : manifest(forceRefresh).versions) {
            if (ref.isOld()) {
                result.add(ref);
            }
        }
        return result;
    }

    /** 最新正式版 id。 */
    public String latestRelease() throws IOException {
        return manifest().latest.release;
    }

    // ------------------------------------------------------------------
    // 版本 JSON
    // ------------------------------------------------------------------

    /**
     * 获取指定版本的详细信息。
     * <p>顺序：内存 → 磁盘缓存 → 网络。磁盘缓存命中时不会产生任何请求。</p>
     */
    public VersionDetail versionDetail(String versionId, boolean forceRefresh) throws IOException {
        Path cacheFile = cachedVersionJson(versionId);
        if (!forceRefresh && Files.isRegularFile(cacheFile)) {
            try {
                VersionDetail detail = JsonUtils.read(cacheFile, VersionDetail.class);
                detail.sourceJson = cacheFile;
                return detail;
            } catch (IOException e) {
                log.warn("版本 JSON 缓存损坏，重新抓取 {}: {}", versionId, e.getMessage());
                FileUtils.deleteQuietly(cacheFile);
            }
        }

        VersionManifest.VersionRef ref = findVersion(versionId);
        if (ref == null) {
            throw new IOException("版本清单中不存在该版本: " + versionId);
        }
        return versionDetail(ref, forceRefresh);
    }

    public VersionDetail versionDetail(VersionManifest.VersionRef ref, boolean forceRefresh) throws IOException {
        Path cacheFile = cachedVersionJson(ref.id);
        if (!forceRefresh && Files.isRegularFile(cacheFile)) {
            try {
                VersionDetail detail = JsonUtils.read(cacheFile, VersionDetail.class);
                detail.sourceJson = cacheFile;
                return detail;
            } catch (IOException e) {
                FileUtils.deleteQuietly(cacheFile);
            }
        }

        String body = fetcher.fetchString(ref.url, DownloadTargetType.VERSION_JSON,
                MetaFetcher.context("versionId", ref.id), java.util.Map.of());

        if (ref.sha1 != null && !ref.sha1.isBlank()) {
            String actual = HashUtils.sha1(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!actual.equalsIgnoreCase(ref.sha1)) {
                // 不直接失败：部分镜像会重新压缩 JSON 导致哈希变化，此处仅告警
                log.warn("版本 JSON 哈希与清单不一致（{} 期望 {} 实际 {}），继续使用",
                        ref.id, ref.sha1, actual);
            }
        }

        FileUtils.ensureParent(cacheFile);
        FileUtils.atomicWrite(cacheFile, body);

        VersionDetail detail = JsonUtils.read(body, VersionDetail.class);
        // 部分镜像返回的内容可能缺失 id，用清单补齐，避免后续按 id 定位失败
        if (detail.id == null || detail.id.isBlank()) {
            detail.id = ref.id;
        }
        detail.sourceJson = cacheFile;
        log.info("已获取版本描述：{}（主类 {}, Java {}）", detail.id, detail.mainClass,
                detail.javaVersion == null ? "?" : detail.javaVersion.majorVersion);
        return detail;
    }

    public VersionDetail versionDetail(String versionId) throws IOException {
        return versionDetail(versionId, false);
    }

    /** 版本 JSON 的缓存路径。 */
    public Path cachedVersionJson(String versionId) {
        return paths.metaCacheDir().resolve("versions").resolve(versionId + ".json");
    }

    /** 读取本地版本 JSON（实例目录内的版本，含加载器 profile）。 */
    public static VersionDetail readLocal(Path versionJson) throws IOException {
        VersionDetail detail = JsonUtils.read(versionJson, VersionDetail.class);
        detail.sourceJson = versionJson;
        if (detail.id == null || detail.id.isBlank()) {
            String fileName = versionJson.getFileName().toString();
            detail.id = fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
        }
        return detail;
    }

    /** 清空元数据缓存。 */
    public void clearCache() {
        cachedManifest = null;
        FileUtils.deleteQuietly(paths.metaCacheDir());
        log.info("已清空版本元数据缓存: {}", paths.metaCacheDir());
    }

    /** 按发布时间正序返回全部版本，供「按时间轴浏览」视图使用。 */
    public List<VersionManifest.VersionRef> chronological(boolean forceRefresh) throws IOException {
        List<VersionManifest.VersionRef> all = new ArrayList<>(manifest(forceRefresh).versions);
        all.sort(Comparator.comparing(ref -> ref.releaseTime == null ? "" : ref.releaseTime));
        return all;
    }
}
