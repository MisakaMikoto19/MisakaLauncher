package com.misaka.launcher.core.java;

import com.fasterxml.jackson.databind.JsonNode;
import com.misaka.launcher.core.download.DownloadTargetType;
import com.misaka.launcher.core.meta.MetaFetcher;
import com.misaka.launcher.core.util.ArchUtils;
import com.misaka.launcher.core.util.JsonUtils;
import com.misaka.launcher.core.util.OsUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Alibaba Dragonwell（阿里巴巴龙井）Java 运行时提供方。
 *
 * <p>作为首选发行版的原因：面向国内网络优化、与上游 OpenJDK 兼容性好、
 * 版本线覆盖 Minecraft 实际会用到的全部主版本（8 / 11 / 17 / 21）。</p>
 *
 * <p><b>与其他发行版的两处显著差异，实现时必须处理：</b></p>
 * <ol>
 *   <li>版本信息只能通过 GitHub Releases 接口获取 —— 没有类似 Adoptium 的独立 API，
 *       也没有清华 TUNA 之类的镜像。因此这里<b>必须</b>做结果缓存，
 *       否则每次安装都会消耗 GitHub 的匿名配额（每小时 60 次，很容易被限流）；
 *       同时 Provider 级失败会让 {@code JavaRuntimeManager} 自动改用下一个发行版。</li>
 *   <li><b>不提供 macOS 构建</b>，且架构只覆盖 x64 与 aarch64。
 *       遇到不支持的平台/架构直接抛错交回管理器降级，绝不拼一个注定 404 的地址。</li>
 * </ol>
 */
public final class DragonwellJavaProvider implements JavaProvider {

    public static final String ID = "dragonwell";

    private static final Logger log = LoggerFactory.getLogger(DragonwellJavaProvider.class);

    /** Dragonwell 只维护这几条版本线；其余主版本交给其它发行版。 */
    private static final List<Integer> SUPPORTED_MAJOR_VERSIONS = List.of(8, 11, 17, 21);

    private static final String RELEASE_API =
            "https://api.github.com/repos/dragonwell-project/dragonwell%d/releases/latest";

    /** 解析结果的缓存有效期：避免频繁请求 GitHub 触发匿名限流。 */
    private static final long CACHE_TTL_MILLIS = Duration.ofHours(6).toMillis();

    private final MetaFetcher fetcher;
    /** 主版本 → 解析结果（含时间戳），value[0] 为 JavaPackage，value[1] 为缓存时间。 */
    private final Map<Integer, Object[]> cache = new LinkedHashMap<>();

    /** 平台覆盖（仅单测使用）：为 null 时由 OsUtils / ArchUtils 探测。 */
    private final String osOverride;
    private final String archOverride;

    public DragonwellJavaProvider(MetaFetcher fetcher) {
        this(fetcher, null, null);
    }

    /**
     * 注入平台信息。
     * <p>存在意义：本 Provider 的核心分支恰恰是「平台不支持时如何降级」
     * （macOS 无构建、arm32 无构建）。若只走静态探测，这些分支在单一平台上永远无法被测试覆盖。</p>
     *
     * @param osOverride   操作系统 id；null 表示探测
     * @param archOverride 架构名（Dragonwell 命名）；null 表示探测
     */
    DragonwellJavaProvider(MetaFetcher fetcher, String osOverride, String archOverride) {
        this.fetcher = fetcher;
        this.osOverride = osOverride;
        this.archOverride = archOverride;
    }

    private String os() {
        return osOverride == null ? OsUtils.id() : osOverride;
    }

    private String arch() {
        return archOverride == null ? ArchUtils.dragonwellArch() : archOverride;
    }

    private boolean isMac() {
        return osOverride == null ? OsUtils.isMacOS() : "macos".equals(osOverride);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Alibaba Dragonwell";
    }

    /**
     * 优先级高于 Adoptium / Zulu（其值分别为 10 / 20），确保在用户未显式指定时优先命中。
     */
    @Override
    public int priority() {
        return 5;
    }

