package com.misaka.launcher.core.mods;

import com.fasterxml.jackson.databind.JsonNode;
import com.misaka.launcher.core.download.DownloadItem;
import com.misaka.launcher.core.download.DownloadManager;
import com.misaka.launcher.core.download.DownloadReport;
import com.misaka.launcher.core.download.DownloadTargetType;
import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.task.ProgressListener;
import com.misaka.launcher.core.util.FileUtils;
import com.misaka.launcher.core.util.JsonUtils;
import com.misaka.launcher.core.util.PathSafety;
import com.misaka.launcher.core.util.ZipUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 整合包安装。
 * <p>支持两种主流清单格式，二者的流程一致：<b>下载整合包归档 → 读清单 → 逐项下载内容 → 展开覆盖文件</b>。</p>
 * <ul>
 *   <li><b>Modrinth</b>（{@code .mrpack}）：清单为 {@code modrinth.index.json}，
 *       每个文件都带直链与 SHA-1/SHA-512，无需 API Key；</li>
 *   <li><b>CurseForge</b>：清单为 {@code manifest.json}，只给 projectID/fileID，
 *       需要经 CurseForge API 换取文件（因此需要 API Key）。</li>
 * </ul>
 * <p>安装完成后返回清单声明的加载器信息，由上层继续执行加载器安装。</p>
 */
public final class ModpackInstaller {

    private static final Logger log = LoggerFactory.getLogger(ModpackInstaller.class);

    private static final String MODRINTH_INDEX = "modrinth.index.json";
    private static final String CURSEFORGE_MANIFEST = "manifest.json";

    private final DownloadManager downloads;
    private final GamePaths paths;
    private final Map<String, ModProvider> providersById;

    public ModpackInstaller(DownloadManager downloads, GamePaths paths, List<ModProvider> providers) {
        this.downloads = downloads;
        this.paths = paths;
        Map<String, ModProvider> map = new LinkedHashMap<>();
        for (ModProvider provider : providers) {
            map.put(provider.id(), provider);
        }
        this.providersById = map;
    }

    /**
     * 安装整合包。
     *
     * @param archive 已下载的整合包归档
     */
    public InstallResult install(Path archive, ProgressListener listener) throws IOException {
        if (!Files.isRegularFile(archive)) {
            throw new IOException("整合包文件不存在: " + archive);
        }
        listener.onStage("读取整合包清单");

        String modrinthIndex = ZipUtils.readText(archive, MODRINTH_INDEX);
        if (modrinthIndex != null) {
            return installModrinth(archive, modrinthIndex, listener);
        }
        String curseForgeManifest = ZipUtils.readText(archive, CURSEFORGE_MANIFEST);
        if (curseForgeManifest != null) {
            return installCurseForge(archive, curseForgeManifest, listener);
        }
        throw new IOException("无法识别的整合包格式：归档中既没有 " + MODRINTH_INDEX
                + " 也没有 " + CURSEFORGE_MANIFEST);
    }

    // ==================================================================
    // Modrinth 格式
    // ==================================================================

    private InstallResult installModrinth(Path archive, String indexJson, ProgressListener listener)
            throws IOException {
        JsonNode index = JsonUtils.read(indexJson);
        String name = JsonUtils.text(index, "name", archive.getFileName().toString());
        String versionId = JsonUtils.text(index, "versionId", "");

        Map<String, String> dependencies = new LinkedHashMap<>();
        JsonNode deps = index.get("dependencies");
        if (deps != null && deps.isObject()) {
            deps.properties().forEach(entry -> dependencies.put(entry.getKey(), entry.getValue().asText()));
        }

        listener.onStage("下载整合包内容");
        List<DownloadItem> items = new ArrayList<>();
        JsonNode files = index.get("files");
        if (files != null && files.isArray()) {
            for (JsonNode file : files) {
                String relative = JsonUtils.text(file, "path", "");
                if (relative.isBlank()) {
                    continue;
                }
                // 仅客户端需要的文件：env.client == "required" 或未声明
                JsonNode env = file.get("env");
                if (env != null && "unsupported".equalsIgnoreCase(JsonUtils.text(env, "client", ""))) {
                    continue;
                }
                List<String> downloadsUrls = JsonUtils.stringList(file, "downloads");
                if (downloadsUrls.isEmpty()) {
                    continue;
                }
                Path target = safeResolve(paths.gameDir(), relative);
                if (target == null) {
                    log.warn("整合包条目路径非法，已跳过: {}", relative);
                    continue;
                }
                JsonNode hashes = file.get("hashes");
                String sha1 = hashes == null ? "" : JsonUtils.text(hashes, "sha1", "");
                items.add(new DownloadItem(target, downloadsUrls.get(0), sha1,
                        JsonUtils.longValue(file, "fileSize", -1L),
                        DownloadTargetType.MOD_FILE, Map.of(), Map.of(), true));
            }
        }

        DownloadReport report = items.isEmpty()
                ? new DownloadReport(0, 0, 0, 0, 0L, 0L, List.of())
                : downloads.downloadAll(items, listener);

        listener.onStage("展开整合包覆盖文件");
        int overridden = extractOverrides(archive, paths.gameDir(), "overrides/");

        return new InstallResult(name, versionId, dependencies,
                report.downloaded(), overridden, report.bytes(), report.failed());
    }

    // ==================================================================
    // CurseForge 格式
    // ==================================================================

