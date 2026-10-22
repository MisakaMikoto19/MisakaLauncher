package com.misaka.launcher.core.install;

import com.misaka.launcher.core.config.ConfigStore;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.config.PortablePaths;
import com.misaka.launcher.core.download.DownloadManager;
import com.misaka.launcher.core.download.SourceGate;
import com.misaka.launcher.core.download.SourceResolver;
import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.meta.LoaderMetaService;
import com.misaka.launcher.core.meta.MetaFetcher;
import com.misaka.launcher.core.meta.VersionManifestService;
import com.misaka.launcher.core.model.LoaderType;
import com.misaka.launcher.core.task.ProgressListener;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 联网的手工验证：真实安装 Forge（默认跳过）。
 *
 * <p>之所以留在仓库里：加载器安装的耗时问题只能在真实网络下复现 ——
 * 镜像排序、预置策略、安装器参数选择全都依赖国内网络的实际表现，
 * 单元测试（本地桩）测不出来。</p>
 *
 * <p>用法：准备一个已装好原版 1.20.1 的游戏目录，然后</p>
 * <pre>
 *   $env:MISAKA_FORGE_CHECK="&lt;gameDir&gt;"
 *   ./gradlew :core:test --tests '*ForgeInstallManualCheck*'
 * </pre>
 * <p>用环境变量而不是 {@code -D}：Gradle 的 {@code -D} 只作用于 Gradle 自身，
 * 环境变量才会随测试 JVM 继承。</p>
 *
 * <p>各阶段耗时（安装器下载 / 预置轮次 / 安装器执行 / 安装后补齐）会写到
 * {@code system-err} 里，用来判断慢在哪一步。</p>
 */
class ForgeInstallManualCheck {

    @Test
    void install() throws Exception {
        String gameDirProperty = System.getenv("MISAKA_FORGE_CHECK");
        Assumptions.assumeTrue(gameDirProperty != null && !gameDirProperty.isBlank(),
                "需要 MISAKA_FORGE_CHECK=<已装原版 1.20.1 的游戏目录> 才会执行联网验证");
        Path gameDir = Path.of(gameDirProperty);
        PortablePaths paths = PortablePaths.resolve(true, gameDir.getParent().toString());
        paths.prepare();
        ConfigStore store = ConfigStore.open(paths);
        LauncherConfig config = store.get();
        config.download.source = LauncherConfig.DownloadSettings.SOURCE_AUTO;

        SourceResolver resolver = new SourceResolver(config.download);
        SourceGate gate = new SourceGate();
        DownloadManager downloads = new DownloadManager(resolver, config.download, gate);
        MetaFetcher fetcher = new MetaFetcher(resolver, gate);
        VersionManifestService versions = new VersionManifestService(paths, fetcher);
        LoaderMetaService loaderMeta = new LoaderMetaService(fetcher);

        GamePaths gamePaths = new GamePaths(gameDir);
        gamePaths.prepare();
        VanillaInstaller vanilla = new VanillaInstaller(versions, downloads, gamePaths);
        Path java = Path.of("E:\\DevelopingTools\\Java\\jdk17\\bin\\java.exe");
        LoaderInstaller installer = new LoaderInstaller(versions, loaderMeta, downloads, vanilla,
                gamePaths, ignored -> java);

        long start = System.currentTimeMillis();
        LoaderInstallResult result = installer.install(LoaderType.FORGE, "1.20.1", "47.4.26",
                new ProgressListener() {
                    @Override
                    public void onStage(String stage) {
                        System.out.println("[stage] " + stage);
                    }

                    @Override
                    public void onMessage(String message) {
                        System.out.println("[msg] " + message);
                    }
                });
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("=== 安装结果：" + result.versionId() + "，耗时 " + (elapsed / 1000) + " 秒 ===");
        System.out.println("=== 下载汇总：" + result.downloadReport().summary() + " ===");
        Path json = gamePaths.versionJson(result.versionId());
        System.out.println("=== 版本 JSON：" + json + " 存在=" + Files.isRegularFile(json) + " ===");
    }
}
