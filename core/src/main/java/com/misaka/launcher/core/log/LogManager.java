package com.misaka.launcher.core.log;

import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.config.PortablePaths;
import com.misaka.launcher.core.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * 日志子系统门面：初始化、实时订阅、游戏日志落盘与敏感信息脱敏。
 * <p>实现基于 {@link java.util.logging}（JUL），通过 SLF4J 的 JUL 桥接接收
 * {@code org.slf4j.Logger} 调用，从而在不引入第三方日志实现的前提下获得
 * 「内存缓冲 + 双维度滚动文件 + 控制台」三路输出，同时保持运行时依赖最小。</p>
 */
public final class LogManager {

    private static final Logger log = LoggerFactory.getLogger(LogManager.class);

    private static final String ROOT_LOGGER_NAME = "";
    private static final String APP_LOGGER_NAME = "com.misaka.launcher";
    /** 诊断开关：临时放开被压制的 JavaFX 内部日志（用于排查界面尺寸/布局问题）。 */
    private static final String JAVAFX_LEVEL_PROPERTY = "misaka.log.javafx";
    private static final DateTimeFormatter GAME_LOG_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final Object LOCK = new Object();

    private static Path logsDir;
    private static RollingFileHandler fileHandler;
    private static MemoryLogBuffer memoryBuffer;
    private static ConsoleHandler consoleHandler;
    private static Level currentLevel = Level.INFO;
    private static boolean initialized = false;

    private LogManager() {
    }

    // ------------------------------------------------------------------
    // 初始化与动态配置
    // ------------------------------------------------------------------

    /** 初始化日志系统。应在应用启动最早期调用。 */
    public static void init(PortablePaths paths, LauncherConfig.LogSettings settings) {
        synchronized (LOCK) {
            logsDir = paths.logsDir();
            try {
                Files.createDirectories(logsDir);
                Files.createDirectories(paths.gameLogsDir());
            } catch (IOException e) {
                System.err.println("[MisakaLog] 创建日志目录失败: " + e.getMessage());
            }

            java.util.logging.LogManager jlm = java.util.logging.LogManager.getLogManager();
            // 清除 JVM 默认配置，避免与自有 handler 重复输出
            jlm.reset();

            currentLevel = parseLevel(settings == null ? "INFO" : settings.level);
            java.util.logging.Logger root = java.util.logging.Logger.getLogger(ROOT_LOGGER_NAME);
            for (Handler handler : root.getHandlers()) {
                root.removeHandler(handler);
            }
            // 关键：必须把「配置的级别」真正应用到根 Logger。
            // 若只给 handler 设 ALL 而让根 Logger 保持 ALL，则所有 TRACE/FINEST 记录都会照常
            // 落盘 —— JavaFX 的布局日志单次启动就能产生数万行、数 MB，
            // 设置页里的「日志级别」会形同虚设，日志页也会被这些噪声淹没。
            root.setLevel(currentLevel);
            java.util.logging.Logger.getLogger(APP_LOGGER_NAME).setLevel(currentLevel);
            silenceNoisyFrameworks();

            memoryBuffer = new MemoryLogBuffer();
            memoryBuffer.setLevel(Level.ALL);
            root.addHandler(memoryBuffer);

            long maxFileBytes = (settings == null ? 8 : Math.max(1, settings.maxFileMb)) * 1024L * 1024L;
            fileHandler = new RollingFileHandler(logsDir, "launcher", maxFileBytes,
                    settings == null ? 20 : Math.max(1, settings.maxFiles / 2));
            fileHandler.setLevel(Level.ALL);
            root.addHandler(fileHandler);

            if (settings == null || settings.consoleOutput) {
                consoleHandler = createConsoleHandler();
                root.addHandler(consoleHandler);
            }

            initialized = true;
            log.info("日志子系统已初始化：dir={}, level={}", logsDir, currentLevel);
        }
    }

