package com.misaka.launcher.core.install;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.misaka.launcher.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Forge / NeoForge 安装器内的档案解析。
 *
 * <p>安装器 jar 里有两份关键 JSON：</p>
 * <ul>
 *   <li>{@code install_profile.json}：本次安装要用的运行库、processors 与 data 映射；</li>
 *   <li>{@code version.json}：安装完成后写入游戏目录的版本描述（其 libraries 也要就位）。</li>
 * </ul>
 *
 * <p>本类只负责把它们声明的<b>可下载构件</b>提取成「maven 相对路径 + 地址 + 哈希」，
 * 供调用方用国内镜像预置 —— 安装器自己只会去官方 maven 取，而那个域名在国内经常完全不可达。</p>
 */
public final class InstallerProfile {

    private static final Logger log = LoggerFactory.getLogger(InstallerProfile.class);

    /** data / 坐标里的 {@code [group:artifact:version:classifier@ext]} 引用。 */
    private static final Pattern MAVEN_REFERENCE = Pattern.compile("\\[([^\\[\\]]+)]");

    /** 处理器参数里的 {@code {TOKEN}} 占位符（完整匹配才算）。 */
    private static final Pattern TOKEN_REFERENCE = Pattern.compile("^\\{([A-Za-z0-9_]+)}$");

    /** 后处理里唯一需要联网的任务：下载 Mojang 官方映射。 */
    private static final String TASK_DOWNLOAD_MOJMAPS = "DOWNLOAD_MOJMAPS";

    /** installertools 提供「输出已存在就不下载」的开关，用来让上一步完全离线。 */
    private static final String ARG_SKIP_IF_EXISTS = "--skipIfExists";

    /** {@code ARG_SKIP_IF_EXISTS} 在 installertools 里的实现位置。 */
    private static final String MOJMAPS_CLASS_SUFFIX = "DownloadMojmaps.class";

    /**
     * 一个待预置的构件。
     *
     * @param path 相对 {@code <gameDir>/libraries} 的路径
     * @param url  构件自带的下载地址；可能为空（只能按 maven 坐标推路径）
     * @param sha1 期望的 SHA-1；可能为空（不校验）
     * @param size 期望字节数；未知为 -1
     */
    public record Artifact(String path, String url, String sha1, long size) {

        /** 是否带可用的官方地址。 */
        public boolean hasUrl() {
            return url != null && !url.isBlank();
        }
    }

    private InstallerProfile() {
    }

    /**
     * 从安装器 jar 中提取全部待预置构件（按路径去重，后者不覆盖已有的带地址条目）。
     */
    public static List<Artifact> artifacts(Path installerJar) throws IOException {
        try (ZipFile zip = new ZipFile(installerJar.toFile())) {
            byte[] profile = readEntry(zip, "install_profile.json");
            byte[] version = readEntry(zip, "version.json");
            if (profile == null && version == null) {
                throw new IOException("安装器内没有 install_profile.json / version.json：" + installerJar);
            }
            return parse(profile, version);
        }
    }

    /**
     * 解析两份 JSON，提取构件清单。
     *
     * @param installProfile {@code install_profile.json} 内容；可为 null
     * @param versionJson    {@code version.json} 内容；可为 null
     */
    static List<Artifact> parse(byte[] installProfile, byte[] versionJson) throws IOException {
        Map<String, Artifact> merged = new LinkedHashMap<>();
        if (installProfile != null) {
            JsonNode root = JsonUtils.read(new String(installProfile, StandardCharsets.UTF_8));
            collectLibraries(root.get("libraries"), merged);
            collectData(root.get("data"), merged);
        }
        if (versionJson != null) {
            JsonNode root = JsonUtils.read(new String(versionJson, StandardCharsets.UTF_8));
            collectLibraries(root.get("libraries"), merged);
        }
        return List.copyOf(merged.values());
    }

    // ------------------------------------------------------------------
    // 后处理：Mojang 映射（唯一需要联网的一步）
    // ------------------------------------------------------------------

