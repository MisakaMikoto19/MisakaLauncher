package com.misaka.launcher.ui.shell;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 页面路由。
 * <p>两处刻意的设计：</p>
 * <ul>
 *   <li><b>懒创建 + 缓存</b>：页面首次进入时才构建，之后复用；
 *       这样启动速度不受页面数量影响；</li>
 *   <li><b>失效重建</b>：切换实例等会改变数据源的场景调用 {@link #invalidate}，
 *       下次进入重新构建，避免显示上一个实例的残留数据。</li>
 * </ul>
 */
public final class ViewRouter {

    private static final Logger log = LoggerFactory.getLogger(ViewRouter.class);

    /** 页面工厂。 */
    public interface ViewFactory {
        Node create();
    }

    private final StackPane host;
    private final Map<String, ViewFactory> factories = new LinkedHashMap<>();
    private final Map<String, Node> cache = new LinkedHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();

    private String currentId;

    public ViewRouter(StackPane host) {
        this.host = host;
    }

    public void register(String id, ViewFactory factory) {
        factories.put(id, factory);
    }

    /** 注册页面切换监听（用于同步导航栏选中态）。 */
    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public String currentId() {
        return currentId;
    }

    /**
     * 当前页面的节点；未创建时返回 null。
     * <p>用于「打开设置并定位分页」这类需要与具体页面交互的场景 ——
     * 外壳不应直接持有页面实例，但可以按接口能力做一次类型判定。</p>
     */
    public Node currentNode() {
        return currentId == null ? null : cache.get(currentId);
    }

    /** 首次注册的页面 id（作为默认首页）。 */
    public String defaultPage() {
        return factories.keySet().stream().findFirst().orElse("");
    }

    /** 切换到指定页面；同一个页面重复调用不产生动画。 */
    public void show(String id) {
        if (id == null || !factories.containsKey(id)) {
            log.warn("未知页面: {}", id);
            return;
        }
        if (id.equals(currentId)) {
            return;
        }
        Node node = cache.computeIfAbsent(id, key -> {
            Node created = factories.get(key).create();
            created.setOpacity(0);
            return created;
        });

        host.getChildren().setAll(node);
        currentId = id;
        animateIn(node);
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    /**
     * 标记页面失效。
     *
     * <p>失效的是<b>当前正在显示</b>的页面时，必须立刻重建并放回 —— 只清空容器会让
     * 内容区空白，而侧边栏的选中态还停在原页面上，看起来就是「菜单选着、右边什么都没有」
     * （真实故障：游戏退出回调会失效 home/logs，用户正停在日志页 → 界面空白，
     * 重选一次菜单才恢复）。重建是确定性动作，失败数据已被丢弃，不存在竞态。</p>
     */
    public void invalidate(String id) {
        cache.remove(id);
        if (id.equals(currentId)) {
            currentId = null;
            show(id);
        }
    }

    public void invalidateAll() {
        cache.clear();
        host.getChildren().clear();
        currentId = null;
    }

    /** 页面切换动画：淡入 + 轻微右移，给「切页」一个空间感。 */
    private void animateIn(Node node) {
        FadeTransition fade = new FadeTransition(Duration.millis(180), node);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.setInterpolator(Interpolator.EASE_OUT);

        TranslateTransition slide = new TranslateTransition(Duration.millis(220), node);
        slide.setFromX(14);
        slide.setToX(0);
        slide.setInterpolator(Interpolator.EASE_OUT);

        fade.play();
        slide.play();
    }
}
