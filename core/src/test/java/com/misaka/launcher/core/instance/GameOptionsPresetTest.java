package com.misaka.launcher.core.instance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置 {@code options.txt} 预设的落盘行为验证。
 *
 * <p>这里锁住的是一条容易写错的产品约定：安装时「只补不覆盖」（玩家自己调过的键位属于玩家），
 * 而实例页的手动重刷是「整份覆盖」。</p>
 */
class GameOptionsPresetTest {

    @TempDir
    Path tempDir;

    @Test
    void presetMustBeBundledWithLauncher() {
        assertTrue(GameOptionsPreset.isBundled(),
                "内置预设缺失：core/src/main/resources/preset/options.txt 没有被 processResources 打进去");
    }

    @Test
    void installShouldWritePresetIncludingKeybinds() throws Exception {
        GamePaths paths = new GamePaths(tempDir);

        assertTrue(GameOptionsPreset.deployIfAbsent(paths), "首次安装应铺预设");

        Path file = tempDir.resolve(GameOptionsPreset.FILE_NAME);
        assertTrue(Files.isRegularFile(file), "预设应落在游戏目录根下");
        String content = Files.readString(file);
        // 必须真的是那份键位文件，而不是写了个空文件
        assertTrue(content.contains("key_key.attack:key.mouse.left"));
        assertTrue(content.contains("key_key.jump:key.keyboard.space"));
        assertTrue(content.contains("key_key.superbwarfare.fire:key.mouse.left"));
    }

    @Test
    void existingOptionsShouldNotBeOverwritten() throws Exception {
        GamePaths paths = new GamePaths(tempDir);
        Path file = tempDir.resolve(GameOptionsPreset.FILE_NAME);
        // 玩家自己改过跳跃键
        Files.writeString(file, "key_key.jump:key.keyboard.f\n");

        assertFalse(GameOptionsPreset.deployIfAbsent(paths), "已存在时不应重铺");
        assertEquals("key_key.jump:key.keyboard.f\n", Files.readString(file),
                "重装/校验修复都不能把玩家自己的键位冲掉");
    }

    @Test
    void manualResetShouldOverwriteWithPreset() throws Exception {
        GamePaths paths = new GamePaths(tempDir);
        Path file = tempDir.resolve(GameOptionsPreset.FILE_NAME);
        Files.writeString(file, "key_key.jump:key.keyboard.f\n");

        GameOptionsPreset.overwrite(paths);

        String content = Files.readString(file);
        assertTrue(content.contains("key_key.jump:key.keyboard.space"), "应覆盖回预设内容");
        // 「整份」覆盖：与画面/音量同在一份文件里的其他预设项也必须回来
        assertTrue(content.contains("key_key.superbwarfare.fire:key.mouse.left"));
        assertTrue(content.contains("lang:zh_cn"));
    }
}
