package com.misaka.launcher.core.sync;

import com.misaka.launcher.core.util.HashUtils;
import com.misaka.launcher.core.util.PathSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 同步计划器：<b>1:1 复刻</b> FileSync 客户端
 * （{@code ServerSyncClientApp.py} 的 {@code FileScanner.scan_local_files} 与
 * {@code _check_file_status_manual}）的判定语义。
 *
 * <p>复刻要点（任何偏离都会导致与原工具行为不一致）：</p>
 * <ol>
 *   <li><b>协议键</b>：{@code "./" + 相对同步根的路径}，分隔符统一为正斜杠；</li>
 *   <li><b>目录规范化</b>：反斜杠转正斜杠并去掉<b>全部</b>尾部斜杠；</li>
 *   <li><b>硬同步前缀推导</b>：只对服务端 {@code mode == hard} 的目录生效；
 *       先按重定向规则改写目录路径，<b>再</b>判断改写结果落在哪个客户端目录内，
 *       落中的客户端目录整体被视为硬同步；</li>
 *   <li><b>服务端文件映射</b>：先对 {@code real_path} 施加重定向，
 *       再要求结果以某个客户端目录<b>加斜杠</b>开头（不匹配「等于目录」的情况）；</li>
 *   <li><b>状态判定</b>：哈希相同 → 已最新；哈希不同 → 需更新；本地有服务端无 →
 *       命中硬前缀则需删除，否则按软同步语义<b>保留</b>；服务端有本地无 → 需下载；</li>
 *   <li><b>执行顺序</b>：先删除、后下载。</li>
 * </ol>
 */
public final class SyncPlanner {

    private static final Logger log = LoggerFactory.getLogger(SyncPlanner.class);

    /** 下载类状态对应的键前缀（{@code "./"}）。 */
    private static final String KEY_PREFIX = "./";

    private SyncPlanner() {
    }

    // ==================================================================
    // 本地扫描
    // ==================================================================

    /**
     * 扫描同步根目录下的受管文件。
     *
     * @param syncRoot      同步根目录（实例的游戏目录）
     * @param directories   客户端配置的目录列表，形如 {@code ./mods/}
     * @param ignoredFiles  按文件名忽略的清单
     * @return 协议键 → 文件信息
     */
    public static Map<String, LocalFileInfo> scanLocal(Path syncRoot,
                                                       List<String> directories,
                                                       List<String> ignoredFiles) throws IOException {
        Map<String, LocalFileInfo> result = new LinkedHashMap<>();
        Path root = syncRoot.toAbsolutePath().normalize();
        Set<String> ignored = ignoredFiles == null ? Set.of() : new LinkedHashSet<>(ignoredFiles);

        for (String directory : directories) {
            if (directory == null || directory.isBlank()) {
                continue;
            }
            Path dir;
            try {
                dir = PathSafety.resolveInside(root, directory, false);
            } catch (IOException e) {
                log.warn("跳过非法同步目录 {}: {}", directory, e.getMessage());
                continue;
            }
            if (!Files.exists(dir)) {
                // 与原客户端一致：目录不存在则自动创建，避免首次同步直接失败
                Files.createDirectories(dir);
                continue;
            }
            if (!Files.isDirectory(dir)) {
                log.warn("同步目录不是目录，已跳过: {}", dir);
                continue;
            }

            try (var stream = Files.walk(dir)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    String fileName = file.getFileName().toString();
                    if (ignored.contains(fileName)) {
                        continue;
                    }
                    String relative = root.relativize(file.toAbsolutePath().normalize())
                            .toString().replace('\\', '/');
                    String fileKey = KEY_PREFIX + relative;
                    result.put(fileKey, new LocalFileInfo(fileKey, file,
                            sizeOf(file), lastModified(file), HashUtils.md5(file)));
                }
            }
        }
        return result;
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    // ==================================================================
    // 计划
    // ==================================================================

