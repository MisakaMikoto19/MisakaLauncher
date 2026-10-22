package com.misaka.launcher.core.auth;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * 玩家头部头像的绘制与裁剪。
 *
 * <p>两件事：</p>
 * <ol>
 *   <li><b>裁剪真实皮肤</b>（{@link #cropHead}）：官方皮肤布局中面部在 {@code (8,8)}、
 *       帽子叠加层在 {@code (40,8)}；Mojang 没有头像接口，只能取皮肤自行裁剪；</li>
 *   <li><b>绘制默认头像</b>（{@link #defaultHead}）：没有真实皮肤时按皮肤模型给出
 *       Steve / Alex —— 这是「默认头像」而非「首字母」，玩家一眼就能判断出
 *       「这个账户还没有皮肤」，而首字母既不像头像也表达不了模型。</li>
 * </ol>
 *
 * <p>为什么默认头像用代码画而不是内置皮肤 PNG：仓库不提交二进制资源；且这里只需要
 * 8×8 的面部像素表就足以还原玩家一眼就能认出的 Steve / Alex。</p>
 *
 * <p>缩放一律用<b>最近邻</b>：皮肤与面部像素表都是像素画，双三次会把方块边缘糊掉。</p>
 */
public final class PlayerHeads {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PlayerHeads.class);

    /**
     * 纯字节的兜底头像（32×32 PNG，base64）。
     *
     * <p>为什么需要它：默认头像是用 AWT 的 {@link BufferedImage} 画的，而某些环境里
     * AWT 原生库根本加载不了 —— 典型场景是 <b>Apple 芯片上运行 x86_64 的 JDK</b>，
     * {@code libawt.dylib} 链接系统 {@code JavaRuntimeSupport} 框架时抛
     * {@code UnsatisfiedLinkError}。</p>
     *
     * <p>而默认头像在 {@code Sidebar} 构造时就会被调用，于是「画不出头像」被放大成
     * 「启动器直接起不来」。这里不碰 AWT：直接给一段现成的 PNG 字节，
     * 代价是头像不区分 Steve / Alex —— 但<b>能启动</b>远比头像精确重要。</p>
     */
    private static final String FALLBACK_HEAD_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJ"
                    + "cEhZcwAADsMAAA7DAcdvqGQAAABaSURBVFhHY/Ay1/g/kJgBXYDeeNQBow4YdcCoAxiOLaj6P5B41AEEHaClKIGB0dWQ"
                    + "oxaGRx0w+B1AazzqgFEHkOQA9BSOC6Prw4dHHUCSA2iBRx0w6oBRBwAAQ/+Pmnus68YAAAAASUVORK5CYII=";

    /** AWT 原生库是否可用；{@code null} = 还没探测过。 */
    private static volatile Boolean awtOk;

    /** 经典模型（Steve）。 */
    public static final String MODEL_DEFAULT = "default";
    /** 纤细模型（Alex）。 */
    public static final String MODEL_SLIM = "slim";

    /** 面部在官方皮肤中的位置与边长（64×64 基准）。 */
    private static final int FACE_X = 8;
    private static final int FACE_Y = 8;
    /** 帽子（第二层）叠加层位置。 */
    private static final int HAT_X = 40;
    private static final int HAT_Y = 8;
    private static final int PATCH = 8;
    /** 官方标准皮肤边长；高清皮肤按此比例换算坐标。 */
    private static final int VANILLA_SKIN_SIZE = 64;

    /**
     * Steve 面部像素表（8×8，自上而下）。
     * <p>眼睛刻意留 2 像素皮肤间隔（{@code SWPSSPWS}）：8 像素宽的表里若让两块
     * 白+瞳直接相邻，放大后会连成一条横带、看着像面罩而不是眼睛。</p>
     */
    private static final String[] STEVE_FACE = {
            "HHHHHHHH",
            "HHHHHHHH",
            "HSSSSSSH",
            "SWPSSPWS",
            "SSSSSSSS",
            "SSSMMSSS",
            "SSSDDSSS",
            "SSSSSSSS",
    };

    /** Alex 面部像素表：长发包住两侧脸颊（最醒目的特征），眼睛是绿瞳。 */
    private static final String[] ALEX_FACE = {
            "HHHHHHHH",
            "HHHHHHHH",
            "HSSSSSSH",
            "HWPSPWSH",
            "HSSSSSSH",
            "HSSMMSSH",
            "HHSSSSHH",
            "HHSSSSHH",
    };

    /*
     * 调色板色值必须是「带 alpha 的 ARGB」：写成 0x3A2A1B 会被当成 alpha=0，
     * 每像素在 setRGB 时都变成全透明，最终得到一张看不见的空白头像（曾经踩过）。
     */

    /** Steve 调色板：棕发 + 小麦肤色 + 蓝瞳。 */
    private static final Map<Character, Integer> STEVE_PALETTE = palette(
            0xFF3A2A1B, // H 头发
            0xFFB98A62, // S 皮肤
            0xFFA2764F, // D 皮肤阴影
            0xFFFFFFFF, // W 眼白
            0xFF2E4AA8, // P 瞳孔（蓝）
            0xFF6B4630);// M 嘴

    /** Alex 调色板：橙发 + 白皙肤色 + 绿瞳。 */
    private static final Map<Character, Integer> ALEX_PALETTE = palette(
            0xFFB36A2E, // H 头发
            0xFFE8BE9E, // S 皮肤
            0xFFD3A57F, // D 皮肤阴影
            0xFFFFFFFF, // W 眼白
            0xFF4C8B3A, // P 瞳孔（绿）
            0xFF8A5A44);// M 嘴

    private PlayerHeads() {
    }

    private static Map<Character, Integer> palette(int hair, int skin, int shade, int white, int pupil, int mouth) {
        Map<Character, Integer> map = new LinkedHashMap<>();
        map.put('H', hair);
        map.put('S', skin);
        map.put('D', shade);
        map.put('W', white);
        map.put('P', pupil);
        map.put('M', mouth);
        return Map.copyOf(map);
    }

    /** 规范化皮肤模型标识：只有 {@code slim} 视为 Alex，其余一律 Steve。 */
    public static String normalizeModel(String skinModel) {
        return skinModel != null && MODEL_SLIM.equals(skinModel.toLowerCase(Locale.ROOT))
                ? MODEL_SLIM : MODEL_DEFAULT;
    }

    /**
     * 从皮肤 PNG 裁出头部头像。
     *
     * <p>帽子叠加层用 {@code SrcOver} 合成：绝大多数皮肤该层完全透明，
     * 透明像素在 SrcOver 下不会破坏底层，因此可以放心叠加。
     * 但叠加<b>只在 64×64 及以上格式进行</b>（见 {@link #hasOverlayLayer}）。</p>
     *
     * @param skin 完整皮肤图（64×64、128×128 高清皮肤，或 64×32 旧格式）
     * @param size 目标边长
     */
    public static BufferedImage cropHead(BufferedImage skin, int size) {
        int target = Math.max(8, size);
        double scale = skin.getWidth() / (double) VANILLA_SKIN_SIZE;
        BufferedImage head = new BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = head.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        drawPatch(g, skin, FACE_X, FACE_Y, scale, target);
        if (hasOverlayLayer(skin)) {
            drawPatch(g, skin, HAT_X, HAT_Y, scale, target);
        }
        g.dispose();
        return head;
    }

    private static void drawPatch(Graphics2D g, BufferedImage skin,
                                  int x, int y, double scale, int size) {
        int sx = (int) Math.round(x * scale);
        int sy = (int) Math.round(y * scale);
        int ex = (int) Math.round((x + PATCH) * scale);
        int ey = (int) Math.round((y + PATCH) * scale);
        if (ex > skin.getWidth() || ey > skin.getHeight()) {
            // 区域超出图片范围（例如只有 32×32 的残缺材质）：直接跳过，别让 drawImage 抛异常
            return;
        }
        g.drawImage(skin, 0, 0, size, size, sx, sy, ex, ey, null);
    }

    /**
     * 该皮肤是否具备第二层（帽子 / 外套）。
     *
     * <p>只有 64×64 及以上的格式才有第二层。旧的 64×32 格式没有 —— 在它上面按
     * 「帽子起点 (40,8)」取样，取到的其实是<b>腿</b>，会把整张脸盖成一块死黑
     * （实测 Notch 这类远古账号的皮肤就是 64×32，症状正是「头像全黑」）。</p>
     */
    private static boolean hasOverlayLayer(BufferedImage skin) {
        return skin.getWidth() >= VANILLA_SKIN_SIZE && skin.getHeight() >= VANILLA_SKIN_SIZE;
    }

    /** 绘制默认头像（Steve / Alex）。 */
    public static BufferedImage defaultHead(String skinModel, int size) {
        boolean slim = MODEL_SLIM.equals(normalizeModel(skinModel));
        String[] rows = slim ? ALEX_FACE : STEVE_FACE;
        Map<Character, Integer> colors = slim ? ALEX_PALETTE : STEVE_PALETTE;

        BufferedImage grid = new BufferedImage(PATCH, PATCH, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < PATCH; y++) {
            String row = rows[y];
            for (int x = 0; x < PATCH; x++) {
                Integer color = colors.get(row.charAt(x));
                grid.setRGB(x, y, color == null ? 0 : color);
            }
        }
        int target = Math.max(8, size);
        BufferedImage head = new BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = head.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(grid, 0, 0, target, target, null);
        g.dispose();
        return head;
    }

    /**
     * AWT 原生库是否可用。
     *
     * <p>结果缓存：失败一次之后不再反复撞异常，后续直接走兜底字节。</p>
     */
    public static boolean awtAvailable() {
        Boolean cached = awtOk;
        if (cached != null) {
            return cached;
        }
        try {
            // 只要触发一次原生库加载就能判断；不需要真的画东西
            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            probe.setRGB(0, 0, 0xFF000000);
            awtOk = Boolean.TRUE;
            return true;
        } catch (Throwable t) {
            awtOk = Boolean.FALSE;
            log.warn("AWT 原生库不可用（{}）：头像改用内置兜底图，界面其余部分不受影响", t.toString());
            return false;
        }
    }

    /** 默认头像的 PNG 字节；AWT 不可用时返回内置兜底图（永不返回 null）。 */
    public static byte[] defaultHeadPng(String skinModel, int size) {
        if (!awtAvailable()) {
            return fallbackHeadPng();
        }
        try {
            return png(defaultHead(skinModel, size));
        } catch (Throwable t) {
            // 画到一半才炸（例如 ImageIO 编码失败）也要降级，不能把启动流程带下去
            awtOk = Boolean.FALSE;
            log.warn("绘制默认头像失败（{}）：改用内置兜底图", t.toString());
            return fallbackHeadPng();
        }
    }

    private static byte[] fallbackHeadPng() {
        return java.util.Base64.getDecoder().decode(FALLBACK_HEAD_PNG_BASE64);
    }

    /** 编码为 PNG。 */
    public static byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ImageIO.write(image, "png", buffer);
            return buffer.toByteArray();
        } catch (IOException e) {
            // 写内存流的 PNG 编码不会因 I/O 失败，这里只可能是编码器缺失
            throw new IllegalStateException("PNG 编码失败", e);
        }
    }
}
