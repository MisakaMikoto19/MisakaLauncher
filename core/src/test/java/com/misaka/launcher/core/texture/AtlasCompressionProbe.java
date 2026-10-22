package com.misaka.launcher.core.texture;

import com.misaka.launcher.core.instance.GamePaths;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 真实整合包的<b>离线实测</b>：对着一台机器上真实存在的实例跑一遍分析与压缩，
 * 把每个图集的占用、档位、写出体积打印出来。
 *
 * <p>默认跳过（普通构建不该依赖开发者机器上的路径）。要跑的话指定实例目录：</p>
 * <pre>
 *   $env:MISAKA_PROBE_GAME_DIR = "D:/Minecraft/.minecraft/instances/1.20.1-Forge"
 *   ./gradlew :core:test --tests "*AtlasCompressionProbe*" -i
 * </pre>
 *
 * <p>产物写到 {@code core/build/atlas-probe/}（不动实例本身的 resourcepacks），
 * 便于人工检查压出来的图长什么样。</p>
 *
 * <p>实测注意：这里固定按非 NVIDIA 的图集上限（16384）算 —— 正是客户机上会崩的那档。</p>
 */
class AtlasCompressionProbe {

    @Test
    void measureAtlasCompressionOnRealModpack() throws Exception {
        String gameDir = System.getenv("MISAKA_PROBE_GAME_DIR");
        Assumptions.assumeTrue(gameDir != null && !gameDir.isBlank(),
                "未设置 MISAKA_PROBE_GAME_DIR，跳过实测");

        GamePaths paths = new GamePaths(Path.of(gameDir));
        Assumptions.assumeTrue(Files.isDirectory(paths.modsDir()), "实例里没有 mods 目录：" + paths.modsDir());

        int max = GpuTextureBudget.MAX_TEXTURE_SIZE_NON_NVIDIA;
        long budget = GpuTextureBudget.budgetPixels(max);

        long started = System.currentTimeMillis();
        AtlasTextureCompressor.Plan plan = AtlasTextureCompressor.plan(paths, max);
        long analyzed = System.currentTimeMillis();

        Path outDir = Path.of("build", "atlas-probe").toAbsolutePath();
        Files.createDirectories(outDir);
        StringBuilder report = new StringBuilder();
        report.append(String.format(Locale.ROOT, "%n===== 贴图图集实测（%s）=====%n", gameDir));
        report.append(String.format(Locale.ROOT, "图集定义文件：%d 个%n", plan.definitionFiles()));
        report.append(String.format(Locale.ROOT,
                "图集上限：%d，物理面积 %.2f 亿像素，可装箱量 %.2f 亿像素，预算 %.2f 亿像素（预留 30%% 给后续模组）%n",
                max, (double) max * max / 100_000_000.0,
                GpuTextureBudget.usablePixels(max) / 100_000_000.0, budget / 100_000_000.0));
        report.append(String.format(Locale.ROOT, "计入估算的贴图：%d 张（含重复出现在多图集的），合计 %.2f 亿像素%n",
                plan.textures(), plan.rawPixels() / 100_000_000.0));
        report.append(String.format(Locale.ROOT, "%-28s %8s %14s %14s %8s%n",
                "图集", "贴图数", "压缩前(亿)", "压缩后(亿)", "档位"));
        // 档位阶梯：一眼看出「再压一档能省多少」，用于决定预留多少余量
        AtlasTextureCompressor.AtlasPlan biggest = plan.atlases().values().stream()
                .max(java.util.Comparator.comparingLong(AtlasTextureCompressor.AtlasPlan::rawPixels))
                .orElse(null);
        if (biggest != null && !biggest.estimates().isEmpty()) {
            double physical = (double) max * max;
            double usable = GpuTextureBudget.usablePixels(max);
            report.append(String.format(Locale.ROOT,
                    "%n【%s 的档位阶梯】物理面积 %.3f 亿 px，可装箱量（70%% 装箱率）%.3f 亿 px，"
                            + "本次预算 %.3f 亿 px（预留 30%%）%n",
                    biggest.id(), physical / 100_000_000.0, usable / 100_000_000.0,
                    budget / 100_000_000.0));
            report.append(String.format(Locale.ROOT, "%-8s %14s %12s %12s %16s%n",
                    "档位", "占用(亿 px)", "占物理", "预计边长", "还能再加模组"));
            for (var entry : biggest.estimates().entrySet()) {
                double pixels = entry.getValue();
                double edge = Math.sqrt(pixels / 0.7);
                long atlasEdge = 1L << (32 - Integer.numberOfLeadingZeros(Math.max(1, (int) Math.ceil(edge))));
                report.append(String.format(Locale.ROOT,
                        "%-8s %14.3f %11.1f%% %12d %13.3f 亿 px%n",
                        entry.getKey() + "px", pixels / 100_000_000.0,
                        100.0 * pixels / physical, atlasEdge,
                        Math.max(0, (usable - pixels)) / 100_000_000.0));
            }
        }
        plan.atlases().values().stream()
                .sorted((a, b) -> Long.compare(b.rawPixels(), a.rawPixels()))
                .forEach(atlas -> report.append(String.format(Locale.ROOT,
                        "%-28s %8d %14.3f %14.3f %8s%n",
                        atlas.id(), atlas.textures(),
                        atlas.rawPixels() / 100_000_000.0,
                        atlas.outputPixels() / 100_000_000.0,
                        atlas.compressed() ? atlas.cap() + "px" : "装得下")));
        report.append(String.format(Locale.ROOT,
                "需要写覆盖的贴图：%d 张，整体占用 %.3f 亿 → %.3f 亿像素%n",
                plan.capByTexture().size(), plan.rawPixels() / 100_000_000.0,
                plan.outputPixels() / 100_000_000.0));
        report.append(String.format(Locale.ROOT, "分析耗时：%.1f s%n", (analyzed - started) / 1000.0));
        for (String note : plan.notes()) {
            report.append("提示：").append(note).append(System.lineSeparator());
        }
        System.out.println(report);
        dumpMembership(paths, outDir);

        if (!plan.needsCompression()) {
            System.out.println("本实例各图集都没超预算，无需压缩 —— 实测结束");
            write(outDir.resolve("report.txt"), report.toString() + "本实例各图集都没超预算，无需压缩\n");
            return;
        }

        Path packRoot = outDir.resolve(AtlasTextureCompressor.PACK_DIR_NAME);
        StringBuilder progress = new StringBuilder();
        AtlasTextureCompressor.Result result = AtlasTextureCompressor.compress(
                paths, plan, packRoot, line -> {
                    progress.append(line).append(System.lineSeparator());
                    System.out.println("[压缩] " + line);
                });
        long finished = System.currentTimeMillis();

        String summary = String.format(Locale.ROOT, """
                ===== 压缩结果 =====
                档位：%dpx（最严的一档；不同图集各自取档）
                实际写出：%d 张（扫描 %d 张）
                图集占用：%.3f 亿 → %.3f 亿像素
                覆盖包体积：%.2f MB
                压缩耗时：%.1f s
                输出目录：%s
                """, result.cap(), result.compressed(), result.scanned(),
                result.rawPixels() / 100_000_000.0, result.outputPixels() / 100_000_000.0,
                result.bytes() / 1024.0 / 1024.0, (finished - analyzed) / 1000.0, packRoot);
        System.out.println(summary);
        write(outDir.resolve("report.txt"), report.toString() + progress + summary);
    }