    /**
     * 应用新的日志设置（不需要重启）。
     * <p>level 与 console 开关可动态生效；文件体积上限在下次滚动时生效。</p>
     */
    public static void applySettings(LauncherConfig.LogSettings settings) {
        if (settings == null) {
            return;
        }
        synchronized (LOCK) {
            currentLevel = parseLevel(settings.level);
            java.util.logging.Logger root = java.util.logging.Logger.getLogger(ROOT_LOGGER_NAME);
            // 动态改级别同样要落到根 Logger，否则「改了设置没效果」
            root.setLevel(currentLevel);
            java.util.logging.Logger.getLogger(APP_LOGGER_NAME).setLevel(currentLevel);
            silenceNoisyFrameworks();
            if (memoryBuffer != null) {
                memoryBuffer.setLevel(Level.ALL);
            }
            if (settings.consoleOutput && consoleHandler == null) {
                consoleHandler = createConsoleHandler();
                root.addHandler(consoleHandler);
            } else if (!settings.consoleOutput && consoleHandler != null) {
                root.removeHandler(consoleHandler);
                consoleHandler.close();
                consoleHandler = null;
            }
            if (fileHandler != null) {
                fileHandler.setLevel(Level.ALL);
            }
            log.info("日志设置已更新：level={}, console={}", currentLevel, settings.consoleOutput);
        }
    }

    /**
     * 压低框架内部日志。
     * <p>典型受害者是 {@code javafx.scene.layout}：它会在 FINEST 级别逐个节点打印
     * 「resized to / moved to」，一次启动就是数万行。这类日志对排查本启动器的问题
     * 几乎没有价值，却会让日志文件膨胀到数 MB、把日志页刷满，因此固定压到 WARNING。</p>
     * <p>诊断界面尺寸问题时需要看这些布局日志，可用系统属性 {@value #JAVAFX_LEVEL_PROPERTY}
     * 临时放开，例如 {@code -Dmisaka.log.javafx=FINEST}。</p>
     */
    private static void silenceNoisyFrameworks() {
        String override = System.getProperty(JAVAFX_LEVEL_PROPERTY);
        Level frameworkLevel = override == null || override.isBlank()
                ? Level.WARNING : parseLevel(override);
        for (String name : List.of("javafx", "com.sun.javafx", "com.sun.prism",
                "com.sun.scenario", "com.sun.javafx.webkit", "com.sun.glass",
                // 每次 TLS 握手都会在 DEBUG 打印一串未替换的 MessageFormat 占位符（{0}/{1}），
                // 对排查本启动器的问题没有帮助
                "jdk.event.security", "sun.net.www")) {
            java.util.logging.Logger.getLogger(name).setLevel(frameworkLevel);
        }
        if (frameworkLevel.intValue() < Level.WARNING.intValue()) {
            log.info("框架日志级别已被 {} 放开为 {}", JAVAFX_LEVEL_PROPERTY, frameworkLevel);
        }
    }

    private static ConsoleHandler createConsoleHandler() {
        // 使用 UTF-8 输出，避免中文日志乱码（Windows 控制台默认 GBK）
        PrintStream stream = new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                System.out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                System.out.write(b, off, len);
            }