    /**
     * 安装器后处理里的一次 {@code DownloadMojmaps} 需求。
     *
     * @param mcVersion        {@code --version}，要下载映射的游戏版本
     * @param side             {@code --side}；客户端安装为 {@code client}
     * @param outputMavenPath  输出文件相对 {@code libraries} 的路径
     * @param processorJarPath 处理器实现所在 jar 相对 {@code libraries} 的路径；解析不出时为空串
     */
    public record MojmapsTask(String mcVersion, String side, String outputMavenPath, String processorJarPath) {
    }

    /**
     * 读取安装器内的 {@code install_profile.json} 原文。
     *
     * @return 原文；安装器里没有该条目时返回 null
     */
    public static byte[] rawInstallProfile(Path installerJar) throws IOException {
        try (ZipFile zip = new ZipFile(installerJar.toFile())) {
            return readEntry(zip, "install_profile.json");
        }
    }

    /**
     * 找出档案里所有会在客户端安装中执行的 {@code DOWNLOAD_MOJMAPS} 处理器。
     *
     * <p>输出路径解析不出来（非 {@code {TOKEN}} 形式、或 data 里没有对应坐标）的处理器会被跳过：
     * 调用方宁可退回「让安装器自己联网」，也不能给出一个指向错误位置的文件。</p>
     */
    public static List<MojmapsTask> mojmapsTasks(byte[] installProfile) throws IOException {
        if (installProfile == null) {
            return List.of();
        }
        JsonNode root = JsonUtils.read(new String(installProfile, StandardCharsets.UTF_8));
        JsonNode data = root.get("data");
        List<MojmapsTask> tasks = new ArrayList<>();
        for (JsonNode processor : arrayOf(root, "processors")) {
            if (!runsOnClient(processor)) {
                continue;
            }
            JsonNode args = processor.get("args");
            if (!isTask(args, TASK_DOWNLOAD_MOJMAPS)) {
                continue;
            }
            String output = tokenTarget(data, argValue(args, "--output"));
            if (output.isBlank()) {
                continue;
            }
            tasks.add(new MojmapsTask(argValue(args, "--version"), normalizeSide(argValue(args, "--side")), output,
                    mavenPath(JsonUtils.text(processor, "jar", ""))));
        }
        return List.copyOf(tasks);
    }

    /**
     * 给所有 {@code DOWNLOAD_MOJMAPS} 处理器补上 {@code --skipIfExists}，
     * 使安装器在输出文件已就位时不再联网下载。
     *
     * <p>改写的是档案副本，由调用方以类路径覆盖的方式喂给安装器 ——
     * 安装器通过 {@code Util.class.getResourceAsStream("/install_profile.json")} 读取它，
     * 类路径靠前者优先命中，因此<b>不必改动已下载的安装器 jar</b>。</p>
     *
     * @return 改写后的档案；没有可改写的处理器时返回空
     */
    public static Optional<byte[]> withMojmapsSkip(byte[] installProfile) throws IOException {
        if (installProfile == null) {
            return Optional.empty();
        }
        JsonNode root = JsonUtils.read(new String(installProfile, StandardCharsets.UTF_8));
        boolean changed = false;
        for (JsonNode processor : arrayOf(root, "processors")) {
            JsonNode args = processor.get("args");
            if (!isTask(args, TASK_DOWNLOAD_MOJMAPS) || containsArg(args, ARG_SKIP_IF_EXISTS)) {
                continue;
            }
            ((ArrayNode) args).add(ARG_SKIP_IF_EXISTS);
            changed = true;
        }
        return changed ? Optional.of(JsonUtils.writePrettyBytes(root)) : Optional.empty();
    }

