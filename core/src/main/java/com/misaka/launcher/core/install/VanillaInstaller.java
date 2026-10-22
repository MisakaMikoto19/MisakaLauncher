package com.misaka.launcher.core.install;

import com.misaka.launcher.core.download.DownloadItem;
import com.misaka.launcher.core.download.DownloadManager;
import com.misaka.launcher.core.download.DownloadReport;
import com.misaka.launcher.core.download.DownloadTargetType;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.instance.GameOptionsPreset;
import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.launch.ResolvedVersion;
import com.misaka.launcher.core.launch.VersionResolver;
import com.misaka.launcher.core.meta.RuleEvaluator;
import com.misaka.launcher.core.meta.VersionManifestService;
import com.misaka.launcher.core.model.AssetIndex;
import com.misaka.launcher.core.model.MavenCoordinate;
import com.misaka.launcher.core.model.VersionDetail;
import com.misaka.launcher.core.task.ProgressListener;
import com.misaka.launcher.core.util.FileUtils;
import com.misaka.launcher.core.util.JsonUtils;
import com.misaka.launcher.core.util.OsUtils;
import com.misaka.launcher.core.util.ZipUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 原版安装流水线。
 * <p>职责：把「版本 id」变成游戏目录里一套可直接启动的文件
 * （客户端 jar、运行库、原生库解压、资源索引与资源对象、日志配置、版本 JSON）。</p>
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>先计划后执行</b>：{@link #plan} 只产出数据，可用于体积预估与完整性校验；</li>
 *   <li><b>幂等</b>：下载层会跳过哈希一致的文件，因此重复安装等于增量修复；</li>
 *   <li><b>共享</b>：资源对象按内容哈希存放在公共 {@code assets/objects}，
 *       多版本、多实例（若共用 gameDir）不会重复占用磁盘。</li>
 * </ul>
 */
public final class VanillaInstaller {

    private static final Logger log = LoggerFactory.getLogger(VanillaInstaller.class);

    /** 官方资源对象分发地址；镜像源通过前缀规则改写。 */
    private static final String RESOURCES_BASE = "https://resources.download.minecraft.net/";
    /** 默认运行库仓库；元数据可覆盖。 */
    private static final String DEFAULT_LIBRARY_BASE = "https://libraries.minecraft.net/";

    private final VersionManifestService meta;
    private final DownloadManager downloads;
    private final GamePaths paths;

    public VanillaInstaller(VersionManifestService meta, DownloadManager downloads, GamePaths paths) {
        this.meta = meta;
        this.downloads = downloads;
        this.paths = paths;
    }

    public GamePaths paths() {
        return paths;
    }

    // ==================================================================
    // 计划
    // ==================================================================

    /**
     * 生成安装计划。
     *
     * @param versionId 版本 id
     * @param features  特性开关；传 null 使用默认值
     */
    public InstallPlan plan(String versionId, Map<String, Boolean> features) throws IOException {
        Map<String, Boolean> effectiveFeatures =
                features == null ? RuleEvaluator.defaultFeatures() : features;

        // 本地已有版本 JSON 时**一律以本地为准**：加载器版本（1.20.1-forge-47.4.26）只存在于
        // 游戏目录里，远端清单根本没有它 —— 以前无条件查清单，于是「Forge 装好了、
        // 点启动却报版本不存在」（校验文件阶段抛「版本清单中不存在该版本」）。
        if (Files.isRegularFile(paths.versionJson(versionId))) {
            return planLocal(versionId, effectiveFeatures);
        }
        return plan(meta.versionDetail(versionId, false), effectiveFeatures);
    }

    public InstallPlan plan(String versionId) throws IOException {
        return plan(versionId, null);
    }

    /**
     * 按本地版本 JSON 生成计划，并沿继承链合并。
     *
     * <p>加载器版本的 JSON 只是增量描述（{@code inheritsFrom} 指向原版），
     * 必须合并才能覆盖原版的客户端 jar、运行库与资源；主 jar 的落点也要用
     * 继承链上**实际存在**的那个 —— 加载器版本目录里没有 jar，若按子版本 id
     * 规划下载，会平白多下一个二十多 MB 的原版 jar。</p>
     */
    private InstallPlan planLocal(String versionId, Map<String, Boolean> features) throws IOException {
        ResolvedVersion resolved;
        try {
            resolved = VersionResolver.resolve(paths, versionId);
        } catch (IOException e) {
            // 本地 JSON 在、但继承链缺环（例如原版被删了）：给出能行动的提示，
            // 而不是让用户看到「远端清单里没有这个加载器版本」这种误导性报错
            throw new IOException("版本 " + versionId + " 的本地文件不完整：" + e.getMessage()
                    + "（加载器版本需要对应的原版版本仍在游戏目录中）", e);
        }
        Path clientJar = resolved.clientJar() == null
                ? paths.versionJar(versionId) : resolved.clientJar();
        return plan(resolved.detail(), clientJar, features);
    }

    /** 基于已获取的版本描述生成计划（加载器合并场景复用）。 */
    public InstallPlan plan(VersionDetail detail, Map<String, Boolean> features) throws IOException {
        return plan(detail, paths.versionJar(detail.effectiveId()), features);
    }

    private InstallPlan plan(VersionDetail detail, Path clientJarTarget,
                             Map<String, Boolean> features) throws IOException {
        String versionId = detail.effectiveId();
        List<DownloadItem> coreItems = new ArrayList<>();
        List<InstallPlan.NativeArchive> natives = new ArrayList<>();

        // 1) 客户端主 jar
        VersionDetail.DownloadInfo client = detail.clientJar();
        if (client != null && client.isUsable()) {
            coreItems.add(new DownloadItem(clientJarTarget, client.url, client.sha1, client.size,
                    DownloadTargetType.GAME_JAR, Map.of(),
                    Map.of("versionId", versionId), true));
        } else if (detail.inheritsFrom == null || detail.inheritsFrom.isBlank()) {
            // 加载器 profile 会通过 inheritsFrom 复用原版的主 jar，因此缺少该字段才算异常
            log.warn("版本 {} 未提供客户端主 jar 下载信息", versionId);
        }

        // 2) 运行库（含平台相关原生库）
        planLibraries(detail, versionId, features, coreItems, natives);

        // 3) 日志配置
        if (detail.logging != null && detail.logging.client != null
                && detail.logging.client.file != null && detail.logging.client.file.isUsable()) {
            VersionDetail.DownloadInfo logConfig = detail.logging.client.file;
            Path target = paths.loggingFile(logConfig.id == null || logConfig.id.isBlank()
                    ? "client-logging.xml" : logConfig.id);
            coreItems.add(new DownloadItem(target, logConfig.url, logConfig.sha1, logConfig.size,
                    DownloadTargetType.LOGGING_CONFIG, Map.of(), Map.of(), false));
        }

        // 4) 资源
        List<DownloadItem> assetItems = new ArrayList<>();
        String assetIndexId = detail.assetIndexId();
        boolean virtualAssets = false;
        boolean mapToResources = false;
        if (assetIndexId != null && !assetIndexId.isBlank()) {
            VersionDetail.AssetIndexInfo indexInfo = detail.assetIndex;
            Path indexFile = paths.assetIndexFile(assetIndexId);
            if (indexInfo != null && indexInfo.isUsable()) {
                assetItems.add(new DownloadItem(indexFile, indexInfo.url, indexInfo.sha1, indexInfo.size,
                        DownloadTargetType.ASSET_INDEX, Map.of(), Map.of(), true));
            }
            AssetIndex index = loadOrFetchAssetIndex(assetIndexId, indexFile, indexInfo);
            if (index != null) {
                virtualAssets = index.virtual;
                mapToResources = index.map_to_resources;
                for (Map.Entry<String, AssetIndex.AssetObject> entry : index.objects.entrySet()) {
                    AssetIndex.AssetObject object = entry.getValue();
                    if (object == null || object.hash == null || object.hash.length() < 2) {
                        continue;
                    }
                    String prefix = object.hash.substring(0, 2);
                    assetItems.add(new DownloadItem(paths.assetObject(object.hash),
                            RESOURCES_BASE + prefix + "/" + object.hash,
                            object.hash, object.size, DownloadTargetType.ASSET_OBJECT,
                            Map.of(), Map.of(), false));
                }
            }
        }

        return new InstallPlan(versionId, detail, coreItems, assetItems, natives,
                assetIndexId, virtualAssets, mapToResources);
    }

    private void planLibraries(VersionDetail detail,
                               String versionId,
                               Map<String, Boolean> features,
                               List<DownloadItem> items,
                               List<InstallPlan.NativeArchive> natives) {
        if (detail.libraries == null) {
            return;
        }
        for (VersionDetail.Library library : detail.libraries) {
            if (library == null || library.name == null || library.name.isBlank()) {
                continue;
            }
            if (!RuleEvaluator.isAllowed(library.rules, features)) {
                continue;
            }

            VersionDetail.DownloadInfo artifact = library.downloads == null
                    ? null : library.downloads.usableArtifact();
            if (artifact != null) {
                String relative = artifact.path != null && !artifact.path.isBlank()
                        ? artifact.path
                        : safeMavenPath(library.name);
                if (relative != null) {
                    items.add(new DownloadItem(paths.libraryFile(relative), artifact.url, artifact.sha1,
                            artifact.size, DownloadTargetType.LIBRARY, Map.of(), Map.of(), true));
                }
            } else if (library.downloads == null) {
                // Forge / Fabric 等第三方元数据只给出坐标与仓库地址：
                // 自行推导路径，并沿用库级别的 sha1 / size 做完整性校验
                String relative = safeMavenPath(library.name);
                if (relative != null) {
                    String base = library.url == null || library.url.isBlank()
                            ? DEFAULT_LIBRARY_BASE : library.url;
                    String url = base.endsWith("/") ? base + relative : base + "/" + relative;
                    items.add(new DownloadItem(paths.libraryFile(relative), url,
                            library.sha1 == null ? "" : library.sha1,
                            library.size,
                            DownloadTargetType.LIBRARY, Map.of(), Map.of(), true));
                }
            }

            // 平台相关原生库
            if (library.hasNatives() && library.downloads != null && library.downloads.classifiers != null) {
                String classifier = library.natives.forOs(OsUtils.mojangOsName(), OsUtils.isAarch64());
                if (classifier != null) {
                    VersionDetail.DownloadInfo nativeInfo = library.downloads.classifiers.get(classifier);
                    if (nativeInfo != null && nativeInfo.isUsable()) {
                        String relative = nativeInfo.path != null && !nativeInfo.path.isBlank()
                                ? nativeInfo.path
                                : classifierPath(library.name, classifier);
                        if (relative != null) {
                            items.add(new DownloadItem(paths.libraryFile(relative), nativeInfo.url,
                                    nativeInfo.sha1, nativeInfo.size, DownloadTargetType.LIBRARY,
                                    Map.of(), Map.of(), true));
                            List<String> excludes = library.extract == null || library.extract.exclude == null
                                    ? List.of() : List.copyOf(library.extract.exclude);
                            natives.add(new InstallPlan.NativeArchive(paths.libraryFile(relative), excludes));
                        }
                    }
                }
            }
        }
    }

    private static String safeMavenPath(String coordinate) {
        try {
            return MavenCoordinate.parse(coordinate).toPath();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String classifierPath(String coordinate, String classifier) {
        try {
            MavenCoordinate base = MavenCoordinate.parse(coordinate);
            return base.groupPath() + "/" + base.artifact() + "/" + base.version() + "/"
                    + base.artifact() + "-" + base.version() + "-" + classifier + "." + base.extension();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 读取资源索引：优先使用本地文件，缺失或损坏时联网抓取。
     * <p>这样在「校验完整性」场景下不会因为一次网络抖动而失败。</p>
     */
    private AssetIndex loadOrFetchAssetIndex(String indexId, Path indexFile, VersionDetail.AssetIndexInfo info) {
        if (Files.isRegularFile(indexFile)) {
            try {
                return JsonUtils.read(indexFile, AssetIndex.class);
            } catch (IOException e) {
                log.warn("本地资源索引损坏，尝试重新抓取 {}: {}", indexId, e.getMessage());
            }
        }
        if (info == null || !info.isUsable()) {
            log.warn("版本未提供资源索引地址，跳过资源下载：{}", indexId);
            return null;
        }
        try {
            String body = meta.fetcher().fetchString(info.url, DownloadTargetType.ASSET_INDEX,
                    Map.of(), Map.of());
            FileUtils.ensureParent(indexFile);
            FileUtils.atomicWrite(indexFile, body);
            return JsonUtils.read(body, AssetIndex.class);
        } catch (IOException e) {
            log.warn("抓取资源索引失败 {}: {}", indexId, e.getMessage());
            return null;
        }
    }

    // ==================================================================
    // 执行
    // ==================================================================

    /** 执行安装。 */
    public InstallResult install(String versionId, ProgressListener listener) throws IOException {
        long start = System.currentTimeMillis();

        listener.onStage(I18n.t("install.stage.meta"));
        InstallPlan plan = plan(versionId, null);
        listener.onMessage("版本 " + plan.versionId() + "：启动文件 " + plan.coreItems().size()
                + " 个，资源文件 " + plan.assetItems().size() + " 个，预计 " + FileUtils.humanSize(plan.totalBytes()));

        // 运行库与资源合成**一个**队列再下（PCL2 的做法也是单队列）：
        //   * 客户端 jar 常有几十 MB，而资源是上千个小文件；分两段跑时，
        //     第一段尾部在等大文件、第二段才开始，线程整体空转一段时间；
        //   * 合成一批后 DownloadManager 也会按「文件数」自动提高并发（见 effectiveConcurrency）。
        List<DownloadItem> batch = new ArrayList<>(plan.coreItems());
        batch.addAll(plan.assetItems());
        DownloadReport coreReport = DownloadReportWrapper.empty();
        if (!batch.isEmpty()) {
            // 只有运行库时仍是「下载运行库」；带了资源才换成合并批次的文案
            listener.onStage(I18n.t(plan.assetItems().isEmpty()
                    ? "install.stage.libraries" : "install.stage.files"));
            coreReport = downloads.downloadAll(batch, listener);
        }
        // 合并批次后资源已包含在 coreReport 里，这里保持一个空报告以便复用下面的合并逻辑
        DownloadReport assetReport = DownloadReportWrapper.empty();

        listener.onStage(I18n.t("install.stage.natives"));
        int extracted = extractNatives(plan.natives(), plan.versionId());

        if (plan.virtualAssets() || plan.mapToResources()) {
            buildVirtualAssets(plan.assetIndexId(), plan.mapToResources());
        }

        listener.onStage(I18n.t("install.stage.finish"));
        Path writtenJson = writeVersionJson(plan.detail());

        DownloadReport merged = DownloadReportWrapper.merge(
                List.of(coreReport, assetReport), System.currentTimeMillis() - start);
        log.info("原版安装完成：{}（下载 {}，跳过 {}，失败 {}），解压原生库 {} 个",
                plan.versionId(), merged.downloaded(), merged.skipped(), merged.failed(), extracted);

        // 顺手铺一份内置键位预设（仅当游戏目录还没有 options.txt）。
        // 放在安装收尾而不是启动时：客户机装完即对齐键位与画面档位。
        // 失败只记日志，绝不影响安装结果的成败判定
        if (GameOptionsPreset.deployIfAbsent(paths)) {
            listener.onMessage(I18n.t("instance.options.applied"));
        }

        return new InstallResult(plan.versionId(), merged, extracted, plan.assetIndexId(),
                writtenJson, System.currentTimeMillis() - start);
    }

    /**
     * 只校验不下载：列出缺失或损坏的必需文件。
     * <p>用于「启动前校验完整性」——只做本地哈希比对，不产生任何网络请求。</p>
     */
    public InstallResult.IntegrityReport verify(String versionId, Map<String, Boolean> features) throws IOException {
        InstallPlan plan = plan(versionId, features);
        List<Path> missing = new ArrayList<>();
        List<DownloadItem> all = plan.allItems();
        for (DownloadItem item : all) {
            if (!item.required() && !DownloadManager.isUpToDate(item)) {
                // 非必需项缺失也计入，但不影响启动判定
                missing.add(item.target());
                continue;
            }
            if (!DownloadManager.isUpToDate(item)) {
                missing.add(item.target());
            }
        }
        if (!Files.isRegularFile(paths.versionJson(versionId))) {
            missing.add(paths.versionJson(versionId));
        }
        return new InstallResult.IntegrityReport(versionId, all.size() + 1, missing.size(), List.copyOf(missing));
    }

    /** 解压原生库到版本隔离目录。 */
    private int extractNatives(List<InstallPlan.NativeArchive> archives, String versionId) throws IOException {
        if (archives.isEmpty()) {
            return 0;
        }
        Path nativesDir = paths.nativesDir(versionId);
        FileUtils.ensureDir(nativesDir);
        int extracted = 0;
        for (InstallPlan.NativeArchive archive : archives) {
            if (!Files.isRegularFile(archive.archive())) {
                log.warn("原生库缺失，跳过解压: {}", archive.archive());
                continue;
            }
            try {
                List<Path> written = ZipUtils.extract(archive.archive(), nativesDir,
                        name -> shouldExtractNative(name, archive.excludes()));
                extracted += written.size();
            } catch (IOException e) {
                throw new IOException("解压原生库失败: " + archive.archive(), e);
            }
        }
        log.info("已解压 {} 个原生库文件到 {}", extracted, nativesDir);
        return extracted;
    }

    private static boolean shouldExtractNative(String entryName, List<String> excludes) {
        // 元数据清单与其他元信息不属于运行所需
        if (entryName.startsWith("META-INF/") || entryName.endsWith("/")) {
            return false;
        }
        for (String exclude : excludes) {
            if (entryName.startsWith(exclude)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构建 1.6 及更早的虚拟资源目录。
     * <p>旧版本按原始路径（如 {@code minecraft/sound/...}）访问资源，
     * 因此需要把哈希寻址的对象另建一份「路径 → 文件」的目录；优先使用硬链接，避免翻倍占用磁盘。</p>
     */
    private void buildVirtualAssets(String indexId, boolean mapToResources) throws IOException {
        if (indexId == null || indexId.isBlank()) {
            return;
        }
        Path indexFile = paths.assetIndexFile(indexId);
        if (!Files.isRegularFile(indexFile)) {
            return;
        }
        AssetIndex index = JsonUtils.read(indexFile, AssetIndex.class);
        Path targetRoot = mapToResources ? paths.gameDir().resolve("resources") : paths.virtualAssetsDir(indexId);
        FileUtils.ensureDir(targetRoot);

        int created = 0;
        for (Map.Entry<String, AssetIndex.AssetObject> entry : index.objects.entrySet()) {
            AssetIndex.AssetObject object = entry.getValue();
            if (object == null || object.hash == null) {
                continue;
            }
            Path source = paths.assetObject(object.hash);
            if (!Files.isRegularFile(source)) {
                continue;
            }
            Path target = targetRoot.resolve(entry.getKey().replace('/', java.io.File.separatorChar));
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            FileUtils.ensureParent(target);
            try {
                Files.createLink(target, source);
            } catch (IOException | UnsupportedOperationException e) {
                // 跨卷或文件系统不支持硬链接时退化为复制
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
            created++;
        }
        log.info("已构建虚拟资源目录 {}：{} 个文件", targetRoot, created);
    }

    /** 把版本 JSON 写入游戏目录，供启动与后续加载器叠加使用。 */
    private Path writeVersionJson(VersionDetail detail) throws IOException {
        String versionId = detail.effectiveId();
        Path target = paths.versionJson(versionId);
        FileUtils.ensureParent(target);

        Path source = detail.sourceJson;
        if (source != null && Files.isRegularFile(source) && !source.toAbsolutePath().equals(target.toAbsolutePath())) {
            // 保留原始字节，避免重新序列化丢失未知字段（加载器会依赖这些字段）
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } else {
            FileUtils.atomicWrite(target, JsonUtils.writePrettyBytes(detail));
        }
        return target;
    }

    /** 汇总辅助，避免在业务代码里手工拼装空报告。 */
    private static final class DownloadReportWrapper {

        static DownloadReport empty() {
            return new DownloadReport(0, 0, 0, 0, 0L, 0L, List.of());
        }

        static DownloadReport merge(List<DownloadReport> reports, long elapsedMillis) {
            int total = 0;
            int downloaded = 0;
            int skipped = 0;
            int failed = 0;
            long bytes = 0L;
            List<DownloadReport.Failure> failures = new ArrayList<>();
            for (DownloadReport report : reports) {
                total += report.total();
                downloaded += report.downloaded();
                skipped += report.skipped();
                failed += report.failed();
                bytes += report.bytes();
                failures.addAll(report.failures());
            }
            return new DownloadReport(total, downloaded, skipped, failed, bytes, elapsedMillis, List.copyOf(failures));
        }
    }
}
