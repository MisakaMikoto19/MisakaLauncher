package com.misaka.launcher.core.launch;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.util.JsonUtils;
import com.misaka.launcher.core.util.OsUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动命令构建验证。
 * <p>覆盖：结构化参数与旧式参数两条路径、rules 条件过滤、占位符替换、
 * 继承链合并后的类路径拼装，以及 Log4Shell 缓解参数的版本区间。</p>
 */
class LaunchCommandBuilderTest {

    @TempDir
    Path tempDir;

    private GamePaths paths;
    private LaunchCommandBuilder builder;

    @BeforeEach
    void setUp() throws IOException {
        paths = new GamePaths(tempDir.resolve("game"));
        paths.prepare();
        builder = new LaunchCommandBuilder("misaka-test", "9.9.9");
    }

    // ------------------------------------------------------------------
    // 结构化参数（1.13+）
    // ------------------------------------------------------------------

    @Test
    void structuredArgsShouldSubstituteAndFilter() throws Exception {
        writeModernVersion("1.20.1");
        touch(paths.versionJar("1.20.1"));

        LaunchPlan plan = build("1.20.1", 4096, 2048);

        List<String> command = plan.command();
        assertTrue(command.get(0).endsWith(javaExecutableName()), "首元素必须是 java: " + command.get(0));
        assertTrue(command.contains("-Xmx4096m"), "应包含最大堆参数");
        assertTrue(command.contains("-Xms2048m"), "应包含初始堆参数");
        assertTrue(command.contains("net.minecraft.client.main.Main"), "应包含主类");

        // 占位符必须全部被替换
        assertFalse(command.stream().anyMatch(arg -> arg.contains("${")),
                "不应残留未替换的占位符: " + command);

        assertTrue(command.contains("Tester"), "玩家名占位符应被替换");
        assertTrue(command.contains("msa"), "微软账户的 userType 应为 msa");
        assertTrue(command.contains("--version"), "版本参数应保留");
        assertTrue(command.contains("1.20.1"), "版本名占位符应被替换");

        // natives 与类路径
        assertTrue(command.stream().anyMatch(arg ->
                        arg.equals("-Djava.library.path=" + paths.nativesDir("1.20.1"))),
                "本地库路径应指向版本隔离目录");

        // 规则不匹配的参数应被剔除，匹配的应保留
        assertFalse(command.contains("--demo"), "规则不匹配的参数不应出现");
        assertTrue(command.contains("--current-os-only"), "规则匹配的参数应保留");

        // 类路径：客户端 jar 在前，运行库在后；被规则排除的运行库不出现
        assertTrue(plan.classpath().get(0).endsWith("1.20.1.jar"), "客户端 jar 应位于类路径首位");
        assertTrue(plan.classpath().stream().anyMatch(p -> p.endsWith("lib-1.0.jar")));
        assertFalse(plan.classpath().stream().anyMatch(p -> p.endsWith("skip-1.0.jar")),
                "规则不匹配的运行库不应进入类路径");

        // 1.20 不需要 Log4Shell 缓解参数
        assertFalse(command.contains("-Dlog4j2.formatMsgNoLookups=true"));
    }

    @Test
    void manualArgsShouldAppendAtEnd() throws Exception {
        writeModernVersion("1.20.1");
        touch(paths.versionJar("1.20.1"));

        LaunchOptions options = new LaunchOptions(
                new AccountInfo("Tester", "00000000000000000000000000000001", "token", AccountInfo.TYPE_MSA, "", ""),
                tempDir.resolve(javaExecutableName()), 2048, 4096,
                List.of("-XX:+UseG1GC", "-Dfile.encoding=UTF-8"),
                List.of("--fullscreen"), 1280, 720, true);

        ResolvedVersion resolved = VersionResolver.resolve(paths, "1.20.1");
        LaunchPlan plan = builder.build(resolved, paths, options, null);

        assertTrue(plan.command().contains("-XX:+UseG1GC"));
        assertTrue(plan.command().contains("-Dfile.encoding=UTF-8"));
        // 自定义分辨率开启后，has_custom_resolution 特性应生效
        assertTrue(plan.command().contains("--width"));
        assertTrue(plan.command().contains("1280"));
        assertTrue(plan.command().contains("--fullscreen"));
    }

    // ------------------------------------------------------------------
    // 旧式参数（1.12 及更早）
    // ------------------------------------------------------------------