    private InstallResult installCurseForge(Path archive, String manifestJson, ProgressListener listener)
            throws IOException {
        JsonNode manifest = JsonUtils.read(manifestJson);
        String name = JsonUtils.text(manifest, "name", archive.getFileName().toString());
        String version = JsonUtils.text(manifest, "version", "");

        ModProvider curseForge = providersById.get(CurseForgeProvider.ID);
        if (curseForge == null || !curseForge.isConfigured()) {
            throw new IOException("该整合包是 CurseForge 格式，需要先配置 CurseForge API Key 才能自动下载内容");
        }

        String gameVersion = JsonUtils.text(manifest.path("minecraft"), "version", "");
        Map<String, String> dependencies = new LinkedHashMap<>();
        dependencies.put("minecraft", gameVersion);
        JsonNode loaders = manifest.path("minecraft").get("modLoaders");
        if (loaders != null && loaders.isArray()) {
            for (JsonNode loader : loaders) {
                // 形如 forge-47.2.0 / fabric-0.15.11
                String id = JsonUtils.text(loader, "id", "");
                if (id.contains("-")) {
                    dependencies.put(id.substring(0, id.indexOf('-')), id.substring(id.indexOf('-') + 1));
                }
            }
        }

        listener.onStage("下载整合包内容");
        List<DownloadItem> items = new ArrayList<>();
        JsonNode files = manifest.get("files");
        if (files != null && files.isArray()) {
            for (JsonNode file : files) {
                listener.throwIfCancelled();
                String projectId = String.valueOf(JsonUtils.longValue(file, "projectID", 0L));
                String fileId = String.valueOf(JsonUtils.longValue(file, "fileID", 0L));
                try {
                    ModFile resolved = curseForge.file(projectId, fileId).orElse(null);
                    if (resolved == null) {
                        log.warn("整合包内容不可用，已跳过：project={} file={}", projectId, fileId);
                        continue;
                    }
                    String url = curseForge.resolveDownloadUrl(resolved).orElse(null);
                    if (url == null) {
                        log.warn("整合包内容被作者禁止第三方下载，已跳过：{}", resolved.fileName());
                        continue;
                    }
                    String fileName = ModInstaller.sanitizeFileName(resolved.fileName());
                    if (fileName == null) {
                        continue;
                    }
                    items.add(new DownloadItem(paths.modsDir().resolve(fileName), url,
                            resolved.sha1() == null ? "" : resolved.sha1(), resolved.size(),
                            DownloadTargetType.MOD_FILE, Map.of(), Map.of(), true));
                } catch (IOException e) {
                    log.warn("解析整合包内容失败（project={}）：{}", projectId, e.getMessage());
                }
            }
        }

        DownloadReport report = items.isEmpty()
                ? new DownloadReport(0, 0, 0, 0, 0L, 0L, List.of())
                : downloads.downloadAll(items, listener);

        listener.onStage("展开整合包覆盖文件");
        String overridesDir = JsonUtils.text(manifest, "overrides", "overrides");
        if (!overridesDir.endsWith("/")) {
            overridesDir += "/";
        }
        int overridden = extractOverrides(archive, paths.gameDir(), overridesDir);

        return new InstallResult(name, version, dependencies,
                report.downloaded(), overridden, report.bytes(), report.failed());
    }

    // ==================================================================

    /** 展开覆盖文件目录；目录不存在时返回 0。 */
    private int extractOverrides(Path archive, Path gameDir, String prefix) throws IOException {
        List<String> names = ZipUtils.listEntryNames(archive);
        boolean present = names.stream().anyMatch(name -> name.startsWith(prefix));
        if (!present) {
            return 0;
        }
        List<Path> written = ZipUtils.extractStrippingPrefix(archive, gameDir, prefix);
        log.info("已展开 {} 个整合包覆盖文件到 {}", written.size(), gameDir);
        return written.size();
    }

    /** 安全解析整合包内的相对路径，拒绝目录穿越。 */
    private static Path safeResolve(Path root, String relative) {
        try {
            return PathSafety.resolveInside(root, relative, false);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 整合包安装结果。
     *
     * @param name          整合包名称
     * @param version       整合包版本
     * @param dependencies  清单声明的依赖（含 minecraft / forge / fabric-loader / neoforge）
     * @param downloaded    下载的内容文件数
     * @param overridden    展开的覆盖文件数
     * @param bytes         传输字节数
     * @param failed        失败数
     */
    public record InstallResult(String name,
                                String version,
                                Map<String, String> dependencies,
                                int downloaded,
                                int overridden,
                                long bytes,
                                int failed) {

        public InstallResult {
            dependencies = Map.copyOf(new LinkedHashMap<>(dependencies));
        }

        /** 声明的 Minecraft 版本。 */
        public String gameVersion() {
            return dependencies.getOrDefault("minecraft", "");
        }

        /** 声明的加载器类型（空表示纯原版整合包）。 */
        public String loader() {
            if (dependencies.containsKey("fabric-loader")) {
                return "fabric";
            }
            if (dependencies.containsKey("quilt-loader")) {
                return "quilt";
            }
            if (dependencies.containsKey("neoforge")) {
                return "neoforge";
            }
            if (dependencies.containsKey("forge")) {
                return "forge";
            }
            return "";
        }

        /** 声明的加载器版本。 */
        public String loaderVersion() {
            String loader = loader();
            return loader.isBlank() ? "" : dependencies.getOrDefault(
                    "fabric".equals(loader) ? "fabric-loader" : loader + "-loader", "");
        }

        public String summary() {
            return name + "：" + gameVersion() + " " + (loader().isBlank() ? "原版" : loader())
                    + "，内容 " + downloaded + " 个，覆盖文件 " + overridden + " 个"
                    + (failed > 0 ? "，失败 " + failed : "");
        }
    }

    /** 便捷方法：把已下载的整合包归档解压到指定游戏目录（供手工导入使用）。 */
    public static void extractArchive(Path archive, Path targetDir) throws IOException {
        FileUtils.ensureDir(targetDir);
        ZipUtils.extractAll(archive, targetDir);
    }
}
