package com.misaka.launcher.core.texture;

import com.misaka.launcher.core.instance.GamePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图集驱动压缩的行为约定。
 *
 * <p>这些用例守的是<b>通用性</b>与<b>禁区</b>两条线：前者保证「以后装的模组换个目录也能被压」，
 * 后者保证「不会把不该压的压坏」。档位数字本身由 {@link GpuTextureBudgetTest} 覆盖，
 * 这里一律把图集上限调小（512）以便用 1024² 的假贴图快速跑完。</p>
 */
class AtlasTextureCompressorTest {

    private static final int SMALL_ATLAS = 512;

    @Test
    void modCustomDirsShouldBeIncluded(@TempDir Path root) throws IOException {
        // 复刻实测现场：Vehicle-Unofficial 声明 directory vehicle，于是另一个命名空间
        // （mtr）下的贴图进了原版 blocks 图集
        modJar(root, "vehicle-addon.jar", Map.of(
                "assets/minecraft/atlases/blocks.json",
                json("{\"sources\":[{\"type\":\"directory\",\"source\":\"vehicle\",\"prefix\":\"vehicle/\"}]}"),
                "assets/mtr/textures/vehicle/big.png", png(1024, 1024),
                // 不占图集空间的实体贴图：不该被算进预算，更不该被压
                "assets/mtr/textures/entity/huge.png", png(1024, 1024)));

        AtlasTextureCompressor.Plan plan = AtlasTextureCompressor.plan(new GamePaths(root), SMALL_ATLAS);

        assertTrue(plan.needsCompression());
        assertTrue(plan.capByTexture().containsKey("assets/mtr/textures/vehicle/big.png"),
                "跨命名空间的 directory 来源必须被识别");
        assertFalse(plan.capByTexture().containsKey("assets/mtr/textures/entity/huge.png"),
                "不在任何图集里的贴图不该被压");
        assertEquals(GpuTextureBudget.strictestCap(), plan.cap(),
                "1M 像素在图集上限 512（预算 0.18M）下必须压到最严档位");
    }

    @Test
    void vanillaAndModAtlasDefinitionsShouldMerge(@TempDir Path root) throws IOException {
        // block/ 目录来自内置的原版基线；模组没有再声明它
        modJar(root, "plain-mod.jar", Map.of(
                "assets/bar/textures/block/big.png", png(1024, 1024)));
        // 另一个 jar 用同一个图集 id 追加来源：必须在同一个图集里一起算
        modJar(root, "extra.jar", Map.of(
                "assets/minecraft/atlases/blocks.json",
                json("{\"sources\":[{\"type\":\"single\",\"resource\":\"bar:block/single\"}]}"),
                "assets/bar/textures/block/single.png", png(1024, 1024)));

        AtlasTextureCompressor.Plan plan = AtlasTextureCompressor.plan(new GamePaths(root), SMALL_ATLAS);

        assertTrue(plan.capByTexture().containsKey("assets/bar/textures/block/big.png"),
                "原版基线的 block/ 目录要生效");
        assertTrue(plan.capByTexture().containsKey("assets/bar/textures/block/single.png"),
                "同名图集定义的来源要合并（single 未写命名空间时默认 minecraft，这里写了 bar）");
        assertEquals(1, plan.atlases().size(), "两份同名定义合并后仍只有一个图集");
    }

    @Test
    void fullyQualifiedSourceTypesShouldBeRecognized(@TempDir Path root) throws IOException {
        // 实测：watut 把 35 条 single 写成 "minecraft:single"
        modJar(root, "watut-like.jar", Map.of(
                "assets/minecraft/atlases/particles.json",
                json("{\"sources\":[{\"type\":\"minecraft:single\",\"resource\":\"foo:particles/big\"}]}"),
                "assets/foo/textures/particles/big.png", png(1024, 1024)));

        AtlasTextureCompressor.Plan plan = AtlasTextureCompressor.plan(new GamePaths(root), SMALL_ATLAS);

        assertTrue(plan.atlases().containsKey("minecraft:particles"));
        assertTrue(plan.capByTexture().containsKey("assets/foo/textures/particles/big.png"),
                "\"minecraft:single\" 必须按 single 处理，否则整份定义会被当成未知类型丢掉");
    }