    @Test
    void legacyArgStringShouldSplitAndGetJvmArgs() throws Exception {
        writeLegacyVersion("1.12.2");
        touch(paths.versionJar("1.12.2"));

        LaunchPlan plan = build("1.12.2", 2048, 1024);
        List<String> command = plan.command();

        assertFalse(command.stream().anyMatch(arg -> arg.contains("${")), "不应残留占位符");
        assertTrue(command.contains("Tester"));
        assertTrue(command.contains("--assetIndex"));

        // 旧版本没有结构化 jvm 参数，需要自行补齐
        assertTrue(command.stream().anyMatch(arg ->
                arg.equals("-Djava.library.path=" + paths.nativesDir("1.12.2"))));
        assertTrue(command.contains("-cp"), "旧版本应显式传入 -cp");
        assertTrue(command.stream().anyMatch(arg -> arg.startsWith("-Dminecraft.launcher.brand=")));
        assertTrue(command.stream().anyMatch(arg -> arg.startsWith("-Dminecraft.launcher.version=")));

        // 1.12.2 在受影响区间内
        assertTrue(command.contains("-Dlog4j2.formatMsgNoLookups=true"));
    }

    @Test
    void legacyArgSplitShouldRespectQuotes() {
        List<String> parts = LaunchCommandBuilder.splitArguments(
                "--username Name --title \"my world\" --flag  --empty");
        assertEquals(List.of("--username", "Name", "--title", "my world", "--flag", "--empty"), parts);
    }

    // ------------------------------------------------------------------
    // 继承链合并（加载器）
    // ------------------------------------------------------------------

    @Test
    void loaderVersionShouldMergeChainAndReuseClientJar() throws Exception {
        writeModernVersion("1.20.1");
        Path baseJar = touch(paths.versionJar("1.20.1"));

        ObjectNode profile = JsonUtils.newObject();
        profile.put("id", "fabric-loader-0.15.11-1.20.1");
        profile.put("inheritsFrom", "1.20.1");
        profile.put("type", "release");
        profile.put("mainClass", "net.fabricmc.loader.impl.launch.knot.KnotClient");
        ObjectNode arguments = profile.putObject("arguments");
        arguments.putArray("game");
        arguments.putArray("jvm").add("-DFabricMcEmu= net.minecraft.client.main.Main ");
        ArrayNode libraries = profile.putArray("libraries");
        libraries.addObject()
                .put("name", "org.ow2.asm:asm:9.6")
                .put("url", "https://maven.fabricmc.net/")
                .put("sha1", "00")
                .put("size", 1);
        writeVersion("fabric-loader-0.15.11-1.20.1", profile);

        // profile 目录不放 jar，应回退到原版的 jar
        assertFalse(Files.exists(paths.versionJar("fabric-loader-0.15.11-1.20.1")));

        ResolvedVersion resolved = VersionResolver.resolve(paths, "fabric-loader-0.15.11-1.20.1");
        assertEquals(List.of("fabric-loader-0.15.11-1.20.1", "1.20.1"), resolved.inheritanceChain());
        assertEquals(baseJar, resolved.clientJar(), "应复用原版客户端 jar");

        LaunchPlan plan = build("fabric-loader-0.15.11-1.20.1", 4096, 2048);

        // 主类与 jvm 参数来自 profile
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", resolved.detail().mainClass);
        assertTrue(plan.command().contains("net.fabricmc.loader.impl.launch.knot.KnotClient"),
                "主类应出现在命令中");
        assertTrue(plan.command().contains("-DFabricMcEmu= net.minecraft.client.main.Main "),
                "profile 的 jvm 参数应被合并进来");

        // 类路径：原版 jar + 原版运行库 + 加载器运行库
        assertTrue(plan.classpath().get(0).endsWith("1.20.1.jar"));
        assertTrue(plan.classpath().stream().anyMatch(p -> p.endsWith("lib-1.0.jar")), "应包含基础版运行库");
        assertTrue(plan.classpath().stream().anyMatch(p -> p.replace('\\', '/')
                .endsWith("org/ow2/asm/asm/9.6/asm-9.6.jar")), "应包含加载器运行库");

        // ${version_name} 必须指向加载器版本而不是原版
        assertTrue(plan.command().contains("fabric-loader-0.15.11-1.20.1"));
    }

