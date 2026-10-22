package com.misaka.launcher.core.texture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 图集成员关系的<b>权威来源</b>：解析 {@code assets/<命名空间>/atlases/<名字>.json}。
 *
 * <p>「哪张贴图进哪张图集」以前是靠白名单目录猜的，猜错就会漏压（新模组换个目录就崩）或过压。
 * 游戏本身的规则写在这些 JSON 里，这里就照抄规则 —— 这也是模组唯一正确的声明方式。</p>
 *
 * <h2>三条必须照抄的游戏行为（都有实测依据）</h2>
 * <ol>
 *   <li><b>图集身份 = 命名空间 + 文件名</b>：{@code assets/minecraft/atlases/blocks.json} 属于
 *       {@code minecraft:blocks}，{@code assets/jei/atlases/gui.json} 属于 {@code jei:gui}（另一个独立图集）。</li>
 *   <li><b>同名文件合并</b>：游戏用资源栈把同一个路径下所有资源包（含每个 mod）里的
 *       {@code sources} 连起来。实测：一个 79 个 jar 的整合包里有 5 个 jar 都提供
 *       {@code assets/minecraft/atlases/blocks.json}（vanilla / curios / kaleidoscope×3 / netmusic /
 *       Vehicle-Unofficial），它们的内容全部生效。</li>
 *   <li><b>{@code directory} 来源跨命名空间扫描</b>：这是最关键的一条。Vehicle-Unofficial 声明的是
 *       {@code {"type":"directory","source":"vehicle","prefix":"vehicle/"}}，于是 <b>mtr 命名空间</b>下的
 *       {@code assets/mtr/textures/vehicle/a320.png}（4096²）就进了原版 {@code minecraft:blocks} 图集 ——
 *       与真实崩溃报告里 {@code Sprites: mtr:vehicle/a320[4096x4096] …} 完全对得上。</li>
 * </ol>
 *
 * <h2>保守原则：宁可多算，不可漏算</h2>
 * <p>估算偏大只是档位严一点（糊一点），估算偏小会让客户机继续崩。因此：</p>
 * <ul>
 *   <li>{@code paletted_permutations} 是按配色表生成 N 份（N = permutations 个数），
 *       生成物没有磁盘文件，只能压它的<b>源贴图</b>；但它的像素占用要按 N 倍计入。</li>
 *   <li>{@code unstitch} 把一张大图按<b>绝对像素</b>切成若干 sprite：源贴图一旦缩放，切出来的区域就会整体
 *       错位，因此源贴图记为「不可缩放」（它的像素按整张计入，属偏大估算）。</li>
 *   <li>{@code paletted_permutations} 的源贴图同样记为「不可缩放」：配色表按<b>颜色</b>映射，
 *       缩放会让双线性插值产生配色表里不存在的颜色，直接花屏/变透明。</li>
 *   <li>{@code filter} 来源只会在图集内做「裁剪」，不会凭空添加 sprite。这里<b>不实现裁剪</b>
 *       （顺序语义无法从定义文件确认），结果偏向「多算」——安全方向。</li>
 * </ul>
 *
 * <h2>已知近似：不扫原版客户端资源</h2>
 * <p>启动器只扫 mod jar 与资源包，不去定位原版客户端 jar / {@code assets} 目录，
 * 因此原版自己的贴图不计入预算。量化过：拿真实崩溃报告里 10,709 条 sprite 算，
 * 原版（{@code minecraft:}）贴图合计仅 0.7 M px，占全部 0.2% —— 可以忽略，
 * 真正吃图集空间的永远是 mod 贴图。</p>
 */
final class AtlasDefinitions {

