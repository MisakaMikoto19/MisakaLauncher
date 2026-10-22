package com.misaka.launcher.core.java;

import com.misaka.launcher.core.config.ConfigStore;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.config.PortablePaths;
import com.misaka.launcher.core.download.DownloadItem;
import com.misaka.launcher.core.download.DownloadManager;
import com.misaka.launcher.core.download.DownloadTargetType;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.install.LoaderInstaller;
import com.misaka.launcher.core.meta.JavaVersionRule;
import com.misaka.launcher.core.meta.MetaFetcher;
import com.misaka.launcher.core.task.ProgressListener;
import com.misaka.launcher.core.util.ArchUtils;
import com.misaka.launcher.core.util.ByteRateLimiter;
import com.misaka.launcher.core.util.FileUtils;
import com.misaka.launcher.core.util.HttpUtils;
import com.misaka.launcher.core.util.OsUtils;
import com.misaka.launcher.core.util.TarUtils;
import com.misaka.launcher.core.util.ZipUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 游戏用 Java 运行时管理。
 * <p>三条职责：</p>
 * <ol>
 *   <li><b>发现</b>：默认只扫描启动器同级的 {@code java/} 目录（自装产物 + 用户手动放置），
 *       另可由配置追加扫描目录 —— 不再翻整个系统，实例的 Java 选择按目录进行，列表可预期；</li>
 *   <li><b>安装</b>：按 Minecraft 版本所需主版本，从镜像择优下载并解压到
 *       <b>启动器同级目录</b> {@code <安装根>/java/<vendor>-<major>-<os>-<arch>}；</li>
 *   <li><b>选用</b>：启动游戏时按版本需求自动挑出最合适的 Java。</li>
 * </ol>
 * <p>与「启动器自带运行时」严格区分：本类管理的 Java 只用于启动游戏，
 * 启动器自身运行在 jpackage 打包出的 {@code runtime/} 上。</p>
 */
public final class JavaRuntimeManager {

    private static final Logger log = LoggerFactory.getLogger(JavaRuntimeManager.class);

    /** 单源探测超时；镜像不可达时要快速失败进入下一个候选。 */
    private static final long PROBE_TIMEOUT_MILLIS = 4000L;

    /** 在解压目录中查找 JAVA_HOME 的最大搜索深度。 */
    private static final int MAX_HOME_SEARCH_DEPTH = 3;

    private final PortablePaths paths;
    private final ConfigStore configStore;
    private final DownloadManager downloads;
    private final List<JavaProvider> providers;

    public JavaRuntimeManager(PortablePaths paths, ConfigStore configStore, DownloadManager downloads) {
        this(paths, configStore, downloads, defaultProviders(downloads));
    }

    public JavaRuntimeManager(PortablePaths paths, ConfigStore configStore, DownloadManager downloads,
                              List<JavaProvider> providers) {
        this.paths = paths;
        this.configStore = configStore;
        this.downloads = downloads;
        this.providers = List.copyOf(providers);
    }

    private static List<JavaProvider> defaultProviders(DownloadManager downloads) {
        MetaFetcher fetcher = new MetaFetcher(downloads.resolver());
        List<JavaProvider> list = new ArrayList<>();
        // 顺序即默认优先级：Dragonwell 面向国内优化，作为首选；
        // Adoptium 附带清华镜像作为主力备选；Zulu 兜底。
        // 各 Provider 还会按「是否支持该平台/版本」自行退出，例如 Dragonwell 无 macOS 构建。
        list.add(new DragonwellJavaProvider(fetcher));
        list.add(new AdoptiumJavaProvider(fetcher));
        list.add(new ZuluJavaProvider(fetcher));
        list.sort(Comparator.comparingInt(JavaProvider::priority));
        return list;
    }

    public List<JavaProvider> providers() {
        return providers;
    }

    public LauncherConfig.JavaSettings settings() {
        return configStore.get().java;
    }

    // ==================================================================
    // 发现
    // ==================================================================

    /** 已登记的运行时（含启动器安装的与本机发现的）。 */
    public List<LauncherConfig.JavaRuntimeEntry> registered() {
        if (settings().runtimes == null) {
            settings().runtimes = new ArrayList<>();
        }
        return settings().runtimes;
    }

