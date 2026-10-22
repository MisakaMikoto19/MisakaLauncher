package com.misaka.launcher.core.pack;

import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.texture.AtlasTextureCompressor;
import com.misaka.launcher.core.util.FileUtils;
import com.misaka.launcher.core.util.ZipUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 当前实例的资源包管理（列表、启停、排序）。
 *
 * <h2>顺序的含义</h2>
 * <p>{@code options.txt} 的 {@code resourcePacks} 列表就是资源栈顺序：<b>靠后的包覆盖靠前的包</b>。
 * 界面按同一顺序展示（越靠下优先级越高），拖动排序就是直接改这个顺序。</p>
 *
 * <h2>与「AMD 贴图压缩」的关系（硬约束）</h2>
 * <p>启动器在启动前可能生成覆盖包 {@code misaka_atlas_compat}（见 {@link AtlasTextureCompressor}），
 * 它必须<b>永远排在最后</b>才能盖掉超出显卡图集上限的大贴图。因此：</p>
 * <ol>
 *   <li>它在列表里显示为「由启动器管理」且<b>不可拖动、不可停用</b>；</li>
 *   <li>任何排序/启停写回后，都把它重新追加到末尾；</li>
 *   <li>它不存在时（用户关掉了压缩）绝不凭空加回去。</li>
 * </ol>
 *
 * <p>只改写 {@code resourcePacks} 那一行的方括号内部，文件其余字节原样保留 ——
 * 里面还有键位、画面、音量等玩家设置。</p>
 */
public final class ResourcePackService {

    private static final Logger log = LoggerFactory.getLogger(ResourcePackService.class);

    /** 只匹配 resourcePacks 行的方括号内部，避免动到行尾换行。 */
    private static final Pattern RESOURCE_PACKS =
            Pattern.compile("(?m)^(resourcePacks:)(\\[)([^\\]]*)(\\])");

    /** 用户包在列表里的前缀；{@code vanilla} / {@code mod_resources} 等是游戏自己加的，不带前缀。 */
    private static final String FILE_PREFIX = "file/";

    /**
     * 一条可管理的资源包。
     *
     * @param fileName  文件名（zip）或目录名
     * @param enabled   当前是否启用
     * @param locked    是否由启动器托管（贴图兼容包）：不可停用、不可拖动
     * @param sizeBytes 文件大小；目录包为 0
     */
    public record PackEntry(String fileName, boolean enabled, boolean locked, long sizeBytes) {
    }

    public ResourcePackService() {
    }

    /**
     * 列出某个实例的资源包。
     *
     * <p>顺序：先已启用的（按 {@code options.txt} 里的顺序，即覆盖优先级由低到高），
     * 再未启用的（按名字排）。未启用的包在游戏里没有位置，放后面也符合「可用列表」的直觉。</p>
     */
    public List<PackEntry> list(GamePaths paths) {
        Path dir = paths.resourcePacksDir();
        List<PackEntry> entries = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return entries;
        }
        List<String> enabled = readResourcePackEntries(paths);
        String compat = AtlasTextureCompressor.packEntry();

