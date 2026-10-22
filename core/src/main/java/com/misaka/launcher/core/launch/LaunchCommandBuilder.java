package com.misaka.launcher.core.launch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.misaka.launcher.core.BuildInfo;
import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.meta.RuleEvaluator;
import com.misaka.launcher.core.model.MavenCoordinate;
import com.misaka.launcher.core.model.VersionDetail;
import com.misaka.launcher.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动命令构建。
 * <p>覆盖官方启动器的完整规则：</p>
 * <ul>
 *   <li>1.13+ 的结构化 {@code arguments}（含 {@code rules} 条件过滤）；</li>
 *   <li>1.12 及更早的 {@code minecraftArguments} 字符串；</li>
 *   <li>全部 {@code ${...}} 占位符替换；</li>
 *   <li>natives 目录、资源目录、类路径拼装；</li>
 *   <li>1.7–1.18 自动追加 Log4Shell 缓解参数。</li>
 * </ul>
 * <p>安全约定：accessToken 只出现在命令行中，日志与界面展示必须使用
 * {@link LaunchPlan#maskedCommandLine()}。</p>
 */
public final class LaunchCommandBuilder {

    private static final Logger log = LoggerFactory.getLogger(LaunchCommandBuilder.class);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    /** 需要追加 Log4Shell 缓解参数的版本区间（含端点）。 */
    private static final int LOG4J_MIN_MINOR = 7;
    private static final int LOG4J_MAX_MINOR = 18;

    private final String launcherBrand;
    private final String launcherVersion;

    public LaunchCommandBuilder(String launcherBrand, String launcherVersion) {
        this.launcherBrand = launcherBrand == null || launcherBrand.isBlank() ? "misaka" : launcherBrand;
        // 兜底不再是字面量：版本号只有 gradle.properties 一处置信（见 BuildInfo）
        this.launcherVersion = launcherVersion == null || launcherVersion.isBlank()
                ? BuildInfo.version() : launcherVersion;
    }

    /**
     * 构建启动计划。
     *
     * @param resolved   已合并继承链的版本
     * @param paths      游戏目录布局
     * @param options    启动选项
     * @param gameLogFile 游戏输出落盘位置；null 表示不落盘
     */
    public LaunchPlan build(ResolvedVersion resolved,
                            GamePaths paths,
                            LaunchOptions options,
                            Path gameLogFile) throws IOException {
        VersionDetail detail = resolved.detail();
        String versionId = resolved.versionId();

        if (options.javaExecutable() == null) {
            throw new IOException("未指定用于启动游戏的 Java 运行时");
        }
        if (!resolved.hasClientJar()) {
            throw new IOException("未找到客户端主 jar，请先完整安装版本 " + versionId);
        }

        Map<String, Boolean> features = RuleEvaluator.defaultFeatures();
        if (options.windowWidth() > 0 && options.windowHeight() > 0) {
            features.put("has_custom_resolution", true);
        }

        // ---- 类路径 ----
        List<Path> classpathEntries = new ArrayList<>();
        classpathEntries.add(resolved.clientJar());
        for (VersionDetail.Library library : detail.libraries) {
            if (library == null || library.name == null || library.name.isBlank()) {
                continue;
            }
            if (!RuleEvaluator.isAllowed(library.rules, features)) {
                continue;
            }
            String relative = libraryRelativePath(library);
            if (relative == null) {
                continue;
            }
            Path file = paths.libraryFile(relative);
            classpathEntries.add(file);
            if (!Files.isRegularFile(file)) {
                log.warn("类路径中的运行库缺失（可能导致启动失败）: {}", file);
            }
        }
        List<String> classpath = classpathEntries.stream().map(Path::toString).toList();
        String classpathString = String.join(File.pathSeparator, classpath);

        // ---- 目录 ----
        Path nativesDir = paths.nativesDir(versionId);
        Path assetsRoot = paths.assetsDir();
        String assetIndexId = detail.assetIndexId();
        Path gameAssets = assetIndexId == null || assetIndexId.isBlank()
                ? assetsRoot : paths.virtualAssetsDir(assetIndexId);

        // ---- 占位符 ----
        Map<String, String> placeholders = buildPlaceholders(options, versionId, detail, paths,
                classpathString, nativesDir, assetsRoot, assetIndexId, gameAssets);

        // ---- JVM 参数 ----
        List<String> jvmArgs = new ArrayList<>();
        jvmArgs.add("-Xms" + options.minMemoryMb() + "m");
        jvmArgs.add("-Xmx" + options.maxMemoryMb() + "m");

        boolean modernArguments = detail.arguments != null && !detail.arguments.jvm.isEmpty();
        if (modernArguments) {
            jvmArgs.addAll(flatten(detail.arguments.jvm, features));
        } else {
            // 旧版本没有结构化 jvm 参数，需要自行补齐这两项（否则找不到本地库与游戏类）
            jvmArgs.add("-Djava.library.path=" + nativesDir);
            jvmArgs.add("-cp");
            jvmArgs.add(classpathString);
            jvmArgs.add("-Dminecraft.launcher.brand=" + launcherBrand);
            jvmArgs.add("-Dminecraft.launcher.version=" + launcherVersion);
        }

        if (needsLog4jMitigation(versionId)) {
            jvmArgs.add("-Dlog4j2.formatMsgNoLookups=true");
        }
        jvmArgs.addAll(options.extraJvmArgs());

        // ---- 游戏参数 ----
        List<String> gameArgs = new ArrayList<>();
        if (detail.arguments != null && !detail.arguments.game.isEmpty()) {
            gameArgs.addAll(flatten(detail.arguments.game, features));
        } else if (detail.minecraftArguments != null && !detail.minecraftArguments.isBlank()) {
            gameArgs.addAll(splitArguments(detail.minecraftArguments));
        }
        gameArgs.addAll(options.extraGameArgs());
        if (options.fullscreen() && !gameArgs.contains("--fullscreen")) {
            gameArgs.add("--fullscreen");
        }

        // ---- 组装并替换占位符 ----
        Set<String> unknown = new LinkedHashSet<>();
        String mainClass = detail.mainClass == null || detail.mainClass.isBlank()
                ? "net.minecraft.client.main.Main" : detail.mainClass;

        List<String> command = new ArrayList<>();
        command.add(options.javaExecutable().toString());
        command.addAll(substitute(jvmArgs, placeholders, unknown));
        command.add(mainClass);
        command.addAll(substitute(gameArgs, placeholders, unknown));

        if (!unknown.isEmpty()) {
            log.warn("以下启动占位符未被识别，已替换为空串：{}", unknown);
        }

        return new LaunchPlan(versionId, paths.gameDir(), options.javaExecutable(), command,
                classpath, nativesDir, gameLogFile, Map.of(), options.maxMemoryMb());
    }

    // ------------------------------------------------------------------
    // 占位符
    // ------------------------------------------------------------------

    private Map<String, String> buildPlaceholders(LaunchOptions options,
                                                  String versionId,
                                                  VersionDetail detail,
                                                  GamePaths paths,
                                                  String classpathString,
                                                  Path nativesDir,
                                                  Path assetsRoot,
                                                  String assetIndexId,
                                                  Path gameAssets) {
        // 没有账户就没有命令行：这里不再兜底造一个离线身份，
        // 否则「未登录」会静默变成「以 Player 进入游戏」
        AccountInfo account = options.account();
        if (account == null || account.accessToken() == null || account.accessToken().isBlank()) {
            throw new IllegalStateException(I18n.t("account.microsoft.required"));
        }

        Map<String, String> map = new LinkedHashMap<>();
        map.put("auth_player_name", account.username());
        map.put("version_name", versionId);
        map.put("game_directory", paths.gameDir().toString());
        map.put("assets_root", assetsRoot.toString());
        map.put("assets_index_name", assetIndexId == null ? "" : assetIndexId);
        map.put("auth_uuid", account.uuidWithoutDashes());
        map.put("auth_access_token", account.accessToken());
        // 旧版本用 auth_session 做登录校验，格式为 token:<accessToken>:<uuid>
        map.put("auth_session", "token:" + account.accessToken() + ":" + account.uuidWithoutDashes());
        map.put("user_type", gameUserType(account));
        map.put("version_type", detail.type == null || detail.type.isBlank() ? "release" : detail.type);
        map.put("natives_directory", nativesDir.toString());
        // 模组加载器的 BootstrapLauncher（Forge / NeoForge 1.13+）依赖这两个：
        // ${library_directory} 是运行库根目录、${classpath_separator} 是平台路径分隔符。
        // 官方启动器都会提供；缺失时它们会被替换成空串，模组路径会因此糊成一团，
        // 表现为「进程启动后立刻退出（退出码 1）」，而且日志里只有一行
        // InaccessibleObjectException，完全看不出是占位符的锅。
        map.put("library_directory", paths.librariesDir().toString());
        map.put("classpath_separator", File.pathSeparator);
        map.put("launcher_name", launcherBrand);
        map.put("launcher_version", launcherVersion);
        map.put("classpath", classpathString);
        map.put("resolution_width", String.valueOf(options.windowWidth()));
        map.put("resolution_height", String.valueOf(options.windowHeight()));
        // 1.6 及更早使用该占位符指向虚拟资源目录
        map.put("game_assets", gameAssets.toString());
        // 用户属性（客户端皮肤之外的附加信息）本项目不用，恒为空对象
        map.put("user_properties", "{}");
        map.put("auth_xuid", account.xuid() == null ? "" : account.xuid());
        map.put("clientid", account.clientId() == null ? "" : account.clientId());
        return map;
    }

    /**
     * 游戏可识别的用户类型。
     * <p>唯一支持的账户类型是微软正版，因此恒为 {@code msa}：客户端据此走官方皮肤与
     * 会话校验路径。写成 {@code legacy} 会让游戏按旧版行为处理令牌。</p>
     */
    private static String gameUserType(AccountInfo account) {
        return "msa";
    }

    private List<String> substitute(List<String> args, Map<String, String> placeholders, Set<String> unknown) {
        List<String> result = new ArrayList<>(args.size());
        for (String arg : args) {
            if (arg == null || arg.indexOf('$') < 0) {
                result.add(arg == null ? "" : arg);
                continue;
            }
            Matcher matcher = PLACEHOLDER.matcher(arg);
            StringBuilder builder = new StringBuilder();
            while (matcher.find()) {
                String key = matcher.group(1);
                String value = placeholders.get(key);
                if (value == null) {
                    unknown.add(key);
                    value = "";
                }
                matcher.appendReplacement(builder, Matcher.quoteReplacement(value));
            }
            matcher.appendTail(builder);
            result.add(builder.toString());
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 参数展开
    // ------------------------------------------------------------------

    /**
     * 展开结构化参数数组。
     * <p>元素可能是纯字符串，也可能是 {@code {"rules": [...], "value": "x" | ["x","y"]}} 形式；
     * 后者需先按平台与特性规则判断是否保留。</p>
     */
    private List<String> flatten(List<JsonNode> elements, Map<String, Boolean> features) {
        List<String> result = new ArrayList<>();
        if (elements == null) {
            return result;
        }
        for (JsonNode element : elements) {
            if (element == null || element.isNull()) {
                continue;
            }
            if (element.isTextual()) {
                result.add(element.asText());
                continue;
            }
            if (!element.isObject()) {
                continue;
            }
            JsonNode rules = element.get("rules");
            if (rules != null && rules.isArray() && !rules.isEmpty()) {
                if (!rulesAllowed(rules, features)) {
                    continue;
                }
            }
            JsonNode value = element.get("value");
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isArray()) {
                value.forEach(item -> result.add(item.asText()));
            } else {
                result.add(value.asText());
            }
        }
        return result;
    }

    /** 把 JSON 规则转换为强类型后复用 {@link RuleEvaluator}，保证与库过滤逻辑完全一致。 */
    private boolean rulesAllowed(JsonNode rules, Map<String, Boolean> features) {
        try {
            List<VersionDetail.Rule> parsed = JsonUtils.mapper()
                    .convertValue(rules, new TypeReference<List<VersionDetail.Rule>>() {
                    });
            return RuleEvaluator.isAllowed(parsed, features);
        } catch (RuntimeException e) {
            log.warn("解析参数规则失败，按「不保留」处理：{}", e.getMessage());
            return false;
        }
    }

    /** 按空白拆分旧式参数字符串，同时尊重引号。 */
    static List<String> splitArguments(String arguments) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < arguments.length(); i++) {
            char c = arguments.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (Character.isWhitespace(c) && !inQuotes) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    /** 运行库在 libraries 目录下的相对路径。 */
    private static String libraryRelativePath(VersionDetail.Library library) {
        if (library.downloads != null) {
            VersionDetail.DownloadInfo artifact = library.downloads.usableArtifact();
            if (artifact != null && artifact.path != null && !artifact.path.isBlank()) {
                return artifact.path;
            }
        }
        try {
            return MavenCoordinate.parse(library.name).toPath();
        } catch (IllegalArgumentException e) {
            log.debug("无法解析运行库坐标，已跳过: {}", library.name);
            return null;
        }
    }

    /**
     * 是否需要 Log4Shell 缓解参数。
     * <p>仅 1.7–1.18 使用受影响的 Log4j2 版本；更高版本已修复，追加无意义。</p>
     */
    static boolean needsLog4jMitigation(String versionId) {
        if (versionId == null) {
            return false;
        }
        String[] parts = versionId.split("[.-]");
        if (parts.length < 2 || !"1".equals(parts[0])) {
            return false;
        }
        try {
            int minor = Integer.parseInt(parts[1]);
            return minor >= LOG4J_MIN_MINOR && minor <= LOG4J_MAX_MINOR;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