    /**
     * 扫描 Java 运行时并合并进登记表。
     *
     * <p><b>默认只扫描启动器同级的 {@code java/} 目录</b>（启动器自己安装的，
     * 以及用户手动丢进去的 JDK），不再翻 JAVA_HOME / PATH / Program Files：
     * 实例的 Java 选择按目录进行，列表必须是可预期的 ——
     * 系统里装了什么 JDK 用户自己最清楚，需要复用时在配置里追加扫描目录即可。</p>
     *
     * @return 新增的条目
     */
    public List<LauncherConfig.JavaRuntimeEntry> scanLocal() {
        Set<Path> candidates = new LinkedHashSet<>();

        // 1) 启动器同级的 java/ 目录（自装产物 + 用户手动放置的 JDK）
        Path javaRoot = paths.javaRoot();
        if (Files.isDirectory(javaRoot)) {
            try (var stream = Files.list(javaRoot)) {
                stream.filter(Files::isDirectory).forEach(candidates::add);
            } catch (IOException e) {
                log.debug("列出台 java 目录失败: {}", e.getMessage());
            }
        }

        // 2) 用户在配置里追加的扫描目录（例如想复用系统里已有的 JDK）
        for (String extra : settings().extraScanPaths) {
            if (extra != null && !extra.isBlank()) {
                candidates.add(Path.of(extra));
            }
        }

        List<LauncherConfig.JavaRuntimeEntry> added = new ArrayList<>();
        for (Path candidate : candidates) {
            Path home = normalizeHome(candidate);
            if (home == null) {
                continue;
            }
            if (findEntryByHome(home).isPresent()) {
                continue;
            }
            int major = detectMajorVersion(home);
            if (major <= 0) {
                log.debug("无法识别 Java 版本，跳过: {}", home);
                continue;
            }
            boolean managed = home.startsWith(paths.javaRoot());
            LauncherConfig.JavaRuntimeEntry entry = new LauncherConfig.JavaRuntimeEntry(
                    managed ? detectVendor(home) : "manual",
                    major, OsUtils.id(), ArchUtils.id(), home.toString(),
                    managed ? "installed" : "discovered");
            entry.versionString = readReleaseVersion(home);
            registered().add(entry);
            added.add(entry);
            log.info("发现 Java 运行时：{}（Java {}，{}）", home, major, entry.source);
        }
        if (!added.isEmpty()) {
            configStore.save();
        }
        return added;
    }

    /** 已登记的条目中按 home 查找。 */
    public Optional<LauncherConfig.JavaRuntimeEntry> findEntryByHome(Path home) {
        Path normalized = home.toAbsolutePath().normalize();
        return registered().stream()
                .filter(entry -> entry.home != null && !entry.home.isBlank())
                .filter(entry -> Path.of(entry.home).toAbsolutePath().normalize().equals(normalized))
                .findFirst();
    }

    // ==================================================================
    // 选用
    // ==================================================================

    /**
     * 为所需主版本挑选最合适的运行时。
     * <p>优先级：主版本完全一致 → 更高的主版本（仅当所需版本 &gt;= 16，
     * 因为 Java 9+ 对旧版 Minecraft 的反射破坏较大，不能反向放宽）。</p>
     */
    public Optional<LauncherConfig.JavaRuntimeEntry> find(int requiredMajor) {
        List<LauncherConfig.JavaRuntimeEntry> usable = new ArrayList<>();
        for (LauncherConfig.JavaRuntimeEntry entry : registered()) {
            if (entry.home == null || entry.home.isBlank()) {
                continue;
            }
            if (!JavaVersionRule.accepts(requiredMajor, entry.majorVersion)) {
                continue;
            }
            if (normalizeHome(Path.of(entry.home)) == null) {
                // 目录已被删除或损坏，跳过
                continue;
            }
            usable.add(entry);
        }
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        usable.sort(Comparator.comparingLong(entry -> score(requiredMajor, entry)));
        return Optional.of(usable.get(0));
    }

    /** 打分：完全匹配优先 → 启动器自管的优先 → 版本差距小的优先。 */
    private static long score(int requiredMajor, LauncherConfig.JavaRuntimeEntry entry) {
        long exactPenalty = entry.majorVersion == requiredMajor ? 0L : 1_000L;
        long sourcePenalty = "installed".equals(entry.source) ? 0L : 100L;
        long distance = Math.abs((long) entry.majorVersion - requiredMajor);
        return exactPenalty + sourcePenalty + distance;
    }

