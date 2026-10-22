package com.misaka.launcher.core.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.config.PortablePaths;
import com.misaka.launcher.core.util.FileUtils;
import com.misaka.launcher.core.util.HashUtils;
import com.misaka.launcher.core.util.HttpUtils;
import com.misaka.launcher.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * 皮肤与头像获取（带本地缓存）。
 *
 * <p><b>头像来源</b>（{@link #avatarBytes}）：</p>
 * <ol>
 *   <li><b>真实皮肤</b>：用账户自己的 UUID 查 Mojang 官方会话档案，取出皮肤直链后下载并
 *       <b>裁剪头部</b>（含帽子叠加层）。Mojang 没有头像接口，
 *       这是拿到「官方皮肤头像」的唯一途径；</li>
 *   <li><b>默认头像</b>：按皮肤模型绘制 Steve / Alex，<b>永远不会失败</b>。</li>
 * </ol>
 *
 * <p><b>不做按用户名查询</b>：那会把与玩家同名的<b>别人</b>的皮肤当成他的头像，
 * 而且在账号体系只剩微软正版之后也没有对应场景了。也<b>不接任何第三方皮肤站</b> ——
 * 头像只可能来自玩家自己账号在官方服务上的皮肤。</p>
 *
 * <p>缓存位于数据目录的 {@code cache/skins/}，按 URL 哈希命名并带有效期，
 * 避免每次打开界面都发起请求。</p>
 */
public final class SkinService {

    private static final Logger log = LoggerFactory.getLogger(SkinService.class);

    /** Mojang 官方会话档案地址前缀（公开接口，无需鉴权）。 */
    static final String MOJANG_SESSION_ROOT =
            "https://sessionserver.mojang.com/session/minecraft/profile/";

    /** 皮肤图片缓存有效期。 */
    private static final long CACHE_TTL_MILLIS = 7L * 24L * 3600L * 1000L;

    /**
     * 「会话档案 → 皮肤直链」解析结果的有效期。
     * <p>取得比图片缓存短：玩家换了皮肤就会得到新的纹理地址，
     * 但如果每次都去查会话档案，Mojang 的速率限制会直接把请求打回
     * （界面每次打开、侧边栏每次刷新都会要头像）。6 小时是两者的折中。</p>
     */
    private static final long RESOLVE_TTL_MILLIS = 6L * 3600L * 1000L;

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final PortablePaths paths;
    private final LauncherConfig.AuthSettings settings;
    private final String sessionRoot;

    public SkinService(PortablePaths paths, LauncherConfig.AuthSettings settings) {
        this(paths, settings, MOJANG_SESSION_ROOT);
    }

    /**
     * 注入官方会话服务地址。
     * <p>存在的意义只有一个：让单测把「官方皮肤」链路指向本地桩服务器。
     * 单测绝不能打到真实网络，否则会变成依赖外部数据的脆弱用例。</p>
     */
    SkinService(PortablePaths paths, LauncherConfig.AuthSettings settings, String sessionRoot) {
        this.paths = paths;
        this.settings = settings;
        this.sessionRoot = sessionRoot;
    }

    /**
     * 取玩家头像（PNG 字节）。
     *
     * <p><b>永不失败</b>：取不到真实皮肤时返回按皮肤模型绘制的默认 Steve / Alex。
     * 调用方因此不需要（也不应该）再用「首字母」之类的降级表现 ——
     * 那会让「没有皮肤」「加载失败」「名字恰好一个字」三种情况长得一模一样。</p>
     *
     * @param entry 账户；可为 null（此时直接给默认头像）
     * @param size  期望边长（像素）
     */
    public byte[] avatarBytes(LauncherConfig.AccountEntry entry, int size) {
        return resolveAvatar(entry, size).data();
    }

    /**
     * 头像解析结果。
     *
     * @param data 头像 PNG 字节
     * @param real 是否来自真实皮肤；{@code false} 表示是本地绘制的默认 Steve / Alex
     */
    public record ResolvedAvatar(byte[] data, boolean real) {
    }

    /**
     * 取头像并说明来源，供调用方决定是否值得缓存。
     *
     * <p>「是否真实皮肤」这个信息很关键：默认头像只是网络不通时的兜底，
     * 若把它也当作正常结果缓存下来，之后即使网络恢复也只能等缓存过期才换回真实皮肤。</p>
     */
    public ResolvedAvatar resolveAvatar(LauncherConfig.AccountEntry entry, int size) {
        int effectiveSize = size > 0 ? size : Math.max(16, settings.avatarSize);
        Optional<byte[]> real = realAvatar(entry, effectiveSize);
        return real.map(bytes -> new ResolvedAvatar(bytes, true))
                .orElseGet(() -> new ResolvedAvatar(
                        PlayerHeads.defaultHeadPng(modelOf(entry), effectiveSize), false));
    }

    /** 真实皮肤裁剪出的头像；取不到返回 empty（由调用方回退到默认头像）。 */
    private Optional<byte[]> realAvatar(LauncherConfig.AccountEntry entry, int size) {
        Optional<Path> skin = skinFile(entry);
        if (skin.isPresent()) {
            try {
                java.awt.image.BufferedImage image =
                        javax.imageio.ImageIO.read(skin.get().toFile());
                if (image != null) {
                    return Optional.of(PlayerHeads.png(PlayerHeads.cropHead(image, size)));
                }
            } catch (IOException e) {
                log.debug("裁剪皮肤头部失败: {}", e.getMessage());
            }
        }
        return Optional.empty();
    }



    /**
     * 读取皮肤纹理直链。
     *
     * <p>只查 Mojang 官方会话档案（公开接口，按 UUID）；账户体系里已经没有别的来源。</p>
     */
    public Optional<String> skinTextureUrl(LauncherConfig.AccountEntry entry) {
        if (entry == null || entry.uuid == null || entry.uuid.isBlank()) {
            return Optional.empty();
        }
        // 官方档案需要无连字符 UUID
        String uuid = entry.uuidWithoutDashes();
        if (uuid.isBlank()) {
            return Optional.empty();
        }
        String apiUrl = sessionRoot + uuid;
        return cachedText("mojang:" + uuid, () -> fetchSkinTexture(apiUrl));
    }

    /** 查询会话档案并解析出皮肤直链。 */
    private Optional<String> fetchSkinTexture(String apiUrl) {
        try {
            HttpUtils.Result result = HttpUtils.send("GET", apiUrl,
                    java.util.Map.of(), null, null, TIMEOUT);
            if (!result.isSuccess()) {
                log.debug("皮肤档案查询失败（HTTP {}）：{}", result.status(), apiUrl);
                return Optional.empty();
            }
            // 204 No Content（Blessing Skin 系对「查无此人」的应答）没有响应体，
            // 直接交给 JSON 解析会抛异常
            if (result.body() == null || result.body().isBlank()) {
                log.debug("皮肤档案为空（HTTP {}）：{}", result.status(), apiUrl);
                return Optional.empty();
            }
            return parseTextureUrl(result.json(), "SKIN");
        } catch (IOException e) {
            log.debug("读取皮肤纹理失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 带缓存的文本解析结果。
     * <p>只缓存非空结果：把「查不到」也缓存下来的话，一次网络抖动会让该账户在缓存期内
     * 永远显示默认头像。</p>
     */
    private Optional<String> cachedText(String key,
                                        java.util.function.Supplier<Optional<String>> loader) {
        Path file = cacheFile("text:" + key, "txt");
        if (Files.isRegularFile(file)
                && System.currentTimeMillis() - lastModified(file) < RESOLVE_TTL_MILLIS) {
            try {
                String cached = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!cached.isEmpty()) {
                    return Optional.of(cached);
                }
            } catch (IOException e) {
                log.debug("读取解析缓存失败: {}", e.getMessage());
            }
        }
        Optional<String> resolved = loader.get();
        resolved.filter(value -> !value.isBlank()).ifPresent(value -> {
            try {
                FileUtils.ensureParent(file);
                Files.writeString(file, value, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.debug("写入解析缓存失败: {}", e.getMessage());
            }
        });
        return resolved;
    }

    /**
     * 下载并缓存皮肤纹理，返回本地 PNG 路径。
     * <p>只缓存皮肤本身（按 URL 哈希）：头部裁剪是纯本地像素操作，
     * 再缓存一层反而增加失效状态。</p>
     */
    public Optional<Path> skinFile(LauncherConfig.AccountEntry entry) {
        return skinTextureUrl(entry).flatMap(url -> downloadToCache(url, "png"));
    }

    /** 该账户应使用的默认头像模型。 */
    private static String modelOf(LauncherConfig.AccountEntry entry) {
        return PlayerHeads.normalizeModel(entry == null ? null : entry.skinModel);
    }

    /** 从官方会话档案响应中解析纹理地址。 */
    private Optional<String> parseTextureUrl(JsonNode profile, String textureType) {
        JsonNode properties = profile.get("properties");
        if (properties == null || !properties.isArray()) {
            return Optional.empty();
        }
        for (JsonNode property : properties) {
            if (!"textures".equals(JsonUtils.text(property, "name", ""))) {
                continue;
            }
            String value = JsonUtils.text(property, "value", "");
            if (value.isBlank()) {
                continue;
            }
            try {
                String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                JsonNode textures = JsonUtils.read(decoded).get("textures");
                if (textures == null) {
                    continue;
                }
                JsonNode texture = textures.get(textureType);
                if (texture != null) {
                    String url = JsonUtils.text(texture, "url", "");
                    if (!url.isBlank()) {
                        return Optional.of(url);
                    }
                }
            } catch (IllegalArgumentException | IOException e) {
                log.debug("解析皮肤属性失败: {}", e.getMessage());
            }
        }
        return Optional.empty();
    }

    /** 下载到缓存目录；失败返回 empty。 */
    private Optional<Path> downloadToCache(String url, String extension) {
        Path cacheFile = cacheFile(url, extension);
        if (Files.isRegularFile(cacheFile)
                && System.currentTimeMillis() - lastModified(cacheFile) < CACHE_TTL_MILLIS) {
            return Optional.of(cacheFile);
        }
        try {
            byte[] data = HttpUtils.getBytes(url);
            if (data.length == 0) {
                return Optional.empty();
            }
            FileUtils.ensureParent(cacheFile);
            FileUtils.atomicWrite(cacheFile, data);
            return Optional.of(cacheFile);
        } catch (IOException e) {
            log.debug("下载资源失败 {}: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    private Path cacheFile(String url, String extension) {
        return paths.skinsCacheDir().resolve(HashUtils.sha1(url.getBytes(StandardCharsets.UTF_8))
                + "." + extension);
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 清空皮肤缓存。 */
    public void clearCache() {
        FileUtils.deleteQuietly(paths.skinsCacheDir());
        log.info("已清空皮肤缓存: {}", paths.skinsCacheDir());
    }

    /** 缓存占用体积，用于设置页展示。 */
    public long cacheSizeBytes() {
        return FileUtils.sizeOf(paths.skinsCacheDir());
    }
}