    /**
     * 生成同步计划。
     *
     * @param syncRoot          同步根目录
     * @param clientDirectories 客户端配置的目录列表
     * @param serverFiles       {@code /api/file_list} 结果
     * @param serverConfig      {@code /api/config} 结果
     * @param localFiles        本地扫描结果
     * @param allowDeleteLocal  为 false 时仅识别出多余文件但<b>不</b>产生删除动作，
     *                          便于用户以「只增不减」的方式安全试用
     */
    public static SyncPlan plan(Path syncRoot,
                                List<String> clientDirectories,
                                List<ServerFileInfo> serverFiles,
                                ServerSyncConfig serverConfig,
                                Map<String, LocalFileInfo> localFiles,
                                boolean allowDeleteLocal) throws IOException {

        Path root = syncRoot.toAbsolutePath().normalize();
        Map<String, String> redirectionMap = buildRedirectionMap(serverConfig);
        Set<String> hardPrefixes = resolveHardPrefixes(clientDirectories, serverConfig, redirectionMap);
        Map<String, ServerFileInfo> serverFileMap = mapServerFiles(clientDirectories, serverFiles, redirectionMap);

        Map<String, SyncStatus> statuses = new LinkedHashMap<>();

        // 1) 本地文件的判定
        for (Map.Entry<String, LocalFileInfo> entry : localFiles.entrySet()) {
            String fileKey = entry.getKey();
            ServerFileInfo serverInfo = serverFileMap.get(fileKey);
            if (serverInfo != null) {
                statuses.put(fileKey, entry.getValue().md5().equalsIgnoreCase(serverInfo.hash)
                        ? SyncStatus.UP_TO_DATE : SyncStatus.NEED_UPDATE);
                continue;
            }
            statuses.put(fileKey, isUnderHardPrefix(fileKey, hardPrefixes)
                    ? SyncStatus.NEED_DELETE : SyncStatus.KEPT);
        }

        // 2) 服务端存在而本地缺失的判定
        for (String fileKey : serverFileMap.keySet()) {
            if (!localFiles.containsKey(fileKey)) {
                statuses.put(fileKey, SyncStatus.NEED_DOWNLOAD);
            }
        }

        // 3) 生成动作
        List<String> toDelete = new ArrayList<>();
        List<SyncDownload> toDownload = new ArrayList<>();
        int upToDateCount = 0;

        for (Map.Entry<String, SyncStatus> entry : statuses.entrySet()) {
            String fileKey = entry.getKey();
            SyncStatus status = entry.getValue();
            switch (status) {
                case NEED_DELETE -> {
                    if (allowDeleteLocal) {
                        toDelete.add(fileKey);
                    }
                }
                case NEED_DOWNLOAD, NEED_UPDATE -> {
                    ServerFileInfo serverInfo = serverFileMap.get(fileKey);
                    if (serverInfo == null) {
                        continue;
                    }
                    Path target = resolveTarget(root, fileKey);
                    toDownload.add(new SyncDownload(fileKey, serverInfo.downloadPath(), target,
                            serverInfo.hash, serverInfo.size));
                }
                default -> upToDateCount++;
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("同步计划：共 {} 项，删除 {}，下载 {}，已最新 {}，硬同步目录 {}",
                    statuses.size(), toDelete.size(), toDownload.size(), upToDateCount, hardPrefixes);
        }
        return new SyncPlan(statuses, toDelete, toDownload, upToDateCount,
                new ArrayList<>(hardPrefixes), redirectionMap);
    }

    // ==================================================================
    // 内部步骤（可单独测试）
    // ==================================================================

    /** 构造重定向映射：{@code from → to}，两者均已规范化。 */
    static Map<String, String> buildRedirectionMap(ServerSyncConfig config) {
        Map<String, String> map = new LinkedHashMap<>();
        if (config == null || config.redirections == null) {
            return map;
        }
        for (ServerSyncConfig.Redirection redirection : config.redirections) {
            if (redirection == null) {
                continue;
            }
            String from = normalize(redirection.from);
            String to = normalize(redirection.to);
            if (!from.isEmpty()) {
                map.put(from, to);
            }
        }
        return map;
    }

    /**
     * 推导客户端侧的硬同步目录前缀。
     * <p>这是原客户端最容易出错、也最容易产生「误删」的一步，因此严格照搬其顺序：
     * 先重定向，再匹配客户端目录。</p>
     */
    static Set<String> resolveHardPrefixes(List<String> clientDirectories,
                                           ServerSyncConfig config,
                                           Map<String, String> redirectionMap) {
        Set<String> prefixes = new LinkedHashSet<>();
        if (config == null || config.directories == null) {
            return prefixes;
        }
        for (ServerSyncConfig.SyncDirectory directory : config.directories) {
            if (directory == null || !directory.isHard()) {
                continue;
            }
            String dirPath = normalize(directory.path);
            String redirected = applyRedirectToDirectory(dirPath, redirectionMap);
            for (String clientDir : clientDirectories) {
                String normalizedClientDir = normalize(clientDir);
                if (redirected.startsWith(normalizedClientDir + "/") || redirected.equals(normalizedClientDir)) {
                    prefixes.add(normalizedClientDir);
                }
            }
        }
        return prefixes;
    }

    /** 对「目录路径」应用重定向：命中即替换前缀并终止匹配。 */
    private static String applyRedirectToDirectory(String dirPath, Map<String, String> redirectionMap) {
        for (Map.Entry<String, String> rule : redirectionMap.entrySet()) {
            String from = rule.getKey();
            if (dirPath.startsWith(from + "/") || dirPath.equals(from)) {
                String relative = stripLeadingSlashes(dirPath.substring(from.length()));
                return relative.isEmpty() ? rule.getValue() : rule.getValue() + "/" + relative;
            }
        }
        return dirPath;
    }

    /**
     * 构造服务端文件映射。
     * <p>顺序同样是「先重定向 real_path，再匹配客户端目录」，且匹配要求以
     * {@code 客户端目录/} 开头（等于目录本身不算）。</p>
     */
    static Map<String, ServerFileInfo> mapServerFiles(List<String> clientDirectories,
                                                      List<ServerFileInfo> serverFiles,
                                                      Map<String, String> redirectionMap) {
        Map<String, ServerFileInfo> map = new LinkedHashMap<>();
        if (serverFiles == null) {
            return map;
        }
        for (ServerFileInfo info : serverFiles) {
            if (info == null) {
                continue;
            }
            String normalizedRelative = KEY_PREFIX + info.effectiveRealPath().replace('\\', '/');
            String redirected = normalizedRelative;
            for (Map.Entry<String, String> rule : redirectionMap.entrySet()) {
                if (normalizedRelative.startsWith(rule.getKey() + "/")) {
                    String after = stripLeadingSlashes(normalizedRelative.substring(rule.getKey().length()));
                    redirected = rule.getValue() + "/" + after;
                    break;
                }
            }
            for (String clientDir : clientDirectories) {
                String normalizedClientDir = normalize(clientDir);
                if (redirected.startsWith(normalizedClientDir + "/")) {
                    map.put(redirected, info);
                    break;
                }
            }
        }
        return map;
    }

    /** 文件键是否命中任一硬同步前缀。 */
    static boolean isUnderHardPrefix(String fileKey, Set<String> hardPrefixes) {
        for (String prefix : hardPrefixes) {
            if (fileKey.startsWith(prefix + "/") || fileKey.equals(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 由协议键得到实际落盘路径，并做越界校验。 */
    static Path resolveTarget(Path syncRoot, String fileKey) throws IOException {
        String relative = fileKey.startsWith(KEY_PREFIX) ? fileKey.substring(KEY_PREFIX.length()) : fileKey;
        return PathSafety.resolveInside(syncRoot, relative, false);
    }

    /** 与 Python 的 {@code .replace('\\','/').rstrip('/')} 等价。 */
    static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String value = path.replace('\\', '/');
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static String stripLeadingSlashes(String value) {
        int start = 0;
        while (start < value.length() && value.charAt(start) == '/') {
            start++;
        }
        return value.substring(start);
    }
}
