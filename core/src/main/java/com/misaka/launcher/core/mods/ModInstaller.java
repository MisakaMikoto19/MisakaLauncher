package com.misaka.launcher.core.mods;

import com.misaka.launcher.core.config.ConfigStore;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.download.DownloadItem;
import com.misaka.launcher.core.download.DownloadManager;
import com.misaka.launcher.core.download.DownloadReport;
import com.misaka.launcher.core.download.DownloadTargetType;
import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.task.ProgressListener;
import com.misaka.launcher.core.util.FileUtils;
import com.misaka.launcher.core.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 模组 / 资源包 / 光影的安装。
 * <p>关键设计：<b>来源标记</b>。安装时把「来源 + projectId + fileId + sha1」写入配置，
 * 后续更新检查就无需猜测本地文件是什么，直接问来源方「有没有更新」即可 ——
 * 这比靠文件名或哈希反查可靠得多。</p>
 * <p>依赖解析：只自动安装 {@code required} 依赖（Modrinth 的 required / CurseForge 的
 * requiredDependency），可选依赖留给用户决定，避免把整合包环境塞满。</p>
 */
public final class ModInstaller {

    private static final Logger log = LoggerFactory.getLogger(ModInstaller.class);

    /** 依赖递归深度上限，防御元数据出现环状依赖。 */
    private static final int MAX_DEPENDENCY_DEPTH = 5;

    private final ConfigStore configStore;
    private final DownloadManager downloads;
    private final List<ModProvider> providers;

    public ModInstaller(ConfigStore configStore, DownloadManager downloads, List<ModProvider> providers) {
        this.configStore = configStore;
        this.downloads = downloads;
        this.providers = List.copyOf(providers);
    }

    public List<ModProvider> providers() {
        return providers;
    }

    /** 已配置且支持检索的来源。 */
    public List<ModProvider> availableProviders() {
        return providers.stream().filter(ModProvider::isConfigured).toList();
    }

