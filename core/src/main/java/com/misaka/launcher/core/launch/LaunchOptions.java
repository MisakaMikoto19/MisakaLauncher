package com.misaka.launcher.core.launch;

import java.util.List;

/**
 * 启动选项（由实例配置与全局设置合成）。
 *
 * <p>这里<b>没有</b>启动器标识与版本号：{@code -Dminecraft.launcher.brand} /
 * {@code -Dminecraft.launcher.version} 由 {@link LaunchCommandBuilder} 自己携带（见其构造器），
 * 曾经在这里各放一份 —— 但那两个字段从未被读取，只是每次升版本号都要顺手改一处字面量。
 * 版本号的唯一来源是构建期的 {@code BuildInfo}。</p>
 *
 * @param account        账户信息
 * @param javaExecutable 用于启动游戏的 java 可执行文件
 * @param minMemoryMb    初始堆
 * @param maxMemoryMb    最大堆
 * @param extraJvmArgs   附加 JVM 参数（手工配置或自动预设）
 * @param extraGameArgs  附加游戏参数
 * @param windowWidth    窗口宽度
 * @param windowHeight   窗口高度
 * @param fullscreen     是否全屏
 */
public record LaunchOptions(AccountInfo account,
                            java.nio.file.Path javaExecutable,
                            int minMemoryMb,
                            int maxMemoryMb,
                            List<String> extraJvmArgs,
                            List<String> extraGameArgs,
                            int windowWidth,
                            int windowHeight,
                            boolean fullscreen) {

    public LaunchOptions {
        extraJvmArgs = extraJvmArgs == null ? List.of() : List.copyOf(extraJvmArgs);
        extraGameArgs = extraGameArgs == null ? List.of() : List.copyOf(extraGameArgs);
    }
}
