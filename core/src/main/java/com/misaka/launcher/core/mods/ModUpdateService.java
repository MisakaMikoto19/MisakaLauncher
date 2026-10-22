package com.misaka.launcher.core.mods;

import com.misaka.launcher.core.config.ConfigStore;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.download.DownloadItem;
import com.misaka.launcher.core.download.DownloadManager;
import com.misaka.launcher.core.download.DownloadReport;
import com.misaka.launcher.core.download.DownloadTargetType;
import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.task.ProgressListener;
import com.misaka.launcher.core.util.HashUtils;
import com.misaka.launcher.core.util.HttpUtils;
import com.misaka.launcher.core.util.PathSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 已装模组的更新检查与执行。
 * <p>核心依赖安装时写入的「来源标记」：知道每个文件来自哪个来源、哪个项目、哪个版本，
 * 就能直接问来源方「有没有新版本」，而不是靠文件名猜测。</p>
 * <p>自愈能力：对于索引中缺失、但确实存在于 mods 目录的文件，会尝试用 SHA-1
 * （Modrinth）或指纹（CurseForge）反查并补登记，因此「用户手动放入的模组」也能纳入更新检查。</p>
 */
public final class ModUpdateService {

    private static final Logger log = LoggerFactory.getLogger(ModUpdateService.class);

    private final ConfigStore configStore;
    private final DownloadManager downloads;
    private final Map<String, ModProvider> providersById;

    /** 「官方地址」检查的 HEAD 超时：这类站点通常很慢，但不能无限等。 */
    private static final java.time.Duration HEAD_TIMEOUT = java.time.Duration.ofSeconds(20);

    public ModUpdateService(ConfigStore configStore, DownloadManager downloads, List<ModProvider> providers) {
        this.configStore = configStore;
        this.downloads = downloads;
        Map<String, ModProvider> map = new LinkedHashMap<>();
        for (ModProvider provider : providers) {
            map.put(provider.id(), provider);
        }
        this.providersById = map;
    }

    // ==================================================================
    // 检查
    // ==================================================================

