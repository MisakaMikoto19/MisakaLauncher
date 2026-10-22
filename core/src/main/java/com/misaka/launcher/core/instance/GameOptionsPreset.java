package com.misaka.launcher.core.instance;

import com.misaka.launcher.core.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 内置的 {@code options.txt} 预设（键位、画面、音量等一并固化）。
 *
 * <p><b>为什么需要它</b>：客户机上的键位与画面档位必须一致，靠玩家进游戏自己调不可能对齐。
 * 安装游戏时铺一份预设，玩家一进去就是配好的状态。</p>
 *
 * <p><b>两条入口的语义刻意不同</b>：</p>
 * <ul>
 *   <li>{@link #deployIfAbsent} 给安装流程用 —— 只在游戏目录<b>还没有</b> {@code options.txt} 时铺，
 *       已有则一律不动（玩家自己调过的键位属于玩家）；且<b>绝不抛异常</b>：
 *       预设只是锦上添花，为它让整个安装失败是最差的结果。</li>
 *   <li>{@link #overwrite} 给实例页的「应用键位预设」按钮用 —— 整份覆盖，
 *       失败必须抛出，界面才能把原因说清楚。</li>
 * </ul>
 *
 * <p>资源位于 {@code core/src/main/resources/preset/options.txt}，按字节原样拷贝。</p>
 */
public final class GameOptionsPreset {

    private static final Logger log = LoggerFactory.getLogger(GameOptionsPreset.class);

    /** 内置预设的 classpath 资源路径。 */
    private static final String RESOURCE = "/preset/options.txt";

    /** 游戏目录里的文件名：Minecraft 固定读这个名字，不可改。 */
    public static final String FILE_NAME = "options.txt";

    private GameOptionsPreset() {
    }

    /** 资源是否随包打了进来（打包遗漏时可据此给出可读提示）。 */
    public static boolean isBundled() {
        return GameOptionsPreset.class.getResource(RESOURCE) != null;
    }

    /**
     * 仅当游戏目录还没有 {@code options.txt} 时铺一份内置预设（幂等，且不抛异常）。
     *
     * @return 真表示这次确实写入了
     */
    public static boolean deployIfAbsent(GamePaths paths) {
        if (paths == null) {
            return false;
        }
        Path target = paths.gameDir().resolve(FILE_NAME);
        try {
            if (Files.isRegularFile(target)) {
                return false;
            }
            copyTo(target);
            log.info("已铺内置键位预设: {}", target);
            return true;
        } catch (IOException e) {
            // 预设失败绝不能连累安装：文件下载与加载器安装都远比它重要
            log.warn("铺内置键位预设失败（忽略）: {}：{}", target, e.getMessage());
            return false;
        }
    }

    /**
     * 整份覆盖为内置预设（实例页的手动重刷）。
     *
     * @throws IOException 资源缺失或写入失败
     */
    public static void overwrite(GamePaths paths) throws IOException {
        Path target = paths.gameDir().resolve(FILE_NAME);
        copyTo(target);
        log.info("已应用内置键位预设: {}", target);
    }

    private static void copyTo(Path target) throws IOException {
        try (InputStream in = GameOptionsPreset.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("内置键位预设缺失（打包遗漏？）: " + RESOURCE);
            }
            FileUtils.ensureParent(target);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