        Set<String> onDisk = new HashSet<>();
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(this::isPackFile).forEach(files::add);
        } catch (IOException e) {
            log.warn("列出资源包失败：{}", e.getMessage());
            return entries;
        }
        for (Path path : files) {
            onDisk.add(path.getFileName().toString());
        }

        // 1) 已启用的：严格按 options.txt 的顺序（这就是覆盖顺序）
        for (String item : enabled) {
            if (!item.startsWith(FILE_PREFIX)) {
                continue;
            }
            String name = item.substring(FILE_PREFIX.length());
            if (!onDisk.contains(name)) {
                continue;
            }
            boolean locked = item.equals(compat);
            entries.add(new PackEntry(name, true, locked, sizeOf(dir.resolve(name))));
        }
        // 2) 未启用的：按名字排，避免每次刷新顺序漂移
        List<Path> disabled = files.stream()
                .filter(path -> entries.stream()
                        .noneMatch(entry -> entry.fileName().equals(path.getFileName().toString())))
                .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                .toList();
        for (Path path : disabled) {
            String name = path.getFileName().toString();
            entries.add(new PackEntry(name, false, false, sizeOf(path)));
        }
        return entries;
    }

    /** 启用 / 停用某个包。 */
    public void setEnabled(GamePaths paths, String fileName, boolean enabled) throws IOException {
        String entry = FILE_PREFIX + fileName;
        String compat = AtlasTextureCompressor.packEntry();
        List<String> items = readResourcePackEntries(paths);
        items.remove(entry);
        if (enabled) {
            // 新启用的包插在兼容包之前（兼容包必须最后）
            int insertAt = items.indexOf(compat);
            if (insertAt < 0) {
                items.add(entry);
            } else {
                items.add(insertAt, entry);
            }
        }
        if (items.remove(compat)) {
            items.add(compat);
        }
        writeResourcePackEntries(paths.gameDir().resolve("options.txt"), items);
        log.info("资源包 {} → {}", fileName, enabled ? "启用" : "停用");
    }

    /**
     * 按界面顺序重排已启用的包。
     *
     * <p>非用户条目（{@code vanilla}、{@code mod_resources}、{@code tacz_resources} 等由游戏/模组
     * 自己注入的包）<b>保持原有相对顺序放在最前</b>：它们在游戏里本来就排在最底层，
     * 挪到用户包后面会改变谁的贴图优先，属于我们不该插手的事。</p>
     *
     * @param enabledOrder 界面上已启用包的新顺序（文件名）
     */
    public void reorder(GamePaths paths, List<String> enabledOrder) throws IOException {
        if (enabledOrder == null) {
            return;
        }
        String compat = AtlasTextureCompressor.packEntry();
        List<String> existing = readResourcePackEntries(paths);

        List<String> rebuilt = new ArrayList<>();
        // 非用户条目原样保留在最前
        for (String item : existing) {
            if (!item.startsWith(FILE_PREFIX)) {
                rebuilt.add(item);
            }
        }
        for (String name : enabledOrder) {
            String entry = FILE_PREFIX + name;
            if (entry.equals(compat)) {
                // 兼容包不接受用户给的顺序，永远由下面统一追加到最后
                continue;
            }
            if (!rebuilt.contains(entry)) {
                rebuilt.add(entry);
            }
        }
        if (existing.contains(compat)) {
            rebuilt.remove(compat);
            rebuilt.add(compat);
        }
        writeResourcePackEntries(paths.gameDir().resolve("options.txt"), rebuilt);
        log.info("资源包顺序已更新（{} 项）", enabledOrder.size());
    }

    /** 把本地 zip（或目录）复制进 {@code resourcepacks/}。 */
    public Path install(GamePaths paths, Path source) throws IOException {
        if (source == null || !Files.exists(source)) {
            throw new IOException("文件不存在：" + source);
        }
        Path dir = paths.resourcePacksDir();
        FileUtils.ensureDir(dir);
        Path target = dir.resolve(source.getFileName().toString());
        if (Files.isDirectory(source)) {
            try (var walk = Files.walk(source)) {
                for (Path path : walk.toList()) {
                    Path relative = source.relativize(path);
                    Path destination = target.resolve(relative.toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        FileUtils.ensureParent(destination);
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } else {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /**
     * 读资源包的缩略图（{@code pack.png}）。
     *
     * <p>zip 包只读那一个条目（不解压整包）；目录包直接读文件。没有图标、或图片损坏时
     * 返回 {@code null}，由界面退回占位图。</p>
     *
     * <p>核心层刻意不返回 JavaFX 的 {@code Image}：core 不依赖 UI 框架，
     * 这里只交出原始字节。</p>
     */
    public byte[] iconBytes(GamePaths paths, String fileName) {
        Path dir = paths.resourcePacksDir();
        Path target = dir.resolve(fileName).normalize();
        if (!target.startsWith(dir.normalize()) || !Files.exists(target)) {
            return null;
        }
        try {
            if (Files.isDirectory(target)) {
                Path icon = target.resolve("pack.png");
                return Files.isRegularFile(icon) ? Files.readAllBytes(icon) : null;
            }
            return ZipUtils.readBytes(target, "pack.png");
        } catch (IOException e) {
            log.debug("读取资源包图标失败 {}：{}", fileName, e.getMessage());
            return null;
        }
    }

    /** 删除某个包（文件或目录），并把它从启用列表里摘掉。 */
    public void delete(GamePaths paths, String fileName) throws IOException {
        Path dir = paths.resourcePacksDir();
        Path target = dir.resolve(fileName).normalize();
        if (!target.startsWith(dir.normalize())) {
            throw new IOException("非法文件名：" + fileName);
        }
        if (Files.isDirectory(target)) {
            FileUtils.deleteRecursively(target);
        } else {
            Files.deleteIfExists(target);
        }
        if (readResourcePackEntries(paths).contains(FILE_PREFIX + fileName)) {
            setEnabled(paths, fileName, false);
        }
    }

    // ------------------------------------------------------------------
    // options.txt 读写
    // ------------------------------------------------------------------

    /** 读取 {@code options.txt} 里启用的资源包条目（含 {@code vanilla} 等非文件条目）。 */
    public List<String> readResourcePackEntries(GamePaths paths) {
        Path optionsFile = paths.gameDir().resolve("options.txt");
        if (!Files.isRegularFile(optionsFile)) {
            return new ArrayList<>();
        }
        try {
            String text = Files.readString(optionsFile, StandardCharsets.UTF_8);
            Matcher matcher = RESOURCE_PACKS.matcher(text);
            if (!matcher.find()) {
                return new ArrayList<>();
            }
            return splitItems(matcher.group(3));
        } catch (IOException e) {
            log.warn("读取 options.txt 失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 只替换方括号内部，文件其余部分逐字节保持原样。 */
    private void writeResourcePackEntries(Path optionsFile, List<String> items) throws IOException {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                list.append(',');
            }
            list.append('"').append(items.get(i)).append('"');
        }
        String rendered = list.toString();
        if (!Files.isRegularFile(optionsFile)) {
            FileUtils.ensureParent(optionsFile);
            Files.writeString(optionsFile,
                    "resourcePacks:[" + rendered + "]" + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            return;
        }
        String text = Files.readString(optionsFile, StandardCharsets.UTF_8);
        Matcher matcher = RESOURCE_PACKS.matcher(text);
        if (!matcher.find()) {
            String separator = text.contains("\r\n") ? "\r\n" : "\n";
            String suffix = text.endsWith("\n") ? "" : separator;
            Files.writeString(optionsFile,
                    text + suffix + "resourcePacks:[" + rendered + "]" + separator,
                    StandardCharsets.UTF_8);
            return;
        }
        String updated = text.substring(0, matcher.start(3)) + rendered + text.substring(matcher.end(3));
        Files.writeString(optionsFile, updated, StandardCharsets.UTF_8);
    }

    private static List<String> splitItems(String list) {
        List<String> items = new ArrayList<>();
        for (String part : list.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
            if (!trimmed.isBlank()) {
                items.add(trimmed);
            }
        }
        return items;
    }

    private boolean isPackFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (Files.isDirectory(path)) {
            // 目录形态的包：里面有 pack.mcmeta / pack.png 才算，避免把临时目录当包列出来
            return Files.isRegularFile(path.resolve("pack.mcmeta"))
                    || Files.isRegularFile(path.resolve("pack.png"));
        }
        return name.endsWith(".zip");
    }

    private static long sizeOf(Path path) {
        try {
            return Files.isDirectory(path) ? 0L : Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }
}