    /** 取出可用于启动游戏的 java 可执行文件。 */
    public Optional<Path> javaExecutable(int requiredMajor) {
        return find(requiredMajor).flatMap(entry -> locateJavaExecutable(Path.of(entry.home)));
    }

    /** 适配 {@link LoaderInstaller#JavaProvider}：把「查找」逻辑注入安装器。 */
    public LoaderInstaller.JavaProvider asInstallerJavaProvider() {
        return majorVersion -> javaExecutable(majorVersion).orElseThrow(
                () -> new IOException("找不到可用的 " + JavaVersionRule.describe(majorVersion)
                        + "，请先在设置中安装"));
    }

    // ==================================================================
    // 安装
    // ==================================================================

    /**
     * 安装指定主版本的 Java 运行时到启动器同级目录。
     * <p>幂等：若目标目录已存在且可用，直接返回既有条目。</p>
     */
    public LauncherConfig.JavaRuntimeEntry install(int majorVersion, ProgressListener listener) throws IOException {
        listener.onStage(I18n.t("java.installing", majorVersion));
        listener.throwIfCancelled();

        // 1) 已存在可用运行时（含本机已装）时不重复下载
        Optional<LauncherConfig.JavaRuntimeEntry> existing = find(majorVersion);
        if (existing.isPresent() && existing.get().majorVersion == majorVersion) {
            Path home = Path.of(existing.get().home);
            if (home.startsWith(paths.javaRoot())) {
                log.info("Java {} 已安装在 {}", majorVersion, home);
                listener.onMessage("Java " + majorVersion + " 已就绪：" + home);
                return existing.get();
            }
        }

        // 2) 逐个候选发行版尝试「解析 + 下载」。
        // 关键点：下载失败同样要换下一个发行版，而不只是解析失败才换。
        // 首选发行版（Dragonwell）仅有 GitHub 一个下载源，国内网络下下载阶段失败是常态；
        // 若只在解析阶段降级，用户会遇到「明明配了备用发行版却依然装不上」。
        List<JavaProvider> ordered = orderProviders();
        JavaPackage chosen = null;
        Path chosenArchive = null;
        IOException lastError = null;
        for (JavaProvider provider : ordered) {
            listener.throwIfCancelled();
            JavaPackage candidate;
            try {
                candidate = provider.resolve(majorVersion);
            } catch (IOException e) {
                lastError = e;
                log.warn("发行版 {} 无法提供 Java {}：{}", provider.id(), majorVersion, e.getMessage());
                continue;
            }

            Path archive = paths.downloadsDir().resolve(candidate.fileName());
            FileUtils.ensureDir(paths.downloadsDir());
            try {
                // 候选顺序即优先级（国内镜像在前、官方兜底），测速仅剔除不可达项
                downloadArchive(orderCandidatesForDownload(candidate), archive, candidate, listener);
                chosen = candidate;
                chosenArchive = archive;
                log.info("Java {} 将由 {} 提供：{}", majorVersion, provider.id(), candidate.versionString());
                break;
            } catch (com.misaka.launcher.core.task.CancelledException e) {
                // 用户主动取消不应被当作「本发行版不可用」而继续尝试下一个
                throw e;
            } catch (IOException e) {
                lastError = e;
                log.warn("发行版 {} 的归档下载失败，改用下一个发行版：{}", provider.id(), e.getMessage());
            }
        }

        if (chosen == null || chosenArchive == null) {
            // 必须把最后一次的底层原因拼进消息：否则用户只会看到「没有可用的发行版」，
            // 无法区分是网络不通、镜像缺失还是校验失败
            String reason = lastError == null ? "未知原因" : lastError.getMessage();
            throw new IOException("没有可用的 Java " + majorVersion + " 发行版（已尝试 "
                    + ordered.size() + " 个）：" + reason, lastError);
        }
        // 循环中赋值的局部变量不是「实际上的最终变量」，lambda 捕获需借助 final 别名
        final JavaPackage pkg = chosen;
        final Path archive = chosenArchive;

        // 4) 解压到暂存目录，再就位（避免解压中断留下半成品目录）
        Path stage = paths.tempDir().resolve("java-stage-" + pkg.vendor() + "-" + majorVersion);
        FileUtils.deleteQuietly(stage);
        FileUtils.ensureDir(stage);
        listener.onStage(I18n.t("java.installing", majorVersion));
        listener.onMessage("解压 " + pkg.fileName());
        try {
            if (pkg.isZip()) {
                ZipUtils.extractAll(archive, stage);
            } else if (pkg.isTarGz()) {
                TarUtils.extractTarGz(archive, stage);
            } else {
                throw new IOException("不支持的归档类型: " + pkg.archiveType());
            }
        } catch (IOException e) {
            FileUtils.deleteQuietly(stage);
            throw new IOException("解压 Java 归档失败: " + e.getMessage(), e);
        }

        Path detectedHome = findJavaHomeUnder(stage);
        if (detectedHome == null) {
            FileUtils.deleteQuietly(stage);
            throw new IOException("解压后未找到 bin/java，归档内容异常：" + pkg.fileName());
        }

        String dirName = pkg.vendor() + "-" + majorVersion + "-" + OsUtils.id() + "-" + ArchUtils.id();
        Path finalDir = paths.javaRoot().resolve(dirName);
        FileUtils.deleteQuietly(finalDir);
        FileUtils.ensureDir(paths.javaRoot());
        TarUtils.moveTree(detectedHome, finalDir);
        FileUtils.deleteQuietly(stage);
        FileUtils.deleteQuietly(archive);

        Path javaHome = normalizeHome(finalDir);
        if (javaHome == null) {
            throw new IOException("Java 安装后校验失败，未找到可执行文件：" + finalDir);
        }

        // 5) 登记
        LauncherConfig.JavaRuntimeEntry entry = findEntryByHome(javaHome)
                .orElseGet(() -> {
                    LauncherConfig.JavaRuntimeEntry created = new LauncherConfig.JavaRuntimeEntry(
                            pkg.vendor(), majorVersion, OsUtils.id(), ArchUtils.id(),
                            javaHome.toString(), "installed");
                    registered().add(created);
                    return created;
                });
        entry.source = "installed";
        entry.majorVersion = majorVersion;
        entry.versionString = readReleaseVersion(javaHome);
        if (entry.versionString.isBlank()) {
            entry.versionString = pkg.versionString();
        }
        configStore.save();

        log.info("Java {} 安装完成：{}", majorVersion, javaHome);
        listener.onStage(I18n.t("status.done"));
        listener.onMessage("Java " + majorVersion + " 安装完成：" + javaHome);
        return entry;
    }