    @Test
    void missingClientJarShouldFailClearly() throws Exception {
        writeModernVersion("1.20.1");

        IOException error = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> build("1.20.1", 2048, 1024));
        assertTrue(error.getMessage().contains("客户端主 jar"), error.getMessage());
    }

    @Test
    void Log4Shell缓解参数应仅作用于受影响版本() {
        assertTrue(LaunchCommandBuilder.needsLog4jMitigation("1.7.10"));
        assertTrue(LaunchCommandBuilder.needsLog4jMitigation("1.12.2"));
        assertTrue(LaunchCommandBuilder.needsLog4jMitigation("1.18.2"));
        assertTrue(LaunchCommandBuilder.needsLog4jMitigation("1.18.2-forge-40.2.0"));
        assertFalse(LaunchCommandBuilder.needsLog4jMitigation("1.19.2"));
        assertFalse(LaunchCommandBuilder.needsLog4jMitigation("1.20.1"));
        assertFalse(LaunchCommandBuilder.needsLog4jMitigation("1.6.4"));
        assertFalse(LaunchCommandBuilder.needsLog4jMitigation(null));
    }

    @Test
    void loaderPlaceholdersMustBeReplaced() throws Exception {
        // Forge / NeoForge（1.13+）用 BootstrapLauncher 启动，它的模块路径由这两个占位符拼成。
        // 缺失时它们会被替换成空串 —— 模块路径糊成一团，进程启动后立刻退出（退出码 1），
        // 而日志里只有一行 InaccessibleObjectException，完全看不出是占位符的问题。
        String id = "1.20.1-forge-47.4.26";
        writeModernVersion(id);
        touch(paths.versionJar(id));

        Path json = paths.versionJson(id);
        ObjectNode node = (ObjectNode) JsonUtils.read(json);
        ArrayNode jvm = (ArrayNode) node.get("arguments").get("jvm");
        jvm.add("-DlibraryDirectory=${library_directory}");
        jvm.add("-p");
        jvm.add("${library_directory}${classpath_separator}cpw.mods.sample.jar");
        Files.write(json, JsonUtils.writePrettyBytes(node));

        LaunchPlan plan = build(id, 2048, 1024);

        assertTrue(plan.command().contains("-DlibraryDirectory=" + paths.librariesDir()),
                "库目录占位符应替换为实际的运行库目录：" + plan.command());
        assertTrue(plan.command().stream()
                        .anyMatch(arg -> arg.equals(paths.librariesDir()
                                + File.pathSeparator + "cpw.mods.sample.jar")),
                "路径分隔符应替换为平台分隔符：" + plan.command());
        assertFalse(plan.command().stream().anyMatch(arg -> arg.contains("${")),
                "不应残留未替换的占位符：" + plan.command());
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private LaunchPlan build(String versionId, int maxMemory, int minMemory) throws IOException {
        LaunchOptions options = new LaunchOptions(
                new AccountInfo("Tester", "00000000000000000000000000000001", "secret-token",
                        AccountInfo.TYPE_MSA, "xuid-1", "client-1"),
                tempDir.resolve(javaExecutableName()), minMemory, maxMemory,
                List.of(), List.of(), 854, 480, false);
        ResolvedVersion resolved = VersionResolver.resolve(paths, versionId);
        return builder.build(resolved, paths, options, null);
    }

    private static String javaExecutableName() {
        return OsUtils.executableName("java");
    }

    private Path touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "");
        return file;
    }

    private void writeVersion(String id, ObjectNode json) throws IOException {
        Path target = paths.versionJson(id);
        Files.createDirectories(target.getParent());
        Files.write(target, JsonUtils.writePrettyBytes(json));
    }

    /** 1.13+ 形态：结构化参数 + 含规则的条件参数 + 含规则过滤的运行库。 */
    private void writeModernVersion(String id) throws IOException {
        ObjectNode version = JsonUtils.newObject();
        version.put("id", id);
        version.put("type", "release");
        version.put("mainClass", "net.minecraft.client.main.Main");
        version.put("assets", "5");
        version.putObject("assetIndex").put("id", "5").put("url", "https://example.invalid/a.json")
                .put("sha1", "00").put("size", 1);
        version.putObject("downloads").putObject("client")
                .put("url", "https://example.invalid/client.jar").put("sha1", "00").put("size", 1);

        ObjectNode arguments = version.putObject("arguments");
        ArrayNode jvm = arguments.putArray("jvm");
        jvm.add("-Djava.library.path=${natives_directory}");
        jvm.add("-Dminecraft.launcher.brand=${launcher_name}");
        jvm.add("-Dminecraft.launcher.version=${launcher_version}");
        jvm.add("-cp");
        jvm.add("${classpath}");

        ArrayNode game = arguments.putArray("game");
        game.add("--username").add("${auth_player_name}");
        game.add("--version").add("${version_name}");
        game.add("--gameDir").add("${game_directory}");
        game.add("--assetsDir").add("${assets_root}");
        game.add("--assetIndex").add("${assets_index_name}");
        game.add("--uuid").add("${auth_uuid}");
        game.add("--accessToken").add("${auth_access_token}");
        game.add("--userType").add("${user_type}");
        game.add("--width").add("${resolution_width}");
        game.add("--height").add("${resolution_height}");

        // 规则不匹配：任何平台都不满足
        ObjectNode demoArg = game.addObject();
        demoArg.putArray("rules").addObject().put("action", "allow").putObject("os").put("name", "a-made-up-os");
        demoArg.put("value", "--demo");

        // 规则匹配当前平台
        ObjectNode currentArg = game.addObject();
        currentArg.putArray("rules").addObject().put("action", "allow").putObject("os")
                .put("name", OsUtils.mojangOsName());
        currentArg.put("value", "--current-os-only");

        // 未知占位符必须被安全替换为空串，而不是原样传给游戏
        game.add("--unused").add("${some_unknown_placeholder}");

        ArrayNode libraries = version.putArray("libraries");
        ObjectNode lib = libraries.addObject();
        lib.put("name", "com.example:lib:1.0");
        ObjectNode artifact = lib.putObject("downloads").putObject("artifact");
        artifact.put("path", "com/example/lib/1.0/lib-1.0.jar");
        artifact.put("url", "https://example.invalid/lib.jar");
        artifact.put("sha1", "00");
        artifact.put("size", 1);

        ObjectNode skipped = libraries.addObject();
        skipped.put("name", "com.example:skip:1.0");
        skipped.putArray("rules").addObject().put("action", "allow").putObject("os").put("name", "a-made-up-os");
        ObjectNode skippedArtifact = skipped.putObject("downloads").putObject("artifact");
        skippedArtifact.put("path", "com/example/skip/1.0/skip-1.0.jar");
        skippedArtifact.put("url", "https://example.invalid/skip.jar");
        skippedArtifact.put("sha1", "00");
        skippedArtifact.put("size", 1);

        writeVersion(id, version);
    }

    /** 1.12 形态：minecraftArguments 字符串，无结构化 jvm 参数。 */
    private void writeLegacyVersion(String id) throws IOException {
        ObjectNode version = JsonUtils.newObject();
        version.put("id", id);
        version.put("type", "release");
        version.put("mainClass", "net.minecraft.client.main.Main");
        version.put("assets", "1.12");
        version.put("minecraftArguments",
                "--username ${auth_player_name} --version ${version_name} "
                        + "--gameDir ${game_directory} --assetsDir ${assets_root} "
                        + "--assetIndex ${assets_index_name} --uuid ${auth_uuid} "
                        + "--accessToken ${auth_access_token} --userType ${user_type} "
                        + "--width ${resolution_width} --height ${resolution_height}");
        version.putObject("assetIndex").put("id", "1.12").put("url", "https://example.invalid/a.json")
                .put("sha1", "00").put("size", 1);
        version.putObject("downloads").putObject("client")
                .put("url", "https://example.invalid/client.jar").put("sha1", "00").put("size", 1);
        version.putArray("libraries");

        writeVersion(id, version);
    }

    /** 命令中类路径必须使用平台分隔符。 */
    @Test
    void classpathShouldUsePlatformSeparator() throws Exception {
        writeModernVersion("1.20.1");
        touch(paths.versionJar("1.20.1"));

        LaunchPlan plan = build("1.20.1", 2048, 1024);
        String joined = plan.classpath().stream().reduce((a, b) -> a + File.pathSeparator + b).orElse("");

        assertTrue(plan.command().contains(joined), "命令中的类路径应为平台分隔符拼接结果");
    }
}
