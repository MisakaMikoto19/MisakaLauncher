package com.misaka.launcher.core.meta;

import com.misaka.launcher.core.model.LoaderType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 加载器「多选 + 冲突 + 最新版本」的规则验证（纯逻辑，离线）。
 *
 * <p>这些都是卡片多选 UI 的行为依据，先锁住规则，界面改动才不会破坏约定：</p>
 * <ul>
 *   <li>核心加载器（Forge/NeoForge/Fabric/Quilt）彼此互斥；</li>
 *   <li>OptiFine 只与 Forge（或原版）共存，与 Fabric/Quilt/NeoForge 冲突；</li>
 *   <li>「最新版本」是数值感知比较，而不是列表顺序。</li>
 * </ul>
 */
class LoaderSelectionTest {

    // ------------------------------------------------------------------
    // 冲突规则
    // ------------------------------------------------------------------

    @Test
    void coreLoadersShouldBeMutuallyExclusive() {
        assertTrue(LoaderType.FORGE.conflictsWith(LoaderType.FABRIC));
        assertTrue(LoaderType.FABRIC.conflictsWith(LoaderType.FORGE));
        assertTrue(LoaderType.NEOFORGE.conflictsWith(LoaderType.QUILT));
        assertTrue(LoaderType.FORGE.conflictsWith(LoaderType.NEOFORGE));
    }

    @Test
    void OptiFine只与Forge共存() {
        assertFalse(LoaderType.FORGE.conflictsWith(LoaderType.OPTIFINE));
        assertFalse(LoaderType.OPTIFINE.conflictsWith(LoaderType.FORGE));
        // 与 Fabric / Quilt / NeoForge 冲突
        assertTrue(LoaderType.FABRIC.conflictsWith(LoaderType.OPTIFINE));
        assertTrue(LoaderType.QUILT.conflictsWith(LoaderType.OPTIFINE));
        assertTrue(LoaderType.NEOFORGE.conflictsWith(LoaderType.OPTIFINE));
    }

    @Test
    void sameOrBlankLoadersShouldNotConflict() {
        assertFalse(LoaderType.FORGE.conflictsWith(LoaderType.FORGE));
        assertFalse(LoaderType.FORGE.conflictsWith(null));
        assertFalse(LoaderType.OPTIFINE.conflictsWith(null));
    }

    // ------------------------------------------------------------------
    // 版本比较（数值感知）
    // ------------------------------------------------------------------

    @Test
    void versionsShouldCompareNumerically() {
        assertTrue(LoaderMetaService.compareVersions("0.15.11", "0.15.9") > 0);
        assertTrue(LoaderMetaService.compareVersions("47.2.0", "47.10.0") < 0);
        // 字典序会把 "9" 排到 "10" 之后，数值比较必须给出相反结论
        assertTrue(LoaderMetaService.compareVersions("10", "9") > 0);
        assertEquals(0, LoaderMetaService.compareVersions("1.0.0", "1.0.0"));
    }

    @Test
    void versionCompareShouldToleratePrereleaseSuffix() {
        assertTrue(LoaderMetaService.compareVersions("20.4.235-beta", "20.4.89") > 0);
        assertTrue(LoaderMetaService.compareVersions("20.4.235", "20.4.235-beta") > 0,
                "同号时正式版应排在测试版之后（数字段更长）");
    }

    @Test
    void blankVersionShouldBeSmallest() {
        assertTrue(LoaderMetaService.compareVersions("", "0.1") < 0);
        assertTrue(LoaderMetaService.compareVersions(null, "0.1") < 0);
        assertEquals(0, LoaderMetaService.compareVersions("", null));
    }

    // ------------------------------------------------------------------
    // 最新版本选择
    // ------------------------------------------------------------------

    private static LoaderMetaService.LoaderVersion v(String version, boolean stable) {
        return new LoaderMetaService.LoaderVersion(version, "1.20.1", stable, "", 0L);
    }

    @Test
    void shouldPickLatestStable() {
        LoaderMetaService.LoaderVersion latest = LoaderMetaService.latest(List.of(
                v("47.1.0", true), v("47.2.0", true), v("47.3.0", true)));
        assertEquals("47.3.0", latest.version());
    }

    @Test
    void noStableShouldFallBackToLatest() {
        LoaderMetaService.LoaderVersion latest = LoaderMetaService.latest(List.of(
                v("47.3.0-beta", false), v("47.2.0-beta", false)));
        assertEquals("47.3.0-beta", latest.version());
    }

    @Test
    void emptyListShouldYieldNull() {
        assertNull(LoaderMetaService.latest(List.of()));
        assertNull(LoaderMetaService.latest(null));
    }
}
