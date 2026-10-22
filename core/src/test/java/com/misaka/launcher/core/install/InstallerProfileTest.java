package com.misaka.launcher.core.install;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.misaka.launcher.core.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forge / NeoForge 安装器档案解析验证。
 *
 * <p>解析错一个路径，预置就会漏构件，安装器又会回头去连不可达的官方 maven ——
 * 也就是「Forge 卡很久」复发。</p>
 */
class InstallerProfileTest {

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void maven坐标应能推出相对路径() {
        assertEquals("net/minecraftforge/JarJarSelector/0.3.19/JarJarSelector-0.3.19.jar",
                InstallerProfile.mavenPath("net.minecraftforge:JarJarSelector:0.3.19"));
        assertEquals("de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/"
                        + "mcp_config-1.20.1-20230612.114412-mappings.txt",
                InstallerProfile.mavenPath("de.oceanlabs.mcp:mcp_config:1.20.1-20230612.114412:mappings@txt"));
        assertEquals("de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/"
                        + "mcp_config-1.20.1-20230612.114412.zip",
                InstallerProfile.mavenPath("de.oceanlabs.mcp:mcp_config:1.20.1-20230612.114412@zip"));
        assertEquals("", InstallerProfile.mavenPath("bad"));
        assertEquals("", InstallerProfile.mavenPath(null));
    }

    @Test
    void shouldCollectArtifactsFromLibrariesAndData() throws Exception {
        ObjectNode profile = JsonUtils.newObject();
        ArrayNode libraries = profile.putArray("libraries");

        // 带 downloads 的（现代格式）：直接用其 path/url/sha1
        libraries.addObject()
                .put("name", "net.minecraftforge:forge:1.20.1-47.4.26")
                .putObject("downloads").putObject("artifact")
                .put("path", "net/minecraftforge/forge/1.20.1-47.4.26/forge-1.20.1-47.4.26-client.jar")
                .put("url", "https://maven.minecraftforge.net/net/minecraftforge/forge/x.jar")
                .put("sha1", "a".repeat(40))
                .put("size", 123L);

        // 没有 downloads 的（老格式）：只能按坐标推路径
        libraries.addObject().put("name", "cpw.mods:bootstraplauncher:1.1.2");

        // data 里的 [坐标] 引用：processors 会用到，同样要就位
        ObjectNode data = profile.putObject("data");
        data.putObject("MAPPINGS").put("client",
                "[de.oceanlabs.mcp:mcp_config:1.20.1-20230612.114412:mappings@txt]");

        List<InstallerProfile.Artifact> artifacts =
                InstallerProfile.parse(bytes(JsonUtils.write(profile)), null);

        assertEquals(3, artifacts.size(), "应收集 3 个构件：" + artifacts);
        InstallerProfile.Artifact forge = artifacts.stream()
                .filter(a -> a.path().endsWith("-client.jar")).findFirst().orElseThrow();
        assertTrue(forge.hasUrl());
        assertEquals("a".repeat(40), forge.sha1());
        assertEquals(123L, forge.size());
        assertTrue(artifacts.stream().anyMatch(a -> a.path().contains("bootstraplauncher")));
        assertTrue(artifacts.stream().anyMatch(a -> a.path().endsWith("mcp_config-1.20.1-20230612.114412-mappings.txt")),
                "data 里的 [坐标] 引用也要预置：" + artifacts);
    }

    @Test
    void versionJson里的构件也要收集() throws Exception {
        ObjectNode version = JsonUtils.newObject();
        version.putArray("libraries").addObject()
                .put("name", "org.ow2.asm:asm-tree:9.9.1")
                .putObject("downloads").putObject("artifact")
                .put("path", "org/ow2/asm/asm-tree/9.9.1/asm-tree-9.9.1.jar")
                .put("url", "https://libraries.minecraft.net/org/ow2/asm/asm-tree/9.9.1/asm-tree-9.9.1.jar")
                .put("sha1", "b".repeat(40));

        List<InstallerProfile.Artifact> artifacts =
                InstallerProfile.parse(null, bytes(JsonUtils.write(version)));

        assertEquals(1, artifacts.size());
        assertEquals("org/ow2/asm/asm-tree/9.9.1/asm-tree-9.9.1.jar", artifacts.get(0).path());
    }

