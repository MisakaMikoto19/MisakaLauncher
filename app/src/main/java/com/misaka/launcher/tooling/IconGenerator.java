package com.misaka.launcher.tooling;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * 应用图标生成器（构建期工具，不参与运行期）。
 *
 * <p><b>图标来源优先级</b>：项目根目录的设计稿（{@code icon.webp} 等）→ 程序化绘制。
 * 设计稿缺失时按产品设计语言绘制（深色底 + 紫青渐变 + 玻璃高光），
 * 保证「没放设计稿也能构建出完整图标」，而不是让打包失败。</p>
 *
 * <p><b>为什么解码必须在构建期</b>：JavaFX 与 JDK 自带 ImageIO 都<b>不支持 WebP</b>，
 * 运行期拿到 .webp 只会得到空图像（且不报错）。因此这里在构建期解码并转成 PNG，
 * 运行期只读 PNG —— 解码器依赖只在构建期类路径上，不会进入分发包。</p>
 *
 * <p>产出 {@code icon-<尺寸>.png}（运行期窗口/任务栏图标按 DPI 挑选）、
 * {@code icon.png}（单文件形态）以及 ICO / ICNS（三个平台 jpackage 所需）。</p>
 *
 * <p>用法：{@code java ... IconGenerator <输出目录> [设计稿路径]}</p>
 */
public final class IconGenerator {

    /** 设计系统用色。 */
    private static final Color BG_DARK = new Color(0x0B0E14);
    private static final Color BG_LIGHT = new Color(0x141926);
    private static final Color ACCENT_A = new Color(0x7C5CFF);
    private static final Color ACCENT_B = new Color(0x22D3EE);
    private static final Color ACCENT_C = new Color(0x5B8CFF);