            @Override
            public void flush() {
                System.out.flush();
            }
        }, true, StandardCharsets.UTF_8);

        ConsoleHandler handler = new ConsoleHandler() {
            @Override
            public void publish(LogRecord record) {
                if (!isLoggable(record)) {
                    return;
                }
                stream.println(LogFormatter.formatLine(record));
                String thrown = LogFormatter.formatThrown(record);
                if (thrown != null) {
                    stream.println(thrown);
                }
                stream.flush();
            }
        };
        handler.setLevel(Level.ALL);
        // 不调用 setFormatter：java.util.logging 不允许传入 null，
        // 且本处理器已自行格式化输出，无需依赖 Formatter
        return handler;
    }

    public static Level parseLevel(String name) {
        if (name == null || name.isBlank()) {
            return Level.INFO;
        }
        return switch (name.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "TRACE", "FINEST", "ALL" -> Level.FINEST;
            case "DEBUG", "FINE" -> Level.FINE;
            case "WARN", "WARNING" -> Level.WARNING;
            case "ERROR", "SEVERE" -> Level.SEVERE;
            case "OFF" -> Level.OFF;
            default -> Level.INFO;
        };
    }

    /** 把 UI 侧级别名转换为 JUL 级别，供过滤与设置页共用。 */
    public static Level levelOf(String name) {
        return parseLevel(name);
    }

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    public static Path logsDir() {
        return logsDir;
    }

    /** 启动器活动日志文件。 */
    public static Path launcherLogFile() {
        return fileHandler != null ? fileHandler.currentFile()
                : (logsDir == null ? null : logsDir.resolve("launcher.log"));
    }

    /** 为一次游戏启动创建日志文件：{@code logs/game/<instanceId>-<时间戳>.log}。 */
    public static Path newGameLogFile(String instanceId) {
        Path dir = logsDir == null ? Path.of("logs", "game") : logsDir.resolve("game");
        try {
            FileUtils.ensureDir(dir);
        } catch (IOException e) {
            log.warn("创建游戏日志目录失败: {}", e.getMessage());
        }
        String safeId = instanceId == null || instanceId.isBlank() ? "instance" : sanitize(instanceId);
        return dir.resolve(safeId + "-" + LocalDateTime.now().format(GAME_LOG_STAMP) + ".log");
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** 打开用于写入游戏输出的流（追加模式，UTF-8）。 */
    public static java.io.Writer newGameLogWriter(Path gameLog) throws IOException {
        FileUtils.ensureParent(gameLog);
        return Files.newBufferedWriter(gameLog, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    // ------------------------------------------------------------------
    // 实时订阅
    // ------------------------------------------------------------------

    /** 历史日志快照。 */
    public static List<LogEntry> history() {
        return memoryBuffer == null ? List.of() : memoryBuffer.snapshot();
    }

    /** 注册实时日志监听；返回句柄用于取消。 */
    public static AutoCloseable addListener(Consumer<LogEntry> listener) {
        if (memoryBuffer == null) {
            return () -> {
            };
        }
        return memoryBuffer.addListener(listener);
    }

    public static void clearBuffer() {
        if (memoryBuffer != null) {
            memoryBuffer.clear();
        }
    }

    public static void shutdown() {
        synchronized (LOCK) {
            if (!initialized) {
                return;
            }
            log.info("日志子系统正在关闭");
            if (fileHandler != null) {
                fileHandler.flush();
                fileHandler.close();
            }
            if (memoryBuffer != null) {
                memoryBuffer.close();
            }
            initialized = false;
        }
    }

    // ------------------------------------------------------------------
    // 敏感信息脱敏
    // ------------------------------------------------------------------

    private static final java.util.regex.Pattern SECRET_PATTERN = java.util.regex.Pattern.compile(
            "(?i)\\b(accessToken|access_token|refreshToken|refresh_token|clientSecret|client_secret|"
                    + "x-api-key|apiKey|api_key|password|serverId|session|token)\\b\\s*[=:]\\s*([^\\s,;&\"']+)");

    private static final java.util.regex.Pattern JWT_PATTERN = java.util.regex.Pattern.compile(
            "eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}");

    /**
     * 脱敏：把令牌类键值对与 JWT 主体替换为掩码。
     * <p>约定：任何可能包含令牌的文本（命令行、HTTP 响应片段）在落日志前必须先经过本方法。</p>
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = SECRET_PATTERN.matcher(text).replaceAll("$1=****");
        return JWT_PATTERN.matcher(masked).replaceAll("****");
    }

    /** 对命令行参数列表整体脱敏（用于日志打印）。 */
    public static String maskCommandLine(List<String> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String arg : args) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (arg.contains(" ") || arg.contains("\t")) {
                sb.append('"').append(arg).append('"');
            } else {
                sb.append(arg);
            }
        }
        return mask(sb.toString());
    }
}
