package com.misaka.launcher.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本号「只有一处来源」的验证。
 *
 * <p>要守住的是这条链路：{@code gradle.properties 的 misaka.version}
 * → {@code :core:generateVersionResource} 生成的 {@code misaka-version.properties}
 * → 运行期的 {@link BuildInfo}。任一环断掉，界面上或产物名里就会出现旧版本号或
 * {@code dev}，而这类问题在测试里最容易悄悄漏掉。</p>
 */
class BuildInfoTest {

    @Test
    void versionShouldComeFromBuildResource() {
        String version = BuildInfo.version();
        assertNotEquals("dev", version,
                "测试运行时应能读到生成资源；拿到 dev 说明 generateVersionResource 没跑或资源没进 classpath");
        assertFalse(version.contains("$"), "构建期模板没被展开：" + version);
        assertTrue(version.matches("\\d+(\\.\\d+)+"), "版本号应形如 1.0.0，实际：" + version);
    }

    @Test
    void versionShouldMatchBuildScript() {
        String expected = System.getProperty("misaka.expectedVersion");
        assertNotNull(expected, "构建脚本应通过 -Dmisaka.expectedVersion 注入期望版本号");
        assertEquals(expected, BuildInfo.version(),
                "运行期版本与 gradle.properties 不一致 —— 多半是某处又写死了版本号");
    }
}
