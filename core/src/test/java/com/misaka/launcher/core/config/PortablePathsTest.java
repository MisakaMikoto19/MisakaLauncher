package com.misaka.launcher.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径布局验证。
 * <p>单文件外壳会把程序解压到 {@code %LOCALAPPDATA%} 运行，如果「启动器同级目录」
 * 解析错了，配置、下载缓存、游戏与 Java 运行时会跟着进 AppData，
 * 还会随版本更新被整体删除 —— 这里的测试锁住「大体积数据贴着启动器放」的约定。</p>
 */
class PortablePathsTest {

    @TempDir
    Path tempDir;

    @Test
    void portableModeShouldKeepGameAndJavaUnderInstallRoot() {
        PortablePaths paths = PortablePaths.resolve(tempDir, true, null);

        Path root = tempDir.toAbsolutePath().normalize();
        assertTrue(paths.javaRoot().toAbsolutePath().normalize().startsWith(root),
                "Java 运行时应安装在启动器同级目录下: " + paths.javaRoot());
        assertTrue(paths.defaultGameDir().toAbsolutePath().normalize().startsWith(root),
                "游戏应默认安装在启动器同级目录下: " + paths.defaultGameDir());
        assertTrue(paths.downloadsDir().toAbsolutePath().normalize().startsWith(root),
                "下载缓存应放在启动器同级目录下: " + paths.downloadsDir());
        assertTrue(paths.configFile().toAbsolutePath().normalize().startsWith(root),
                "便携模式下配置也应贴着启动器放: " + paths.configFile());
    }

    @Test
    void nonPortableLargeDataShouldStayNearLauncher() {
        PortablePaths paths = PortablePaths.resolve(tempDir, false, null);

        // 配置 / 日志等小文件按用户意图放数据目录（AppData）；
        // 但游戏与 Java 动辄上 GB，放 AppData 里既难找也不该藏在系统目录里
        Path root = tempDir.toAbsolutePath().normalize();
        assertTrue(paths.javaRoot().toAbsolutePath().normalize().startsWith(root),
                "Java 运行时不应进 AppData: " + paths.javaRoot());
        assertTrue(paths.defaultGameDir().toAbsolutePath().normalize().startsWith(root),
                "游戏目录不应进 AppData: " + paths.defaultGameDir());
        assertEquals(PortablePaths.defaultUserDataDir().toAbsolutePath().normalize(),
                paths.dataRoot().toAbsolutePath().normalize(),
                "非便携模式下配置目录应落在用户数据目录");
    }
}
