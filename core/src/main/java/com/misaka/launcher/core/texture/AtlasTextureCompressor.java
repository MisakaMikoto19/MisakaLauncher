package com.misaka.launcher.core.texture;

import com.misaka.launcher.core.config.LauncherConfig;
import com.misaka.launcher.core.instance.GamePaths;
import com.misaka.launcher.core.launch.GpuPreference;
import com.misaka.launcher.core.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 为非 NVIDIA 显卡生成「贴图兼容包」：把塞不进图集的大贴图等比缩小，
 * 以覆盖资源包的形式交给游戏。
 *
 * <p><b>为什么是资源包而不是改 mod</b>：绝不能改 {@code mods/*.jar} ——
 * 那会破坏文件校验（同步/校验会把它当损坏），而且 mod 一升级就白改。
 * 资源包是官方支持、可随时撤销的覆盖层，且我们只写「比原图小」的那些贴图，
 * 其余资源一律不出现，不会误伤任何东西。</p>
 *
 * <p><b>压谁</b>：不再靠目录白名单猜，而是读游戏自己的图集定义
 * （见 {@link AtlasDefinitions}）：只有<b>真的会进图集</b>的贴图才算、才压。
 * 于是以后装任何按官方方式声明图集的模组都会自动纳入，不需要改启动器。</p>
 *
 * <p><b>压到多少</b>：<b>按图集分别算</b>。会崩的是「某一张图集塞不下」，
 * 不是「全包总和超了」—— 把多个图集加成一个数会让没超限的图集跟着一起被压。
 * 每个图集各自取「够用的最宽档位」（{@link GpuTextureBudget}）。</p>
 *
 * <p><b>硬性禁区</b>（跳过并记日志，宁可漏压不可压错）：</p>
 * <ul>
 *   <li>带动画的贴图：竖排帧的高必须能被帧数整除，缩放会破坏这一点；</li>
 *   <li>模型 JSON 里声明了 {@code texture_size} 的贴图：{@code uv} 按绝对像素解释；</li>
 *   <li>{@code unstitch} / {@code paletted_permutations} 的输入贴图：
 *       前者的切分坐标是绝对像素，后者的配色映射按颜色索引（插值出来的颜色不在配色表里）；</li>
 *   <li>不在任何图集里的贴图：它们不占图集空间，压了只会白白牺牲画质。</li>
 * </ul>
 */
public final class AtlasTextureCompressor {

    private static final Logger log = LoggerFactory.getLogger(AtlasTextureCompressor.class);

    /** 生成的覆盖资源包目录名（同时是 options.txt 里的引用名）。 */
    public static final String PACK_DIR_NAME = "misaka_atlas_compat";

    private static final String PACK_ENTRY = "file/" + PACK_DIR_NAME;

    /** 1.20.1 的 pack_format。 */
    private static final int PACK_FORMAT = 15;

    /** 描述用 ASCII：pack.mcmeta 会被游戏按 UTF-8 读，但没必要在这里引入编码风险。 */
    private static final String PACK_DESCRIPTION = "Misaka texture compatibility pack (auto-generated)";

    /** PNG 签名 8 + 块长度 4 + "IHDR" 4 + 宽 4 + 高 4。 */
    private static final int PNG_HEADER_BYTES = 24;

    private AtlasTextureCompressor() {
    }

    // ------------------------------------------------------------------ 结果模型

    /**
     * 单个图集的压缩方案。
     *
     * @param id            图集 id，例如 {@code minecraft:blocks}
     * @param textures      该图集计入估算的贴图数
     * @param rawPixels     压缩前的像素占用
     * @param outputPixels  按选定档位压缩后的像素占用（{@code cap == 0} 时等于 {@code rawPixels}）
     * @param cap           选定的长边上限；{@code 0} 表示这个图集装得下、不需要压
     * @param lockedTextures 该图集里因禁区而不可缩放的贴图数
     * @param estimates     档位 → 压缩后的像素占用（从宽到严，含所选档位；用于评估「再压一档能省多少」）
     */
    public record AtlasPlan(String id, int textures, long rawPixels, long outputPixels,
                            int cap, int lockedTextures, Map<Integer, Long> estimates) {

        public boolean compressed() {
            return cap > 0;
        }
    }

    /**
     * 分析结果（不写盘）。
     *
     * @param atlases        图集 id → 方案
     * @param capByTexture   需要写覆盖的资源路径 → 该贴图的长边上限
     * @param cap            本次用到的<b>最严</b>档位（供界面提示）；无需压缩时为 0
     * @param textures       计入估算的贴图总数（含重复出现在多个图集里的）
     * @param rawPixels      全部图集压缩前的像素合计
     * @param outputPixels   全部图集压缩后的像素合计
     * @param definitionFiles 读到的图集定义文件数（不含内置的原版基线）
     * @param notes          需要记录的异常/降级情况
     * @param scanFailures   打不开的贴图来源（jar／资源包目录）
     */
    public record Plan(Map<String, AtlasPlan> atlases, Map<String, Integer> capByTexture,
                       int cap, int textures, long rawPixels, long outputPixels,
                       int definitionFiles, List<String> notes, List<String> scanFailures) {

        public boolean needsCompression() {
            return !capByTexture.isEmpty();
        }

        /**
         * 这次估算有没有漏掉来源。
         *
         * <p>漏了就不能下「各图集都装得下」的结论：估算偏低会把本该压缩的包判成「装得下」，
         * 于是保护被撤掉，客户机下次启动继续崩在贴图拼接上。真出过：同步中断留下的半个 jar
         * 让整个 mods 目录的枚举提前结束，估算凭空小了一个数量级。</p>
         */
        public boolean scanComplete() {
            return scanFailures.isEmpty();
        }

        /** 逐图集一行，用于日志与实测输出。 */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            for (AtlasPlan atlas : atlases.values()) {
                if (!atlas.compressed()) {
                    continue;
                }
                sb.append(String.format(Locale.ROOT,
                        "%n  · %s：%d 张贴图，%d M px → %d M px（长边 %d，%d 张因禁区跳过）",
                        atlas.id(), atlas.textures(), atlas.rawPixels() / 1_000_000,
                        atlas.outputPixels() / 1_000_000, atlas.cap(), atlas.lockedTextures()));
            }
            return sb.toString();
        }
    }

    /**
     * 压缩结果。
     *
     * @param cap        实际执行的最严档位
     * @param scanned    处理过程中读到的贴图数
     * @param compressed 真正写出覆盖文件的贴图数
     * @param rawPixels  压缩前像素（全部图集合计）
     * @param outputPixels 压缩后像素（全部图集合计）
     * @param bytes      写出的覆盖资源包体积
     */
    public record Result(int cap, int scanned, int compressed, long rawPixels, long outputPixels,
                         long bytes, Path packRoot, Map<String, AtlasPlan> atlases) {

        public boolean changed() {
            return compressed > 0;
        }
    }

    /** options.txt 里登记的条目名。 */
    public static String packEntry() {
        return PACK_ENTRY;
    }

    /** 校验配置里的模式取值；缺失或非法一律按 {@code auto} 处理。 */
    public static String normalizeMode(String mode) {
        if (mode == null) {
            return LauncherConfig.LaunchSettings.COMPRESSION_AUTO;
        }
        String value = mode.trim().toLowerCase(Locale.ROOT);
        return LauncherConfig.LaunchSettings.COMPRESSION_ALWAYS.equals(value)
                || LauncherConfig.LaunchSettings.COMPRESSION_OFF.equals(value)
                ? value : LauncherConfig.LaunchSettings.COMPRESSION_AUTO;
    }

    /**
     * 本次启动是否需要压缩贴图。
     *
     * <p>{@code off} 永不；{@code always} 总是；{@code auto} 交给显卡判定
     * （不是 NVIDIA 才压）。显卡判定本身不够可靠，所以它只是 {@code auto} 的
     * 默认依据，最终由用户在设置里决定 —— 例如 macOS 上并没有这个问题。</p>
     */
    public static boolean shouldCompress(String mode) {
        String normalized = normalizeMode(mode);
        if (LauncherConfig.LaunchSettings.COMPRESSION_OFF.equals(normalized)) {
            return false;
        }
        if (LauncherConfig.LaunchSettings.COMPRESSION_ALWAYS.equals(normalized)) {
            return true;
        }
        return !GpuPreference.hasNvidiaAdapter();
    }

    /** 按非 NVIDIA 的图集上限分析（AMD / Intel 的常见值）。 */
    public static Plan plan(GamePaths paths) {
        return plan(paths, GpuTextureBudget.MAX_TEXTURE_SIZE_NON_NVIDIA);
    }

    /**
     * 分析：算出每个图集压到多少、哪些贴图需要写覆盖文件。不写盘，可以放心重复调用。
     *
     * @param maxTextureSize 图集边长上限（驱动报告的 {@code GL_MAX_TEXTURE_SIZE}）
     */
    public static Plan plan(GamePaths paths, int maxTextureSize) {
        Sources opened = openSources(paths);
        List<TextureSource> sources = opened.items();
        try {
            AtlasDefinitions.Resolved definitions = AtlasDefinitions.resolve(sources);
            long budget = GpuTextureBudget.budgetPixels(maxTextureSize);
            Map<String, GpuTextureBudget.TextureSize> sizes = readSizes(definitions);

            // 模型用绝对像素 UV 引用 / 带动画：这两类由内容决定，与图集定义无关
            Set<String> modelPinned = collectModelPinnedTextures(sources);
            Set<String> definitionLocked = definitions.neverScalable();

            Map<String, AtlasPlan> atlasPlans = new LinkedHashMap<>();
            Map<String, Integer> capByTexture = new LinkedHashMap<>();
            long rawTotal = 0L;
            long outputTotal = 0L;
            int textureTotal = 0;
            int strictest = 0;

            for (Map.Entry<String, Set<AtlasDefinitions.AtlasTexture>> entry
                    : definitions.atlases().entrySet()) {
                String atlasId = entry.getKey();
                List<Row> rows = new ArrayList<>();
                int locked = 0;
                for (AtlasDefinitions.AtlasTexture texture : entry.getValue()) {
                    GpuTextureBudget.TextureSize size = sizes.get(texture.assetPath());
                    if (size == null) {
                        continue;
                    }
                    boolean scalable = texture.scalable()
                            && !definitionLocked.contains(texture.assetPath())
                            && !modelPinned.contains(texture.assetPath())
                            && !isAnimated(sources, texture.assetPath());
                    if (!scalable) {
                        locked++;
                    }
                    rows.add(new Row(texture.assetPath(), size, scalable, texture.copies()));
                }

                long raw = rawPixels(rows);
                Map<Integer, Long> estimates = new LinkedHashMap<>();
                for (int candidate : GpuTextureBudget.candidateCaps()) {
                    estimates.put(candidate, estimate(rows, candidate));
                }
                int cap = 0;
                long output = raw;
                if (raw > budget && !rows.isEmpty()) {
                    cap = chooseCap(rows, budget);
                    output = estimate(rows, cap);
                    if (output > budget) {
                        log.warn("图集 {} 即使压到最严档位（{}px）仍超出预算：{} M px > {} M px —— "
                                        + "需要裁剪超大材质（如 4K 载具贴图）才能根治",
                                atlasId, cap, output / 1_000_000, budget / 1_000_000);
                    }
                    for (Row row : rows) {
                        if (row.scalable() && row.size().longestEdge() > cap) {
                            capByTexture.merge(row.assetPath(), cap, Math::min);
                        }
                    }
                    strictest = strictest == 0 ? cap : Math.min(strictest, cap);
                }
                atlasPlans.put(atlasId,
                        new AtlasPlan(atlasId, rows.size(), raw, output, cap, locked, estimates));
                rawTotal += raw;
                outputTotal += output;
                textureTotal += rows.size();
            }

            Plan plan = new Plan(atlasPlans, capByTexture, strictest, textureTotal, rawTotal,
                    outputTotal, definitions.definitionFiles(), definitions.notes(), opened.failures());
            log.info("贴图分析：{} 个图集、{} 张贴图、{} M px（预算 {} M px）；需覆盖 {} 张，来源打不开 {} 个{}",
                    atlasPlans.size(), textureTotal, rawTotal / 1_000_000, budget / 1_000_000,
                    capByTexture.size(), opened.failures().size(), plan.describe());
            return plan;
        } finally {
            closeAll(sources);
        }
    }

    /**
     * 撤掉以前生成的覆盖包：删目录 + 从 options.txt 的列表里摘下条目。
     *
     * <p>用在两个地方：用户在设置里选了「关闭」，以及本次检查发现各图集都装得下。
     * 少了这一步，「关闭」只是不再生成新包，旧覆盖会长期生效（画面被一份过期的、压得更糊的贴图盖着）。</p>
     *
     * @return 真表示确实清掉了东西
     */
    public static boolean removeGeneratedPack(GamePaths paths) {
        boolean removed = false;
        Path packRoot = paths.resourcePacksDir().resolve(PACK_DIR_NAME);
        if (Files.exists(packRoot)) {
            deleteRecursively(packRoot);
            removed = true;
            log.info("已撤下旧的贴图兼容包：{}", packRoot);
        }
        try {
            if (GameOptionsResourcePacks.remove(paths.gameDir().resolve("options.txt"), PACK_ENTRY)) {
                removed = true;
                log.info("已从 options.txt 的资源包列表中移除 {}", PACK_ENTRY);
            }
        } catch (IOException e) {
            log.warn("从 options.txt 移除 {} 失败：{}", PACK_ENTRY, e.getMessage());
        }
        return removed;
    }

    /** 按方案生成覆盖资源包（写到实例的 {@code resourcepacks/} 下）。 */
    public static Result compress(GamePaths paths, Plan plan) {
        return compress(paths, plan, paths.resourcePacksDir().resolve(PACK_DIR_NAME), null);
    }

    /**
     * 按方案生成覆盖资源包到指定目录。
     *
     * <p>包目录整体由我们生成，因此每次都先清空再写：档位或 mod 变化后旧文件会残留，
     * 继续覆盖着不该覆盖的贴图（实测踩过：256 档留下的 851 个文件混进了重算后的 1024 档结果里）。</p>
     *
     * @param progress 可选进度回调（每张纸面贴图推一行），供离线实测观察长任务
     */
    static Result compress(GamePaths paths, Plan plan, Path packRoot, Consumer<String> progress) {
        deleteRecursively(packRoot);
        int scanned = 0;
        int compressed = 0;
        Map<String, AtlasPlan> output = new LinkedHashMap<>(plan.atlases());

        Sources opened = openSources(paths);
        List<TextureSource> sources = opened.items();
        if (!opened.failures().isEmpty()) {
            log.warn("有 {} 个贴图来源打不开，其中的贴图这次压不到：{}",
                    opened.failures().size(), String.join("；", opened.failures()));
        }
        try {
            int total = plan.capByTexture().size();
            for (Map.Entry<String, Integer> entry : plan.capByTexture().entrySet()) {
                String assetPath = entry.getKey();
                int cap = entry.getValue();
                TextureSource source = ownerOf(sources, assetPath);
                if (source == null) {
                    continue;
                }
                scanned++;
                try {
                    byte[] png = source.read(assetPath, Integer.MAX_VALUE);
                    if (png == null) {
                        continue;
                    }
                    int[] size = readPngSize(png);
                    if (size == null || Math.max(size[0], size[1]) <= cap) {
                        continue;
                    }
                    Path target = packRoot.resolve(assetPath);
                    writeScaled(png, size[0], size[1], cap, target);
                    compressed++;
                    if (progress != null) {
                        progress.accept(String.format(Locale.ROOT, "  压到 %dpx：%s（%d/%d）",
                                cap, assetPath, compressed, total));
                    }
                } catch (IOException e) {
                    log.warn("压缩 {} 失败（跳过）: {}", assetPath, e.getMessage());
                }
            }
            if (compressed > 0) {
                try {
                    writePackMeta(packRoot);
                } catch (IOException e) {
                    // 没有 pack.mcmeta 游戏不会加载这个包：记下来，但别让整段启动前检查失败
                    log.warn("写入 pack.mcmeta 失败: {}", e.getMessage());
                }
            }
        } finally {
            closeAll(sources);
        }

        long writtenBytes = directorySize(packRoot);
        log.info("贴图兼容包就绪：cap={} 扫描 {} 张，压缩 {} 张，体积 {} KB，图集占用 {} M px → {} M px",
                plan.cap(), scanned, compressed, (writtenBytes + 1023) / 1024,
                plan.rawPixels() / 1_000_000, plan.outputPixels() / 1_000_000);
        return new Result(plan.cap(), scanned, compressed, plan.rawPixels(), plan.outputPixels(),
                writtenBytes, packRoot, output);
    }

    // ------------------------------------------------------------------ 估算

    /** 估算用的一行：一张贴图在某图集里的占用。 */
    private record Row(String assetPath, GpuTextureBudget.TextureSize size, boolean scalable, int copies) {
    }

    private static long rawPixels(List<Row> rows) {
        long total = 0L;
        for (Row row : rows) {
            total += row.size().pixels() * row.copies();
        }
        return total;
    }

    /** 按档位估算占用：不可缩放的贴图保持原样，缩放的按长边限制等比缩小。 */
    private static long estimate(List<Row> rows, int cap) {
        long total = 0L;
        for (Row row : rows) {
            long pixels = row.scalable()
                    ? GpuTextureBudget.scaledPixels(row.size(), cap)
                    : row.size().pixels();
            total += pixels * row.copies();
        }
        return total;
    }

    /** 取第一个「压完装得下」的档位；都装不下则返回最严档位。 */
    private static int chooseCap(List<Row> rows, long budget) {
        for (int cap : GpuTextureBudget.candidateCaps()) {
            if (estimate(rows, cap) <= budget) {
                return cap;
            }
        }
        return GpuTextureBudget.strictestCap();
    }

    /** 读一遍所有图集里出现的贴图尺寸（只读 PNG 头，不解码像素）。 */
    private static Map<String, GpuTextureBudget.TextureSize> readSizes(
            AtlasDefinitions.Resolved definitions) {
        Map<String, GpuTextureBudget.TextureSize> sizes = new LinkedHashMap<>();
        for (Map.Entry<String, TextureSource> entry : definitions.owners().entrySet()) {
            String assetPath = entry.getKey();
            if (!referenced(definitions, assetPath)) {
                continue;
            }
            try {
                int[] size = readPngSize(entry.getValue().read(assetPath, PNG_HEADER_BYTES));
                if (size != null) {
                    sizes.put(assetPath, new GpuTextureBudget.TextureSize(size[0], size[1]));
                }
            } catch (IOException e) {
                log.warn("读取 {} 的尺寸失败（跳过）：{}", assetPath, e.getMessage());
            }
        }
        return sizes;
    }

    private static boolean referenced(AtlasDefinitions.Resolved definitions, String assetPath) {
        for (Set<AtlasDefinitions.AtlasTexture> textures : definitions.atlases().values()) {
            for (AtlasDefinitions.AtlasTexture texture : textures) {
                if (texture.assetPath().equals(assetPath)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 贴图来源

    /**
     * 一次枚举的结果。
     *
     * @param items    成功打开的来源
     * @param failures 打不开的来源（名称 + 原因），用于让调用方判断估算是否可信
     */
    record Sources(List<TextureSource> items, List<String> failures) {
    }

    /**
     * 打开实例里的全部贴图来源（包内可见：离线实测需要单独枚举图集成员做覆盖率交叉验证）。
     *
     * <p><b>一个来源打不开不能拖垮整次扫描</b>：客户机上什么都可能遇到 —— 同步／下载中断留下的
     * 半个 jar、杀软正锁着的文件、权限不足的目录。以前这里是「枚举失败就跳出这个目录」，
     * 于是坏文件之后的模组一个都扫不到，估算凭空掉一个数量级，「各图集都装得下」被误判成立，
     * 保护被静默撤掉。现在逐个兜住，并把失败名单交给调用方。</p>
     */
    static Sources openSources(GamePaths paths) {
        List<TextureSource> sources = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Path packsDir = paths.resourcePacksDir();
        for (Path dir : List.of(paths.modsDir(), packsDir)) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var stream = Files.list(dir)) {
                for (Path file : stream.sorted().toList()) {
                    String name = file.getFileName().toString();
                    try {
                        if (Files.isDirectory(file)) {
                            // 自己生成的兼容包不能再当输入，否则会把已压过的图再压一遍
                            if (dir.equals(packsDir) && PACK_DIR_NAME.equals(name)) {
                                continue;
                            }
                            if (dir.equals(packsDir)) {
                                sources.add(new FolderSource(file));
                            }
                            continue;
                        }
                        String lower = name.toLowerCase(Locale.ROOT);
                        if (lower.endsWith(".jar") || lower.endsWith(".zip")) {
                            sources.add(new ArchiveSource(file));
                        }
                    } catch (IOException e) {
                        failures.add(name + "（" + e.getMessage() + "）");
                        log.warn("跳过打不开的贴图来源 {}：{}", file, e.getMessage());
                    }
                }
            } catch (IOException e) {
                failures.add(dir.getFileName() + "（" + e.getMessage() + "）");
                log.warn("枚举 {} 失败: {}", dir, e.getMessage());
            }
        }
        return new Sources(sources, failures);
    }

    private static TextureSource ownerOf(List<TextureSource> sources, String assetPath) {
        for (TextureSource source : sources) {
            if (source.has(assetPath)) {
                return source;
            }
        }
        return null;
    }

    private static void closeAll(List<TextureSource> sources) {
        for (TextureSource source : sources) {
            try {
                source.close();
            } catch (Exception e) {
                log.debug("关闭贴图来源失败: {}", e.getMessage());
            }
        }
    }

    /** mods 与资源包里的压缩包。 */
    private static final class ArchiveSource implements TextureSource {

        private final Path path;
        private final ZipFile zip;
        private final List<String> pngs;
        private final List<String> models;
        private final List<String> atlases;

        ArchiveSource(Path path) throws IOException {
            this.path = path;
            this.zip = new ZipFile(path.toFile());
            List<String> foundPngs = new ArrayList<>();
            List<String> foundModels = new ArrayList<>();
            List<String> foundAtlases = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (isPng(name)) {
                    foundPngs.add(name);
                } else if (isModelJson(name)) {
                    foundModels.add(name);
                } else if (isAtlasDefinition(name)) {
                    foundAtlases.add(name);
                }
            }
            this.pngs = List.copyOf(foundPngs);
            this.models = List.copyOf(foundModels);
            this.atlases = List.copyOf(foundAtlases);
        }

        @Override
        public String description() {
            return path.getFileName().toString();
        }

        @Override
        public List<String> pngAssetPaths() {
            return pngs;
        }

        @Override
        public List<String> modelJsonPaths() {
            return models;
        }

        @Override
        public List<String> atlasDefinitionPaths() {
            return atlases;
        }

        @Override
        public boolean has(String assetPath) {
            return zip.getEntry(assetPath) != null;
        }

        @Override
        public byte[] read(String assetPath, int maxBytes) throws IOException {
            ZipEntry entry = zip.getEntry(assetPath);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return readLimited(in, maxBytes);
            }
        }

        @Override
        public void close() {
            try {
                zip.close();
            } catch (IOException e) {
                log.debug("关闭 {} 失败: {}", path, e.getMessage());
            }
        }
    }

    /** 解压出来的资源包文件夹。 */
    private static final class FolderSource implements TextureSource {

        private final Path root;
        private final List<String> pngs;
        private final List<String> models;
        private final List<String> atlases;

        FolderSource(Path root) throws IOException {
            this.root = root;
            List<String> foundPngs = new ArrayList<>();
            List<String> foundModels = new ArrayList<>();
            List<String> foundAtlases = new ArrayList<>();
            try (var stream = Files.walk(root)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    String name = root.relativize(file).toString().replace('\\', '/');
                    if (isPng(name)) {
                        foundPngs.add(name);
                    } else if (isModelJson(name)) {
                        foundModels.add(name);
                    } else if (isAtlasDefinition(name)) {
                        foundAtlases.add(name);
                    }
                }
            }
            this.pngs = List.copyOf(foundPngs);
            this.models = List.copyOf(foundModels);
            this.atlases = List.copyOf(foundAtlases);
        }

        @Override
        public String description() {
            return root.getFileName() + "/";
        }

        @Override
        public List<String> pngAssetPaths() {
            return pngs;
        }

        @Override
        public List<String> modelJsonPaths() {
            return models;
        }

        @Override
        public List<String> atlasDefinitionPaths() {
            return atlases;
        }

        @Override
        public boolean has(String assetPath) {
            return Files.isRegularFile(root.resolve(assetPath));
        }

        @Override
        public byte[] read(String assetPath, int maxBytes) throws IOException {
            Path file = root.resolve(assetPath);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            try (InputStream in = Files.newInputStream(file)) {
                return readLimited(in, maxBytes);
            }
        }

        @Override
        public void close() {
            // 文件夹来源无需释放
        }
    }

    // ------------------------------------------------------------------ 判定与读写

    /** 所有贴图：{@code assets/<ns>/textures/**}{@code .png}。归属哪个图集由图集定义决定。 */
    private static boolean isPng(String assetPath) {
        return assetPath.startsWith("assets/") && assetPath.contains("/textures/")
                && assetPath.toLowerCase(Locale.ROOT).endsWith(".png");
    }

    private static boolean isModelJson(String assetPath) {
        return assetPath.startsWith("assets/") && assetPath.endsWith(".json")
                && assetPath.contains("/models/");
    }

    /** 图集定义：{@code assets/<ns>/atlases/<名字>.json}。 */
    private static boolean isAtlasDefinition(String assetPath) {
        return assetPath.startsWith("assets/") && assetPath.endsWith(".json")
                && assetPath.contains("/atlases/");
    }

    /**
     * 收集「被模型以绝对像素 UV 引用」的贴图。
     *
     * <p>模型里出现 {@code texture_size} 时，{@code uv} 是按该尺寸的<b>像素</b>解释的
     * （而不是 0-16 的相对值）。把这类贴图缩小，模型取到的区域就会整体错位 ——
     * 这是缩放贴图唯一的硬性禁区，宁可漏压也不能压错。</p>
     */
    private static Set<String> collectModelPinnedTextures(List<TextureSource> sources) {
        Set<String> pinned = new LinkedHashSet<>();
        Pattern reference = Pattern.compile("\"([a-z0-9_.-]+):([a-z0-9_./-]+)\"");
        for (TextureSource source : sources) {
            for (String assetPath : source.modelJsonPaths()) {
                try {
                    byte[] json = source.read(assetPath, 4 * 1024 * 1024);
                    if (json == null) {
                        continue;
                    }
                    String text = new String(json, StandardCharsets.UTF_8);
                    if (!text.contains("\"texture_size\"")) {
                        continue;
                    }
                    Matcher matcher = reference.matcher(text);
                    while (matcher.find()) {
                        pinned.add("assets/" + matcher.group(1) + "/textures/" + matcher.group(2) + ".png");
                    }
                } catch (IOException e) {
                    log.debug("读取模型 {} 失败: {}", assetPath, e.getMessage());
                }
            }
        }
        return pinned;
    }

    /** 带动画的贴图：同目录下有 {@code .png.mcmeta} 且声明了 {@code animation}。 */
    private static boolean isAnimated(List<TextureSource> sources, String assetPath) {
        String meta = assetPath + ".mcmeta";
        for (TextureSource source : sources) {
            if (!source.has(meta)) {
                continue;
            }
            try {
                byte[] content = source.read(meta, 8192);
                return content != null && new String(content, StandardCharsets.UTF_8).contains("\"animation\"");
            } catch (IOException e) {
                // 读不到就按「动画」处理：宁可漏压，也不要压坏一张动画贴图
                return true;
            }
        }
        return false;
    }

    /** 只解析 PNG 的 IHDR，拿到宽高（非 PNG 或数据不足时返回 null）。 */
    static int[] readPngSize(byte[] header) {
        if (header == null || header.length < PNG_HEADER_BYTES) {
            return null;
        }
        boolean signature = (header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G';
        if (!signature) {
            return null;
        }
        int width = readInt(header, 16);
        int height = readInt(header, 20);
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new int[] {width, height};
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static byte[] readLimited(InputStream in, int maxBytes) throws IOException {
        if (maxBytes == Integer.MAX_VALUE) {
            return in.readAllBytes();
        }
        return in.readNBytes(maxBytes);
    }

    /**
     * 等比缩小并写出 PNG，返回实际写出的宽高。
     *
     * <p>缩小超过一半时先反复折半再收尾：一步到位的双线性在大幅缩小时会丢细节，
     * 渐进折半是常见且效果明显更好的做法。</p>
     */
    private static int[] writeScaled(byte[] png, int width, int height, int cap, Path target)
            throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(png));
        if (source == null) {
            throw new IOException("无法解码 PNG");
        }
        double ratio = (double) cap / Math.max(width, height);
        int targetWidth = Math.max(1, (int) Math.round(width * ratio));
        int targetHeight = Math.max(1, (int) Math.round(height * ratio));

        BufferedImage current = source;
        while (current.getWidth() / 2 >= targetWidth && current.getHeight() / 2 >= targetHeight
                && current.getWidth() > 2 && current.getHeight() > 2) {
            current = scale(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        BufferedImage result = scale(current, targetWidth, targetHeight);

        FileUtils.ensureParent(target);
        ImageIO.write(result, "png", target.toFile());
        return new int[] {targetWidth, targetHeight};
    }

    private static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage result = new BufferedImage(Math.max(1, width), Math.max(1, height),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                    RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            graphics.drawImage(source, 0, 0, result.getWidth(), result.getHeight(), null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    /** 递归删除。包目录整体由我们生成，可以放心清掉（内容不是玩家数据）。 */
    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("清理旧的贴图兼容包失败: {}", e.getMessage());
        }
    }

    /** 目录（含子目录）内全部文件的总字节数。 */
    private static long directorySize(Path root) {
        if (!Files.isDirectory(root)) {
            return 0L;
        }
        try (var stream = Files.walk(root)) {
            long total = 0L;
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                total += Files.size(path);
            }
            return total;
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void writePackMeta(Path packRoot) throws IOException {
        FileUtils.ensureParent(packRoot.resolve("pack.mcmeta"));
        String json = "{\n  \"pack\": {\n    \"pack_format\": " + PACK_FORMAT
                + ",\n    \"description\": \"" + PACK_DESCRIPTION + "\"\n  }\n}\n";
        Files.writeString(packRoot.resolve("pack.mcmeta"), json, StandardCharsets.UTF_8);
    }
}