    /**
     * 把「算出来的图集成员」与「实例里全部贴图」落盘，供与真实崩溃报告交叉验证覆盖率。
     *
     * <p>崩溃报告里带着游戏当时<b>真实拼进图集</b>的 sprite 清单（本例 10,709 条），
     * 把两边都换成贴图路径做集合运算，就能量化「通用方法会不会漏」—— 这是比自说自话更硬的证据。</p>
     */
    private static void dumpMembership(GamePaths paths, Path outDir) throws Exception {
        java.util.List<TextureSource> sources = AtlasTextureCompressor.openSources(paths).items();
        try {
            AtlasDefinitions.Resolved definitions = AtlasDefinitions.resolve(sources);
            Path atlasDir = java.nio.file.Files.createDirectories(outDir.resolve("atlas"));
            for (var entry : definitions.atlases().entrySet()) {
                StringBuilder lines = new StringBuilder();
                for (AtlasDefinitions.AtlasTexture texture : entry.getValue()) {
                    lines.append(spriteIdOf(texture.assetPath())).append('\t')
                            .append(texture.copies()).append(System.lineSeparator());
                }
                java.nio.file.Files.writeString(
                        atlasDir.resolve(entry.getKey().replace(':', '_') + ".sprite.tsv"), lines);
            }
            StringBuilder all = new StringBuilder();
            for (String asset : definitions.owners().keySet()) {
                all.append(spriteIdOf(asset)).append(System.lineSeparator());
            }
            java.nio.file.Files.writeString(outDir.resolve("all-instance-sprites.txt"), all);
            System.out.println("图集成员清单已写入：" + atlasDir);
        } finally {
            for (TextureSource source : sources) {
                source.close();
            }
        }
    }

    /** {@code assets/mtr/textures/vehicle/a320.png} → {@code mtr:vehicle/a320}。 */
    private static String spriteIdOf(String assetPath) {
        int marker = assetPath.indexOf("/textures/");
        if (marker < 0) {
            return assetPath;
        }
        String namespace = assetPath.substring("assets/".length(), marker);
        String path = assetPath.substring(marker + "/textures/".length());
        return namespace + ":" + path.substring(0, path.length() - ".png".length());
    }

    /** 报告同时落盘：Gradle 默认不转发测试的标准输出，落盘才拿得到完整结果。 */
    private static void write(Path file, String content) throws Exception {
        Files.writeString(file, content, java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("报告已写入：" + file);
    }
}
