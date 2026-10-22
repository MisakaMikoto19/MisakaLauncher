package com.misaka.launcher.core.texture;

import com.misaka.launcher.core.config.LauncherConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图集预算与压缩档位选择。
 *
 * <p>样本按一例真实崩溃（10,709 张贴图、合计 3.26 亿像素）的<b>贴图尺寸分布</b>构造，
 * 只是把占绝大多数的普通贴图数量缩到 2000 张，合计约 2.2 亿像素 —— 单测不该为了凑数
 * 造上万个小对象，但必须保住关键性质：在 AMD（上限 16384）上必崩、在 NVIDIA（32768）上能进。
 * 这正是要区分的情形。</p>
 */
class GpuTextureBudgetTest {

    private static final int AMD_MAX = GpuTextureBudget.MAX_TEXTURE_SIZE_NON_NVIDIA;
    private static final int NVIDIA_MAX = 32768;

    @Test
    void budgetShouldApplyPackingAndReserve() {
        // 物理面积 × 装箱率 0.70 × (1 − 成长预留 0.30)
        assertEquals(187_904_819L, GpuTextureBudget.usablePixels(AMD_MAX));
        assertEquals(131_533_373L, GpuTextureBudget.budgetPixels(AMD_MAX));
        assertEquals(526_133_493L, GpuTextureBudget.budgetPixels(NVIDIA_MAX));
        assertTrue(GpuTextureBudget.budgetPixels(AMD_MAX) < GpuTextureBudget.usablePixels(AMD_MAX),
                "预算必须小于可装箱量 —— 剩下的就是留给整合包继续加模组的余量");
    }

    @Test
    void texturesWithinBudgetShouldNotUpscale() {
        GpuTextureBudget.TextureSize size = new GpuTextureBudget.TextureSize(16, 16);
        assertEquals(256L, GpuTextureBudget.scaledPixels(size, 512));
    }

    @Test
    void compressionShouldPreserveAspectRatio() {
        GpuTextureBudget.TextureSize size = new GpuTextureBudget.TextureSize(4096, 2048);
        assertEquals(512L * 256L, GpuTextureBudget.scaledPixels(size, 512));
    }

    @Test
    void oversizedPackShouldFitExactly() {
        List<GpuTextureBudget.TextureSize> sizes = realisticPack();
        long raw = sizes.stream().mapToLong(GpuTextureBudget.TextureSize::pixels).sum();
        assertTrue(raw > 200_000_000L, "样本应贴近真实规模，实际 " + raw);

        // 1024 档仍高于预算，640 之类没在候选表里 —— 落点是 768，而不是腰斩到 512
        assertTrue(GpuTextureBudget.estimatePixels(sizes, 1024) > GpuTextureBudget.budgetPixels(AMD_MAX));
        assertEquals(768, GpuTextureBudget.chooseCap(sizes, AMD_MAX));

        long after = GpuTextureBudget.estimatePixels(sizes, 768);
        assertTrue(after <= GpuTextureBudget.budgetPixels(AMD_MAX), "压完必须真的装得下：" + after);
        assertTrue(after < raw / 2, "应显著低于原始占用，实际 " + after + " / " + raw);
        // 余量：压完后离「可装箱量」还差多少 —— 这就是整合包还能长的空间
        assertTrue(GpuTextureBudget.usablePixels(AMD_MAX) - after > 90_000_000L,
                "应留出成规模的成长余量，实际 " + (GpuTextureBudget.usablePixels(AMD_MAX) - after));
    }

    @Test
    void manySmallTexturesShouldUseStrictestTier() {
        // 现实里可能出现：不可压/小尺寸贴图本身就超过预算，此时再怎么压也装不下 ——
        // 调用方（AtlasTextureCompressor）会记 WARN 提示必须裁材质
        List<GpuTextureBudget.TextureSize> sizes = new ArrayList<>();
        // 110² × 12,000 ≈ 1.45 亿像素 > 预算 1.32 亿：压到最严档也还是它自己
        add(sizes, 12_000, 110);
        assertEquals(GpuTextureBudget.strictestCap(), GpuTextureBudget.chooseCap(sizes, AMD_MAX));
        assertTrue(GpuTextureBudget.estimatePixels(sizes, GpuTextureBudget.strictestCap())
                > GpuTextureBudget.budgetPixels(AMD_MAX));
    }

    @Test
    void nvidiaShouldSkipCompression() {
        List<GpuTextureBudget.TextureSize> sizes = realisticPack();
        long raw = sizes.stream().mapToLong(GpuTextureBudget.TextureSize::pixels).sum();
        // N 卡面积是 A 卡的 4 倍，2.2 亿像素绰绰有余 —— 调用方据此直接跳过
        assertTrue(raw <= GpuTextureBudget.budgetPixels(NVIDIA_MAX));
    }

    @Test
    void invalidModeShouldFallBackToAuto() {
        assertEquals(LauncherConfig.LaunchSettings.COMPRESSION_AUTO,
                AtlasTextureCompressor.normalizeMode(null));
        assertEquals(LauncherConfig.LaunchSettings.COMPRESSION_AUTO,
                AtlasTextureCompressor.normalizeMode("  nonsense "));
        assertEquals(LauncherConfig.LaunchSettings.COMPRESSION_ALWAYS,
                AtlasTextureCompressor.normalizeMode(" Always "));

        // off / always 不依赖显卡探测，可以稳定断言
        assertFalse(AtlasTextureCompressor.shouldCompress(LauncherConfig.LaunchSettings.COMPRESSION_OFF));
        assertTrue(AtlasTextureCompressor.shouldCompress(LauncherConfig.LaunchSettings.COMPRESSION_ALWAYS));
    }

    /** 按真实崩溃报告的贴图尺寸分布构造贴图清单（普通贴图数量等比缩减）。 */
    private static List<GpuTextureBudget.TextureSize> realisticPack() {
        List<GpuTextureBudget.TextureSize> sizes = new ArrayList<>();
        add(sizes, 2, 4096);
        add(sizes, 1, 3024);
        add(sizes, 7, 2048);
        add(sizes, 19, 1472);
        add(sizes, 81, 1024);
        add(sizes, 2000, 110);
        return sizes;
    }

    private static void add(List<GpuTextureBudget.TextureSize> sizes, int count, int edge) {
        for (int i = 0; i < count; i++) {
            sizes.add(new GpuTextureBudget.TextureSize(edge, edge));
        }
    }
}