    @Override
    public JavaPackage resolve(int majorVersion) throws IOException {
        JavaPackage cached = cached(majorVersion);
        if (cached != null) {
            return cached;
        }
        // 先做能力判定：不支持的组合立刻失败，让管理器降级到其它发行版
        validateSupport(majorVersion);

        String os = os();
        String arch = arch();
        String platform = platformFor(os);
        String extension = "windows".equals(os) ? ".zip" : ".tar.gz";

        JsonNode release = fetcher.fetchJson(RELEASE_API.formatted(majorVersion),
                DownloadTargetType.JAVA_RUNTIME,
                Map.of("Accept", "application/vnd.github+json"), Map.of());
        if (release == null || release.isNull()) {
            throw new IOException("Dragonwell 未返回 Java " + majorVersion + " 的发布信息");
        }

        JsonNode assets = release.get("assets");
        if (assets == null || !assets.isArray() || assets.isEmpty()) {
            throw new IOException("Dragonwell Java " + majorVersion + " 的发布中没有任何资产");
        }

        // 资产命名：Alibaba_Dragonwell_{Standard|Extended}_{版本}_{架构}_{平台}{扩展名}
        // 同一组合只会有一个主归档，但 Standard / Extended 同时存在时要优先 Standard
        JsonNode chosen = pickAsset(assets, arch, platform + extension);
        if (chosen == null) {
            throw new IOException("Dragonwell Java " + majorVersion + " 没有 "
                    + os + "/" + arch + " 的" + (extension.equals(".zip") ? " zip" : " tar.gz") + " 归档");
        }

        String fileName = JsonUtils.text(chosen, "name", "");
        String url = JsonUtils.text(chosen, "browser_download_url", "");
        long size = JsonUtils.longValue(chosen, "size", -1L);
        if (fileName.isBlank() || url.isBlank()) {
            throw new IOException("Dragonwell Java " + majorVersion + " 的资产信息不完整");
        }

        String checksum = resolveChecksum(chosen, assets, fileName);
        String versionString = readVersionString(release, fileName, majorVersion);

        // 归档只托管在 GitHub Releases 上，国内直连极慢：展开出加速镜像候选，
        // 由下载环节实测择优；拿不到官方哈希时不启用第三方加速（见 GithubMirrors）
        List<String> urls = GithubMirrors.expand(url, !checksum.isBlank());
        if (urls.size() > 1) {
            log.info("Dragonwell Java {} 已启用 GitHub 加速镜像：{}", majorVersion,
                    GithubMirrors.mirrorHosts());
        }

        JavaPackage pkg = new JavaPackage(ID, majorVersion, versionString, fileName,
                extension.equals(".zip") ? "zip" : "tar.gz", checksum, size, urls);
        cache.put(majorVersion, new Object[]{pkg, System.currentTimeMillis()});
        log.info("Dragonwell Java {} 解析完成：{}（{}）", majorVersion, fileName, versionString);
        return pkg;
    }

    // ------------------------------------------------------------------
    // 内部步骤
    // ------------------------------------------------------------------

    /** 校验主版本 / 平台 / 架构是否有官方构建。 */
    private void validateSupport(int majorVersion) throws IOException {
        if (!SUPPORTED_MAJOR_VERSIONS.contains(majorVersion)) {
            throw new IOException("Dragonwell 不提供 Java " + majorVersion
                    + "（仅提供 " + SUPPORTED_MAJOR_VERSIONS + "）");
        }
        if (isMac()) {
            // 关键事实：Dragonwell 至今没有 macOS 构建，在 macOS 上必须换成其它发行版
            throw new IOException("Dragonwell 未提供 macOS 构建，将改用其它发行版");
        }
        if (arch().isEmpty()) {
            throw new IOException("Dragonwell 不支持当前 CPU 架构（" + ArchUtils.id()
                    + "），仅提供 x64 与 aarch64");
        }
    }

    /** 平台标识：Dragonwell 只区分 windows 与 linux。 */
    private static String platformFor(String os) throws IOException {
        return switch (os) {
            case "windows" -> "windows";
            case "linux" -> "linux";
            default -> throw new IOException("Dragonwell 不支持当前操作系统：" + os);
        };
    }

