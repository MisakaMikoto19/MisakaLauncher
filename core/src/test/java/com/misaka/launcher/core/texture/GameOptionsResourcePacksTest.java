package com.misaka.launcher.core.texture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 兼容包必须始终排在 {@code resourcePacks} 的<b>最后一条</b>：列表靠后的包覆盖靠前的包，
 * 而 mod_resources / tacz_resources 这些自动加入的包（里面就是我们要覆盖的原始大贴图）
 * 都排在用户包前面。
 */
class GameOptionsResourcePacksTest {

    private static final String ENTRY = "file/misaka_atlas_compat";
    private static final String QUOTED = "\"" + ENTRY + "\"";

    @Test
    void alreadyLastShouldNotRewrite(@TempDir Path dir) throws IOException {
        Path options = write(dir, "resourcePacks:[\"vanilla\",\"mod_resources\"," + QUOTED + "]\n");

        assertFalse(GameOptionsResourcePacks.ensureLast(options, ENTRY));
        assertEquals("resourcePacks:[\"vanilla\",\"mod_resources\"," + QUOTED + "]\n",
                Files.readString(options, StandardCharsets.UTF_8));
    }

    @Test
    void middleEntryShouldMoveToEnd(@TempDir Path dir) throws IOException {
        // 场景：游戏把自动启用的模组包写回 options.txt，我们的条目被挤到了前面
        Path options = write(dir,
                "resourcePacks:[\"vanilla\"," + QUOTED + ",\"mod_resources\",\"tacz_resources\"]\n");

        assertTrue(GameOptionsResourcePacks.ensureLast(options, ENTRY));
        assertEquals("resourcePacks:[\"vanilla\",\"mod_resources\",\"tacz_resources\"," + QUOTED + "]\n",
                Files.readString(options, StandardCharsets.UTF_8));
    }

    @Test
    void missingEntryShouldAppend(@TempDir Path dir) throws IOException {
        Path options = write(dir, "resourcePacks:[\"vanilla\",\"mod_resources\"]\n");

        assertTrue(GameOptionsResourcePacks.ensureLast(options, ENTRY));
        assertEquals("resourcePacks:[\"vanilla\",\"mod_resources\"," + QUOTED + "]\n",
                Files.readString(options, StandardCharsets.UTF_8));
    }

    @Test
    void missingLineShouldBeAdded(@TempDir Path dir) throws IOException {
        Path options = write(dir, "fov:0.5\n");

        assertTrue(GameOptionsResourcePacks.ensureLast(options, ENTRY));
        assertEquals("fov:0.5\nresourcePacks:[" + QUOTED + "]\n",
                Files.readString(options, StandardCharsets.UTF_8));
    }

    @Test
    void otherSettingsAndCrlfShouldSurvive(@TempDir Path dir) throws IOException {
        Path options = write(dir,
                "fov:0.5\r\nresourcePacks:[\"vanilla\"]\r\nmouseSensitivity:0.4\r\n");

        assertTrue(GameOptionsResourcePacks.ensureLast(options, ENTRY));
        assertEquals("fov:0.5\r\nresourcePacks:[\"vanilla\"," + QUOTED + "]\r\n"
                        + "mouseSensitivity:0.4\r\n",
                Files.readString(options, StandardCharsets.UTF_8));
    }

    @Test
    void emptyListShouldBeWritable(@TempDir Path dir) throws IOException {
        Path options = write(dir, "resourcePacks:[]\n");

        assertTrue(GameOptionsResourcePacks.ensureLast(options, ENTRY));
        assertEquals("resourcePacks:[" + QUOTED + "]\n",
                Files.readString(options, StandardCharsets.UTF_8));
    }

    @Test
    void missingOptionsFileShouldBeCreated(@TempDir Path dir) throws IOException {
        // 第一次启动前游戏目录里没有 options.txt。此时若只是放弃登记，
        // 第一次启动就没有保护 —— 而那一次恰恰最容易崩在贴图拼接上
        Path options = dir.resolve("options.txt");

        assertTrue(GameOptionsResourcePacks.ensureLast(options, ENTRY));
        assertEquals("resourcePacks:[" + QUOTED + "]" + System.lineSeparator(),
                Files.readString(options, StandardCharsets.UTF_8));
        assertFalse(GameOptionsResourcePacks.ensureLast(options, ENTRY), "再次调用应为无操作");
    }

    @Test
    void disablingTierShouldRemoveEntry(@TempDir Path dir) throws IOException {
        Path options = write(dir, "resourcePacks:[\"vanilla\",\"mod_resources\"," + QUOTED + "]\n");

        assertTrue(GameOptionsResourcePacks.remove(options, ENTRY));
        assertEquals("resourcePacks:[\"vanilla\",\"mod_resources\"]\n",
                Files.readString(options, StandardCharsets.UTF_8));
    }

    @Test
    void removeEntryFromMiddle(@TempDir Path dir) throws IOException {
        Path options = write(dir,
                "resourcePacks:[\"vanilla\"," + QUOTED + ",\"mod_resources\"]\n");

        assertTrue(GameOptionsResourcePacks.remove(options, ENTRY));
        assertEquals("resourcePacks:[\"vanilla\",\"mod_resources\"]\n",
                Files.readString(options, StandardCharsets.UTF_8));
    }

    @Test
    void removingAbsentEntryShouldNotRewrite(@TempDir Path dir) throws IOException {
        Path options = write(dir, "resourcePacks:[\"vanilla\"]\n");

        assertFalse(GameOptionsResourcePacks.remove(options, ENTRY));
        assertEquals("resourcePacks:[\"vanilla\"]\n",
                Files.readString(options, StandardCharsets.UTF_8));
    }

    private static Path write(Path dir, String content) throws IOException {
        Path options = dir.resolve("options.txt");
        Files.writeString(options, content, StandardCharsets.UTF_8);
        return options;
    }
}