    /**
     * 检查指定实例的模组更新。
     *
     * @param instanceId  实例 id
     * @param paths       游戏目录布局
     * @param gameVersion 目标游戏版本
     * @param loader      目标加载器
     */
    public List<ModUpdate> check(String instanceId,
                                 GamePaths paths,
                                 String gameVersion,
                                 String loader,
                                 ProgressListener listener) throws IOException {
        List<ModUpdate> updates = new ArrayList<>();
        Map<String, LauncherConfig.InstalledModEntry> index = configStore.get().mods.installed;
        String prefix = instanceId + "\u0000";

        // 1) 反向扫描：把磁盘上存在但索引缺失的模组补登记
        adoptUnknownFiles(instanceId, paths, gameVersion, loader, listener);

        // 2) 逐个比对
        for (Map.Entry<String, LauncherConfig.InstalledModEntry> entry : index.entrySet()) {
            listener.throwIfCancelled();
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            LauncherConfig.InstalledModEntry installed = entry.getValue();
            ModProvider provider = providersById.get(installed.source);
            if (provider == null || !provider.isConfigured()) {
                continue;
            }

            Path localFile = resolveLocalFile(paths, entry.getKey());
            if (localFile == null || !Files.isRegularFile(localFile)) {
                continue;
            }

            String projectId = installed.projectId;
            if (projectId == null || projectId.isBlank()) {
                continue;
            }

            try {
                List<ModFile> versions = provider.versions(projectId, gameVersion, loader);
                ModFile latest = pickLatest(versions, gameVersion, loader);
                if (latest == null) {
                    continue;
                }
                if (latest.versionId().equals(installed.versionId)) {
                    continue;
                }
                String title = provider.project(projectId).map(ModProject::title).orElse(installed.fileName);
                updates.add(new ModUpdate(installed.source, projectId, title,
                        installed.versionId, installed.fileName, latest, localFile, provider, entry.getKey()));
            } catch (IOException e) {
                log.debug("检查更新失败 {}：{}", installed.fileName, e.getMessage());
                listener.onMessage("检查失败：" + installed.fileName + "（" + e.getMessage() + "）");
            }
        }
        // 3) 官方地址：这类模组不在任何模组库里，没有「版本列表」可比 ——
        //    用 HEAD 取远端的 Content-Length / Last-Modified，与安装时记下的基线比对
        for (Map.Entry<String, LauncherConfig.InstalledModEntry> entry : index.entrySet()) {
            listener.throwIfCancelled();
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            LauncherConfig.InstalledModEntry installed = entry.getValue();
            if (!"direct".equals(installed.source) || installed.url == null || installed.url.isBlank()) {
                continue;
            }
            Path localFile = resolveLocalFile(paths, entry.getKey());
            if (localFile == null || !Files.isRegularFile(localFile)) {
                continue;
            }
            try {
                HttpUtils.Head head = HttpUtils.head(installed.url, HEAD_TIMEOUT);
                if (!head.isSuccess()) {
                    continue;
                }
                boolean sizeChanged = head.contentLength() > 0 && installed.size > 0
                        && head.contentLength() != installed.size;
                boolean timeChanged = !head.lastModified().isBlank()
                        && !installed.lastModified.isBlank()
                        && !head.lastModified().equals(installed.lastModified);
                if (!sizeChanged && !timeChanged) {
                    continue;
                }
                ModFile remote = new ModFile("direct", "", head.lastModified(), head.lastModified(),
                        installed.fileName, installed.fileName, installed.url, "", "",
                        head.contentLength(), List.of(), List.of(), List.of(), "release", "", true);
                updates.add(new ModUpdate("direct", "", installed.fileName, installed.versionId,
                        installed.fileName, remote, localFile, null, entry.getKey()));
            } catch (IOException e) {
                log.debug("官方地址检查失败 {}：{}", installed.url, e.getMessage());
            }
        }

        listener.onMessage("发现 " + updates.size() + " 项可更新");
        return updates;
    }

    /**
     * 实例当前有多少个模组文件。
     *
     * <p>直接数磁盘上的 {@code mods/*.jar}，而不是数索引条数：用户手动拖进来的模组、
     * 整合包解出来的模组都不一定在索引里，而界面上那句「检测到 N 个模组」指的应该是
     * 用户眼里能看到的文件。</p>
     */
    public int installedModCount(GamePaths paths) {
        Path modsDir = paths.modsDir();
        if (!Files.isDirectory(modsDir)) {
            return 0;
        }
        return (int) com.misaka.launcher.core.util.FileUtils.listFiles(modsDir).stream()
                .filter(path -> "jar".equals(com.misaka.launcher.core.util.FileUtils.extensionOf(path)))
                .count();
    }

    /**
     * 挑选最新版本。
     * <p>策略：优先稳定版，其次按发布时间倒序；这样不会把用户从 release 带到 beta。</p>
     */
    static ModFile pickLatest(List<ModFile> versions, String gameVersion, String loader) {
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        List<ModFile> compatible = versions.stream()
                .filter(file -> file.isCompatible(gameVersion, loader))
                .toList();
        List<ModFile> candidates = compatible.isEmpty() ? versions : compatible;
        return candidates.stream()
                .filter(ModFile::isStable)
                .findFirst()
                .orElse(candidates.get(0));
    }

