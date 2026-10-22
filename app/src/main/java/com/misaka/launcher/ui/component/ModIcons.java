package com.misaka.launcher.ui.component;

import javafx.scene.image.Image;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模组图标的加载与缓存。
 *
 * <p><b>为什么用 {@link Image} 的 backgroundLoading</b>：列表里可能有几十行图标，
 * 逐个自己开线程下载、再手动 post 回 UI 线程是最容易写错的那类代码（漏掉取消、贴错行）。
 * JavaFX 的 {@code Image} 自带后台加载，把加载中的对象先交给 {@code ImageView}，
 * 加载完成时它会自己重绘 —— 行被复用也不会串图。</p>
 *
 * <p>缓存按「URL + 尺寸」索引，并设上限：翻页翻上十几页时不该把几十 MB 的
 * 缩略图一直攥在内存里。</p>
 */
public final class ModIcons {

    /** 内存里最多保留多少张图标；超了就清空重来（图标很小，代价可忽略）。 */
    private static final int MAX_ENTRIES = 400;

    /** 只允许在 JavaFX 线程访问，因此不需要并发容器。 */
    private static final Map<String, Image> CACHE = new LinkedHashMap<>();

    private ModIcons() {
    }

    /**
     * 取图标（可能仍在后台加载，返回的 {@code Image} 会随后自行完成）。
     *
     * @param url  图标地址；为空或非法时返回 {@code null}
     * @param size 显示边长（像素）；内部按 2 倍请求，避免高 DPI 下发虚
     */
    public static Image image(String url, double size) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String key = url + "|" + size;
        Image cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (CACHE.size() >= MAX_ENTRIES) {
            CACHE.clear();
        }
        // 参数顺序：requestedWidth / requestedHeight / preserveRatio / smooth / backgroundLoading
        Image image = new Image(url, size * 2, size * 2, true, true, true);
        CACHE.put(key, image);
        return image;
    }
}
