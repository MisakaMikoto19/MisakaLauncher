package com.misaka.launcher.core.texture;

import com.misaka.launcher.core.util.FileUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把资源包登记进 {@code options.txt} 的 {@code resourcePacks} 列表，并<b>始终保持在最后一条</b>。
 *
 * <h2>为什么必须是最后一条</h2>
 * <p>{@code resourcePacks} 列表就是资源栈的顺序，<b>靠后的包覆盖靠前的包</b>。
 * 游戏启动时会把真实顺序打进日志：</p>
 * <pre>
 *   Reloading ResourceManager: vanilla, mod_resources, tacz_resources,
 *                              file/XXX.zip, programmer_art, file/puffer fish sun.zip,
 *                              file/misaka_atlas_compat
 * </pre>
 *
 * <p>而我们要覆盖的贴图，<b>同一路径在别的包里一定也存在</b>：Forge 的 {@code mod_resources}
 * 聚合了全部 mod jar，TACZ 这类模组还会在启动时自己注册一个包（实测 {@code tacz_resources}，
 * 直接读游戏目录里的 {@code tacz/}）。这些自动加入的包都排在用户包前面，
 * 所以只要我们在末尾就能盖掉它们的原始大贴图；<b>一旦排到它们前面，压缩就白做了</b>。</p>
 *
 * <p><b>实测对照</b>（同一台 16384 上限的机器、同一份整合包）：</p>
 * <ul>
 *   <li>没有我们的包：{@code Unable to fit: dragonrise_reforge:item/cnfast - size: 512x512} → 崩溃；</li>
 *   <li>加了排在末尾的包：{@code Created: 16384x16384x4 minecraft:textures/atlas/blocks.png-atlas}
 *       正常进游戏 —— 图集最终尺寸刚好顶到 16384 上限，正是「压缩后的贴图被真正采用」的结果
 *       （未压缩总量为 3.26 亿像素，物理上塞不进 2.68 亿像素的图集）。</li>
 * </ul>
 *
 * <p>因此不能只判「条目在不在列表里」：游戏会把自动启用的包一起写回 options.txt，
 * 顺序有可能被改动，必须把我们的条目重新挪到末尾，否则会静默退化成「包在、但不生效」。</p>
 *
 * <p>只改 {@code resourcePacks} 这一行的方括号内部，其余字节原样保留：options.txt 是游戏自己的文件，
 * 里面还有键位、画面、音量等玩家设置，重写整个文件等于把玩家调过的东西全丢掉。</p>
 */
public final class GameOptionsResourcePacks {

    /** 只匹配到方括号内部，避免动到行尾的换行符（CRLF 的 \r 很容易被顺带吃掉）。 */
    private static final Pattern RESOURCE_PACKS =
            Pattern.compile("(?m)^(resourcePacks:)(\\[)([^\\]]*)(\\])");

    private GameOptionsResourcePacks() {
    }

    /**
     * 确保列表中包含该条目，且它是最后一条（幂等）。
     *
     * @param optionsFile 游戏目录下的 {@code options.txt}
     * @param entry       形如 {@code file/misaka_atlas_compat} 的条目
     * @return 真表示这次真的改写了文件（含「文件原本不存在，本次新建」）
     */
    public static boolean ensureLast(Path optionsFile, String entry) throws IOException {
        if (!Files.isRegularFile(optionsFile)) {
            // 实例还没生成过 options.txt（第一次启动前就是这种状态）。这里补一份最小的：
            // 只写 resourcePacks 一行，其余键游戏会按默认值处理。不补的话第一次启动没有保护，
            // 而「第一次启动」恰恰最容易崩在贴图拼接上 —— 那一次崩了，用户只会看到一屏报错
            FileUtils.ensureParent(optionsFile);
            Files.writeString(optionsFile,
                    "resourcePacks:[\"" + entry + "\"]" + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            return true;
        }
        String text = Files.readString(optionsFile, StandardCharsets.UTF_8);
        Matcher matcher = RESOURCE_PACKS.matcher(text);
        String quoted = "\"" + entry + "\"";
        if (!matcher.find()) {
            // 极旧的 options.txt 没有这一行：补一行，让兼容包至少能生效
            String separator = text.contains("\r\n") ? "\r\n" : "\n";
            String suffix = text.endsWith("\n") ? "" : separator;
            Files.writeString(optionsFile, text + suffix + "resourcePacks:[" + quoted + "]" + separator,
                    StandardCharsets.UTF_8);
            return true;
        }

        List<String> items = splitItems(matcher.group(3));
        if (!items.isEmpty() && items.get(items.size() - 1).equals(quoted)) {
            return false;
        }
        items.removeIf(quoted::equals);
        items.add(quoted);

        String replaced = String.join(",", items);
        String updated = text.substring(0, matcher.start(3)) + replaced + text.substring(matcher.end(3));
        Files.writeString(optionsFile, updated, StandardCharsets.UTF_8);
        return true;
    }

    /** 解析方括号内部：按逗号切分，去掉空白与空项（游戏有时会写入空列表或带空格）。 */
    private static List<String> splitItems(String list) {
        List<String> items = new ArrayList<>();
        for (String item : list.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items;
    }

    /**
     * 从列表中摘掉该条目（幂等）。
     *
     * <p>用户把档位改成「关闭」、或者整合包瘦身后已经装得下时，必须把以前登记的覆盖包撤下来 ——
     * 否则「关闭」只是不再生成新包，游戏里旧的覆盖依旧在生效，玩家的画面会被一份过期的、
     * 压得更糊的贴图长期盖着。</p>
     *
     * @return 真表示这次真的改写了文件
     */
    public static boolean remove(Path optionsFile, String entry) throws IOException {
        if (!Files.isRegularFile(optionsFile)) {
            return false;
        }
        String text = Files.readString(optionsFile, StandardCharsets.UTF_8);
        Matcher matcher = RESOURCE_PACKS.matcher(text);
        if (!matcher.find()) {
            return false;
        }
        String quoted = "\"" + entry + "\"";
        List<String> items = splitItems(matcher.group(3));
        if (!items.removeIf(quoted::equals)) {
            return false;
        }
        String replaced = String.join(",", items);
        String updated = text.substring(0, matcher.start(3)) + replaced + text.substring(matcher.end(3));
        Files.writeString(optionsFile, updated, StandardCharsets.UTF_8);
        return true;
    }
}