    /**
     * 把磁盘上存在但未登记的模组文件纳入索引。
     * <p>使用 SHA-1（Modrinth）与指纹（CurseForge）反查；两者都失败时保持忽略，不影响其它检查。</p>
     */
    private void adoptUnknownFiles(String instanceId,
                                   GamePaths paths,
                                   String gameVersion,
                                   String loader,
                                   ProgressListener listener) {
        Path modsDir = paths.modsDir();
        if (!Files.isDirectory(modsDir)) {
            return;
        }
        Map<String, LauncherConfig.InstalledModEntry> index = configStore.get().mods.installed;
        List<Path> files = com.misaka.launcher.core.util.FileUtils.listFiles(modsDir).stream()
                .filter(path -> "jar".equals(com.misaka.launcher.core.util.FileUtils.extensionOf(path)))
                .toList();

        boolean changed = false;
        for (Path file : files) {
            listener.throwIfCancelled();
            String key = ModInstaller.indexKey(instanceId, "mods/" + file.getFileName());
            if (index.containsKey(key)) {
                continue;
            }
            try {
                String sha1 = HashUtils.sha1(file);
                for (ModProvider provider : providersById.values()) {
                    if (!provider.isConfigured()) {
                        continue;
                    }
                    Optional<ModFile> found = provider.fileByHash(sha1, file.getFileName().toString(), file);
                    if (found.isEmpty()) {
                        continue;
                    }
                    ModFile version = found.get();
                    LauncherConfig.InstalledModEntry entry = new LauncherConfig.InstalledModEntry();
                    entry.source = provider.id();
                    entry.projectId = version.projectId();
                    entry.versionId = version.versionId();
                    entry.fileName = file.getFileName().toString();
                    entry.sha1 = sha1;
                    entry.installedAt = System.currentTimeMillis();
                    index.put(key, entry);
                    changed = true;
                    log.info("已识别已装模组：{}（来源 {}）", file.getFileName(), provider.id());
                    break;
                }
            } catch (IOException e) {
                log.debug("识别本地模组失败 {}：{}", file, e.getMessage());
            }
        }
        if (changed) {
            configStore.save();
        }
    }

    // ==================================================================
    // 执行更新
    // ==================================================================

    /**
     * 应用更新：下载新文件、删除旧文件、更新索引。
     * <p>先下载后删除，保证任何中断都不会让用户失去可用文件。</p>
     */
    public UpdateOutcome apply(List<ModUpdate> updates, ProgressListener listener) {
        int updated = 0;
        int failed = 0;
        long bytes = 0L;
        List<String> messages = new ArrayList<>();

        for (ModUpdate update : updates) {
            listener.throwIfCancelled();
            try {
                // 官方地址来源没有 provider：直链就在记录里，直接用它
                String url = update.provider() == null
                        ? update.latest().downloadUrl()
                        : update.provider().resolveDownloadUrl(update.latest()).orElse(null);
                if (url == null || url.isBlank()) {
                    messages.add(update.projectTitle() + "：无法获取下载地址");
                    failed++;
                    continue;
                }
                String fileName = ModInstaller.sanitizeFileName(update.latest().fileName());
                if (fileName == null) {
                    messages.add(update.projectTitle() + "：文件名非法");
                    failed++;
                    continue;
                }
                Path targetDir = update.installedFile().getParent();
                Path target = targetDir.resolve(fileName);
                if (!PathSafety.isInside(targetDir, target)) {
                    messages.add(update.projectTitle() + "：目标路径越界");
                    failed++;
                    continue;
                }

                listener.onMessage("更新 " + update.projectTitle() + " → " + update.latest().displayName());
                DownloadItem item = new DownloadItem(target, url,
                        update.latest().sha1() == null ? "" : update.latest().sha1(),
                        update.latest().size(), DownloadTargetType.MOD_FILE,
                        Map.of(), Map.of(), true);
                DownloadReport report = downloads.downloadAll(List.of(item), listener);
                bytes += report.bytes();
                if (report.hasRequiredFailure()) {
                    messages.add(update.projectTitle() + "：下载失败");
                    failed++;
                    continue;
                }

                // 新旧文件名不同时清理旧文件，并把索引键迁移到新路径
                if (!update.installedFile().equals(target)) {
                    try {
                        Files.deleteIfExists(update.installedFile());
                        String newKey = relocatedIndexKey(update.indexKey(), fileName);
                        removeIndexEntry(update.indexKey());
                        registerIndexEntry(newKey, update, fileName);
                    } catch (IOException e) {
                        messages.add(update.projectTitle() + "：旧文件清理失败（" + e.getMessage() + "）");
                    }
                } else {
                    registerIndexEntry(update.indexKey(), update, fileName);
                }
                updated++;
            } catch (IOException e) {
                messages.add(update.projectTitle() + "：" + e.getMessage());
                failed++;
            }
        }
        configStore.save();
        return new UpdateOutcome(updated, failed, bytes, messages);
    }

