package com.misaka.launcher.core.instance;

import com.misaka.launcher.core.config.PortablePaths;

import java.nio.file.Path;

/**
 * 单个游戏目录内部的路径布局。
 * <p>与官方启动器保持一致：{@code versions/}、{@code libraries/}、{@code assets/}、{@code mods/} 等。</p>
 * <p>多实例隔离：每个实例拥有独立的 {@code gameDir}，因此版本、模组、存档、配置天然互不影响；
 * 若希望共享运行库与资源（省磁盘），把多个实例的 {@code gameDir} 指向同一目录即可。</p>
 */
public final class GamePaths {

    private final Path gameDir;

    public GamePaths(Path gameDir) {
        this.gameDir = gameDir.toAbsolutePath().normalize();
    }

    /** 按配置推导默认游戏目录。 */
    public static GamePaths defaultFor(PortablePaths paths) {
        return new GamePaths(paths.defaultGameDir());
    }

    public Path gameDir() {
        return gameDir;
    }

    // ------------------------------------------------------------------
    // 版本
    // ------------------------------------------------------------------

    public Path versionsDir() {
        return gameDir.resolve("versions");
    }

    public Path versionDir(String versionId) {
        return versionsDir().resolve(versionId);
    }

    public Path versionJson(String versionId) {
        return versionDir(versionId).resolve(versionId + ".json");
    }

    public Path versionJar(String versionId) {
        return versionDir(versionId).resolve(versionId + ".jar");
    }

    /**
     * 本地库解压目录。
     * <p>按版本隔离，避免不同版本的原生库互相覆盖导致难以排查的崩溃。</p>
     */
    public Path nativesDir(String versionId) {
        return versionDir(versionId).resolve("natives");
    }

    /** 加载器安装器的工作目录（解压 install_profile 时使用）。 */
    public Path versionWorkDir(String versionId) {
        return versionDir(versionId).resolve(".install");
    }

    // ------------------------------------------------------------------
    // 运行库与资源
    // ------------------------------------------------------------------

    public Path librariesDir() {
        return gameDir.resolve("libraries");
    }

    /** 按 maven 相对路径定位运行库文件。 */
    public Path libraryFile(String mavenRelativePath) {
        return librariesDir().resolve(mavenRelativePath.replace('/', java.io.File.separatorChar));
    }

    public Path assetsDir() {
        return gameDir.resolve("assets");
    }

    public Path assetIndexesDir() {
        return assetsDir().resolve("indexes");
    }

    public Path assetIndexFile(String indexId) {
        return assetIndexesDir().resolve(indexId + ".json");
    }

    public Path objectsDir() {
        return assetsDir().resolve("objects");
    }

    /** 资源对象路径：{@code objects/<hash前两位>/<hash>}。 */
    public Path assetObject(String hash) {
        if (hash == null || hash.length() < 2) {
            throw new IllegalArgumentException("非法资源哈希: " + hash);
        }
        return objectsDir().resolve(hash.substring(0, 2)).resolve(hash);
    }

    /** 1.6 及更早的虚拟资源目录。 */
    public Path virtualAssetsDir(String indexId) {
        return assetsDir().resolve("virtual").resolve(indexId);
    }

    /** 1.6 及更早被映射到 {@code resources/} 的资源目录。 */
    public Path legacyAssetsDir(String indexId) {
        return assetsDir().resolve("legacy").resolve(indexId);
    }

    public Path loggingDir() {
        return assetsDir().resolve("log_configs");
    }

    public Path loggingFile(String fileName) {
        return loggingDir().resolve(fileName);
    }

    // ------------------------------------------------------------------
    // 实例内容
    // ------------------------------------------------------------------

    public Path modsDir() {
        return gameDir.resolve("mods");
    }

    public Path resourcePacksDir() {
        return gameDir.resolve("resourcepacks");
    }

    public Path shaderPacksDir() {
        return gameDir.resolve("shaderpacks");
    }

    public Path configDir() {
        return gameDir.resolve("config");
    }

    public Path savesDir() {
        return gameDir.resolve("saves");
    }

    public Path screenshotsDir() {
        return gameDir.resolve("screenshots");
    }

    /**
     * 崩溃报告目录：游戏（含 Forge / NeoForge）崩溃时在此写入
     * {@code crash-report-<日期>-<时间>-<side>.txt}。
     */
    public Path crashReportsDir() {
        return gameDir.resolve("crash-reports");
    }

    public Path defaultConfigDir() {
        return gameDir.resolve("defaultconfigs");
    }

    /** 该实例是否已安装指定版本（版本 JSON 存在即可视为已安装）。 */
    public boolean hasVersion(String versionId) {
        return java.nio.file.Files.isRegularFile(versionJson(versionId));
    }

    /** 创建必需的基础目录。 */
    public void prepare() throws java.io.IOException {
        for (Path dir : new Path[]{gameDir, versionsDir(), librariesDir(), assetsDir(),
                assetIndexesDir(), objectsDir(), modsDir(), resourcePacksDir(), shaderPacksDir(),
                configDir(), savesDir(), loggingDir()}) {
            java.nio.file.Files.createDirectories(dir);
        }
    }

    @Override
    public String toString() {
        return gameDir.toString();
    }
}