    private static final Logger log = LoggerFactory.getLogger(AtlasDefinitions.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 图集定义的路径形状：{@code assets/<ns>/atlases/<名字>.json}。 */
    private static final String ATLAS_SEGMENT = "/atlases/";

    /** 贴图路径形状：{@code assets/<ns>/textures/<相对路径>.png}。 */
    private static final String TEXTURES_SEGMENT = "/textures/";

    /** 无法确定命名空间时用的默认值（与 {@code ResourceLocation} 的解析规则一致）。 */
    private static final String DEFAULT_NAMESPACE = "minecraft";

    /**
     * 内置的原版 {@code minecraft:blocks} 图集基线（1.20.1 客户端 jar 内
     * {@code assets/minecraft/atlases/blocks.json} 的原文，见各条注释）。
     *
     * <p>为什么必须内置：这份文件在客户端 jar 里，我们不想为了它去定位、解析版本 jar。
     * 而它决定了「{@code block/}、{@code item/} 这两个目录会进方块图集」——
     * mod 的贴图正是靠它进图集的（实测：{@code yunbeiuc:block/…} 与 {@code superbwarfare:item/lod/…}
     * 都没有自己的图集定义文件，却出现在崩溃报告的图集清单里）。</p>
     */
    private static final String VANILLA_BLOCKS_JSON = """
            { "sources": [
                { "type": "directory", "source": "block", "prefix": "block/" },
                { "type": "directory", "source": "item", "prefix": "item/" },
                { "type": "directory", "source": "entity/conduit", "prefix": "entity/conduit/" },
                { "type": "single", "resource": "entity/bell/bell_body" },
                { "type": "single", "resource": "entity/decorated_pot/decorated_pot_side" },
                { "type": "single", "resource": "entity/enchanting_table_book" },
                { "type": "paletted_permutations",
                  "textures": [ "trims/items/leggings_trim", "trims/items/chestplate_trim",
                                "trims/items/helmet_trim", "trims/items/boots_trim" ],
                  "palette_key": "trims/color_palettes/trim_palette",
                  "permutations": {
                    "quartz": "trims/color_palettes/quartz",
                    "iron": "trims/color_palettes/iron",
                    "gold": "trims/color_palettes/gold",
                    "diamond": "trims/color_palettes/diamond",
                    "netherite": "trims/color_palettes/netherite",
                    "redstone": "trims/color_palettes/redstone",
                    "copper": "trims/color_palettes/copper",
                    "emerald": "trims/color_palettes/emerald",
                    "lapis": "trims/color_palettes/lapis",
                    "amethyst": "trims/color_palettes/amethyst",
                    "iron_darker": "trims/color_palettes/iron_darker",
                    "gold_darker": "trims/color_palettes/gold_darker",
                    "diamond_darker": "trims/color_palettes/diamond_darker",
                    "netherite_darker": "trims/color_palettes/netherite_darker"
                  }
                }
            ] }
            """;

    /** 原版方块图集的图集 id。 */
    private static final String VANILLA_BLOCKS_ATLAS = "minecraft:blocks";

    /**
     * 一个图集里的一张<b>贴图文件</b>。
     *
     * @param assetPath 仓库内路径，例如 {@code assets/mtr/textures/vehicle/a320.png}
     * @param scalable  这张图能不能被缩放（几何/颜色被绝对引用时必须为 false）
     * @param copies    它在图集里被"用掉"几份（{@code paletted_permutations} 会按配色数量复制）
     */
    record AtlasTexture(String assetPath, boolean scalable, int copies) {
    }

    /**
     * 解析结果。
     *
     * @param atlases         图集 id → 它的贴图集合
     * @param owners          全部 PNG 路径 → 提供它的来源（第一次出现的来源胜出）
     * @param definitionFiles 实际读到的图集定义文件数（不含内置基线）
     * @param notes           需要让调用方知道的情况（未支持的类型、filter 等）
     */
    record Resolved(Map<String, Set<AtlasTexture>> atlases,
                    Map<String, TextureSource> owners,
                    int definitionFiles,
                    List<String> notes) {

        /**
         * 只要在<b>任何</b>图集里被标记为「不可缩放」，这张贴图就全局不许动。
         *
         * <p>一张图同时出现在两个图集里的情况很少，但取向必须是保守的：
         * 某个图集不允许缩放（几何/颜色被绝对引用），那就宁可在另一个图集里也不压它。</p>
         */
        Set<String> neverScalable() {
            Set<String> result = new LinkedHashSet<>();
            for (Set<AtlasTexture> textures : atlases.values()) {
                for (AtlasTexture texture : textures) {
                    if (!texture.scalable()) {
                        result.add(texture.assetPath());
                    }
                }
            }
            return result;
        }
    }

    private AtlasDefinitions() {
    }

    /** 解析全部图集定义（含内置的原版基线）。 */
    static Resolved resolve(List<TextureSource> sources) {
        List<String> notes = new ArrayList<>();
        Map<String, List<JsonNode>> definitions = new LinkedHashMap<>();
        int files = 0;

        for (TextureSource source : sources) {
            for (String path : source.atlasDefinitionPaths()) {
                String atlasId = atlasIdOf(path);
                if (atlasId == null) {
                    continue;
                }
                JsonNode root = parse(source, path, notes);
                if (root == null) {
                    continue;
                }
                JsonNode list = root.path("sources");
                if (!list.isArray()) {
                    notes.add("图集定义没有 sources 数组：" + path);
                    continue;
                }
                definitions.computeIfAbsent(atlasId, key -> new ArrayList<>()).add(list);
                files++;
            }
        }

        JsonNode vanilla = parse(VANILLA_BLOCKS_JSON, notes);
        if (vanilla != null) {
            definitions.computeIfAbsent(VANILLA_BLOCKS_ATLAS, key -> new ArrayList<>())
                    .add(vanilla.path("sources"));
        }

        // PNG 索引：一次遍历把所有来源的贴图登记下来，之后读尺寸/读像素都走它。
        // 同一个路径出现在多个来源时取先枚举到的那个（真出现会告警，见 AtlasTextureCompressor）。
        Map<String, TextureSource> owners = new LinkedHashMap<>();
        for (TextureSource source : sources) {
            for (String png : source.pngAssetPaths()) {
                owners.putIfAbsent(png, source);
            }
        }

        Map<String, Set<AtlasTexture>> atlases = new LinkedHashMap<>();
        for (Map.Entry<String, List<JsonNode>> entry : definitions.entrySet()) {
            Accumulator accumulator = new Accumulator();
            for (JsonNode sourcesNode : entry.getValue()) {
                for (JsonNode source : sourcesNode) {
                    accumulator.accept(source);
                }
            }
            atlases.put(entry.getKey(), accumulator.resolve(owners, notes));
        }

        log.info("图集定义解析完成：{} 个图集、{} 个定义文件（含内置原版基线）、{} 张贴图建档",
                atlases.size(), files, owners.size());
        for (String note : notes) {
            log.warn("图集定义：{}", note);
        }
        return new Resolved(atlases, owners, files, notes);
    }

    // ------------------------------------------------------------------ 解析

    private static JsonNode parse(TextureSource source, String path, List<String> notes) {
        byte[] json = null;
        try {
            json = source.read(path, 4 * 1024 * 1024);
        } catch (IOException e) {
            notes.add("读取图集定义失败：" + path + "（" + e.getMessage() + "）");
        }
        if (json == null) {
            return null;
        }
        return parse(json, notes);
    }

    private static JsonNode parse(byte[] json, List<String> notes) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            notes.add("解析图集定义失败：" + e.getMessage());
            return null;
        }
    }

    private static JsonNode parse(String json, List<String> notes) {
        return parse(json.getBytes(StandardCharsets.UTF_8), notes);
    }

    // ------------------------------------------------------------------ 路径

    /** {@code assets/<ns>/atlases/<名字>.json} → {@code <ns>:<名字>}；形状不符返回 null。 */
    private static String atlasIdOf(String assetPath) {
        if (!assetPath.startsWith("assets/") || !assetPath.endsWith(".json")) {
            return null;
        }
        int marker = assetPath.indexOf(ATLAS_SEGMENT);
        if (marker < "assets/".length()) {
            return null;
        }
        String namespace = assetPath.substring("assets/".length(), marker);
        String name = assetPath.substring(marker + ATLAS_SEGMENT.length(),
                assetPath.length() - ".json".length());
        if (namespace.isEmpty() || name.isEmpty() || namespace.contains("/") || name.contains("/")) {
            return null;
        }
        return namespace + ":" + name;
    }

    /** {@code assets/<ns>/textures/block/stone.png} → {@code block/stone.png}；不是贴图返回 null。 */
    private static String texturesRelative(String assetPath) {
        if (!assetPath.startsWith("assets/") || !assetPath.toLowerCase(Locale.ROOT).endsWith(".png")) {
            return null;
        }
        int marker = assetPath.indexOf(TEXTURES_SEGMENT);
        if (marker < "assets/".length()) {
            return null;
        }
        String remainder = assetPath.substring(marker + TEXTURES_SEGMENT.length());
        return remainder.indexOf('/') > 0 ? remainder : null;
    }

    /** sprite id（{@code block/stone} 或 {@code mtr:vehicle/a320}）→ 贴图路径。 */
    private static String toAssetPath(String spriteId) {
        String namespace = DEFAULT_NAMESPACE;
        String path = spriteId;
        int colon = spriteId.indexOf(':');
        if (colon > 0) {
            namespace = spriteId.substring(0, colon);
            path = spriteId.substring(colon + 1);
        }
        return "assets/" + namespace + "/textures/" + path + ".png";
    }

    // ------------------------------------------------------------------ 来源累加

    /** 一个图集的定义累加器：先把各类型来源收好，再一次性解析成贴图集合。 */
    private static final class Accumulator {

        private record Directory(String source, String prefix) {
        }

        /** 一条 paletted_permutations 来源：要复制的贴图 + 配色数量。 */
        private record Paletted(String spriteId, int copies) {
        }

        private final List<Directory> directories = new ArrayList<>();
        private final List<String> singles = new ArrayList<>();
        private final List<Paletted> paletted = new ArrayList<>();
        private final List<String> unstitch = new ArrayList<>();
        private int filters;
        private final Set<String> unknownTypes = new LinkedHashSet<>();

        /**
         * 归一化来源类型名。
         *
         * <p>类型是 ResourceLocation，模组常把它写成全限定形式。实测碰到过：
         * {@code watut-forge-1.20.1-1.2.3.jar} 的 {@code assets/minecraft/atlases/particles.json}
         * 里 35 条 {@code single} 全写成 {@code "minecraft:single"} —— 不归一就会整份定义被当成
         * 未知类型丢掉（这里正好都是小贴图，换成一份带 4K 贴图的定义就会漏压到崩）。</p>
         *
         * <p>非 {@code minecraft} 命名空间的是 mod 自定义类型，这里不予支持，交由调用方记一条提示。</p>
         */
        private static String normalizeType(String raw) {
            int colon = raw.indexOf(':');
            if (colon < 0 || !"minecraft".equals(raw.substring(0, colon))) {
                return raw;
            }
            return raw.substring(colon + 1);
        }

        void accept(JsonNode source) {
            String type = normalizeType(source.path("type").asText(""));
            switch (type) {
                case "directory" -> {
                    String dir = source.path("source").asText("");
                    if (!dir.isBlank()) {
                        directories.add(new Directory(dir, source.path("prefix").asText("")));
                    }
                }
                case "single" -> {
                    String resource = source.path("resource").asText("");
                    if (!resource.isBlank()) {
                        singles.add(resource);
                    }
                }
                case "paletted_permutations" -> {
                    // 份数按「这一条来源」记，不能按图集取最大值：同一个图集里
                    // 原版基线有 14 种配色、模组可能只有 2 种，取最大值会把模组贴图的
                    // 占用高估 7 倍，档位跟着被压得过严
                    int copies = source.path("permutations").isObject()
                            ? source.path("permutations").size() : 0;
                    if (copies <= 0) {
                        return;
                    }
                    for (JsonNode texture : source.path("textures")) {
                        String id = texture.asText("");
                        if (!id.isBlank()) {
                            paletted.add(new Paletted(id, copies));
                        }
                    }
                }
                case "unstitch" -> {
                    String resource = source.path("resource").asText("");
                    if (!resource.isBlank()) {
                        unstitch.add(resource);
                    }
                }
                case "filter" -> filters++;
                case "" -> {
                    // 空条目：忽略
                }
                default -> unknownTypes.add(type);
            }
        }

        Set<AtlasTexture> resolve(Map<String, TextureSource> owners, List<String> notes) {
            Set<AtlasTexture> textures = new LinkedHashSet<>();

            // directory：跨命名空间匹配（模组的贴图正是靠它进图集的）
            for (String assetPath : owners.keySet()) {
                String relative = texturesRelative(assetPath);
                if (relative == null) {
                    continue;
                }
                for (Directory directory : directories) {
                    if (relative.startsWith(directory.source() + "/")) {
                        textures.add(new AtlasTexture(assetPath, true, 1));
                        break;
                    }
                }
            }
            for (String single : singles) {
                textures.add(new AtlasTexture(toAssetPath(single), true, 1));
            }
            for (Paletted texture : paletted) {
                textures.add(new AtlasTexture(toAssetPath(texture.spriteId()), false, texture.copies()));
            }
            for (String texture : unstitch) {
                textures.add(new AtlasTexture(toAssetPath(texture), false, 1));
            }

            if (filters > 0) {
                notes.add("有 " + filters + " 条 filter 来源未做裁剪（按保守策略计入全部候选贴图）");
            }
            for (String unknown : unknownTypes) {
                notes.add("忽略未支持的图集来源类型：" + unknown);
            }
            return textures;
        }
    }
}
