package com.misaka.launcher.core.auth;

import com.misaka.launcher.core.config.PortablePaths;
import com.misaka.launcher.core.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 头像的本地持久化。
 *
 * <p><b>为什么需要</b>：拿到一张头像是件「贵」事 —— 要经
 * 「UUID → 官方会话档案 → 皮肤直链 → 下载 → 裁剪」好几跳，
 * 其中多个域名在国内经常不可达（每跳都要等超时）。这些以前只存在于内存缓存里，
 * 于是<b>每次启动都要重走一遍</b>：用户看到的就是「每次打开都重新加载、而且慢」。</p>
 *
 * <p><b>存储策略</b>：</p>
 * <ul>
 *   <li>最终裁好的 PNG 落到 {@code cache/avatars/}，文件名形如
 *       {@code <账户id>-<键哈希>.png}——前缀让「账户被删除/资料变化」可以精确清理，</li>
 *   <li>带有效期（{@value #TTL_MILLIS} 毫秒）：<b>过期不删</b>，调用方仍可立刻拿到旧图先显示，
 *       再在后台刷新。玩家换皮肤不该让界面空着等一次网络往返。</li>
 * </ul>
 */
public final class AvatarStore {

    private static final Logger log = LoggerFactory.getLogger(AvatarStore.class);

    /** 头像有效期；过期仍返回（标记为不新鲜），由调用方决定是否后台刷新。 */
    private static final long TTL_MILLIS = 3L * 24L * 3600L * 1000L;

    /** 一次读取的结果。 */
    public record Cached(byte[] data, boolean fresh) {
    }

    private final Path dir;

    public AvatarStore(PortablePaths paths) {
        this.dir = paths.avatarsDir();
    }

    /**
     * 读取缓存。
     *
     * @return 命中时返回数据；{@link Cached#fresh()} 为 false 表示已过期（仍可用，建议后台刷新）
     */
    public Optional<Cached> read(String key) {
        Path file = fileOf(key);
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            byte[] data = Files.readAllBytes(file);
            if (data.length == 0) {
                return Optional.empty();
            }
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis();
            return Optional.of(new Cached(data, age >= 0 && age < TTL_MILLIS));
        } catch (IOException e) {
            log.debug("读取头像缓存失败 {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /** 写入缓存（尽力而为：失败不影响界面显示）。 */
    public void write(String key, byte[] png) {
        if (key == null || key.isBlank() || png == null || png.length == 0) {
            return;
        }
        Path file = fileOf(key);
        if (file == null) {
            return;
        }
        try {
            FileUtils.ensureParent(file);
            FileUtils.atomicWrite(file, png);
        } catch (IOException e) {
            log.debug("写入头像缓存失败 {}: {}", file, e.getMessage());
        }
    }

    /** 丢弃某个账户的全部头像缓存（账户被移除、登录信息变化时调用）。 */
    public void invalidateAccount(String accountId) {
        String prefix = safeSegment(accountId) + "-";
        if (accountId == null || accountId.isBlank() || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> targets = stream
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .toList();
            for (Path target : targets) {
                FileUtils.deleteQuietly(target);
            }
        } catch (IOException e) {
            log.debug("清理头像缓存失败 {}: {}", dir, e.getMessage());
        }
    }

    /**
     * 缓存文件路径。
     * <p>文件名 = 账户段 + 完整键的哈希：账户段用于精确清理，哈希保证
     * 「同一账户的不同皮肤模型 / UUID」互不覆盖。</p>
     *
     * @return 键不合法（无法取出账户段）时返回 null
     */
    private Path fileOf(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        int separator = key.indexOf('|');
        String accountPart = separator > 0 ? key.substring(0, separator) : key;
        String segment = safeSegment(accountPart);
        if (segment.isBlank()) {
            return null;
        }
        return dir.resolve(segment + "-" + hash(key) + ".png");
    }

    /** 只保留安全的文件名片段：账户 id 理论上就是 UUID，这里仍然防一手手改配置。 */
    private static String safeSegment(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (char ch : value.toCharArray()) {
            if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