    @Test
    void paletted_permutations_源贴图不缩放但按配色数计入(@TempDir Path root) throws IOException {
        // 图集 id = 命名空间 + 文件名：模组想把内容加进原版方块图集，就得写 assets/minecraft/atlases/blocks.json
        // sprite id 不写命名空间时默认 minecraft（与 ResourceLocation 的解析规则一致），模组引用自己的贴图必须带前缀
        modJar(root, "trims.jar", Map.of(
                "assets/minecraft/atlases/blocks.json", json("""
                        {"sources":[{"type":"paletted_permutations","textures":["foo:trim/a"],
                         "palette_key":"foo:trim/palette","permutations":{"x":"p/x","y":"p/y"}}]}
                        """),
                "assets/foo/textures/trim/a.png", png(1024, 1024)));

        AtlasTextureCompressor.Plan plan = AtlasTextureCompressor.plan(new GamePaths(root), SMALL_ATLAS);
        AtlasTextureCompressor.AtlasPlan atlas = plan.atlases().get("minecraft:blocks");

        assertEquals(2L * 1024 * 1024, atlas.rawPixels(),
                "生成 2 份就要按 2 份计入图集占用；实际图集为 " + plan.atlases().keySet());
        assertFalse(plan.capByTexture().containsKey("assets/foo/textures/trim/a.png"),
                "配色映射按颜色索引，缩放会插值出不存在的颜色，必须跳过");
    }

    @Test
    void unstitch_的源贴图不缩放(@TempDir Path root) throws IOException {
        // 故意放在自定义目录：避开原版基线的 block/ item/ 目录，
        // 这样「不可缩放」只可能来自 unstitch 本身
        modJar(root, "unstitch.jar", Map.of(
                "assets/minecraft/atlases/blocks.json", json("""
                        {"sources":[{"type":"unstitch","resource":"foo:custom/sheet",
                         "regions":[{"sprite":"a","x":0,"y":0,"width":16,"height":16}]}]}
                        """),
                "assets/foo/textures/custom/sheet.png", png(1024, 1024)));

        AtlasTextureCompressor.Plan plan = AtlasTextureCompressor.plan(new GamePaths(root), SMALL_ATLAS);

        assertFalse(plan.capByTexture().containsKey("assets/foo/textures/custom/sheet.png"),
                "切分坐标是绝对像素，缩放会让区域整体错位");
    }

    @Test
    void animatedAndModelPinnedTexturesShouldBeSkipped(@TempDir Path root) throws IOException {
        modJar(root, "pinned.jar", Map.of(
                "assets/foo/textures/block/anim.png", png(1024, 1024),
                "assets/foo/textures/block/anim.png.mcmeta", json("{\"animation\":{\"frametime\":2}}"),
                "assets/foo/textures/block/pinned.png", png(1024, 1024),
                "assets/foo/models/block/pinned.json",
                json("{\"textures\":{\"0\":\"foo:block/pinned\"},\"texture_size\":[1024,1024]}"),
                "assets/foo/textures/block/plain.png", png(1024, 1024)));

        AtlasTextureCompressor.Plan plan = AtlasTextureCompressor.plan(new GamePaths(root), SMALL_ATLAS);

        assertFalse(plan.capByTexture().containsKey("assets/foo/textures/block/anim.png"),
                "动画帧高必须能被帧数整除，缩放会破坏它");
        assertFalse(plan.capByTexture().containsKey("assets/foo/textures/block/pinned.png"),
                "模型声明了 texture_size，uv 按绝对像素解释");
        assertTrue(plan.capByTexture().containsKey("assets/foo/textures/block/plain.png"),
                "同一目录下的普通贴图照压");
    }

    @Test
    void nothingShouldBeCompressedWhenItFits(@TempDir Path root) throws IOException {
        // 「始终压缩」也不是无脑压：档位判定照旧按图集预算走，装得下就一个文件都不写
        modJar(root, "tiny.jar", Map.of("assets/foo/textures/block/small.png", png(16, 16)));

        AtlasTextureCompressor.Plan plan =
                AtlasTextureCompressor.plan(new GamePaths(root), GpuTextureBudget.MAX_TEXTURE_SIZE_NON_NVIDIA);

        assertEquals(256L, plan.atlases().get("minecraft:blocks").rawPixels(),
                "16×16 的贴图应被认进方块图集");
        assertFalse(plan.needsCompression(), "远低于预算时不该写覆盖包");
    }