    /**
     * 判断处理器的 installertools 是否认识 {@code --skipIfExists}。
     *
     * <p>必须问清楚再注入：老版本安装器不支持该开关，joptsimple 会直接以
     * {@code UnrecognizedOptionException} 让处理器失败 —— 那就从「可能装不上」
     * 变成「必然装不上」了。这里直接读处理器类文件的常量池，
     * 不依赖版本号猜测。</p>
     *
     * @return {@code TRUE} / {@code FALSE} 为确定结论；jar 缺失或读不出时返回 {@code null}（未知）
     */
    public static Boolean supportsSkipIfExists(Path installertoolsJar) {
        if (installertoolsJar == null || !Files.isRegularFile(installertoolsJar)) {
            return null;
        }
        try (ZipFile zip = new ZipFile(installertoolsJar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(MOJMAPS_CLASS_SUFFIX)) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    // ISO-8859-1 是「字节透明」的解码：常量池里的 UTF-8 字面量一定能被逐字节还原
                    return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1)
                            .contains(ARG_SKIP_IF_EXISTS.substring(2));
                }
            }
            return Boolean.FALSE;
        } catch (IOException e) {
            log.debug("无法确认 installertools 是否支持 {}（{}）: {}",
                    ARG_SKIP_IF_EXISTS, installertoolsJar, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** {@code libraries[]}：优先用 downloads 里的 path/url/sha1，缺失时按坐标推路径。 */
    private static void collectLibraries(JsonNode libraries, Map<String, Artifact> target) {
        if (libraries == null || !libraries.isArray()) {
            return;
        }
        for (JsonNode library : libraries) {
            JsonNode downloads = library.get("downloads");
            boolean collected = false;
            if (downloads != null) {
                collected |= collectArtifact(downloads.get("artifact"), target);
                JsonNode classifiers = downloads.get("classifiers");
                if (classifiers != null && classifiers.isObject()) {
                    for (JsonNode classifier : classifiers) {
                        collected |= collectArtifact(classifier, target);
                    }
                }
            }
            if (!collected) {
                // 老格式（或精简声明）没有 downloads：只能按 maven 坐标推路径
                String path = mavenPath(JsonUtils.text(library, "name", ""));
                if (!path.isBlank()) {
                    put(target, new Artifact(path, null, "", -1L));
                }
            }
        }
    }

    /** data 里出现的 {@code [group:artifact:...]} 引用也要就位（processors 会用到）。 */
    private static void collectData(JsonNode data, Map<String, Artifact> target) {
        if (data == null || !data.isObject()) {
            return;
        }
        for (JsonNode entry : data) {
            for (String side : List.of("client", "server")) {
                String value = JsonUtils.text(entry, side, "");
                Matcher matcher = MAVEN_REFERENCE.matcher(value);
                while (matcher.find()) {
                    String path = mavenPath(matcher.group(1));
                    if (!path.isBlank()) {
                        put(target, new Artifact(path, null, "", -1L));
                    }
                }
            }
        }
    }

    private static boolean collectArtifact(JsonNode node, Map<String, Artifact> target) {
        if (node == null || node.isNull()) {
            return false;
        }
        String path = JsonUtils.text(node, "path", "");
        if (path.isBlank()) {
            return false;
        }
        put(target, new Artifact(path, JsonUtils.text(node, "url", ""),
                JsonUtils.text(node, "sha1", ""), JsonUtils.longValue(node, "size", -1L)));
        return true;
    }

    /** 同名路径只保留一份，但带地址的那份优先（地址更可靠，哈希也更完整）。 */
    private static void put(Map<String, Artifact> target, Artifact artifact) {
        Artifact existing = target.get(artifact.path());
        if (existing == null || (!existing.hasUrl() && artifact.hasUrl())) {
            target.put(artifact.path(), artifact);
        }
    }

    /**
     * maven 坐标 → 相对路径。
     *
     * <p>支持 {@code group:artifact:version}、{@code group:artifact:version:classifier}
     * 与 {@code ...@extension} 三种后缀形式，例如
     * {@code de.oceanlabs.mcp:mcp_config:1.20.1-20230612.114412:mappings@txt}。</p>
     *
     * @return 相对路径；无法解析时返回空串
     */
    static String mavenPath(String coordinates) {
        if (coordinates == null || coordinates.isBlank()) {
            return "";
        }
        String value = coordinates.trim();
        String extension = "jar";
        int at = value.indexOf('@');
        if (at >= 0) {
            extension = value.substring(at + 1).trim();
            value = value.substring(0, at);
        }
        String[] parts = value.split(":");
        if (parts.length < 3) {
            return "";
        }
        String group = parts[0].trim();
        String artifact = parts[1].trim();
        String version = parts[2].trim();
        String classifier = parts.length >= 4 && !parts[3].isBlank() ? "-" + parts[3].trim() : "";
        if (group.isEmpty() || artifact.isEmpty() || version.isEmpty()) {
            return "";
        }
        String fileName = artifact + "-" + version + classifier + "." + extension;
        return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + fileName;
    }

    private static byte[] readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    /** 供日志/诊断：构件数量摘要。 */
    public static int count(List<Artifact> artifacts) {
        return artifacts == null ? 0 : artifacts.size();
    }

    /** 便于排查：把构件路径列成一行。 */
    public static String describe(List<Artifact> artifacts) {
        List<String> paths = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            paths.add(artifact.path());
        }
        log.debug("安装器构件：{}", paths);
        return String.join(", ", paths);
    }

    // ------------------------------------------------------------------
    // JSON 取值辅助
    // ------------------------------------------------------------------

    private static List<JsonNode> arrayOf(JsonNode root, String field) {
        JsonNode node = root == null ? null : root.get(field);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> items = new ArrayList<>();
        node.forEach(items::add);
        return items;
    }

    /**
     * 该处理器是否会在客户端安装中执行。
     * <p>{@code sides} 缺失表示两侧都跑；显式声明时只认包含 {@code client} 的。</p>
     */
    private static boolean runsOnClient(JsonNode processor) {
        JsonNode sides = processor.get("sides");
        if (sides == null || !sides.isArray() || sides.isEmpty()) {
            return true;
        }
        for (JsonNode side : sides) {
            if ("client".equalsIgnoreCase(side.asText())) {
                return true;
            }
        }
        return false;
    }

    /** 参数数组是否为指定 task（形如 {@code --task <name>}）。 */
    private static boolean isTask(JsonNode args, String task) {
        return task.equals(argValue(args, "--task"));
    }

    /**
     * 取 {@code --name value} 形式参数的值。
     *
     * @return 值；参数不存在或没有跟随值时返回空串
     */
    private static String argValue(JsonNode args, String name) {
        if (args == null || !args.isArray()) {
            return "";
        }
        for (int i = 0; i + 1 < args.size(); i++) {
            if (name.equals(args.get(i).asText())) {
                return args.get(i + 1).asText();
            }
        }
        return "";
    }

    /** 参数数组里是否存在某个独立开关（例如 {@code --skipIfExists}）。 */
    private static boolean containsArg(JsonNode args, String flag) {
        if (args == null || !args.isArray()) {
            return false;
        }
        for (JsonNode arg : args) {
            if (flag.equals(arg.asText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 归一化 {@code --side}。
     * <p>档案里写的是 {@code {SIDE}} —— 由安装器在运行时替换（客户端为 {@code client}）。
     * 我们只执行 {@code --installClient}，因此未替换的占位符一律视为 {@code client}。</p>
     */
    private static String normalizeSide(String side) {
        return side == null || side.isBlank() || side.startsWith("{") ? "client" : side;
    }

    /**
     * 把 {@code {TOKEN}} 形式的输出参数解析成 {@code libraries} 下的相对路径。
     *
     * <p>只认完整匹配的 {@code {TOKEN}}：安装器允许参数里出现绝对路径，
     * 那种情况我们无法把它安全地映射到 libraries 布局，交给安装器自己处理。</p>
     */
    static String tokenTarget(JsonNode data, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return "";
        }
        Matcher token = TOKEN_REFERENCE.matcher(rawValue.trim());
        if (!token.matches()) {
            return "";
        }
        JsonNode entry = data == null ? null : data.get(token.group(1));
        if (entry == null || !entry.isObject()) {
            return "";
        }
        for (String side : List.of("client", "server")) {
            Matcher reference = MAVEN_REFERENCE.matcher(JsonUtils.text(entry, side, ""));
            if (reference.find()) {
                String path = mavenPath(reference.group(1));
                if (!path.isBlank()) {
                    return path;
                }
            }
        }
        return "";
    }
}