    public Optional<ModProvider> provider(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return providers.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** 索引键：实例 id + 相对路径，保证多实例之间互不串扰。 */
    public static String indexKey(String instanceId, String relativePath) {
        return instanceId + "\u0000" + relativePath.replace('\\', '/');
    }

    // ==================================================================
    // 安装
    // ==================================================================

    /**
     * 按项目安装。
     *
     * @param fileId      指定版本 id；为空则自动挑选与游戏版本/加载器最匹配的稳定版
     */
    public InstallOutcome install(String instanceId,
                                 GamePaths paths,
                                 String source,
                                 String projectId,
                                 String fileId,
                                 String gameVersion,
                                 String loader,
                                 ProgressListener listener) throws IOException {
        ModProvider provider = provider(source)
                .orElseThrow(() -> new IOException("未知的内容来源: " + source));
        ModProject project = provider.project(projectId)
                .orElseThrow(() -> new IOException("找不到项目: " + projectId));

        List<ModFile> versions = provider.versions(projectId, gameVersion, loader);
        if (versions.isEmpty()) {
            throw new IOException("该项目没有适用于 " + gameVersion + " / " + loader + " 的版本");
        }
        ModFile file = selectFile(versions, fileId);
        if (file == null) {
            throw new IOException("未找到指定的文件版本: " + fileId);
        }
        return installFile(instanceId, paths, provider, project, file, gameVersion, loader, listener, 0);
    }

    /** 从候选版本中挑选目标版本。 */
    static ModFile selectFile(List<ModFile> versions, String fileId) {
        if (fileId == null || fileId.isBlank()) {
            // 优先稳定版；同级别取发布时间最新
            return versions.stream()
                    .filter(ModFile::isStable)
                    .findFirst()
                    .orElse(versions.get(0));
        }
        return versions.stream()
                .filter(file -> fileId.equals(file.versionId()))
                .findFirst()
                .orElse(null);
    }

    /** 安装一个已确定的文件（含依赖）。 */
    public InstallOutcome installFile(String instanceId,
                                      GamePaths paths,
                                      ModProvider provider,
                                      ModProject project,
                                      ModFile file,
                                      String gameVersion,
                                      String loader,
                                      ProgressListener listener,
                                      int depth) throws IOException {
        OutcomeBuilder builder = new OutcomeBuilder();
        if (depth > MAX_DEPENDENCY_DEPTH) {
            builder.message("依赖层级过深，已停止继续解析");
            return builder.build();
        }

        // 内容类型决定落盘目录
        ModContentType contentType = project.contentType();
        if (contentType == ModContentType.MODPACK) {
            throw new IOException("整合包需要通过「安装整合包」流程处理，不能作为普通内容安装");
        }
        Path targetDir = targetDir(paths, contentType);

        boolean installed = installSingle(instanceId, provider, project, file, targetDir, listener, builder, depth);
        if (!installed) {
            return builder.build();
        }

        // 依赖
        if (configStore.get().mods.autoInstallDependencies) {
            for (ModFile.ModDependency dependency : file.requiredDependencies()) {
                listener.throwIfCancelled();
                if (isSatisfied(instanceId, dependency)) {
                    builder.skipped();
                    continue;
                }
                try {
                    resolveAndInstallDependency(instanceId, paths, provider, dependency,
                            gameVersion, loader, listener, builder, depth + 1);
                } catch (IOException e) {
                    // 单个依赖失败不阻断主安装：主文件已可用，缺失依赖会在游戏内明确报错
                    builder.message("依赖安装失败（" + dependency.projectId() + "）：" + e.getMessage());
                    builder.failed();
                }
            }
        }
        return builder.build();
    }

    /**
     * 下载单个文件并登记来源。
     *
     * @return 是否已成功就位（含「已是最新而跳过」）
     */
    private boolean installSingle(String instanceId,
                                  ModProvider provider,
                                  ModProject project,
                                  ModFile file,
                                  Path targetDir,
                                  ProgressListener listener,
                                  OutcomeBuilder builder,
                                  int depth) throws IOException {
        String fileName = sanitizeFileName(file.fileName());
        if (fileName == null) {
            builder.message("文件名非法，已跳过：" + file.fileName());
            builder.failed();
            return false;
        }
        Path target = targetDir.resolve(fileName);

        String url = provider.resolveDownloadUrl(file).orElse(null);
        if (url == null || url.isBlank()) {
            builder.message("该文件无法获取下载地址（作者可能禁止第三方下载）：" + file.fileName());
            builder.failed();
            return false;
        }

        DownloadItem item = new DownloadItem(target, url,
                file.sha1() == null ? "" : file.sha1(), file.size(),
                DownloadTargetType.MOD_FILE, java.util.Map.of(), java.util.Map.of(), true);

        if (DownloadManager.isUpToDate(item)) {
            builder.skipped();
            builder.file(target);
            remember(instanceId, project, file, fileName);
            return true;
        }

        listener.onMessage("下载 " + project.title() + " " + file.displayName());
        DownloadReport report = downloads.downloadAll(List.of(item), listener);
        if (report.hasRequiredFailure()) {
            builder.failed();
            // 把底层原因带出来：否则用户只看到「下载失败」而无法判断是网络、镜像还是校验问题
            String reason = report.failures().isEmpty() ? "未知原因" : report.failures().get(0).reason();
            builder.message("下载失败：" + file.fileName() + "（" + reason + "）");
            return false;
        }
        builder.installed();
        builder.file(target);
        remember(instanceId, project, file, fileName);
        log.info("已安装 {} {}（{}）", project.title(), file.displayName(), target);
        return true;
    }

    private void resolveAndInstallDependency(String instanceId,
                                             GamePaths paths,
                                             ModProvider provider,
                                             ModFile.ModDependency dependency,
                                             String gameVersion,
                                             String loader,
                                             ProgressListener listener,
                                             OutcomeBuilder builder,
                                             int depth) throws IOException {
        String projectId = dependency.projectId();
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        ModProject depProject = provider.project(projectId).orElse(null);
        if (depProject == null) {
            builder.message("找不到依赖项目：" + projectId);
            return;
        }
        List<ModFile> versions = provider.versions(projectId, gameVersion, loader);
        if (versions.isEmpty()) {
            builder.message("依赖项目没有匹配当前版本的可用文件：" + depProject.title());
            return;
        }
        ModFile depFile = dependency.versionId() == null || dependency.versionId().isBlank()
                ? selectFile(versions, null)
                : selectFile(versions, dependency.versionId());
        if (depFile == null) {
            depFile = selectFile(versions, null);
        }
        if (depFile == null) {
            return;
        }
        listener.onMessage("安装前置依赖：" + depProject.title());
        installFile(instanceId, paths, provider, depProject, depFile, gameVersion, loader, listener, depth);
    }

    /** 依赖是否已满足（索引中已登记同一项目）。 */
    private boolean isSatisfied(String instanceId, ModFile.ModDependency dependency) {
        for (LauncherConfig.InstalledModEntry entry : configStore.get().mods.installed.values()) {
            if (!dependency.projectId().equals(entry.projectId)) {
                continue;
            }
            if (entry.fileName == null || entry.fileName.isBlank()) {
                continue;
            }
            // 只要索引中记录了该项目，即认为依赖已由本启动器处理过
            return true;
        }
        return false;
    }

    /** 记录来源标记，供后续更新检查使用。 */
    private void remember(String instanceId, ModProject project, ModFile file, String fileName) {
        String key = indexKey(instanceId, relativeKeyOf(fileName, project.contentType()));
        LauncherConfig.InstalledModEntry entry = new LauncherConfig.InstalledModEntry();
        entry.source = project.source();
        entry.projectId = project.id();
        entry.versionId = file.versionId();
        entry.fileName = fileName;
        entry.sha1 = file.sha1() == null ? "" : file.sha1();
        entry.installedAt = System.currentTimeMillis();
        configStore.get().mods.installed.put(key, entry);
        configStore.save();
    }

    /**
     * 用<b>官方直链</b>安装一个模组。
     *
     * <p>用途：不在 CurseForge / Modrinth 上的模组（作者自己发布在 GitHub Release、
     * 个人站点等）。这类来源没有版本接口，所以把直链与「安装时的字节数、Last-Modified」
     * 一起记进索引 —— 更新检查靠 {@code HEAD} 这个地址比对，见
     * {@code ModUpdateService} 的 direct 分支。</p>
     *
     * @param url 直链（http/https），文件名取自 URL 的最后一段
     */
    public InstallOutcome installFromUrl(String instanceId, GamePaths paths, String url,
                                         ProgressListener listener) throws IOException {
        String trimmed = url == null ? "" : url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IOException("只支持 http/https 直链：" + trimmed);
        }
        String fileName = fileNameOfUrl(trimmed);
        Path dir = paths.modsDir();
        FileUtils.ensureDir(dir);
        Path target = dir.resolve(fileName);

        listener.onMessage("从官方地址下载 " + fileName);
        DownloadReport report = downloads.downloadAll(List.of(
                DownloadItem.of(target, trimmed, "", -1L, DownloadTargetType.MOD_FILE)), listener);
        if (report.hasRequiredFailure() || !Files.isRegularFile(target)) {
            throw new IOException("下载失败：" + trimmed);
        }

        LauncherConfig.InstalledModEntry entry = new LauncherConfig.InstalledModEntry();
        entry.source = "direct";
        entry.url = trimmed;
        entry.fileName = fileName;
        entry.sha1 = HashUtils.sha1(target);
        entry.size = Files.size(target);
        entry.installedAt = System.currentTimeMillis();
        try {
            // 记下远端基线：拿不到就算了，更新检查会退化为「按字节数比对」
            com.misaka.launcher.core.util.HttpUtils.Head head =
                    com.misaka.launcher.core.util.HttpUtils.head(trimmed, java.time.Duration.ofSeconds(15));
            if (head.isSuccess()) {
                entry.lastModified = head.lastModified();
                if (head.contentLength() > 0) {
                    entry.size = head.contentLength();
                }
            }
        } catch (IOException e) {
            log.debug("读取远端头部失败（不影响安装）：{}", e.getMessage());
        }
        configStore.get().mods.installed.put(indexKey(instanceId, "mods/" + fileName), entry);
        configStore.save();
        log.info("已从官方地址安装模组：{}", fileName);
        return new InstallOutcome(1, 0, 0, List.of(target), List.of("已安装 " + fileName));
    }

