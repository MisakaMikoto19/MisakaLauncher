package com.misaka.launcher.core.java;

import com.misaka.launcher.core.config.ConfigStore;
import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.config.PortablePaths;
import com.misaka.launcher.core.download.DownloadManager;
import com.misaka.launcher.core.download.SourceResolver;
import com.misaka.launcher.core.meta.JavaVersionRule;
import com.misaka.launcher.core.task.ProgressListener;
import com.misaka.launcher.core.util.HashUtils;
import com.misaka.launcher.core.util.OsUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java 运行时管理端到端验证。
 * <p>用本地桩发行版（一个内含 {@code bin/java} 与 {@code release} 的 zip）
 * 走通「解析 → 下载 → 解压 → 就位到启动器同级目录 → 登记 → 选用」全链路。</p>
 */
@Timeout(value = 90, unit = java.util.concurrent.TimeUnit.SECONDS)
class JavaRuntimeManagerTest {

    private static final String JDK_FILE = "stub-jdk-17.zip";

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String base;
    private final Map<String, byte[]> payloads = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();
    private StubProvider stubProvider;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // 安装链路
    // ------------------------------------------------------------------

    @Test
    void installShouldExtractBesideLauncherAndRegister() throws Exception {
        Fixture fixture = prepare();

        LauncherConfig.JavaRuntimeEntry entry = fixture.manager.install(17, ProgressListener.NOOP);

        assertEquals(17, entry.majorVersion);
        assertEquals("installed", entry.source);
        assertTrue(entry.home.startsWith(fixture.paths.javaRoot().toString()),
                "Java 必须安装到启动器同级目录: " + entry.home);

        Path home = Path.of(entry.home);
        assertTrue(JavaRuntimeManager.hasJavaExecutable(home), "安装目录应包含 bin/java");
        assertTrue(Files.isRegularFile(home.resolve("release")), "release 文件应随包解出");

        // 解压出的顶层目录应被「拍平」：home 下直接就是 bin/
        assertEquals("stub-17-" + OsUtils.id() + "-" + com.misaka.launcher.core.util.ArchUtils.id(),
                home.getFileName().toString());

        assertEquals(1, fixture.manager.registered().size());
        assertTrue(Files.exists(fixture.paths.configFile()), "登记后配置应落盘");
    }

    @Test
    void reinstallShouldSkipDownload() throws Exception {
        Fixture fixture = prepare();

        fixture.manager.install(17, ProgressListener.NOOP);
        int requestsAfterFirst = requestCount();

        LauncherConfig.JavaRuntimeEntry second = fixture.manager.install(17, ProgressListener.NOOP);

        assertEquals(17, second.majorVersion);
        assertEquals(requestsAfterFirst, requestCount(), "已安装时不应再次下载");
    }

    @Test
    void archiveHashMismatchShouldFailCleanly() throws Exception {
        Fixture fixture = prepare();
        // 提供一个错误的 sha256，迫使校验失败
        stubProvider.checksumOverride = "0".repeat(64);

        IOException error = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> fixture.manager.install(17, ProgressListener.NOOP));
        assertTrue(error.getMessage().contains("下载失败") || error.getMessage().contains("校验"),
                "错误信息应可读: " + error.getMessage());

