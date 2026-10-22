package com.misaka.launcher.core.update;

import com.misaka.launcher.core.i18n.I18n;
import com.misaka.launcher.core.util.FileUtils;
import com.misaka.launcher.core.util.OsUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 自更新安装器：把下载好的新版启动器换上，并给出「启动新版」的命令行。
 *
 * <p>这是全流程里<b>唯一有平台差异</b>的一环，差异集中在本类，其余代码（检查、下载、校验）
 * 三平台完全一致：</p>
 *
 * <table border="1">
 *   <caption>各平台的替换与重启方式</caption>
 *   <tr><th>形态</th><th>替换对象</th><th>重启方式</th></tr>
 *   <tr>
 *     <td>Windows 单文件 exe</td>
 *     <td>正在运行的 exe 自身（<b>先改名再落新文件</b>：Windows 不允许覆盖正在运行的镜像，
 *         但允许改名）</td>
 *     <td>直接启动新 exe；它会自解压到新版本目录，数据根目录不变</td>
 *   </tr>
 *   <tr>
 *     <td>Linux/macOS 单文件 .sh</td>
 *     <td>脚本自身（Unix 允许直接覆盖运行中的文件）</td>
 *     <td>补可执行位后启动；<b>不补位会 Permission denied</b>（下载下来的文件是 0644）</td>
 *   </tr>
 *   <tr>
 *     <td>可执行 jar / 应用镜像里的 app jar</td>
 *     <td>当前 jar（先改名再落新文件；应用镜像另有外层启动器 exe 时优先用它重启）</td>
 *     <td>{@code javaw -jar}（Windows，避免弹黑框）/ {@code java -jar}；
 *         有外层启动器时直接启动它，图标与控制台行为与原来一致</td>
 *   </tr>
 * </table>
 *
 * <p>两条兜底原则：</p>
 * <ul>
 *   <li><b>替换失败就并排启动</b>：目标被锁、介质只读、权限不足时，不把用户卡在
 *       「更新失败且没有新版可用」，而是直接启动下载好的新文件（留在缓存目录里），
 *       并保留旧文件不动 —— 客户机上「能用」优先；</li>
 *   <li><b>旧文件下次启动再删</b>：正在运行的文件改名后仍被占用，删除要等进程退出，
 *       所以清理放在启动时做（见 {@link #cleanupLeftovers(Path)}）。</li>
 * </ul>
 */
public final class SelfUpdateInstaller {

    private static final Logger log = LoggerFactory.getLogger(SelfUpdateInstaller.class);

    /** 单文件外壳写进环境变量的自身路径，用于让启动器知道自己「是被哪个文件启动的」。 */
    public static final String SELF_EXE_ENV = "MISAKA_SELF_EXE";

    /** 数据根目录的系统属性（与 {@code PortablePaths.ROOT_PROPERTY} 同一个键）。 */
    private static final String ROOT_PROPERTY = "misaka.launcher.root";

    /** 备份后缀；下次启动时清理。 */
    private static final String BACKUP_SUFFIX = ".old";

    /**
     * 改名前的产品标识（旧版 jpackage 用的外层启动器名）。
     * <p>只用于「在老安装目录里找到外层可执行文件」，不参与任何新产物的命名。</p>
     */
    private static final String LEGACY_STUB = "MisakaMinecraftLauncher";

    private SelfUpdateInstaller() {
    }

    /** 启动器当前的安装形态。 */
    public enum Form {
        /** 可执行 jar（含 jpackage 应用镜像里的 app jar）。 */
        JAR("update.form.jar"),
        /** Windows 单文件 exe。 */
        WINDOWS_EXE("update.form.windowsExe"),
        /** Linux / macOS 单文件脚本。 */
        UNIX_SCRIPT("update.form.unixScript"),
        /** 其它形态（IDE 中运行、未知布局），不支持自动替换。 */
        UNSUPPORTED("update.form.unsupported");

        private final String key;

        Form(String key) {
            this.key = key;
        }

        /** 展示名。<b>每次取都过一遍国际化</b>：枚举常量在类加载时就构造好了，
         * 把文案存进字段会让语言切换后仍然显示旧语言。 */
        public String label() {
            return I18n.t(key);
        }
    }

    /**
     * 当前安装位置。
     *
     * @param form        形态
     * @param file        要替换的文件（jar / exe / sh）
     * @param stub        外层启动器（应用镜像的 exe 或 bin 下的启动脚本）；没有则为 null
     * @param description 供日志与界面展示的描述
     */
    public record Location(Form form, Path file, Path stub, String description) {
    }

    /**
     * 一次替换的结果。
     *
     * @param command    启动新版所需的完整命令行
     * @param launched   实际会被启动的文件
     * @param replaced   被替换掉的原文件；并排启动（未替换）时为 null
     * @param sideBySide 是否退化为「并排启动」（原文件未动）
     * @param description 供界面展示的说明
     */
    public record Relaunch(List<String> command, Path launched, Path replaced, boolean sideBySide,
                           String description) {
    }

    // ------------------------------------------------------------------
    // 探测
    // ------------------------------------------------------------------

    /** 定位当前安装形态。识别不出来时返回 {@link Form#UNSUPPORTED}，由调用方提示手动更新。 */
    public static Location locate() {
        String selfExe = System.getenv(SELF_EXE_ENV);
        if (selfExe != null && !selfExe.isBlank()) {
            Path path = Path.of(selfExe.trim());
            if (Files.isRegularFile(path)) {
                Form form = OsUtils.isWindows() ? Form.WINDOWS_EXE : Form.UNIX_SCRIPT;
                return new Location(form, path, path, path.getFileName().toString());
            }
            log.debug("{} 指向的文件不存在，忽略: {}", SELF_EXE_ENV, selfExe);
        }
        Path code = codeLocation();
        if (code == null) {
            return new Location(Form.UNSUPPORTED, null, null, I18n.t("update.form.unknown"));
        }
        if (Files.isDirectory(code)) {
            // IDE / classpath 运行：没有可替换的文件
            return new Location(Form.UNSUPPORTED, code, null, code.getFileName().toString());
        }
        String name = code.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jar")) {
            Path stub = findStub(code);
            return new Location(Form.JAR, code, stub, code.getFileName().toString());
        }
        if (name.endsWith(".exe")) {
            return new Location(Form.WINDOWS_EXE, code, code, code.getFileName().toString());
        }
        if (name.endsWith(".sh")) {
            return new Location(Form.UNIX_SCRIPT, code, code, code.getFileName().toString());
        }
        return new Location(Form.UNSUPPORTED, code, null, code.getFileName().toString());
    }

    /** 当前代码所在位置（jar 文件或 classes 目录）。 */
    public static Path codeLocation() {
        try {
            var source = SelfUpdateInstaller.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            return Path.of(source.getLocation().toURI());
        } catch (Exception e) {
            log.debug("无法定位自身代码位置: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 在应用镜像（jpackage）布局里寻找外层启动器。
     * <p>布局为 {@code <root>/app/*.jar} + {@code <root>/MisakaLauncher.exe}（Windows）
     * 或 {@code <root>/bin/MisakaLauncher}（Linux/macOS）。</p>
     *
     * <p><b>旧名要留着</b>：产品改名（旧名为 {@code MisakaMinecraftLauncher}）之前装好的目录，
     * 外层可执行文件名还是旧的。自更新只替换 jar、不动目录布局，若此处只认新名，
     * 那些老安装升级后就找不到重启目标 —— 表现为「更新成功但启动器没再起来」。</p>
     */
    private static Path findStub(Path jar) {
        Path appDir = jar.getParent();
        if (appDir == null) {
            return null;
        }
        Path home = appDir.getParent();
        if (home == null) {
            return null;
        }
        List<Path> candidates = new ArrayList<>();
        if (OsUtils.isWindows()) {
            candidates.add(home.resolve("MisakaLauncher.exe"));
            candidates.add(home.resolve(LEGACY_STUB + ".exe"));
        } else {
            candidates.add(home.resolve("bin").resolve("MisakaLauncher"));
            candidates.add(home.resolve(LEGACY_STUB));
            candidates.add(home.resolve("bin").resolve(LEGACY_STUB));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 替换与重启
    // ------------------------------------------------------------------

    /**
     * 用下载好的新文件替换当前启动器。
     *
     * @param artifact 已下载并通过校验的新文件
     * @param root     数据根目录（重启时通过 {@code -Dmisaka.launcher.root} 传下去，保证数据不搬家）
     */
    public static Relaunch install(Path artifact, Path root) throws IOException {
        return install(locate(), artifact, root);
    }

    /**
     * 用下载好的新文件替换指定位置的启动器。
     * <p>与 {@link #install(Path, Path)} 分开，是为了让替换逻辑可以脱离「真实的自身位置」被测试
     * —— 否则这个只在现场出问题的分支永远验证不到。</p>
     */
    static Relaunch install(Location location, Path artifact, Path root) throws IOException {
        if (artifact == null || !Files.isRegularFile(artifact)) {
            throw new IOException(I18n.t("update.error.artifactMissing"));
        }
        if (location.form() == Form.UNSUPPORTED || location.file() == null) {
            // 先判「有没有可替换的文件」：这种情况下「更新包形态不符」是一句会把人带偏的废话
            throw new IOException(I18n.t("update.error.unsupportedForm", location.description()));
        }
        // 再判形态：把 jar 复制成 .exe（或反过来）不会报任何错，
        // 但用户手里会立刻多出一个「双击没反应」的启动器 —— 这种损坏是不可逆的
        requireSameKind(location.form(), artifact);
        Path target = location.file();
        Path backup = target.resolveSibling(target.getFileName() + BACKUP_SUFFIX);
        boolean renamed = false;
        try {
            Files.deleteIfExists(backup);
            // Windows：正在运行的 exe / 被 JVM 打开的 jar 都不允许覆盖，但允许改名
            Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
            renamed = true;
            Files.copy(artifact, target, StandardCopyOption.REPLACE_EXISTING);
            markExecutable(target);
            log.info("启动器已更新到 {}（原文件备份为 {}）", target.getFileName(), backup.getFileName());
            return new Relaunch(commandFor(location, target, root), target, target, false,
                    I18n.t("update.relaunch.replaced", target.getFileName()));
        } catch (IOException e) {
            if (renamed && !Files.exists(target)) {
                // 落新文件失败：把原文件放回去，否则用户手里就没有启动器了
                try {
                    Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException rollbackFailure) {
                    log.error("回滚启动器备份失败，请手动把 {} 改回 {}", backup, target, rollbackFailure);
                }
            }
            log.warn("就地替换启动器失败，改为并排启动新版本：{}", e.getMessage());
            return new Relaunch(commandFor(location, artifact, root), artifact, null, true,
                    I18n.t("update.relaunch.sideBySide", artifact.getFileName()));
        }
    }

    /**
     * 启动新版启动器。
     *
     * @param relaunch {@link #install(Path, Path)} 的结果
     */
    public static void start(Relaunch relaunch) throws IOException {
        if (relaunch == null || relaunch.command().isEmpty()) {
            throw new IOException(I18n.t("update.error.relaunchCommand"));
        }
        ProcessBuilder builder = new ProcessBuilder(relaunch.command());
        Path workDir = relaunch.launched().getParent();
        if (workDir != null && Files.isDirectory(workDir)) {
            builder.directory(workDir.toFile());
        }
        builder.redirectErrorStream(true);
        // 不让新进程继承 stdout/stderr 句柄：本进程随后就要退出，
        // 继承的句柄一关会让新进程的日志写入直接报错
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.start();
        log.info("已启动新版启动器：{}", String.join(" ", relaunch.command()));
    }

    /**
     * 校验更新包形态与当前运行形态一致。
     * <p>发布时同一个产品下很可能同时挂着 exe、sh、jar 三种包，选错一个就会把安装毁掉；
     * 宁可在替换前报错，也不能写进去。</p>
     */
    private static void requireSameKind(Form form, Path artifact) throws IOException {
        String name = artifact.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean matches = switch (form) {
            case JAR -> name.endsWith(".jar");
            case WINDOWS_EXE -> name.endsWith(".exe");
            // Unix 单文件包按约定是 .sh；不认后缀的裸二进制不允许，避免把 zip 之类写进去
            case UNIX_SCRIPT -> name.endsWith(".sh");
            case UNSUPPORTED -> false;
        };
        if (!matches) {
            throw new IOException(I18n.t("update.error.kindMismatch", form.label(),
                    artifact.getFileName().toString()));
        }
    }

    /** 拼出「启动某个启动器文件」的命令行。 */
    private static List<String> commandFor(Location location, Path file, Path root) {
        if (location.form() == Form.WINDOWS_EXE || location.form() == Form.UNIX_SCRIPT) {
            // 用户可能直接选中了 exe / 脚本，用它自己启动最贴合原状
            if (file.equals(location.file()) && location.stub() != null) {
                return List.of(file.toString());
            }
            if (location.form() == Form.UNIX_SCRIPT) {
                // 并排启动时用 sh 显式执行：绕过可执行位，避免 Permission denied
                return isExecutable(file) ? List.of(file.toString())
                        : List.of("/bin/sh", file.toString());
            }
            return List.of(file.toString());
        }
        // jar：优先用外层启动器，其次用当前 JVM
        if (location.stub() != null && file.equals(location.file())) {
            return List.of(location.stub().toString());
        }
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        // 数据根目录必须显式传：并排启动时新文件在缓存目录里，
        // 靠「向上找标记」会解析出完全不同的根，用户的实例与设置就"丢"了
        if (root != null) {
            command.add("-D" + ROOT_PROPERTY + "=" + root);
        }
        // JDK 17 在 Windows 上默认字符集是 GBK，不显式指定会让中文路径与配置读写乱码
        command.add("-Dfile.encoding=UTF-8");
        command.add("-jar");
        command.add(file.toString());
        return command;
    }

    /** 当前 JVM 的可执行文件；Windows 优先 javaw，避免弹出一个多余的控制台窗口。 */
    private static String javaExecutable() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        if (OsUtils.isWindows()) {
            Path javaw = bin.resolve("javaw.exe");
            if (Files.isRegularFile(javaw)) {
                return javaw.toString();
            }
        }
        return OsUtils.executableName(bin.resolve("java").toString());
    }

    /** 补可执行位；Windows 与不支持 POSIX 权限的文件系统上静默跳过。 */
    private static void markExecutable(Path file) {
        if (OsUtils.isWindows()) {
            return;
        }
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (IOException | UnsupportedOperationException e) {
            log.warn("补可执行位失败（{}）：{}", file, e.getMessage());
        }
    }

    private static boolean isExecutable(Path file) {
        if (OsUtils.isWindows()) {
            return true;
        }
        try {
            return Files.getPosixFilePermissions(file).contains(PosixFilePermission.OWNER_EXECUTE);
        } catch (IOException | UnsupportedOperationException e) {
            return Files.isExecutable(file);
        }
    }

    /**
     * 清理上次更新留下的备份文件。
     * <p>正在运行的文件改名后仍被占用，删除必须等进程退出 —— 所以放在每次启动时做。</p>
     *
     * @return 清理掉的文件数
     */
    public static int cleanupLeftovers() {
        Location location = locate();
        if (location.file() == null || location.file().getParent() == null) {
            return 0;
        }
        return cleanupLeftovers(location.file().getParent());
    }

    /** 清理指定目录里的备份文件（与自身位置解耦，便于测试这一分支）。 */
    static int cleanupLeftovers(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        int removed = 0;
        for (Path file : FileUtils.listFiles(dir)) {
            String name = file.getFileName().toString();
            if (name.endsWith(BACKUP_SUFFIX) && FileUtils.deleteQuietly(file)) {
                removed++;
            }
        }
        if (removed > 0) {
            log.info("已清理 {} 个更新备份文件", removed);
        }
        return removed;
    }

    /** 更新包下载目录。 */
    public static Path defaultDownloadDir(Path cacheDir) {
        return cacheDir.resolve("updates");
    }
}
