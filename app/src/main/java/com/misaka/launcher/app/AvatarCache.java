package com.misaka.launcher.app;

import com.misaka.launcher.core.auth.AvatarStore;
import com.misaka.launcher.core.auth.PlayerHeads;
import com.misaka.launcher.core.auth.SkinService;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.config.PortablePaths;
import javafx.application.Platform;
import javafx.scene.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 头像的统一缓存与广播。
 *
 * <p><b>解决什么问题</b>：侧边栏与账户页各自发起一次「取头像」，
 * 于是同一张头像被加载两遍，且两处的替换时机互不相干 ——
 * 用户看到的是「一个先变、一个后变，而且都很慢」。</p>
 *
 * <p><b>做法</b>：</p>
 * <ul>
 *   <li>统一按 {@link #CANONICAL_SIZE} 取图，各控件自行等比缩放：
 *       侧边栏 34px 与账户页 84px 不会各拉一份；</li>
 *   <li>同一账户只加载一次，完成后<b>广播</b>给所有订阅者 ——
 *       两处在同一帧内一起替换，不再有先后；</li>
 *   <li>订阅者以弱引用保存：页面被路由失效重建时，不会因为忘记退订而泄漏。</li>
 * </ul>
 *
 * <p>缓存键包含皮肤模型与 UUID：换模型会换成另一张默认头像，
 * 登录后 UUID 变化也应当重新拉取，两者都不需要外部显式清缓存。</p>
 */
public final class AvatarCache {

    private static final Logger log = LoggerFactory.getLogger(AvatarCache.class);

    /** 统一取图尺寸：大于任何展示尺寸，缩小时保留细节。 */
    private static final int CANONICAL_SIZE = 192;

    private final SkinService skins;
    /** 跨次启动复用的头像落盘存储。 */
    private final AvatarStore store;

    /** 缓存键 → 头像；只在 FX 线程读写。 */
    private final Map<String, Image> images = new HashMap<>();
    /** 正在后台加载的缓存键，避免并发重复请求。 */
    private final Set<String> loading = new HashSet<>();
    /** 订阅者（弱引用）。 */
    private final List<WeakReference<Runnable>> subscribers = new ArrayList<>();

    public AvatarCache(PortablePaths paths, SkinService skins) {
        this.skins = skins;
        this.store = new AvatarStore(paths);
    }

    /**
     * 请求头像。
     *
     * <p>三级取图，越靠前越快：</p>
     * <ol>
     *   <li><b>内存</b>：本次会话已拿到 → 直接返回；</li>
     *   <li><b>磁盘</b>（{@link AvatarStore}）：同步解出来立刻返回 ——
     *       这是「不需要每次打开启动器都重新加载」的关键。缓存已过期也先返回旧图，
     *       同时触发后台刷新（换皮肤不该让头像区空着等一次网络往返）；</li>
     *   <li><b>网络</b>：都没有才后台加载，完成后落盘 + 广播。</li>
     * </ol>
     *
     * @return 已就绪的头像；尚未就绪返回 {@code null}（调用方先用默认头像占位），
     *         后台加载完成后会广播，届时重新调用本方法即可拿到
     */
    public Image request(LauncherConfig.AccountEntry entry) {
        if (entry == null) {
            return null;
        }
        String key = keyOf(entry);
        Image cached = images.get(key);
        if (cached != null) {
            return cached;
        }

        AvatarStore.Cached stored = store.read(key).orElse(null);
        if (stored != null) {
            Image image = decode(stored.data());
            if (image != null) {
                images.put(key, image);
                if (stored.fresh()) {
                    return image;
                }
                log.debug("头像缓存已过期，先显示旧图并在后台刷新：{}", key);
                loadInBackground(entry, key);
                return image;
            }
        }

        loadInBackground(entry, key);
        return null;
    }

    /**
     * 后台加载头像：网络取图 → 落盘 → 回 FX 线程换图并广播。
     *
     * <p>落盘刻意放在后台线程：FX 线程只做「换图 + 通知」这两件轻活。</p>
     */
    private void loadInBackground(LauncherConfig.AccountEntry entry, String key) {
        if (!loading.add(key)) {
            return;
        }
        // 后台线程只使用快照：账户对象在 FX 线程上可能被继续修改（改名、换模型）
        AccountSnapshot snapshot = new AccountSnapshot(entry);
        Thread loader = new Thread(() -> {
            SkinService.ResolvedAvatar resolved = skins.resolveAvatar(snapshot.toEntry(), CANONICAL_SIZE);
            // 只把真实皮肤落盘：默认头像是「网络不通」的兜底，缓存它会把这个兜底固化下来，
            // 之后即便网络恢复也要等有效期过去才能换回真实皮肤
            if (resolved.real()) {
                store.write(key, resolved.data());
            }
            Image image = decode(resolved.data());
            Platform.runLater(() -> {
                loading.remove(key);
                if (image == null) {
                    log.debug("头像解码失败：{}", key);
                    return;
                }
                images.put(key, image);
                notifySubscribers();
            });
        }, "misaka-avatar");
        loader.setDaemon(true);
        loader.start();
    }

    private static Image decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        Image image = new Image(new ByteArrayInputStream(bytes));
        return image.isError() ? null : image;
    }

    /** 订阅「头像已更新」；内部以弱引用保存，调用方无需退订。 */
    public void subscribe(Runnable subscriber) {
        if (subscriber != null) {
            subscribers.add(new WeakReference<>(subscriber));
        }
    }

    /** 丢弃某个账户的缓存（账户被移除、资料变化时使用），内存与磁盘一起清。 */
    public void invalidate(String accountId) {
        if (accountId == null) {
            return;
        }
        images.keySet().removeIf(key -> key.startsWith(accountId + "|"));
        store.invalidateAccount(accountId);
    }

    private void notifySubscribers() {
        List<WeakReference<Runnable>> alive = new ArrayList<>(subscribers.size());
        for (WeakReference<Runnable> reference : subscribers) {
            Runnable subscriber = reference.get();
            if (subscriber != null) {
                alive.add(reference);
                subscriber.run();
            }
        }
        // 顺手清理已被回收的订阅者，避免列表无限增长
        subscribers.clear();
        subscribers.addAll(alive);
    }

    private static String keyOf(LauncherConfig.AccountEntry entry) {
        return (entry.id == null ? "" : entry.id)
                + "|" + PlayerHeads.normalizeModel(entry.skinModel)
                + "|" + (entry.uuid == null ? "" : entry.uuid);
    }

    /** 后台线程读取用的账户字段快照。 */
    private static final class AccountSnapshot {

        private final String id;
        private final String type;
        private final String username;
        private final String uuid;
        private final String skinModel;

        private AccountSnapshot(LauncherConfig.AccountEntry entry) {
            this.id = entry.id;
            this.type = entry.type;
            this.username = entry.username;
            this.uuid = entry.uuid;
            this.skinModel = entry.skinModel;
        }

        private LauncherConfig.AccountEntry toEntry() {
            LauncherConfig.AccountEntry copy = new LauncherConfig.AccountEntry();
            copy.id = id;
            copy.type = type;
            copy.username = username;
            copy.uuid = uuid;
            copy.skinModel = skinModel;
            return copy;
        }
    }
}