    @Test
    void duplicatePathsShouldPreferEntryWithUrl() throws Exception {
        ObjectNode profile = JsonUtils.newObject();
        ArrayNode libraries = profile.putArray("libraries");
        String path = "net/minecraftforge/forge/1.20.1-47.4.26/forge-1.20.1-47.4.26-client.jar";
        // 同一条目在两份 JSON 里的信息量可能不同：先遇到没有 url 的，后遇到有 url 的
        libraries.addObject()
                .put("name", "net.minecraftforge:forge:1.20.1-47.4.26")
                .putObject("downloads").putObject("artifact")
                .put("path", path)
                .put("url", "");
        libraries.addObject()
                .put("name", "net.minecraftforge:forge:1.20.1-47.4.26")
                .putObject("downloads").putObject("artifact")
                .put("path", path)
                .put("url", "https://maven.minecraftforge.net/x.jar")
                .put("sha1", "c".repeat(40));

        List<InstallerProfile.Artifact> artifacts =
                InstallerProfile.parse(bytes(JsonUtils.write(profile)), null);

        assertEquals(1, artifacts.size(), "同路径只能保留一份：" + artifacts);
        assertTrue(artifacts.get(0).hasUrl(), "带地址的条目应覆盖没有地址的那份");
        assertEquals("c".repeat(40), artifacts.get(0).sha1(), "哈希也应来自更完整的那份");
    }

    @Test
    void emptyProfileShouldReturnEmptyList() throws Exception {
        assertTrue(InstallerProfile.parse(null, null).isEmpty());
        assertTrue(InstallerProfile.parse(bytes("{}"), bytes("{}")).isEmpty());
        // 不是安装器（或文件不存在）时应明确报错，调用方据此跳过预置
        assertThrows(java.io.IOException.class,
                () -> InstallerProfile.artifacts(java.nio.file.Path.of("不存在.jar")));
    }

    // ------------------------------------------------------------------
    // 后处理：Mojang 映射
    // ------------------------------------------------------------------

    /**
     * 真实 Forge 1.20.1 档案的缩略版：processors 里只有 DOWNLOAD_MOJMAPS 是联网的，
     * 输出路径以 {@code {MOJMAPS}} 占位符指向 data 里的 maven 坐标。
     */
    private static byte[] forgeLikeProfile(ArrayNode processors) throws Exception {
        ObjectNode profile = JsonUtils.newObject();
        profile.put("spec", 1);
        ObjectNode data = profile.putObject("data");
        data.putObject("MOJMAPS").put("client",
                "[net.minecraft:client:1.20.1-20230612.114412:mappings@txt]");
        data.putObject("MERGED_MAPPINGS").put("client",
                "[de.oceanlabs.mcp:mcp_config:1.20.1-20230612.114412:mappings-merged@txt]");
        profile.set("processors", processors);
        return bytes(JsonUtils.write(profile));
    }

    private static ArrayNode mojmapsProcessor(String side, String version) {
        ArrayNode processors = JsonUtils.newArray();
        ObjectNode processor = processors.addObject();
        processor.put("jar", "net.minecraftforge:installertools:1.4.1");
        processor.putArray("classpath").add("net.sf.jopt-simple:jopt-simple:6.0-alpha-3");
        ArrayNode args = processor.putArray("args");
        args.add("--task").add("DOWNLOAD_MOJMAPS").add("--version").add(version)
                .add("--side").add(side).add("--output").add("{MOJMAPS}");
        return processors;
    }

