package com.misaka.launcher.core.log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 游戏崩溃原因识别与解决建议。
 *
 * <p>只提示「退出码 -1」毫无价值 —— 用户既不知道发生了什么，也不知道该做什么。
 * 这里把崩溃报告 / 游戏日志的文本按已知特征归类，给出可以照做的建议。</p>
 *
 * <p>设计取舍：</p>
 * <ul>
 *   <li><b>纯文本匹配，不做解析</b>：崩溃报告格式随版本与加载器变化，
 *       按结构解析会随版本碎掉；而关键特征串（{@code OutOfMemoryError}、
 *       {@code UnsupportedClassVersionError} …）多年稳定。</li>
 *   <li><b>宁缺毋滥</b>：只列出确有把握的条目，识别不出就老实说「未能自动识别」，
 *       并引导用户去看崩溃报告 —— 编一个错误的建议比不给建议更糟。</li>
 *   <li><b>只产出 i18n 键</b>：core 层不依赖 UI，也不做翻译；文案由界面渲染。</li>
 * </ul>
 */
public final class CrashAdvisor {

    /** 一次诊断最多给出的建议条数：再多用户就不会读了。 */
    private static final int MAX_ADVICE = 4;

    /**
     * 一条建议。
     *
     * @param id        规则标识（{@code atlasFull} / {@code javaHeap} …），供界面判断可否给出动作按钮
     * @param titleKey  i18n 键：一句话结论
     * @param detailKey i18n 键：具体怎么做
     */
    public record Advice(String id, String titleKey, String detailKey) {
    }

    /**
     * 诊断结果。
     *
     * @param graphicsRelated 是否与显卡 / 显存相关（界面据此提供「改用独立显卡」按钮）。
     *                        注意 {@code atlasFull}（图集装不下）<b>不算</b>：
     *                        它与显存无关，给出独显按钮只会把用户引到错误方向。
     * @param advice          建议列表，至少一条（识别不出时为 {@code unknown}）
     */
    public record Diagnosis(boolean graphicsRelated, List<Advice> advice) {
    }

    /**
     * 一条规则：{@code patterns} 中<b>任意一个</b>出现即命中。
     *
     * @param id       规则标识
     * @param patterns 全部小写的特征串
     */
    private record Rule(String id, List<String> patterns) {
    }

    /**
     * 规则表，顺序即优先级。
     *
     * <p>特征串全部小写、按「越具体越靠前」排列。前四条是实际故障里最常出现的四类：
     * 图集装不下、Java 堆不足、模组冲突、Java 版本不符。</p>
     */
    private static final List<Rule> RULES = List.of(
            // 1) 纹理图集装不下。这一条与显存无关，必须排在 gpuMemory 之前单独成立。
            //
            //    "Unable to fit" 是 Minecraft 把方块/物品贴图拼进「一张」大图（texture atlas）
            //    时的装箱失败（Stitcher）。图集边长取自驱动报告的 GL_MAX_TEXTURE_SIZE，
            //    而它的分界是<b>厂商</b>而不是独显/核显：NVIDIA 通常 32768，AMD 与 Intel 通常
            //    16384 —— 面积整整差 4 倍。所以整合包的贴图总量一旦超过 16384² ≈ 2.68 亿像素，
            //    AMD 机器必然进不去，同一份包在 NVIDIA 机器上却能正常进。
            //
            //    真实案例（2026-10-10）：报错机器只有一块 AMD RX 7900 GRE，驱动正常、
            //    实际渲染器就是它、显存 16GB，仍然崩在这里 —— 贴图合计 3.26 亿像素，
            //    超出 AMD 侧 2.68 亿像素的图集上限。此时任何「改用独显」的建议都是错的。
            new Rule("atlasFull", List.of(
                    "unable to fit",
                    "stitcherexception",
                    "description: stitching")),

            // 2) 显存 / 驱动内存真的分配失败。与上面严格区分：这里才是「显存不够」。
            //    刻意不收 "max texture size"：崩溃报告的 "Max Texture Size: 16384" 是
            //    诊断信息（图集边长上限），凡拼接崩溃都会带上它，收了必然把 atlasFull 连坐。
            new Rule("gpuMemory", List.of(
                    "gl_out_of_memory",
                    "out of memory (direct buffer",
                    "failed to allocate",
                    "could not allocate",
                    "insufficient memory")),

            // 3) Java 堆不足。注意与上面区分：这里是 "Java heap space"，不是显存
            new Rule("javaHeap", List.of(
                    "java heap space",
                    "gc overhead limit exceeded")),

            // 4) 模组冲突 / 缺依赖
            new Rule("modConflict", List.of(
                    "modresolutionexception",
                    "missing or unsupported mandatory dependencies",
                    "incompatible mod set",
                    "duplicatemodsfoundexception",
                    "nomethodfounderror",
                    "nosuchmethoderror",
                    "noclassdeffounderror",
                    "classnotfoundexception",
                    "mixin apply failed",
                    "mixinapplyerror")),

            // 5) Java 版本不匹配（旧模组跑在新 Java 上、或反之）
            new Rule("javaVersion", List.of(
                    "unsupportedclassversionerror",
                    "class file version",
                    "has been compiled by a more recent version")),

            // 6) 显卡驱动相关（这些 DLL 名只在显卡驱动里出现，命中即高置信）
            new Rule("gpuDriver", List.of(
                    "nvoglv64.dll",
                    "nvwgf2umx.dll",
                    "atio6axx.dll",
                    "amdvlk64.dll",
                    "ig9icd64.dll",
                    "ig7icd64.dll",
                    "igdumdim64.dll",
                    "opengl32.dll")),

            // 7) 通用访问违例：多数也是驱动/overlay/Java 层面的问题，给出通用排查方向
            new Rule("accessViolation", List.of(
                    "exception_access_violation",
                    "fatal error in native code")));

    private CrashAdvisor() {
    }

    /**
     * 分析崩溃文本，给出建议。
     *
     * @param text 崩溃报告或游戏日志的片段（可以为空，空时返回 unknown）
     */
    public static Diagnosis analyze(String text) {
        String haystack = text == null ? "" : text.toLowerCase(Locale.ROOT);
        List<Advice> advice = new ArrayList<>();

        for (Rule rule : RULES) {
            if (advice.size() >= MAX_ADVICE) {
                break;
            }
            if (!matches(haystack, rule)) {
                continue;
            }
            advice.add(new Advice(rule.id(),
                    "crash.advice." + rule.id() + ".title",
                    "crash.advice." + rule.id() + ".detail"));
        }

        if (advice.isEmpty()) {
            advice.add(new Advice("unknown",
                    "crash.advice.unknown.title",
                    "crash.advice.unknown.detail"));
        }
        return new Diagnosis(isGraphicsRelated(advice.get(0)), List.copyOf(advice));
    }

    /**
     * 是否该给出「改用独立显卡」这类显卡动作。
     *
     * <p>只看<b>第一条</b>（最具体的那条）结论，而不是「任何一条沾显卡就算」：
     * 一份崩溃报告里出现多个特征串是常态，只要有一条沾显卡就放行，
     * 会出现「主因是图集装不下、却弹出独显按钮」这种把人带偏的组合
     * —— 真实案例里报错机器本来就只有独显，那颗按钮毫无意义。</p>
     */
    private static boolean isGraphicsRelated(Advice primary) {
        return "gpuMemory".equals(primary.id()) || "gpuDriver".equals(primary.id());
    }

    private static boolean matches(String haystack, Rule rule) {
        for (String pattern : rule.patterns) {
            if (haystack.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
}