    @Test
    void brokenSourceShouldNotBlockOthers(@TempDir Path root) throws IOException {
        // 客户机上什么都可能遇到：同步中断留下的半个 jar、杀软正锁着的文件。
        // 以前一个文件打不开就会中断整个目录的枚举，排在它后面的模组一张都扫不到，
        // 估算凭空小一个数量级 → 「各图集都装得下」被误判成立 → 保护被静默撤掉
        modJar(root, "zz-big.jar", Map.of("assets/foo/textures/block/big.png", png(1024, 1024)));
        Files.write(root.resolve("mods").resolve("aa-broken.jar"), new byte[] {1, 2, 3, 4, 5});

        AtlasTextureCompressor.Plan plan = AtlasTextureCompressor.plan(new GamePaths(root), SMALL_ATLAS);

        assertTrue(plan.needsCompression(), "坏文件不能把整次扫描拖垮");
        assertTrue(plan.capByTexture().containsKey("assets/foo/textures/block/big.png"),
                "排在坏文件之后的来源必须照常被扫到");
        assertFalse(plan.scanComplete(), "有来源打不开时估算不可信，调用方要据此拒绝「装得下」");
        assertEquals(1, plan.scanFailures().size());
    }

    @Test
    void overridePackShouldFollowPlanAndCleanStale(@TempDir Path root) throws IOException {
        modJar(root, "big.jar", Map.of(
                "assets/foo/textures/block/big.png", png(1024, 1024),
                "assets/foo/textures/block/other.png", png(1024, 1024)));
        GamePaths paths = new GamePaths(root);

        AtlasTextureCompressor.Plan plan = AtlasTextureCompressor.plan(paths, SMALL_ATLAS);
        AtlasTextureCompressor.Result result = AtlasTextureCompressor.compress(paths, plan);

        Path packRoot = paths.resourcePacksDir().resolve(AtlasTextureCompressor.PACK_DIR_NAME);
        assertEquals(plan.capByTexture().size(), result.compressed());
        assertTrue(Files.isRegularFile(packRoot.resolve("pack.mcmeta")), "资源包必须有 pack.mcmeta");
        for (String asset : plan.capByTexture().keySet()) {
            Path written = packRoot.resolve(asset);
            assertTrue(Files.isRegularFile(written), "缺少覆盖文件：" + asset);
            BufferedImage image = ImageIO.read(written.toFile());
            assertTrue(Math.max(image.getWidth(), image.getHeight()) <= plan.cap(),
                    "覆盖文件必须真的被缩小到档位以内：" + asset);
        }

        // 重算后档位变宽（或干脆不需要压）时，旧文件必须消失 ——
        // 否则上一档留下的覆盖会继续压着本该恢复清晰的贴图
        AtlasTextureCompressor.Plan empty =
                new AtlasTextureCompressor.Plan(Map.of(), Map.of(), 0, 0, 0, 0, 0,
                        List.of(), List.of());
        AtlasTextureCompressor.compress(paths, empty);
        assertFalse(Files.exists(packRoot), "旧覆盖包必须被整体清理");
    }

    @Test
    void removingPackShouldClearDirAndEntry(@TempDir Path root) throws IOException {
        modJar(root, "big.jar", Map.of("assets/foo/textures/block/big.png", png(1024, 1024)));
        GamePaths paths = new GamePaths(root);
        Files.writeString(paths.gameDir().resolve("options.txt"),
                "resourcePacks:[\"vanilla\",\"mod_resources\"]\n", StandardCharsets.UTF_8);

        AtlasTextureCompressor.Plan plan = AtlasTextureCompressor.plan(paths, SMALL_ATLAS);
        AtlasTextureCompressor.compress(paths, plan);
        GameOptionsResourcePacks.ensureLast(paths.gameDir().resolve("options.txt"),
                AtlasTextureCompressor.packEntry());
        Path packRoot = paths.resourcePacksDir().resolve(AtlasTextureCompressor.PACK_DIR_NAME);
        assertTrue(Files.exists(packRoot));

        assertTrue(AtlasTextureCompressor.removeGeneratedPack(paths));

        assertFalse(Files.exists(packRoot), "关闭档位后旧的覆盖目录必须删掉，否则它还在生效");
        assertEquals("resourcePacks:[\"vanilla\",\"mod_resources\"]\n",
                Files.readString(paths.gameDir().resolve("options.txt"), StandardCharsets.UTF_8),
                "登记项也要摘掉");
        assertFalse(AtlasTextureCompressor.removeGeneratedPack(paths), "重复撤回应为无操作");
    }

    // ------------------------------------------------------------------ 夹具

    private static void modJar(Path instanceRoot, String name, Map<String, byte[]> entries)
            throws IOException {
        Path mods = Files.createDirectories(instanceRoot.resolve("mods"));
        Files.createDirectories(instanceRoot.resolve("resourcepacks"));
        Map<String, byte[]> ordered = new LinkedHashMap<>(entries);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(mods.resolve(name)))) {
            for (Map.Entry<String, byte[]> entry : ordered.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    private static byte[] json(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