    @Test
    void mojmapsProcessorShouldResolveOutputPathFromCoords() throws Exception {
        List<InstallerProfile.MojmapsTask> tasks =
                InstallerProfile.mojmapsTasks(forgeLikeProfile(mojmapsProcessor("{SIDE}", "1.20.1")));

        assertEquals(1, tasks.size(), "应识别出 1 个 mojmaps 处理器：" + tasks);
        InstallerProfile.MojmapsTask task = tasks.get(0);
        assertEquals("1.20.1", task.mcVersion());
        assertEquals("client", task.side(), "{SIDE} 占位符应归一化为 client");
        assertEquals("net/minecraft/client/1.20.1-20230612.114412/"
                        + "client-1.20.1-20230612.114412-mappings.txt",
                task.outputMavenPath(), "输出路径必须与安装器实际写入的位置一致");
        assertEquals("net/minecraftforge/installertools/1.4.1/installertools-1.4.1.jar",
                task.processorJarPath(), "能力探测需要定位处理器所在的 jar");
    }

    @Test
    void serverOnlyMojmapsShouldBeSkippedOnClient() throws Exception {
        ArrayNode processors = mojmapsProcessor("{SIDE}", "1.20.1");
        ((ObjectNode) processors.get(0)).putArray("sides").add("server");

        assertTrue(InstallerProfile.mojmapsTasks(forgeLikeProfile(processors)).isEmpty(),
                "sides 只声明 server 的处理器在客户端安装里不会执行");
    }

    @Test
    void skipInjectionShouldOnlyAffectMojmapsAndBeIdempotent() throws Exception {
        // 混入一个非 mojmaps 的处理器，确认不会被误改
        ArrayNode processors = mojmapsProcessor("{SIDE}", "1.20.1");
        ArrayNode other = processors.addObject().putArray("args");
        other.add("--task").add("MCP_DATA").add("--input").add("{MCP_CONFIG}");

        byte[] patched = InstallerProfile.withMojmapsSkip(
                forgeLikeProfile(processors)).orElseThrow();

        JsonNode root = JsonUtils.read(new String(patched, StandardCharsets.UTF_8));
        JsonNode mojmapsArgs = root.get("processors").get(0).get("args");
        assertTrue(mojmapsArgs.get(mojmapsArgs.size() - 1).asText().equals("--skipIfExists"),
                "跳过开关应追加在参数末尾：" + mojmapsArgs);
        assertFalse(root.get("processors").get(1).get("args").toString().contains("--skipIfExists"),
                "非 mojmaps 处理器不应被改动");
        // 其它字段必须原样保留，否则安装器会读到一个不完整的档案
        assertEquals("1.20.1", InstallerProfile.mojmapsTasks(patched).get(0).mcVersion());
        assertTrue(InstallerProfile.withMojmapsSkip(patched).isEmpty(), "已经改过就不应重复改写");
    }

    @Test
    void profileWithoutMojmapsShouldStayUntouched() throws Exception {
        ObjectNode profile = JsonUtils.newObject();
        profile.putObject("data");
        profile.putArray("processors").addObject()
                .putArray("args").add("--task").add("MCP_DATA");

        assertTrue(InstallerProfile.withMojmapsSkip(bytes(JsonUtils.write(profile))).isEmpty());
        assertTrue(InstallerProfile.mojmapsTasks(null).isEmpty());
    }

    @Test
    void nonTokenOutputShouldNotGuessPath() throws Exception {
        ObjectNode data = JsonUtils.newObject();
        data.putObject("MOJMAPS").put("client", "[net.minecraft:client:1.20.1:mappings@txt]");

        assertEquals("net/minecraft/client/1.20.1/client-1.20.1-mappings.txt",
                InstallerProfile.tokenTarget(data, "{MOJMAPS}"));
        assertEquals("", InstallerProfile.tokenTarget(data, "/tmp/mappings.txt"),
                "绝对路径无法映射到 libraries 布局，应放弃预置");
        assertEquals("", InstallerProfile.tokenTarget(data, "{UNKNOWN}"));
        assertEquals("", InstallerProfile.tokenTarget(null, "{MOJMAPS}"));
    }

    @Test
    void missingProcessorJarShouldReportUnknown() {
        assertNull(InstallerProfile.supportsSkipIfExists(null));
        assertNull(InstallerProfile.supportsSkipIfExists(java.nio.file.Path.of("不存在.jar")),
                "读不出结论时应返回 null：调用方据此保留档案覆盖，而不是误判为不支持");
    }
}
