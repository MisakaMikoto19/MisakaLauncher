package com.misaka.launcher.core.mods;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模组扫描与启停（改名）验证。
 */
class InstalledModsTest {

    @TempDir
    Path tempDir;

    private Path modsDir;

    private Path mods() throws IOException {
        if (modsDir == null) {
            modsDir = tempDir.resolve("mods");
            Files.createDirectories(modsDir);
        }
        return modsDir;
    }

    private void jar(String name, int bytes) throws IOException {
        Files.write(mods().resolve(name), new byte[bytes]);
    }

    @Test
    void shouldRecognizeEnabledAndDisabledSuffixes() throws IOException {
        jar("sodium.jar", 10);
        jar("iris.jar.disabled", 20);
        jar("readme.txt", 5);

        List<InstalledMods.Entry> entries = InstalledMods.scan(mods());

        assertEquals(2, entries.size(), "只应列出模组文件：" + entries);
        assertTrue(entries.stream().anyMatch(e -> e.name().equals("sodium") && e.enabled()));
        assertTrue(entries.stream().anyMatch(e -> e.name().equals("iris") && !e.enabled()));
    }

    @Test
    void enabledEntriesShouldComeFirst() throws IOException {
        jar("aaa.jar.disabled", 1);
        jar("zzz.jar", 1);

        List<InstalledMods.Entry> entries = InstalledMods.scan(mods());

        assertTrue(entries.get(0).enabled(), "启用态应排在禁用态之前：" + entries);
    }

    @Test
    void disableAndEnableShouldRoundTrip() throws IOException {
        jar("sodium.jar", 32);
        InstalledMods.Entry before = InstalledMods.scan(mods()).get(0);

        Path disabled = InstalledMods.setEnabled(before, false);
        assertEquals("sodium.jar.disabled", disabled.getFileName().toString());
        assertTrue(Files.isRegularFile(disabled));

        InstalledMods.Entry after = InstalledMods.scan(mods()).get(0);
        assertFalse(after.enabled());
        assertEquals("sodium", after.name(), "展示名不应带禁用后缀");

        Path restored = InstalledMods.setEnabled(after, true);
        assertEquals("sodium.jar", restored.getFileName().toString());
        assertEquals(32, Files.size(restored), "改名不应改动文件内容");
    }

    @Test
    void nestedModsShouldBeScanned() throws IOException {
        Path nested = mods().resolve("1.20.1");
        Files.createDirectories(nested);
        Files.write(nested.resolve("create.jar"), new byte[4]);

        List<InstalledMods.Entry> entries = InstalledMods.scan(mods());

        assertEquals(1, entries.size());
        assertEquals("create", entries.get(0).name());
        assertTrue(entries.get(0).relative().contains("1.20.1"), "应记录相对路径：" + entries.get(0).relative());
    }

    @Test
    void missingDirShouldReturnEmpty() {
        assertTrue(InstalledMods.scan(tempDir.resolve("missing")).isEmpty());
        assertTrue(InstalledMods.scan(null).isEmpty());
    }

    @Test
    void existingTargetShouldFailInsteadOfOverwrite() throws IOException {
        // 用户手工复制造成 sodium.jar 与 sodium.jar.disabled 并存：
        // 静默覆盖会丢掉其中一个，宁可报错让用户自己决定
        jar("sodium.jar", 1);
        jar("sodium.jar.disabled", 1);
        InstalledMods.Entry enabled = InstalledMods.scan(mods()).stream()
                .filter(InstalledMods.Entry::enabled).findFirst().orElseThrow();

        assertThrows(IOException.class, () -> InstalledMods.setEnabled(enabled, false));
    }

    @Test
    void alreadyInTargetStateShouldReturnAsIs() throws IOException {
        jar("sodium.jar", 1);
        InstalledMods.Entry entry = InstalledMods.scan(mods()).get(0);

        assertEquals(entry.path(), InstalledMods.setEnabled(entry, true));
        assertTrue(Files.isRegularFile(mods().resolve("sodium.jar")));
    }
}