        assertFalse(Files.exists(fixture.paths.javaRoot().resolve(
                        "stub-17-" + OsUtils.id() + "-" + com.misaka.launcher.core.util.ArchUtils.id())),
                "校验失败不应留下安装目录");
        assertTrue(fixture.manager.registered().isEmpty(), "校验失败不应登记运行时");
    }

    @Test
    void failedVendorShouldFallBackToNext() throws Exception {
        Fixture fixture = prepare();
        // 首选发行版解析正常但归档地址不可达：
        // 这正是 Dragonwell 在国内的典型失败形态（唯一下载源为 GitHub Releases）
        UnreachableProvider unreachable = new UnreachableProvider(base + "/not-found.zip");
        JavaRuntimeManager manager = new JavaRuntimeManager(fixture.paths, fixture.store,
                fixture.downloads, List.of(unreachable, stubProvider));

        LauncherConfig.JavaRuntimeEntry entry = manager.install(17, ProgressListener.NOOP);

        assertEquals(17, entry.majorVersion);
        assertEquals("stub", entry.vendor, "下载阶段失败后必须回退到可用发行版");
        assertTrue(entry.home.startsWith(fixture.paths.javaRoot().toString()));
        assertTrue(JavaRuntimeManager.hasJavaExecutable(Path.of(entry.home)),
                "回退后的发行版应完成安装并可用");
    }

    // ------------------------------------------------------------------
    // 选用规则
    // ------------------------------------------------------------------
    @Test
    void exactMajorMatchShouldBePreferred() throws Exception {
        Fixture fixture = prepare();
        fixture.manager.install(17, ProgressListener.NOOP);
        registerFake(fixture, 21);

        Optional<LauncherConfig.JavaRuntimeEntry> exact = fixture.manager.find(17);
        assertTrue(exact.isPresent());
        assertEquals(17, exact.get().majorVersion, "存在完全匹配时不应选出更高版本");

        Optional<LauncherConfig.JavaRuntimeEntry> higher = fixture.manager.find(20);
        assertTrue(higher.isPresent(), "所需版本 >= 16 时应接受更高版本");
        assertEquals(21, higher.get().majorVersion);
    }

    @Test
    void java8NeedShouldRejectHigherVersions() throws Exception {
        Fixture fixture = prepare();
        fixture.manager.install(17, ProgressListener.NOOP);
        registerFake(fixture, 21);

        assertTrue(fixture.manager.find(8).isEmpty(),
                "Java 8 时代依赖反射内部 API，不能放宽到更高版本");
        assertTrue(fixture.manager.javaExecutable(8).isEmpty());
    }

    @Test
    void deletedRuntimeShouldNotBeSelected() throws Exception {
        Fixture fixture = prepare();
        LauncherConfig.JavaRuntimeEntry entry = fixture.manager.install(17, ProgressListener.NOOP);
        assertTrue(fixture.manager.find(17).isPresent());

        com.misaka.launcher.core.util.FileUtils.deleteRecursively(Path.of(entry.home));

        assertTrue(fixture.manager.find(17).isEmpty(), "目录不存在时该条目应被跳过");
        assertTrue(fixture.manager.javaExecutable(17).isEmpty());
    }

    @Test
    void manualRegisterShouldAcceptExeAndJavaHome() throws Exception {
        Fixture fixture = prepare();
        Path fakeHome = tempDir.resolve("manual-jdk");
        writeFakeJdk(fakeHome, "1.8.0_392");

        LauncherConfig.JavaRuntimeEntry byHome = fixture.manager.register(fakeHome.toString());
        assertEquals(8, byHome.majorVersion);

        LauncherConfig.JavaRuntimeEntry byExecutable = fixture.manager.register(
                fakeHome.resolve("bin").resolve(OsUtils.executableName("java")).toString());
        assertEquals(fakeHome.toAbsolutePath().normalize().toString(),
                Path.of(byExecutable.home).toAbsolutePath().normalize().toString());
        assertEquals(1, fixture.manager.registered().size(), "同一目录不应重复登记");
    }

    @Test
    void removeShouldRejectOutsideLauncherDir() throws Exception {
        Fixture fixture = prepare();
        Path external = tempDir.resolve("external-jdk");
        writeFakeJdk(external, "17.0.9");
        fixture.manager.register(external.toString());

        assertTrue(fixture.manager.remove(external.toString(), true));
        assertTrue(Files.isDirectory(external), "启动器目录之外的 Java 目录不应被删除");
        assertTrue(fixture.manager.registered().isEmpty());
    }

    // ------------------------------------------------------------------
    // 纯函数规则
    // ------------------------------------------------------------------

    @Test
    void versionToMajorMappingShouldFollowConvention() {
        assertEquals(21, JavaVersionRule.requiredMajorVersion("1.21.4"));
        assertEquals(21, JavaVersionRule.requiredMajorVersion("1.20.5"));
        assertEquals(17, JavaVersionRule.requiredMajorVersion("1.20.4"));
        assertEquals(17, JavaVersionRule.requiredMajorVersion("1.20.1"));
        assertEquals(17, JavaVersionRule.requiredMajorVersion("1.18.2"));
        // 1.17 官方要求 16，但 16 已 EOL，统一归一化到 17
        assertEquals(17, JavaVersionRule.requiredMajorVersion("1.17.1"));
        // 1.16 → 11
        assertEquals(11, JavaVersionRule.requiredMajorVersion("1.16.5"));
        // 1.15.x 及以下 → 8
        assertEquals(8, JavaVersionRule.requiredMajorVersion("1.15.2"));
        assertEquals(8, JavaVersionRule.requiredMajorVersion("1.12.2"));
        assertEquals(8, JavaVersionRule.requiredMajorVersion("1.7.10"));
        // 周快照按年份推算
        assertEquals(21, JavaVersionRule.requiredMajorVersion("24w14a"));
        assertEquals(17, JavaVersionRule.requiredMajorVersion("21w03a"));
        // 无法识别时给出保守的现代默认值
        assertEquals(17, JavaVersionRule.requiredMajorVersion(""));
    }

    @Test
    void preinstallableMajorsShouldCoverAllNeeds() {
        // 界面的手动安装选项必须覆盖每个 Minecraft 版本实际需要的 JDK，
        // 否则会出现「手动装了却仍然启动不了」的死角
        for (String version : java.util.List.of("1.7.10", "1.12.2", "1.15.2", "1.16.5",
                "1.17.1", "1.18.2", "1.20.1", "1.20.4", "1.20.5", "1.21.4")) {
            int required = JavaVersionRule.requiredMajorVersion(version);
            boolean covered = false;
            for (int major : JavaVersionRule.INSTALLABLE_MAJORS) {
                if (major == required) {
                    covered = true;
                    break;
                }
            }
            assertTrue(covered, version + " 需要 Java " + required + "，但手动安装选项中没有该版本");
        }
    }

    @Test
    void majorParsingShouldTolerateLegacyVersions() {
        assertEquals(8, JavaRuntimeManager.parseMajorVersion("1.8.0_392"));
        assertEquals(17, JavaRuntimeManager.parseMajorVersion("17.0.12"));
        assertEquals(21, JavaRuntimeManager.parseMajorVersion("21"));
        assertEquals(11, JavaRuntimeManager.parseMajorVersion("\"11.0.20\""));
        assertEquals(-1, JavaRuntimeManager.parseMajorVersion(""));
        assertEquals(-1, JavaRuntimeManager.parseMajorVersion(null));
    }

    @Test
    void versionCompatibilityShouldFollowRules() {
        assertTrue(JavaVersionRule.accepts(17, 17));
        assertTrue(JavaVersionRule.accepts(17, 21));
        assertTrue(JavaVersionRule.accepts(21, 21));
        assertFalse(JavaVersionRule.accepts(21, 17));
        assertTrue(JavaVersionRule.accepts(16, 17));
        assertFalse(JavaVersionRule.accepts(8, 17));
        assertFalse(JavaVersionRule.accepts(8, 8 - 1));
        assertFalse(JavaVersionRule.accepts(17, -1));
    }

    // ------------------------------------------------------------------
    // 测试装置
    // ------------------------------------------------------------------

    private record Fixture(PortablePaths paths, ConfigStore store, JavaRuntimeManager manager,
                           DownloadManager downloads) {
    }

    /** 桩发行版：把本地归档当作「某个 JDK 发行版的下载包」。 */
    private final class StubProvider implements JavaProvider {

        private final String url;
        private final long size;
        private final String checksum;
        /** 非空时覆盖校验值，用于验证校验失败的路径。 */
        private String checksumOverride;

        StubProvider(String url, long size, String checksum) {
            this.url = url;
            this.size = size;
            this.checksum = checksum;
        }

        @Override
        public String id() {
            return "stub";
        }

        @Override
        public String displayName() {
            return "Stub JDK";
        }

        @Override
        public int priority() {
            return 2;
        }

        @Override
        public JavaPackage resolve(int majorVersion) {
            String effective = checksumOverride == null ? checksum : checksumOverride;
            return new JavaPackage("stub", majorVersion, majorVersion + ".0.1", JDK_FILE, "zip",
                    effective, size, List.of(url));
        }
    }

    /**
     * 桩发行版：解析成功但归档地址不可达。
     * <p>用于验证「首选发行版下载失败后自动改用下一个发行版」——
     * Dragonwell 只有 GitHub 一个下载源，国内网络下这正是最常见的失败方式，
     * 若无下载阶段降级，用户会遇到「配了备用发行版却依然装不上」。</p>
     */
    private final class UnreachableProvider implements JavaProvider {

        private final String url;

        UnreachableProvider(String url) {
            this.url = url;
        }

        @Override
        public String id() {
            return "unreachable";
        }

        @Override
        public String displayName() {
            return "Unreachable JDK";
        }

        @Override
        public int priority() {
            // 数值更小 → 先于 StubProvider 被尝试
            return 1;
        }

        @Override
        public JavaPackage resolve(int majorVersion) {
            return new JavaPackage("unreachable", majorVersion, majorVersion + ".0.9", "unreachable-jdk.zip",
                    "zip", "0".repeat(64), -1L, List.of(url));
        }
    }

    /** 累计的归档下载请求次数，用于验证幂等。 */
    private int requestCount() {
        AtomicInteger counter = requestCounts.get("/" + JDK_FILE);
        return counter == null ? 0 : counter.get();
    }

    private Fixture prepare() throws IOException {
        // 显式把「安装根」固定到临时目录：Java 运行时安装在 <安装根>/java 下，
        // 若沿用自动探测，测试会写进构建输出目录并跨用例互相污染
        PortablePaths paths = PortablePaths.resolve(tempDir, false, tempDir.resolve("data").toString());
        paths.prepare();
        ConfigStore store = ConfigStore.open(paths);

        byte[] archive = buildJdkArchive("jdk-17.0.12+7", "17.0.12");
        payloads.put("/" + JDK_FILE, archive);
        requestCounts.put("/" + JDK_FILE, new AtomicInteger());

        stubProvider = new StubProvider(base + "/" + JDK_FILE, archive.length, HashUtils.sha256Hex(archive));

        LauncherConfig.DownloadSettings downloadSettings = new LauncherConfig.DownloadSettings();
        downloadSettings.source = LauncherConfig.DownloadSettings.SOURCE_OFFICIAL;
        downloadSettings.concurrency = 2;
        downloadSettings.timeoutSeconds = 10;
        SourceResolver resolver = new SourceResolver(downloadSettings,
                List.of(new com.misaka.launcher.core.download.OfficialSource()));
        DownloadManager downloadManager = new DownloadManager(resolver, downloadSettings);

        JavaRuntimeManager manager = new JavaRuntimeManager(paths, store, downloadManager,
                List.of(stubProvider));
        return new Fixture(paths, store, manager, downloadManager);
    }

    /** 直接写入登记表，模拟「本机已存在某版本 Java」。 */
    private void registerFake(Fixture fixture, int majorVersion) throws IOException {
        Path home = tempDir.resolve("fake-jdk-" + majorVersion);
        writeFakeJdk(home, majorVersion + ".0.1");
        fixture.manager.register(home.toString());
    }

    private void writeFakeJdk(Path home, String version) throws IOException {
        Path bin = home.resolve("bin");
        Files.createDirectories(bin);
        Files.writeString(bin.resolve(OsUtils.executableName("java")), "fake");
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
    }

    /** 构造一个带顶层目录的 JDK zip（含 bin/java 与 release）。 */
    private byte[] buildJdkArchive(String topLevelDir, String version) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry(topLevelDir + "/bin/" + OsUtils.executableName("java")));
            zip.write("fake-java-binary".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry(topLevelDir + "/release"));
            zip.write(("JAVA_VERSION=\"" + version + "\"\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry(topLevelDir + "/lib/modules"));
            zip.write("fake-modules".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requestCounts.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
        byte[] data = payloads.get(path);
        try {
            if (data == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
        } finally {
            exchange.close();
        }
    }
}