    /** 生成的全部尺寸；ICO 与 ICNS 都会用到。 */
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256, 512};

    private IconGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path outputDir = args.length > 0 ? Path.of(args[0]) : Path.of("app/build/generated/images");
        Path artwork = args.length > 1 ? Path.of(args[1]) : null;
        Files.createDirectories(outputDir);

        BufferedImage design = loadArtwork(artwork);
        // 统一入口：有设计稿就按尺寸缩放，没有就按设计系统程序化绘制
        IntFunction<BufferedImage> renderer = design == null
                ? IconGenerator::render : size -> scale(design, size);

        List<BufferedImage> images = new ArrayList<>();
        for (int size : SIZES) {
            BufferedImage image = renderer.apply(size);
            images.add(image);
            // 每个尺寸单独落盘：运行期窗口 / 任务栏图标会按系统 DPI 挑选最合适的那张，
            // 只提供一张会被系统拉伸得发虚（150% 缩放的任务栏尤其明显）
            ImageIO.write(image, "png", outputDir.resolve("icon-" + size + ".png").toFile());
        }

        // 单文件发布形态与 Linux jpackage 取这张最大的
        ImageIO.write(images.get(images.size() - 1), "png", outputDir.resolve("icon.png").toFile());

        List<int[]> icoEntries = List.of(
                new int[]{16, indexOf(16)}, new int[]{32, indexOf(32)},
                new int[]{48, indexOf(48)}, new int[]{256, indexOf(256)});
        writeIco(outputDir.resolve("icon.ico"), images, icoEntries);

        writeIcns(outputDir.resolve("icon.icns"), renderer, new int[]{128, 256, 512});

        System.out.println("图标已生成到 " + outputDir.toAbsolutePath()
                + "（来源：" + (design == null ? "程序化绘制" : artwork.getFileName()) + "）");
    }

    // ------------------------------------------------------------------
    // 设计稿：读取与缩放
    // ------------------------------------------------------------------

    /**
     * 读取设计稿。
     *
     * @param source 设计稿路径；{@code null} 或文件不存在时返回 {@code null}（走程序化绘制）
     */
    private static BufferedImage loadArtwork(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            System.out.println("未找到图标设计稿" + (source == null ? "" : "：" + source)
                    + "，改用程序化绘制的图标");
            return null;
        }
        BufferedImage raw = ImageIO.read(source.toFile());
        if (raw == null) {
            // 这里必须硬失败：静默退回程序化图标会让用户以为「换了图标却没生效」，
            // 而真正的原因（缺少 WebP 解码器）被完全掩盖
            throw new IOException("无法解码图标设计稿 " + source.toAbsolutePath()
                    + "。JavaFX 与 JDK 自带 ImageIO 都不支持 WebP，"
                    + "构建脚本需在构建期类路径引入 com.twelvemonkeys.imageio:imageio-webp");
        }
        System.out.println("图标设计稿：" + source.getFileName() + " "
                + raw.getWidth() + "x" + raw.getHeight()
                + (Math.min(raw.getWidth(), raw.getHeight()) >= 256
                ? "" : "（小图按最近邻放大以保留像素风格；如需大尺寸下的平滑观感请提供 ≥256px 源图）"));
        return square(raw);
    }

    /** 居中裁剪为正方形：图标必须是正方形，非正方形源图直接拉扯会明显变形。 */
    private static BufferedImage square(BufferedImage raw) {
        int side = Math.min(raw.getWidth(), raw.getHeight());
        if (side == raw.getWidth() && side == raw.getHeight()) {
            return raw;
        }
        int x = (raw.getWidth() - side) / 2;
        int y = (raw.getHeight() - side) / 2;
        BufferedImage cropped = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = cropped.createGraphics();
        g.drawImage(raw, 0, 0, side, side, x, y, x + side, y + side, null);
        g.dispose();
        return cropped;
    }

    /**
     * 把设计稿缩放到目标尺寸。
     *
     * <p><b>放大用最近邻</b>：设计稿常常是 36×36 这类像素画（Minecraft 素材本身也是像素画），
     * 用双三次放大到 256px 会糊成一团、失去方块边缘；最近邻虽然块状，但那正是像素画应有的观感。</p>
     *
     * <p><b>缩小用双三次</b>：最近邻缩小时会直接丢弃像素（36→16 要丢掉一半以上内容），
     * 必须平滑采样；并按「单步不超过 2 倍」逐级进行，避免一步缩小产生锯齿与摩尔纹。</p>
     */
    private static BufferedImage scale(BufferedImage source, int size) {
        if (source.getWidth() <= size) {
            return resize(source, size, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        }
        BufferedImage current = source;
        // 防御性上限：极端情况下四舍五入可能让尺寸不再变化，避免死循环
        for (int guard = 0; guard < 16 && current.getWidth() > size; guard++) {
            double ratio = (double) size / current.getWidth();
            double step = ratio < 0.5 ? 0.5 : ratio;
            int next = Math.max(size, (int) Math.round(current.getWidth() * step));
            current = resize(current, next, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        }
        return current.getWidth() == size
                ? current
                : resize(current, size, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static BufferedImage resize(BufferedImage source, int size, Object interpolation) {
        BufferedImage target = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, 0, 0, size, size, null);
        g.dispose();
        return target;
    }

    private static int indexOf(int size) {
        for (int i = 0; i < SIZES.length; i++) {
            if (SIZES[i] == size) {
                return i;
            }
        }
        throw new IllegalArgumentException("未支持的尺寸: " + size);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    /**
     * 绘制单个尺寸的图标。
     * <p>使用归一化坐标（0..1）再放大，保证任何尺寸下比例一致。</p>
     */
    static BufferedImage render(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        double s = size;
        double radius = s * 0.22;

        // 1) 圆角底板：斜向渐变
        g.setPaint(new LinearGradientPaint(
                new Point2D.Double(0, 0), new Point2D.Double(s, s),
                new float[]{0f, 0.55f, 1f},
                new Color[]{BG_LIGHT, BG_DARK, new Color(0x060A12)}));
        g.fill(new RoundRectangle2D.Double(0, 0, s, s, radius, radius));

        // 2) 左上角的柔和光晕（玻璃质感的关键）
        g.setPaint(new RadialGradientPaint(
                new Point2D.Double(s * 0.26, s * 0.2), (float) (s * 0.72),
                new float[]{0f, 1f},
                new Color[]{new Color(124, 92, 255, 105), new Color(124, 92, 255, 0)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fill(new RoundRectangle2D.Double(0, 0, s, s, radius, radius));

        // 3) 主体：立方体线框（Minecraft 的方块意象）
        double cx = s * 0.5;
        double cy = s * 0.52;
        double w = s * 0.30;
        double hFactor = 0.58;

        Path2D.Double cube = new Path2D.Double();
        // 顶面菱形
        cube.moveTo(cx, cy - w * hFactor);
        cube.lineTo(cx + w, cy - w * hFactor * 0.45);
        cube.lineTo(cx, cy + w * 0.12);
        cube.lineTo(cx - w, cy - w * hFactor * 0.45);
        cube.closePath();

        // 左下侧面
        Path2D.Double left = new Path2D.Double();
        left.moveTo(cx - w, cy - w * hFactor * 0.45);
        left.lineTo(cx - w, cy + w * (hFactor * 0.45));
        left.lineTo(cx, cy + w * 1.02);
        left.lineTo(cx, cy + w * 0.12);
        left.closePath();

        // 右下侧面
        Path2D.Double right = new Path2D.Double();
        right.moveTo(cx + w, cy - w * hFactor * 0.45);
        right.lineTo(cx + w, cy + w * (hFactor * 0.45));
        right.lineTo(cx, cy + w * 1.02);
        right.lineTo(cx, cy + w * 0.12);
        right.closePath();

        g.setPaint(new LinearGradientPaint(
                new Point2D.Double(cx - w, cy - w), new Point2D.Double(cx + w, cy + w),
                new float[]{0f, 1f}, new Color[]{ACCENT_A, ACCENT_B}));

        float stroke = Math.max(1f, (float) (s * 0.052));
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        g.setColor(new Color(ACCENT_A.getRed(), ACCENT_A.getGreen(), ACCENT_A.getBlue(), 46));
        g.fill(cube);

        g.setPaint(new LinearGradientPaint(
                new Point2D.Double(cx - w, cy - w), new Point2D.Double(cx + w, cy + w),
                new float[]{0f, 0.5f, 1f},
                new Color[]{ACCENT_A, ACCENT_C, ACCENT_B}));
        g.draw(cube);
        g.draw(left);
        g.draw(right);

        // 4) 顶面高光：强化「玻璃」观感
        g.setPaint(new GradientPaint(
                (float) (cx - w), (float) (cy - w * hFactor),
                new Color(255, 255, 255, 120),
                (float) cx, (float) (cy + w * 0.12),
                new Color(255, 255, 255, 0)));
        g.fill(cube);

        // 5) 右下角光点
        g.setPaint(new RadialGradientPaint(
                new Point2D.Double(s * 0.78, s * 0.8), (float) (s * 0.3),
                new float[]{0f, 1f},
                new Color[]{new Color(34, 211, 238, 130), new Color(34, 211, 238, 0)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fill(new Ellipse2D.Double(s * 0.48, s * 0.5, s * 0.6, s * 0.6));

        // 6) 内描边：让图标在深色界面上有边界感
        g.setStroke(new BasicStroke(Math.max(1f, (float) (s * 0.012))));
        g.setColor(new Color(255, 255, 255, 38));
        double inset = s * 0.006;
        g.draw(new RoundRectangle2D.Double(inset, inset, s - inset * 2, s - inset * 2,
                radius - inset, radius - inset));

        g.dispose();
        return image;
    }

    // ------------------------------------------------------------------
    // ICO：Windows
    // ------------------------------------------------------------------

    /**
     * 写出多尺寸 ICO。
     * <p>尺寸 ≥ 48 的条目直接内嵌 PNG 数据（Vista 起支持），小尺寸同样用 PNG 也可被
     * Windows 正确加载，因此无需实现 BMP+掩码那套旧格式。</p>
     */
    static void writeIco(Path target, List<BufferedImage> images, List<int[]> entries) throws IOException {
        List<byte[]> payloads = new ArrayList<>();
        for (int[] entry : entries) {
            payloads.add(toPng(images.get(entry[1])));
        }

        try (OutputStream fileOut = Files.newOutputStream(target);
             DataOutputStream out = new DataOutputStream(fileOut)) {
            writeLittleEndianShort(out, 0);              // reserved
            writeLittleEndianShort(out, 1);              // type = icon
            writeLittleEndianShort(out, entries.size()); // count

            int offset = 6 + entries.size() * 16;
            for (int i = 0; i < entries.size(); i++) {
                int size = entries.get(i)[0];
                byte[] payload = payloads.get(i);
                out.writeByte(size >= 256 ? 0 : size); // 宽度（0 表示 256）
                out.writeByte(size >= 256 ? 0 : size); // 高度
                out.writeByte(0);                      // 调色板数量
                out.writeByte(0);                      // reserved
                writeLittleEndianShort(out, 1);         // color planes
                writeLittleEndianShort(out, 32);        // bits per pixel
                writeLittleEndianInt(out, payload.length);
                writeLittleEndianInt(out, offset);
                offset += payload.length;
            }
            for (byte[] payload : payloads) {
                out.write(payload);
            }
        }
    }

    private static void writeLittleEndianShort(DataOutputStream out, int value) throws IOException {
        out.writeByte(value & 0xFF);
        out.writeByte((value >>> 8) & 0xFF);
    }

    private static void writeLittleEndianInt(DataOutputStream out, int value) throws IOException {
        out.writeByte(value & 0xFF);
        out.writeByte((value >>> 8) & 0xFF);
        out.writeByte((value >>> 16) & 0xFF);
        out.writeByte((value >>> 24) & 0xFF);
    }

    // ------------------------------------------------------------------
    // ICNS：macOS
    // ------------------------------------------------------------------

    /** 图标类型标识与对应尺寸。 */
    private static final String[] ICNS_TYPES = {"ic07", "ic08", "ic09", "ic10"};
    private static final int[] ICNS_SIZES = {128, 256, 512, 1024};

    /**
     * 写出 ICNS。
     * <p>现代 macOS 接受 PNG 数据条目，因此无需实现苹果私有的压缩位图格式。</p>
     *
     * @param renderer 按尺寸取图（设计稿缩放或程序化绘制），1024 这类大尺寸由它现算
     */
    static void writeIcns(Path target, IntFunction<BufferedImage> renderer, int[] requiredSizes)
            throws IOException {
        List<String> types = new ArrayList<>();
        List<byte[]> payloads = new ArrayList<>();

        for (int i = 0; i < ICNS_SIZES.length; i++) {
            int size = ICNS_SIZES[i];
            boolean needed = false;
            for (int required : requiredSizes) {
                if (required == size) {
                    needed = true;
                    break;
                }
            }
            // 1024 用作最大尺寸的兜底（部分工具要求存在）
            if (!needed && size != 1024) {
                continue;
            }
            types.add(ICNS_TYPES[i]);
            payloads.add(toPng(renderer.apply(size)));
        }

        int total = 8;
        for (byte[] payload : payloads) {
            total += 8 + payload.length;
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(total);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeBytes("icns");
            out.writeInt(total);
            for (int i = 0; i < types.size(); i++) {
                out.writeBytes(types.get(i));
                out.writeInt(8 + payloads.get(i).length);
                out.write(payloads.get(i));
            }
        }
        // ICNS 的总长度字段为大端，writeInt 已是大端序，无需额外修正
        Files.write(target, buffer.toByteArray());
    }

    private static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "png", buffer);
        return buffer.toByteArray();
    }
}