    /** 按用户偏好排序发行版。 */
    private List<JavaProvider> orderProviders() {
        String preferred = settings().preferredVendor;
        List<JavaProvider> ordered = new ArrayList<>(providers);
        ordered.sort(Comparator
                .comparingInt((JavaProvider provider) -> provider.id().equals(preferred) ? 0 : 1)
                .thenComparingInt(JavaProvider::priority));
        return ordered;
    }

    /**
     * 候选地址排序：<b>保持候选列表声明的优先级</b>，只把探测不到的地址降级到最后。
     *
     * <p>为什么不按延迟排序：这里只能测到握手（1 字节区间请求），而 JDK 归档是上百 MB 的
     * 大文件。实测同一构件：直连 GitHub 握手 795 ms、加速镜像 853 ms（看似直连更快），
     * 但 2 MB 片段的吞吐是 559 KB/s vs 1039 KB/s —— 按握手延迟排序会把用户送回慢链路。
     * 因此顺序由候选列表本身表达（国内镜像在前、官方兜底），
     * 测速只负责剔除连不上的地址。这与 {@code SourceResolver} 的取舍保持一致。</p>
     */
    private List<String> orderCandidatesForDownload(JavaPackage pkg) {
        List<String> urls = new ArrayList<>(pkg.downloadUrls());
        if (urls.size() <= 1) {
            return urls;
        }
        List<String> reachable = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();
        for (String url : urls) {
            long latency = HttpUtils.latencyMillis(url, PROBE_TIMEOUT_MILLIS);
            if (latency > 0) {
                log.info("Java 下载源可用：{}（握手 {} ms）", url, latency);
                reachable.add(url);
            } else {
                log.warn("Java 下载源不可达，降级到最后：{}", url);
                unreachable.add(url);
            }
        }
        reachable.addAll(unreachable);
        return reachable;
    }

