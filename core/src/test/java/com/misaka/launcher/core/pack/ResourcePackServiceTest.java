package com.misaka.launcher.core.pack;

import com.misaka.launcher.core.instance.GamePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资源包启停与排序的硬约束：<b>启动器的贴图兼容包必须始终排在最后</b>。
 *
 * <p>这条一旦破了，AMD / Intel 用户的贴图压缩会静默失效 —— 游戏照常启动，
 * 但下次遇到贴图超限就崩在一次「找不到原因」的地方。所以这里把它锁成用例。</p>
 */
class ResourcePackServiceTest {

    @TempDir
    Path root;

    private GamePaths paths() {
        return new GamePaths(root);
    }

    private void writeOptions(String content) throws Exception {
        Files.writeString(root.resolve("options.txt"), content, StandardCharsets.UTF_8);
    }

    @Test
    void compatPackMustStayLastAfterEnabling() throws Exception {
        writeOptions("resourcePacks:[\"vanilla\",\"file/misaka_atlas_compat\"]\nfov:70\n");

        new ResourcePackService().setEnabled(paths(), "my-pack.zip", true);

        List<String> items = new ResourcePackService().readResourcePackEntries(paths());
        assertTrue(items.contains("file/my-pack.zip"), "新包应被登记：" + items);
        assertEquals("file/misaka_atlas_compat", items.get(items.size() - 1),
                "兼容包必须仍在最后：" + items);
        // options.txt 里其它设置不能被顺手改掉（键位、画面都在这份文件里）
        assertTrue(Files.readString(root.resolve("options.txt")).contains("fov:70"));
    }

    @Test
    void disablingUserPackShouldNotTouchCompat() throws Exception {
        writeOptions("resourcePacks:[\"vanilla\",\"file/my-pack.zip\",\"file/misaka_atlas_compat\"]\n");

        new ResourcePackService().setEnabled(paths(), "my-pack.zip", false);

        List<String> items = new ResourcePackService().readResourcePackEntries(paths());
        assertTrue(items.stream().noneMatch("file/my-pack.zip"::equals), "用户包应被移除：" + items);
        assertEquals("file/misaka_atlas_compat", items.get(items.size() - 1),
                "兼容包必须仍在最后：" + items);
    }

    @Test
    void compatShouldNotBeInjectedWhenAbsent() throws Exception {
        writeOptions("resourcePacks:[\"vanilla\"]\n");

        new ResourcePackService().setEnabled(paths(), "only-user.zip", true);

        List<String> items = new ResourcePackService().readResourcePackEntries(paths());
        // 压缩被用户关掉时本来就没有兼容包，这里不能把它加回来（那等于偷偷开启压缩）
        assertTrue(items.stream().noneMatch("file/misaka_atlas_compat"::equals), items.toString());
        assertEquals("file/only-user.zip", items.get(items.size() - 1), items.toString());
    }

    @Test
    void reorderShouldKeepCompatLastAndGameEntries() throws Exception {
        // vanilla / mod_resources 是游戏与模组注入的，我们不该改动它们的相对位置
        writeOptions("resourcePacks:[\"vanilla\",\"mod_resources\",\"file/a.zip\",\"file/b.zip\","
                + "\"file/misaka_atlas_compat\"]\n");

        // 用户把 b 拖到 a 前面
        new ResourcePackService().reorder(paths(), List.of("b.zip", "a.zip"));

        List<String> items = new ResourcePackService().readResourcePackEntries(paths());
        assertEquals(List.of("vanilla", "mod_resources", "file/b.zip", "file/a.zip",
                "file/misaka_atlas_compat"), items);
    }

    @Test
    void shouldReadPackPngFromZipAndFolder() throws Exception {
        Path dir = root.resolve("resourcepacks");
        Files.createDirectories(dir);

        // zip 形态的包：只探那一个条目，不解压整包
        Path zip = dir.resolve("zip-pack.zip");
        try (java.util.zip.ZipOutputStream out =
                     new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("pack.mcmeta"));
            out.write("{}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new java.util.zip.ZipEntry("pack.png"));
            out.write(new byte[]{1, 2, 3, 4});
            out.closeEntry();
        }

        // 目录形态的包
        Path folder = dir.resolve("folder-pack");
        Files.createDirectories(folder);
        Files.write(folder.resolve("pack.mcmeta"), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(folder.resolve("pack.png"), new byte[]{9, 9});

        ResourcePackService service = new ResourcePackService();
        assertArrayEquals(new byte[]{1, 2, 3, 4}, service.iconBytes(paths(), "zip-pack.zip"));
        assertArrayEquals(new byte[]{9, 9}, service.iconBytes(paths(), "folder-pack"));
        // 没有图标 / 文件不存在 / 试图穿越目录：都返回 null，由界面画占位
        assertNull(service.iconBytes(paths(), "missing.zip"));
        assertNull(service.iconBytes(paths(), "../escape.zip"));
    }

    @Test
    void compatInRequestedOrderShouldStillMoveToEnd() throws Exception {
        writeOptions("resourcePacks:[\"vanilla\",\"file/a.zip\",\"file/misaka_atlas_compat\"]\n");

        // 界面上兼容包不可拖，但万一调用方把它传进来，也必须仍然最后
        new ResourcePackService().reorder(paths(),
                List.of("misaka_atlas_compat", "a.zip"));

        List<String> items = new ResourcePackService().readResourcePackEntries(paths());
        assertEquals(List.of("vanilla", "file/a.zip", "file/misaka_atlas_compat"), items);
    }
}
