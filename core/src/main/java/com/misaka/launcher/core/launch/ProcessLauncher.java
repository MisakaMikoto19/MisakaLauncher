package com.misaka.launcher.core.launch;

import com.misaka.launcher.core.log.LogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 游戏进程启动与日志接管。
 * <p>职责：启动进程、把标准输出同时写盘与推送给界面、在退出时回调结果。</p>
 */
public final class ProcessLauncher {

    private static final Logger log = LoggerFactory.getLogger(ProcessLauncher.class);

    /** 强制结束前的等待时间。 */
    private static final long GRACEFUL_SHUTDOWN_SECONDS = 5L;

    private ProcessLauncher() {
    }

    /**
     * 游戏进程事件回调。
     */
    public interface GameProcessListener {

        /** 收到一行游戏输出（已脱敏）。 */
        default void onOutput(String line) {
        }

        /** 进程退出。 */
        default void onExit(int exitCode, long durationMillis) {
        }
    }

    /**
     * 启动结果。
     *
     * @param process   进程句柄
     * @param logFile   游戏日志文件；未落盘时为 null
     * @param startedAt 启动时间戳
     */
    public record StartResult(Process process, Path logFile, long startedAt) {

        public boolean isAlive() {
            return process != null && process.isAlive();
        }

        public long pid() {
            return process == null ? -1L : process.pid();
        }
    }

    /**
     * 启动游戏进程。
     *
     * @param plan     启动计划
     * @param listener 事件回调；可为 null
     */
    public static StartResult start(LaunchPlan plan, GameProcessListener listener) throws IOException {
        if (!plan.hasValidJava()) {
            throw new IOException("Java 可执行文件不存在: " + plan.javaExecutable());
        }
        if (!Files.isDirectory(plan.gameDir())) {
            throw new IOException("游戏目录不存在: " + plan.gameDir());
        }

        ProcessBuilder builder = new ProcessBuilder(plan.command());
        builder.directory(plan.gameDir().toFile());
        // 合并错误流：Minecraft 与 Forge 都会把部分日志写到 stderr
        builder.redirectErrorStream(true);
        if (!plan.environment().isEmpty()) {
            builder.environment().putAll(plan.environment());
        }

        long startedAt = System.currentTimeMillis();
        Process process = builder.start();
        log.info("游戏进程已启动：pid={}，版本={}，Java={}",
                process.pid(), plan.versionId(), plan.javaExecutable());

        Path logFile = plan.gameLogFile();
        Writer writer = null;
        if (logFile != null) {
            try {
                writer = LogManager.newGameLogWriter(logFile);
                writer.write(LogManager.mask("# 命令：" + plan.maskedCommandLine()));
                writer.write(System.lineSeparator());
                writer.flush();
            } catch (IOException e) {
                log.warn("无法创建游戏日志文件 {}: {}", logFile, e.getMessage());
                writer = null;
            }
        }

        pump(process, writer, listener, startedAt);
        return new StartResult(process, writer == null ? null : logFile, startedAt);
    }

    /**
     * 在守护线程中消费进程输出。
     * <p>使用独立线程而非阻塞调用方：界面需要在游戏运行时保持可用。</p>
     */
    private static void pump(Process process, Writer writer, GameProcessListener listener, long startedAt) {
        Writer effectiveWriter = writer;
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 游戏输出可能回显启动参数，因此统一脱敏后再落盘与展示
                    String safe = LogManager.mask(line);
                    if (effectiveWriter != null) {
                        try {
                            effectiveWriter.write(safe);
                            effectiveWriter.write(System.lineSeparator());
                            effectiveWriter.flush();
                        } catch (IOException e) {
                            // 日志写失败不应中断游戏输出读取
                            log.debug("写入游戏日志失败: {}", e.getMessage());
                        }
                    }
                    if (listener != null) {
                        try {
                            listener.onOutput(safe);
                        } catch (RuntimeException e) {
                            log.debug("游戏输出回调异常: {}", e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("读取游戏输出失败: {}", e.getMessage());
            } finally {
                if (effectiveWriter != null) {
                    try {
                        effectiveWriter.close();
                    } catch (IOException ignored) {
                        // 忽略
                    }
                }
                int exitCode;
                try {
                    exitCode = process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    exitCode = -1;
                }
                long duration = System.currentTimeMillis() - startedAt;
                log.info("游戏进程已退出：pid={}，退出码={}，运行 {} 秒",
                        process.pid(), exitCode, duration / 1000);
                if (listener != null) {
                    try {
                        listener.onExit(exitCode, duration);
                    } catch (RuntimeException e) {
                        log.debug("退出回调异常: {}", e.getMessage());
                    }
                }
            }
        }, "misaka-game-output");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 优雅结束游戏进程：先请求终止，超时后强制结束。
     *
     * @return 进程是否已结束
     */
    public static boolean terminate(Process process) {
        if (process == null || !process.isAlive()) {
            return true;
        }
        log.info("正在结束游戏进程 pid={}", process.pid());
        process.destroy();
        try {
            if (process.waitFor(GRACEFUL_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        process.destroyForcibly();
        try {
            return process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 等待进程结束并返回退出码；中断时返回 -1。 */
    public static int awaitExit(Process process) {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** 判断进程是否仍在运行。 */
    public static boolean isRunning(Process process) {
        return process != null && process.isAlive();
    }
}
