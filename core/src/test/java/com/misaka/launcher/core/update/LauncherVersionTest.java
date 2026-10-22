package com.misaka.launcher.core.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本号语义。
 * <p>这套规则必须与服务端 ZooKeeper 的版本比较一致：一旦两边算法不同，就会出现
 * 「服务端说有新版、本地算成没新版」，而这种分歧在现场几乎无法解释。</p>
 */
class LauncherVersionTest {

    @Test
    void versionShouldNormalizeToFourParts() {
        assertEquals("1.2.3.0", LauncherVersion.normalize("1.2.3"));
        assertEquals("1.0.0.0", LauncherVersion.normalize("1.0.0.0"));
        // 多于四段只保留前四段，与服务端一致
        assertEquals("1.2.3.4", LauncherVersion.normalize("1.2.3.4.5"));
    }

    @Test
    void unrecognizedShouldReturnEmptyNotZero() {
        assertEquals("", LauncherVersion.normalize("dev"));
        assertEquals("", LauncherVersion.normalize(""));
        assertEquals("", LauncherVersion.normalize(null));
    }

    @Test
    void segmentsShouldCompareNumerically() {
        assertTrue(LauncherVersion.isNewer("1.10.0", "1.9.0"), "1.10.0 比 1.9.0 新");
        assertFalse(LauncherVersion.isNewer("1.9.0", "1.10.0"));
        assertTrue(LauncherVersion.isNewer("1.0.1", "1.0.0"));
    }

    @Test
    void threeAndFourPartShouldBeEqual() {
        assertEquals(0, LauncherVersion.compare("1.1.12", "1.1.12.0"));
        assertFalse(LauncherVersion.isNewer("1.1.12.0", "1.1.12"));
    }

    @Test
    void versionShouldBeExtractedFromPrefixed() {
        assertEquals("1.2.3", LauncherVersion.extract("v1.2.3"));
        assertEquals("1.2.3", LauncherVersion.extract("MisakaLauncher 1.2.3 (build 7)"));
        assertTrue(LauncherVersion.isNewer("v1.2.3", "1.2.2"));
    }

    @Test
    void unknownVersionShouldSuppressUpdatePrompt() {
        // 开发构建（dev）不比任何版本新：否则开发者每次启动都被弹一次更新提示
        assertFalse(LauncherVersion.isNewer("dev", "1.0.0"));
        assertFalse(LauncherVersion.isNewer("1.0.0", "dev"));
    }

    @Test
    void displayShouldTrimTrailingZeros() {
        assertEquals("1.2.3", LauncherVersion.display("1.2.3.0"));
        assertEquals("1.2.3.4", LauncherVersion.display("1.2.3.4"));
        assertEquals("dev", LauncherVersion.display("dev"));
    }
}
