package com.misaka.launcher.core.texture;

import java.util.List;

/**
 * 纹理图集的容量预算与压缩档位选择。
 *
 * <p><b>为什么需要它</b>：Minecraft 把方块与物品贴图全部拼进<b>一张</b>纹理（texture atlas），
 * 这张纹理的边长上限就是驱动报告的 {@code GL_MAX_TEXTURE_SIZE}。AMD 与 Intel 通常报
 * <b>16384</b>，NVIDIA 通常报 <b>32768</b> —— 面积整整差 4 倍。整合包的贴图总量一旦超过
 * 16384² ≈ 2.68 亿像素，A 卡机器就必然在拼接阶段崩溃
 * （{@code StitcherException: Unable to fit}），而同一份包在 N 卡上却能正常进。</p>
 *
 * <p>真实的 1.20.1-Forge 包实测：10,709 张贴图合计 <b>3.26 亿像素</b>，超限必然崩。
 * 这里负责算出「该把长边压到多少才装得下」，而不是拍一个固定值。</p>
 *
 * <p>纯计算、零 IO，便于单测；具体压缩由 {@link AtlasTextureCompressor} 执行。</p>
 */
public final class GpuTextureBudget {

    /** 非 NVIDIA 显卡按此边长上限估算（AMD / Intel 的常见值）。 */
    public static final int MAX_TEXTURE_SIZE_NON_NVIDIA = 16384;

    /**
     * 装箱可用比例：图集是一个装箱问题，{@code Stitcher} 不可能 100% 填满，
     * 大贴图之间的空隙会白白浪费掉。按 70% 估算 —— 宁可多压一点，
     * 也不要算得刚好然后仍然崩（崩了用户看到的是一屏报错，压过头只是糊一点）。
     */
    private static final double USABLE_RATIO = 0.70;

    /**
     * 留给「以后继续往整合包里加模组」的比例。
     *
     * <p>整合包是会长的：客户机上今天装得下，加几个大材质模组后就不一定了。
     * 每次启动都会重算，所以理论上加了新模组下次启动会自动压得更狠 —— 但那只会在
     * <b>已经贴着上限</b>时才发生，而贴着上限意味着两件事：装箱率假设（70%）一旦偏乐观就直接崩，
     * 以及图集被顶到 16384（方块图集一张就占 1 GB 显存）。因此这里主动留出 30%，
     * 让整合包长大时先吃掉余量，而不是立刻把档位往下砸一档。</p>
     *
     * <p>实测（79 个模组的 1.20.1 包、图集上限 16384）：</p>
     * <ul>
     *   <li>不留余量（1024px 档）：方块图集占 1.52 亿 px = 物理上限的 56.7%，只剩 0.36 亿 px 的成长空间（+23%）；</li>
     *   <li>留 30%（本次策略）：占 0.86~1.2 亿 px 上下，成长空间翻倍以上；</li>
     *   <li>再往下压到 256px 只多省 0.26 亿 px —— 因为不可压的贴图（动画、模型钉住、配色生成）
     *       本身已占 0.6 亿 px，压这些小图没有意义。</li>
     * </ul>
     */
    private static final double GROWTH_RESERVE = 0.30;

    /**
     * 候选档位，从宽到严；{@link #chooseCap} 取第一个能满足预算的。
     *
     * <p>步长刻意做细（不是 2048/1024/512 这种腰斩）：粗阶梯会在「只需要再压一点点」时
     * 直接掉到半数分辨率 —— 实测那份包在 1024 与 512 之间就能停在 768，画质与余量都能兼顾。
     * 全部取 2 的幂与 3×2ⁿ，缩放后的尺寸对 2 的幂原图（绝大多数贴图）仍是干净比例，
     * 生成的 mipmap 不会出现非整除的层级。</p>
     */
    private static final int[] CANDIDATE_CAPS = {2048, 1536, 1024, 768, 512, 384, 256};

    /** 候选档位（副本，供 {@link AtlasTextureCompressor} 按图集逐个试档）。 */
    public static int[] candidateCaps() {
        return CANDIDATE_CAPS.clone();
    }

    /** 最严档位：连它都装不下就说明必须裁材质，而不是继续压。 */
    public static int strictestCap() {
        return CANDIDATE_CAPS[CANDIDATE_CAPS.length - 1];
    }

    private GpuTextureBudget() {
    }

    /** 一张贴图的原始尺寸。 */
    public record TextureSize(int width, int height) {

        public int longestEdge() {
            return Math.max(width, height);
        }

        public long pixels() {
            return (long) width * height;
        }
    }

    /**
     * 可用的图集像素预算：物理面积 × 装箱率 × 剩余可用比例。
     *
     * <p>{@code maxTextureSize² × USABLE_RATIO × (1 - GROWTH_RESERVE)}
     * —— 以 16384 上限为例：2.684 亿 × 0.70 × 0.70 ≈ 1.32 亿像素。</p>
     */
    public static long budgetPixels(int maxTextureSize) {
        return (long) (maxTextureSize * (double) maxTextureSize * USABLE_RATIO * (1 - GROWTH_RESERVE));
    }

    /** 不含成长预留的「可装箱量」，用于日志里说明物理余量。 */
    public static long usablePixels(int maxTextureSize) {
        return (long) (maxTextureSize * (double) maxTextureSize * USABLE_RATIO);
    }

    /** 把长边压到 {@code cap}（等比、不放大）之后的总像素。 */
    public static long estimatePixels(List<TextureSize> sizes, int cap) {
        long total = 0L;
        for (TextureSize size : sizes) {
            total += scaledPixels(size, cap);
        }
        return total;
    }

    /** 单张贴图压缩后的像素数；长边不超过 {@code cap} 时原样返回（绝不放大）。 */
    public static long scaledPixels(TextureSize size, int cap) {
        int longest = size.longestEdge();
        if (longest <= cap) {
            return size.pixels();
        }
        double ratio = (double) cap / longest;
        long w = Math.max(1L, Math.round(size.width() * ratio));
        long h = Math.max(1L, Math.round(size.height() * ratio));
        return w * h;
    }

    /**
     * 选出能满足预算的<b>最宽</b>档位（尽量少损失清晰度）。
     *
     * <p>连最严档位都装不下时返回最严档位 —— 那时已无能为力，
     * 压到最小至少还留一线机会，且调用方会把实际情况写进日志。</p>
     */
    public static int chooseCap(List<TextureSize> sizes, int maxTextureSize) {
        long budget = budgetPixels(maxTextureSize);
        for (int cap : CANDIDATE_CAPS) {
            if (estimatePixels(sizes, cap) <= budget) {
                return cap;
            }
        }
        return CANDIDATE_CAPS[CANDIDATE_CAPS.length - 1];
    }
}