    /** 按候选顺序下载归档，失败自动切换下一个源。 */
    private void downloadArchive(List<String> candidates, Path archive, JavaPackage pkg, ProgressListener listener)
            throws IOException {
        ByteRateLimiter limiter = downloads.rateLimiter();
        IOException lastError = null;
        for (String url : candidates) {
            listener.throwIfCancelled();
            try {
                listener.onMessage("下载 " + pkg.fileName() + "（" + url + "）");
                HttpUtils.download(URI.create(url), archive, listener, pkg.sha256(), pkg.size(), java.util.Map.of(),
                        java.time.Duration.ofMinutes(10), limiter);
                return;
            } catch (IOException e) {
                lastError = e;
                log.warn("Java 归档下载失败：{}（{}）", url, e.getMessage());
            }
        }
        throw new IOException("Java 归档下载失败（已尝试 " + candidates.size() + " 个地址）", lastError);
    }

    /** 在解压目录中自上而下查找包含 bin/java 的目录。 */
    static Path findJavaHomeUnder(Path root) {
        Path direct = normalizeHome(root);
        if (direct != null) {
            return direct;
        }
        List<Path> current = new ArrayList<>();
        current.add(root);
        for (int depth = 0; depth < MAX_HOME_SEARCH_DEPTH; depth++) {
            List<Path> next = new ArrayList<>();
            for (Path dir : current) {
                try (var stream = Files.list(dir)) {
                    for (Path child : stream.filter(Files::isDirectory).toList()) {
                        Path home = normalizeHome(child);
                        if (home != null) {
                            return home;
                        }
                        next.add(child);
                    }
                } catch (IOException ignored) {
                    // 目录不可读则跳过
                }
            }
            if (next.isEmpty()) {
                break;
            }
            current = next;
        }
        return null;
    }

    // ==================================================================
    // 手工登记与移除
    // ==================================================================

    /** 手工登记一个 JAVA_HOME 或 java 可执行文件路径。 */
    public LauncherConfig.JavaRuntimeEntry register(String pathOrExecutable) throws IOException {
        Path home = normalizeHome(Path.of(pathOrExecutable));
        if (home == null) {
            throw new IOException("该路径下没有找到 bin/java: " + pathOrExecutable);
        }
        int major = detectMajorVersion(home);
        if (major <= 0) {
            throw new IOException("无法识别该 Java 的版本: " + home);
        }
        LauncherConfig.JavaRuntimeEntry entry = findEntryByHome(home).orElseGet(() -> {
            LauncherConfig.JavaRuntimeEntry created = new LauncherConfig.JavaRuntimeEntry(
                    "manual", major, OsUtils.id(), ArchUtils.id(), home.toString(), "manual");
            registered().add(created);
            return created;
        });
        entry.majorVersion = major;
        entry.source = "manual";
        entry.versionString = readReleaseVersion(home);
        configStore.save();
        return entry;
    }

    /** 从登记表移除（可选是否同时删除启动器自管的目录）。 */
    public boolean remove(String home, boolean deleteFiles) {
        if (home == null) {
            return false;
        }
        Optional<LauncherConfig.JavaRuntimeEntry> found = findEntryByHome(Path.of(home));
        if (found.isEmpty()) {
            return false;
        }
        registered().remove(found.get());
        if (deleteFiles) {
            Path target = Path.of(home).toAbsolutePath().normalize();
            if (target.startsWith(paths.javaRoot())) {
                FileUtils.deleteQuietly(target);
            } else {
                log.warn("拒绝删除启动器目录之外的 Java: {}", target);
            }
        }
        configStore.save();
        return true;
    }

    // ==================================================================
    // 静态探测工具
    // ==================================================================

    /**
     * 把任意候选路径规范化为 JAVA_HOME。
     *
     * @param candidate JAVA_HOME、bin 目录、java 可执行文件，或 JDK 包解压根目录
     * @return 可用的 JAVA_HOME；无法识别返回 null
     */
    public static Path normalizeHome(Path candidate) {
        if (candidate == null || !Files.exists(candidate)) {
            return null;
        }
        if (Files.isRegularFile(candidate)) {
            Path parent = candidate.getParent();
            if (parent != null && "bin".equals(parent.getFileName().toString())
                    && parent.getParent() != null && hasJavaExecutable(parent.getParent())) {
                return parent.getParent().toAbsolutePath().normalize();
            }
            return null;
        }
        if (hasJavaExecutable(candidate)) {
            return candidate.toAbsolutePath().normalize();
        }
        // macOS 的 .jdk 包结构：<home>/Contents/Home/bin/java
        Path nested = candidate.resolve("Contents").resolve("Home");
        if (hasJavaExecutable(nested)) {
            return nested.toAbsolutePath().normalize();
        }
        return null;
    }

