package com.misaka.launcher.core.auth;

import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.config.PortablePaths;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家头像链路验证。
 *
 * <p><b>全程离线</b>：官方会话服务地址通过包内构造器注入到本地桩服务器。
 * 绝不能指向真实接口 —— 真实皮肤会随玩家改动而变，那样的测试既不可复现，
 * 又会在 CI 上变成随机失败。</p>
 */
@Timeout(value = 60, unit = java.util.concurrent.TimeUnit.SECONDS)
class SkinServiceTest {

    /** 桩皮肤把面部涂成不透明红、帽子层留透明：便于断言「确实裁到了脸」。 */
    private static final int FACE_COLOR = 0xFFFF0000;
    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";
    private static final String PROFILE_PATH = "/session/minecraft/profile/" + UUID;

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String base;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    /** 会话档案是否可用；false 时返回 404，用于验证「拿不到皮肤」的降级路径。 */
    private volatile boolean profileAvailable = true;
    /** 模拟会话服务对未知 UUID 的应答（204 + 空响应体）。 */
    private volatile boolean profileEmpty = false;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // 桩服务器
    // ------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();

        byte[] payload;
        String contentType;
        int status = 200;
        if ("/skin.png".equals(path)) {
            payload = skinPng();
            contentType = "image/png";
        } else if (path.contains("/session/minecraft/profile/")) {
            contentType = "application/json";
            if (!profileAvailable) {
                status = 404;
                payload = new byte[0];
            } else if (profileEmpty) {
                // Blessing Skin 系对未知玩家返回 204 No Content（无响应体）
                status = 204;
                payload = new byte[0];
            } else {
                payload = profileJson().getBytes(StandardCharsets.UTF_8);
            }
        } else {
            status = 404;
            payload = new byte[0];
            contentType = "text/plain";
        }

