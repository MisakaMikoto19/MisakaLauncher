package com.misaka.launcher.ui.component;

import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 资源包缩略图（{@code pack.png}）的缓存。
 *
 * <p><b>为什么要缓存字节</b>：每次勾选、拖拽排序后列表都会重建，若每次都重新打开
 * zip 读 {@code pack.png}，几十个包就是几十次磁盘随机读 —— 排序一次卡一下，
 * 手感全毁。这里以「文件名 + 体积」为键缓存已解码的图片：换包（体积变了）会自然失效，
 * 用户刚补上的 {@code pack.png} 也能在下次刷新时被读到（失败不缓存）。</p>
 */
public final class PackIcons {

    /** 内存里最多保留多少张；资源包图标比模组更少，200 足够。 */
    private static final int MAX_ENTRIES = 200;

    /** 只在 JavaFX 线程访问，不需要并发容器。 */
    private static final Map<String, Image> CACHE = new LinkedHashMap<>();

    private PackIcons() {
    }

    /**
     * 取缩略图。命中缓存时<b>不会</b>调用 {@code pngSupplier}。
     *
     * @param cacheKey     缓存键（调用方负责包含「会变的部分」，如文件体积）
     * @param pngSupplier  取 PNG 原始字节；返回 {@code null} 表示这个包没有图标
     * @param size         显示边长；内部按 2 倍解码，避免高 DPI 发虚
     * @return 图片；没有图标时返回 {@code null}（由调用方画占位）
     */
    public static Image image(String cacheKey, Supplier<byte[]> pngSupplier, double size) {
        Image cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        byte[] png = pngSupplier.get();
        if (png == null || png.length == 0) {
            return null;
        }
        if (CACHE.size() >= MAX_ENTRIES) {
            CACHE.clear();
        }
        try {
            Image image = new Image(new ByteArrayInputStream(png), size * 2, size * 2, true, true);
            if (image.isError()) {
                return null;
            }
            CACHE.put(cacheKey, image);
            return image;
        } catch (RuntimeException e) {
            // 损坏的 pack.png 不该让整行渲染失败
            return null;
        }
    }
}
