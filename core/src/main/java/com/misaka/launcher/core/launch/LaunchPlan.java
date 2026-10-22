package com.misaka.launcher.core.launch;

import com.misaka.launcher.core.log.LogManager;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 可执行的启动计划。
 *
 * @param versionId      实际启动的版本 id
 * @param gameDir        工作目录
 * @param javaExecutable 使用的 java 路径
 * @param command        完整命令行（首元素为 java）
 * @param classpath      拼装后的类路径（便于排查缺库问题）
 * @param nativesDir     本地库目录
 * @param gameLogFile    游戏输出落盘位置；null 表示不落盘
 * @param environment    附加环境变量
 * @param maxMemoryMb    分配的最大堆（用于界面展示）
 */
public record LaunchPlan(String versionId,
                         Path gameDir,
                         Path javaExecutable,
                         List<String> command,
                         List<String> classpath,
                         Path nativesDir,
                         Path gameLogFile,
                         Map<String, String> environment,
                         int maxMemoryMb) {

    public LaunchPlan {
        command = List.copyOf(command);
        classpath = List.copyOf(classpath);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    /**
     * 已脱敏的命令行文本。
     * <p>accessToken 等敏感值会被替换为掩码，可安全写入日志与展示给用户。</p>
     */
    public String maskedCommandLine() {
        return LogManager.maskCommandLine(command);
    }

    public boolean hasValidJava() {
        return javaExecutable != null && java.nio.file.Files.isRegularFile(javaExecutable);
    }

    /** 类的数量，用于界面展示「加载了 N 个库」。 */
    public int classpathSize() {
        return classpath.size();
    }
}