    /**
     * 从资产列表中选择目标归档。
     * <p>匹配规则：文件名以 {@code _{arch}_{platform}{extension}} 结尾；
     * 命中多个时优先 {@code Standard}（体积更小、且是官方默认版本线），
     * 其次 {@code Extended}。</p>
     */
    private static JsonNode pickAsset(JsonNode assets, String arch, String suffix) {
        String tail = "_" + arch + "_" + suffix;
        JsonNode standard = null;
        JsonNode extended = null;
        for (JsonNode asset : assets) {
            String name = JsonUtils.text(asset, "name", "");
            if (!name.endsWith(tail)) {
                continue;
            }
            if (name.contains("_Standard_")) {
                standard = asset;
            } else if (name.contains("_Extended_")) {
                extended = asset;
            } else if (standard == null && extended == null) {
                // Dragonwell 8 的旧命名没有 Standard/Extended 标识，直接用
                standard = asset;
            }
        }
        return standard != null ? standard : extended;
    }

    /**
     * 获取归档的 SHA-256。
     * <p>优先用 GitHub 资产自带的 {@code digest}（形如 {@code sha256:xxxx}）；
     * 缺失时回退读取同目录的 {@code .sha256.txt} 边车文件。
     * 两者都拿不到时返回空串（下载环节会跳过校验而非失败）——
     * 这比因为拿不到哈希就完全无法安装要好。</p>
     */
    private String resolveChecksum(JsonNode asset, JsonNode assets, String fileName) {
        String digest = JsonUtils.text(asset, "digest", "");
        String fromDigest = extractHex(digest);
        if (!fromDigest.isBlank()) {
            return fromDigest;
        }
        // 回退：release 中带有 <文件名>.sha256.txt
        for (JsonNode candidate : assets) {
            String name = JsonUtils.text(candidate, "name", "");
            if (!name.equals(fileName + ".sha256.txt")) {
                continue;
            }
            String url = JsonUtils.text(candidate, "browser_download_url", "");
            if (url.isBlank()) {
                continue;
            }
            String text = fetcher.tryFetchString(url);
            String fromSidecar = extractHex(text);
            if (!fromSidecar.isBlank()) {
                return fromSidecar;
            }
        }
        log.warn("未能获取 {} 的 SHA-256，将跳过下载校验", fileName);
        return "";
    }

    /** 从 {@code sha256:xxxx} 或 {@code xxxx  filename} 这类文本中提取 64 位十六进制串。 */
    static String extractHex(String text) {
        if (text == null) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[0-9a-fA-F]{64}")
                .matcher(text);
        return matcher.find() ? matcher.group().toLowerCase(java.util.Locale.ROOT) : "";
    }

    /** 版本串：优先从 tag 名里取（形如 {@code dragonwell-standard-17.0.20.0.21+8_jdk-17.0.20-ga}）。 */
    private static String readVersionString(JsonNode release, String fileName, int majorVersion) {
        String tag = JsonUtils.text(release, "tag_name", "");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("jdk-(\\d+(?:\\.\\d+)*)").matcher(tag);
        if (matcher.find()) {
            return matcher.group(1);
        }
        // 资产名中也带版本：Alibaba_Dragonwell_Standard_17.0.20.0.21.8_x64_windows.zip
        String[] parts = fileName.split("_");
        if (parts.length >= 4) {
            return parts[3];
        }
        return String.valueOf(majorVersion);
    }

    /** 命中缓存且未过期时返回，否则返回 null。 */
    private JavaPackage cached(int majorVersion) {
        Object[] entry = cache.get(majorVersion);
        if (entry == null) {
            return null;
        }
        long timestamp = (long) entry[1];
        if (System.currentTimeMillis() - timestamp > CACHE_TTL_MILLIS) {
            cache.remove(majorVersion);
            return null;
        }
        return (JavaPackage) entry[0];
    }

    /** 支持的主版本，供界面展示「哪些版本能用该发行版」。 */
    public static List<Integer> supportedMajorVersions() {
        return new ArrayList<>(SUPPORTED_MAJOR_VERSIONS);
    }
}
