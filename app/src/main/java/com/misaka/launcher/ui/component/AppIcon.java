package com.misaka.launcher.ui.component;

import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * 应用图标：窗口图标与系统任务栏图标。
 *
 * <p>图标来自构建期由项目根目录设计稿（{@code icon.webp}）转出的多尺寸 PNG
 * （见 {@code IconGenerator}）。之所以不在运行期直接读设计稿：
 * <b>JavaFX 不支持 WebP</b>，读出来只会得到一张空图且不报错。</p>
 *
 * <p>这里把全部尺寸一起注册：Windows 任务栏在不同 DPI 缩放下会挑选不同像素尺寸
 * （125% 缩放要 24/32px、200% 要 48/64px），只给一张图会被系统拉伸得发虚。</p>
 */
public final class AppIcon {

    private static final Logger log = LoggerFactory.getLogger(AppIcon.class);

    /** 与 {@code app/build.gradle.kts} 中 copyIconResource 的落盘结构一致。 */
    private static final String RESOURCE_PREFIX = "/icons/icon-";

    /** 需与 {@code IconGenerator.SIZES} 保持一致。 */
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256, 512};

    private AppIcon() {
    }

    /**
     * 把应用图标应用到窗口。
     *
     * <p>必须在 {@code stage.show()} 之前调用：任务栏条目在窗口首次显示时就被系统创建，
     * 之后再补图标不会刷新已经存在的条目。</p>
     */
    public static void apply(Stage stage) {
        int loaded = 0;
        for (int size : SIZES) {
            Image image = load(RESOURCE_PREFIX + size + ".png");
            if (image != null) {
                stage.getIcons().add(image);
                loaded++;
            }
        }
        if (loaded == 0) {
            // 图标缺失只是观感问题，绝不能因此影响启动；但要留下可诊断的线索
            log.warn("未找到应用图标资源（{}*），将使用系统默认图标；"
                    + "请确认 generateIcons / copyIconResource 已执行", RESOURCE_PREFIX);
        } else {
            log.info("已注册 {} 档尺寸的应用图标（窗口 / 任务栏）", loaded);
        }
    }

    /**
     * 取应用图标本体（挑最接近期望尺寸的那一档）。
     *
     * <p>供界面复用（例如标题栏品牌图标），保证「软件图标」在全应用内只有一份来源 ——
     * 都是构建期由 {@code icon.webp} 转出的 PNG。</p>
     *
     * @param size 期望边长（像素）
     * @return 图标；资源缺失时返回 {@code null}
     */
    public static Image image(double size) {
        int best = SIZES[0];
        for (int candidate : SIZES) {
            if (Math.abs(candidate - size) < Math.abs(best - size)) {
                best = candidate;
            }
        }
        return load(RESOURCE_PREFIX + best + ".png");
    }

    private static Image load(String resource) {
        try (InputStream in = AppIcon.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            Image image = new Image(in);
            if (image.isError()) {
                log.warn("图标资源 {} 解码失败: {}", resource,
                        image.getException() == null ? "未知原因" : image.getException().getMessage());
                return null;
            }
            return image;
        } catch (IOException e) {
            log.warn("读取图标资源 {} 失败: {}", resource, e.getMessage());
            return null;
        }
    }
}