        exchange.getResponseHeaders().add("Content-Type", contentType);
        if (payload.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /** 64×64 皮肤：默认全透明，仅面部区域涂成 FACE_COLOR；帽子层保持透明。 */
    private static byte[] skinPng() throws IOException {
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 8; y < 16; y++) {
            for (int x = 8; x < 16; x++) {
                skin.setRGB(x, y, FACE_COLOR);
            }
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(skin, "png", buffer);
        return buffer.toByteArray();
    }

    /** 官方会话档案响应。 */
    private String profileJson() {
        String textures = "{\"textures\":{\"SKIN\":{\"url\":\"" + base + "/skin.png\"}}}";
        String encoded = Base64.getEncoder()
                .encodeToString(textures.getBytes(StandardCharsets.UTF_8));
        return "{\"id\":\"" + UUID + "\",\"name\":\"MisakaPlayer\",\"properties\":"
                + "[{\"name\":\"textures\",\"value\":\"" + encoded + "\"}]}";
    }

    private SkinService service() {
        PortablePaths paths = PortablePaths.resolve(tempDir, false, tempDir.resolve("data").toString());
        paths.prepare();
        LauncherConfig.AuthSettings settings = new LauncherConfig.AuthSettings();
        // 包内构造器：把官方会话服务指向本地桩，避免单测打真实网络
        return new SkinService(paths, settings, base + "/session/minecraft/profile/");
    }

    private static LauncherConfig.AccountEntry microsoftAccount() {
        LauncherConfig.AccountEntry entry = new LauncherConfig.AccountEntry();
        entry.type = LauncherConfig.AccountEntry.TYPE_MICROSOFT;
        entry.username = "MisakaPlayer";
        entry.uuid = UUID;
        entry.skinModel = "default";
        return entry;
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertTrue(image != null, "返回的字节必须是可解码的图片");
        return image;
    }

    // ------------------------------------------------------------------
    // 正版：官方皮肤
    // ------------------------------------------------------------------

    @Test
    void premiumAccountShouldFetchOfficialSkinAndCropHead() throws Exception {
        BufferedImage head = decode(service().avatarBytes(microsoftAccount(), 32));

        assertEquals(32, head.getWidth());
        assertEquals(32, head.getHeight());
        // 桩皮肤只有面部是红色：中心像素为红 ⇒ 取的是「裁剪后的脸」，
        // 既不是整张皮肤，也不是默认头像
        assertEquals(FACE_COLOR, head.getRGB(16, 16) & 0xFFFFFFFF, "应裁剪出面部区域");
        assertTrue(hits.containsKey(PROFILE_PATH),
                "应查询官方会话档案，实际请求: " + hits.keySet());
    }

    @Test
    void skinModelShouldNotAffectAvatarWhenSkinExists() throws Exception {
        LauncherConfig.AccountEntry slim = microsoftAccount();
        slim.skinModel = "slim";

        byte[] actual = service().avatarBytes(slim, 32);

        assertEquals(FACE_COLOR, decode(actual).getRGB(16, 16) & 0xFFFFFFFF,
                "皮肤模型只决定默认头像，不影响真实皮肤");
        assertFalse(Arrays.equals(PlayerHeads.defaultHeadPng("slim", 32), actual));
    }

    @Test
    void missingOfficialSkinShouldFallBackToDefaultAvatar() throws Exception {
        profileAvailable = false;

        byte[] actual = service().avatarBytes(microsoftAccount(), 32);

        assertArrayEquals(PlayerHeads.defaultHeadPng("default", 32), actual,
                "拿不到真实皮肤时应给默认 Steve 头像，而不是首字母之类的占位");
    }

    @Test
    void Alex模型应给出与Steve可区分的默认头像() throws Exception {
        profileAvailable = false;
        LauncherConfig.AccountEntry alex = microsoftAccount();
        alex.skinModel = "slim";

        byte[] slim = service().avatarBytes(alex, 16);

        assertArrayEquals(PlayerHeads.defaultHeadPng("slim", 16), slim);
        assertFalse(Arrays.equals(PlayerHeads.defaultHeadPng("default", 16), slim),
                "两种模型的默认头像必须能一眼区分");
        // Alex 的长发包住两侧脸颊：左下角是头发，而 Steve 同位置是皮肤
        assertNotEquals(decode(PlayerHeads.defaultHeadPng("default", 16)).getRGB(0, 14),
                decode(slim).getRGB(0, 14),
                "Alex 的下颌两侧应被头发覆盖");
    }

    // ------------------------------------------------------------------
    // 空响应
    // ------------------------------------------------------------------

    @Test
    void emptyProfileShouldFallBackToDefaultAvatar() throws Exception {
        // 会话服务对未知 UUID 返回 204 + 空响应体：必须安全降级而不是抛异常
        profileEmpty = true;

        byte[] actual = service().avatarBytes(microsoftAccount(), 32);

        assertArrayEquals(PlayerHeads.defaultHeadPng("default", 32), actual,
                "空档案必须降级到默认头像");
    }

    // ------------------------------------------------------------------
    // 缓存
    // ------------------------------------------------------------------

    @Test
    void repeatedAvatarLookupShouldHitCache() throws Exception {
        LauncherConfig.AccountEntry entry = microsoftAccount();
        SkinService service = service();

        service.avatarBytes(entry, 32);
        int afterFirst = hits.get(PROFILE_PATH).get();
        service.avatarBytes(entry, 32);

        assertEquals(afterFirst, hits.get(PROFILE_PATH).get(),
                "会话档案有速率限制，重复取头像必须命中缓存");
    }

    @Test
    void missingSkinShouldNotBeCached() throws Exception {
        profileAvailable = false;
        LauncherConfig.AccountEntry entry = microsoftAccount();
        SkinService service = service();

        service.avatarBytes(entry, 16);
        // 网络恢复后应能拿到真实皮肤（如果空结果被缓存，这里会一直显示默认头像）
        profileAvailable = true;
        byte[] actual = service.avatarBytes(entry, 16);

        assertEquals(FACE_COLOR, decode(actual).getRGB(8, 8) & 0xFFFFFFFF,
                "一次失败不应被缓存下来，否则该账户会在缓存期内永远显示默认头像");
    }
}