    /** 从直链里取文件名；URL 没有可用段时退回一个安全的名字。 */
    static String fileNameOfUrl(String url) {
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        String safe = sanitizeFileName(name);
        if (safe == null || safe.isBlank()) {
            return "download.jar";
        }
        return safe.toLowerCase(java.util.Locale.ROOT).endsWith(".jar") ? safe : safe + ".jar";
    }

    private static String relativeKeyOf(String fileName, ModContentType type) {
        return type.directory() + "/" + fileName;
    }

    /** 内容类型 → 落盘目录。 */
    public static Path targetDir(GamePaths paths, ModContentType type) {
        return switch (type) {
            case RESOURCE_PACK -> paths.resourcePacksDir();
            case SHADER -> paths.shaderPacksDir();
            case DATAPACK -> paths.gameDir().resolve("datapacks");
            case MODPACK -> paths.gameDir();
            default -> paths.modsDir();
        };
    }

    /** 拒绝可能逃出目标目录的文件名。 */
    static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return FileUtils.isSafeFileName(name) ? name : null;
    }

    /** 供界面与更新服务复用：把索引键解析回相对路径。 */
    public static String relativePathOf(String indexKey) {
        int separator = indexKey.indexOf('\u0000');
        return separator < 0 ? indexKey : indexKey.substring(separator + 1);
    }

    /** 供界面与更新服务复用：把索引键解析回实例 id。 */
    public static String instanceIdOf(String indexKey) {
        int separator = indexKey.indexOf('\u0000');
        return separator < 0 ? "" : indexKey.substring(0, separator);
    }

    /** 安装结果汇总。 */
    public record InstallOutcome(int installed, int skipped, int failed, List<Path> files, List<String> messages) {

        public InstallOutcome {
            files = List.copyOf(files);
            messages = List.copyOf(messages);
        }

        public boolean success() {
            return installed + skipped > 0 && failed == 0;
        }

        public String summary() {
            return "安装 " + installed + " 项，跳过 " + skipped + " 项"
                    + (failed > 0 ? "，失败 " + failed + " 项" : "");
        }
    }

    /** 累加器，避免在多个分支里手工拼接结果。 */
    private static final class OutcomeBuilder {
        private int installed;
        private int skipped;
        private int failed;
        private final Set<Path> files = new LinkedHashSet<>();
        private final List<String> messages = new ArrayList<>();

        void installed() {
            installed++;
        }

        void skipped() {
            skipped++;
        }

        void failed() {
            failed++;
        }

        void file(Path path) {
            files.add(path);
        }

        void message(String message) {
            if (message != null && !message.isBlank()) {
                messages.add(message);
            }
        }

        InstallOutcome build() {
            return new InstallOutcome(installed, skipped, failed, new ArrayList<>(files), messages);
        }
    }
}