    /** 取出 java 可执行文件路径。 */
    public static Optional<Path> locateJavaExecutable(Path pathOrHome) {
        Path home = normalizeHome(pathOrHome);
        if (home == null) {
            return Optional.empty();
        }
        Path executable = home.resolve("bin").resolve(OsUtils.executableName("java"));
        return Files.isRegularFile(executable) ? Optional.of(executable) : Optional.empty();
    }

    /** 判断目录是否是合法的 JAVA_HOME。 */
    public static boolean hasJavaExecutable(Path home) {
        if (home == null) {
            return false;
        }
        Path executable = home.resolve("bin").resolve(OsUtils.executableName("java"));
        return Files.isRegularFile(executable);
    }

    /**
     * 探测 JAVA_HOME 的主版本。
     * <p>优先读 {@code release} 文件（零进程开销）；缺失时回退执行 {@code java -version}。</p>
     *
     * @return 主版本号；无法识别返回 -1
     */
    public static int detectMajorVersion(Path pathOrHome) {
        Path home = normalizeHome(pathOrHome);
        if (home == null) {
            return -1;
        }
        String version = readReleaseVersion(home);
        if (version.isBlank()) {
            version = queryVersionFromProcess(home);
        }
        return parseMajorVersion(version);
    }

    /** 读取 {@code <home>/release} 中的 {@code JAVA_VERSION}。 */
    public static String readReleaseVersion(Path home) {
        Path release = home.resolve("release");
        if (!Files.isRegularFile(release)) {
            return "";
        }
        try {
            for (String line : Files.readAllLines(release, StandardCharsets.UTF_8)) {
                if (line.startsWith("JAVA_VERSION=")) {
                    return line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                }
            }
        } catch (IOException e) {
            log.debug("读取 release 失败 {}: {}", release, e.getMessage());
        }
        return "";
    }

    /** 执行 {@code java -version} 并解析版本（release 缺失时的兜底）。 */
    private static String queryVersionFromProcess(Path home) {
        Optional<Path> executable = locateJavaExecutable(home);
        if (executable.isEmpty()) {
            return "";
        }
        try {
            Process process = new ProcessBuilder(executable.get().toString(), "-version")
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            String text = output.toString();
            int start = text.indexOf('"');
            int end = text.indexOf('"', start + 1);
            return start >= 0 && end > start ? text.substring(start + 1, end) : "";
        } catch (IOException e) {
            log.debug("执行 java -version 失败 {}: {}", home, e.getMessage());
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /**
     * 从版本字符串解析主版本：
     * {@code 1.8.0_392 -> 8}、{@code 17.0.12 -> 17}、{@code 21 -> 21}。
     */
    public static int parseMajorVersion(String version) {
        if (version == null || version.isBlank()) {
            return -1;
        }
        String value = version.trim().replace("\"", "");
        if (value.startsWith("1.")) {
            String[] parts = value.split("\\.");
            if (parts.length < 2) {
                return -1;
            }
            return safeInt(parts[1]);
        }
        int index = 0;
        while (index < value.length() && Character.isDigit(value.charAt(index))) {
            index++;
        }
        return index == 0 ? -1 : safeInt(value.substring(0, index));
    }

    private static int safeInt(String value) {
        try {
            return Integer.parseInt(value.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 由目录名推断发行版（仅用于展示）。 */
    private static String detectVendor(Path home) {
        String name = home.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.contains("dragonwell")) {
            return DragonwellJavaProvider.ID;
        }
        if (name.contains("zulu")) {
            return ZuluJavaProvider.ID;
        }
        if (name.contains("temurin") || name.contains("adoptium")) {
            return AdoptiumJavaProvider.ID;
        }
        return "manual";
    }

    /** 供界面展示的摘要。 */
    public String describe() {
        return "javaRoot=" + paths.javaRoot() + ", registered=" + registered().size()
                + ", providers=" + providers.stream().map(JavaProvider::id).toList();
    }

    /** 便捷判断：指定主版本是否已具备可用运行时。 */
    public boolean isReady(int requiredMajor) {
        return find(requiredMajor).isPresent();
    }
}
