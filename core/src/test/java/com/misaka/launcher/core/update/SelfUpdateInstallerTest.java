package com.misaka.launcher.core.update;

import com.misaka.launcher.core.util.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自更新替换。
 * <p>这里覆盖的是全流程唯一有平台差异、也最容易把用户「更新到没有启动器可用」的一步：
 * 替换失败必须退化为并排启动，而不是把原文件删了又没换上新文件。</p>
 */
class SelfUpdateInstallerTest {

    @Test
    void inPlaceReplaceShouldKeepBackup(@TempDir Path dir) throws IOException {
        Path current = dir.resolve("MisakaLauncher-1.0.0-windows-amd64.jar");
        Files.writeString(current, "old-jar");
        Path artifact = dir.resolve("cache").resolve("updates")
                .resolve("MisakaLauncher-1.0.1-windows-amd64.jar");
        FileUtils.ensureParent(artifact);
        Files.writeString(artifact, "new-jar");

        SelfUpdateInstaller.Location location = new SelfUpdateInstaller.Location(
                SelfUpdateInstaller.Form.JAR, current, null, current.getFileName().toString());
        SelfUpdateInstaller.Relaunch relaunch = SelfUpdateInstaller.install(location, artifact, dir);

        assertFalse(relaunch.sideBySide(), "正常路径不应退化为并排启动");
        assertEquals("new-jar", Files.readString(current));
        // 备份留着，等下次启动再清：此刻旧文件可能还被占用，删不掉
        assertEquals("old-jar", Files.readString(dir.resolve(current.getFileName() + ".old")));
        assertEquals(current, relaunch.launched());
    }

    @Test
    void relaunchCommandShouldCarryDataRoot(@TempDir Path dir) throws IOException {
        Path current = dir.resolve("misaka-app.jar");
        Files.writeString(current, "old");
        Path artifact = dir.resolve("新版本.jar");
        Files.writeString(artifact, "new");

        SelfUpdateInstaller.Location location = new SelfUpdateInstaller.Location(
                SelfUpdateInstaller.Form.JAR, current, null, current.getFileName().toString());
        SelfUpdateInstaller.Relaunch relaunch = SelfUpdateInstaller.install(location, artifact, dir);

        assertTrue(relaunch.command().contains("-jar"));
        assertTrue(relaunch.command().contains(current.toString()));
        // 数据根目录必须显式传下去：否则新进程会按「向上找标记」重新猜，用户的实例与账户就"丢"了
        assertTrue(relaunch.command().stream()
                        .anyMatch(part -> part.equals("-Dmisaka.launcher.root=" + dir)),
                "命令行里应带上数据根目录：" + relaunch.command());
    }

    @Test
    void failedReplaceShouldRelaunchSideBySide(@TempDir Path dir) throws IOException {
        // 用一个不存在的目标位置模拟「改名/写入失败」（权限不足、介质只读、文件被锁）
        Path missing = dir.resolve("not-writable").resolve("MisakaLauncher.jar");
        Path artifact = dir.resolve("cache").resolve("updates").resolve("new.jar");
        FileUtils.ensureParent(artifact);
        Files.writeString(artifact, "new-jar");

        SelfUpdateInstaller.Location location = new SelfUpdateInstaller.Location(
                SelfUpdateInstaller.Form.JAR, missing, null, "MisakaLauncher.jar");
        SelfUpdateInstaller.Relaunch relaunch = SelfUpdateInstaller.install(location, artifact, dir);

        assertTrue(relaunch.sideBySide(), "替换失败必须退化为并排启动");
        assertNull(relaunch.replaced(), "并排启动时原文件必须原地不动");
        assertEquals(artifact, relaunch.launched());
        assertTrue(Files.isRegularFile(artifact), "并排启动时新文件必须还在原地");
        assertTrue(relaunch.command().contains(artifact.toString()));
    }

    @Test
    void noReplacementFileShouldBeRejected(@TempDir Path dir) throws IOException {
        Path artifact = dir.resolve("new.jar");
        Files.writeString(artifact, "new");
        SelfUpdateInstaller.Location unsupported = new SelfUpdateInstaller.Location(
                SelfUpdateInstaller.Form.UNSUPPORTED, null, null, "classes");

        IOException error = assertThrows(IOException.class,
                () -> SelfUpdateInstaller.install(unsupported, artifact, dir));
        // 提示里要带上当前形态，否则用户只看得到「不支持自动更新」而不知道该改用什么
        assertTrue(error.getMessage().contains("classes"), error.getMessage());
    }

    @Test
    void wrongShapeShouldBeRejected(@TempDir Path dir) throws IOException {
        // 发布时同一产品下常常同时挂着 exe / sh / jar 三种包，选错一个就会把安装写坏：
        // 把 jar 覆盖到 .exe 上不会报错，但用户手里立刻多出一个「双击没反应」的启动器
        Path current = dir.resolve("MisakaLauncher.exe");
        Files.writeString(current, "exe");
        Path jarArtifact = dir.resolve("MisakaLauncher-1.0.1-linux-amd64.jar");
        Files.writeString(jarArtifact, "jar");

        SelfUpdateInstaller.Location location = new SelfUpdateInstaller.Location(
                SelfUpdateInstaller.Form.WINDOWS_EXE, current, current, current.getFileName().toString());
        IOException error = assertThrows(IOException.class,
                () -> SelfUpdateInstaller.install(location, jarArtifact, dir));

        // 报错要说清楚「哪个包不能用」，否则用户只知道失败、不知道该换哪个包重试
        assertTrue(error.getMessage().contains(jarArtifact.getFileName().toString()), error.getMessage());
        assertEquals("exe", Files.readString(current), "被拒绝时原文件必须原样保留");
        assertFalse(Files.exists(dir.resolve(current.getFileName() + ".old")), "不应留下备份文件");
    }

    @Test
    void cleanupShouldOnlyDeleteDotOld(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("app.jar"), "x");
        Files.writeString(dir.resolve("app.jar.old"), "y");
        Files.writeString(dir.resolve("notes.txt"), "z");

        assertEquals(1, SelfUpdateInstaller.cleanupLeftovers(dir));
        assertFalse(Files.exists(dir.resolve("app.jar.old")));
        assertTrue(Files.exists(dir.resolve("app.jar")));
        assertTrue(Files.exists(dir.resolve("notes.txt")));
    }
}