    private void registerIndexEntry(String indexKey, ModUpdate update, String fileName) {        LauncherConfig.InstalledModEntry entry = new LauncherConfig.InstalledModEntry();
        entry.source = update.source();
        entry.projectId = update.projectId();
        entry.versionId = update.latest().versionId();
        entry.fileName = fileName;
        entry.sha1 = update.latest().sha1() == null ? "" : update.latest().sha1();
        entry.installedAt = System.currentTimeMillis();
        if ("direct".equals(update.source())) {
            // 直链来源必须把 URL 与新的远端基线一起写回，否则下次检查会一直判「有更新」
            entry.url = update.latest().downloadUrl();
            entry.size = update.latest().size();
            entry.lastModified = update.latest().versionId();
        }
        configStore.get().mods.installed.put(indexKey, entry);
    }

    private void removeIndexEntry(String indexKey) {
        configStore.get().mods.installed.remove(indexKey);
    }

    /**
     * 文件名变化时迁移索引键。
     * <p>若不迁移，索引会指向已被删除的旧文件，导致下次检查更新时该模组「消失」。</p>
     */
    static String relocatedIndexKey(String oldIndexKey, String newFileName) {
        String instanceId = ModInstaller.instanceIdOf(oldIndexKey);
        String oldRelative = ModInstaller.relativePathOf(oldIndexKey);
        int slash = oldRelative.lastIndexOf('/');
        String newRelative = slash < 0 ? newFileName : oldRelative.substring(0, slash + 1) + newFileName;
        return ModInstaller.indexKey(instanceId, newRelative);
    }

    /** 由索引键解析本地文件路径。 */
    private Path resolveLocalFile(GamePaths paths, String indexKey) {
        String relative = ModInstaller.relativePathOf(indexKey);
        try {
            return PathSafety.resolveInside(paths.gameDir(), relative, false);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 一条可应用的更新。
     *
     * @param source         来源
     * @param projectId      项目 id
     * @param projectTitle   项目名
     * @param installedVersionId 已装版本 id
     * @param installedFileName  已装文件名
     * @param latest         可更新的目标版本
     * @param installedFile  已装文件路径
     * @param provider       来源实例
     * @param indexKey       索引键
     */
    public record ModUpdate(String source,
                            String projectId,
                            String projectTitle,
                            String installedVersionId,
                            String installedFileName,
                            ModFile latest,
                            Path installedFile,
                            ModProvider provider,
                            String indexKey) {

        /** 面向界面的描述。 */
        public String description() {
            return projectTitle + "：" + installedVersionId + " → " + latest.versionNumber();
        }
    }

    /**
     * 更新执行结果。
     *
     * @param updated  成功更新数
     * @param failed   失败数
     * @param bytes    传输字节数
     * @param messages 明细（失败原因等）
     */
    public record UpdateOutcome(int updated, int failed, long bytes, List<String> messages) {

        public UpdateOutcome {
            messages = List.copyOf(messages);
        }

        public boolean success() {
            return failed == 0;
        }

        public String summary() {
            return "更新 " + updated + " 项" + (failed > 0 ? "，失败 " + failed + " 项" : "");
        }
    }
}
