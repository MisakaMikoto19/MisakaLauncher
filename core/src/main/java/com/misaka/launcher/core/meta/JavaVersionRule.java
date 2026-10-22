package com.misaka.launcher.core.meta;

import com.misaka.launcher.core.model.VersionDetail;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft 版本 → 所需 Java 主版本判定。
 * <p>权威来源是版本 JSON 的 {@code javaVersion.majorVersion}（由 Mojang 随版本发布维护），
 * 本类的对照表仅作为<b>回退</b>使用：加载器 profile 可能缺失该字段，
 * 或在尚未下载版本 JSON 时需要提前给出估算值。</p>
 *
 * <p>回退对照表（与界面「自动」模式展示给用户的一致）：</p>
 * <ul>
 *   <li>1.15.x 及以下 → Java 8</li>
 *   <li>1.16 → Java 11</li>
 *   <li>1.17 / 1.18 ~ 1.20.4 → Java 17（1.17 官方为 16，已 EOL 故提升为 17）</li>
 *   <li>1.20.5 及以上、1.21+ → Java 21</li>
 * </ul>
 *
 * <p>注意：版本 JSON 存在时以其中 {@code javaVersion.majorVersion} 为准，
 * 该字段对 1.16 声明的是 Java 8（Mojang 官方值），与上表的 11 不同。</p>
 */
public final class JavaVersionRule {

    /** 周快照版本号，例如 {@code 24w14a}。 */
    private static final Pattern SNAPSHOT = Pattern.compile("^(\\d{2})w(\\d{2})[a-z]$");

    private JavaVersionRule() {
    }

    /**
     * 由版本描述判定，优先使用官方字段。
     *
     * @param detail 版本描述；可为 null
     * @param fallbackVersionId 用于回退判断的版本号
     */
    public static int requiredMajorVersion(VersionDetail detail, String fallbackVersionId) {
        if (detail != null && detail.javaVersion != null && detail.javaVersion.majorVersion > 0) {
            return normalize(detail.javaVersion.majorVersion);
        }
        return requiredMajorVersion(fallbackVersionId);
    }

    /**
     * 由版本号字符串估算所需 Java 主版本。
     *
     * @param mcVersion 版本号，例如 {@code 1.20.1}、{@code 24w14a}
     */
    public static int requiredMajorVersion(String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()) {
            // 无从判断时给出保守且现代的默认值，避免误用 Java 8 启动新版本
            return 17;
        }
        String value = mcVersion.trim();

        Matcher snapshot = SNAPSHOT.matcher(value);
        if (snapshot.matches()) {
            int year = Integer.parseInt(snapshot.group(1));
            if (year >= 24) {
                return 21;
            }
            if (year >= 21) {
                return 17;
            }
            if (year >= 18) {
                return 16;
            }
            return 8;
        }

        int[] parts = parseVersion(value);
        if (parts == null) {
            return 17;
        }
        int major = parts[0];
        int minor = parts.length > 1 ? parts[1] : 0;
        int patch = parts.length > 2 ? parts[2] : 0;

        if (major != 1) {
            // 理论不存在，防御性返回默认值
            return 17;
        }
        if (minor >= 21) {
            return 21;
        }
        if (minor == 20) {
            // 1.20.5 起改用 Java 21，之前仍为 Java 17
            return patch >= 5 ? 21 : 17;
        }
        if (minor >= 18) {
            return 17;
        }
        if (minor == 17) {
            // 官方为 Java 16，但 16 已 EOL：统一要求 17，避免让用户多下载一个废弃运行时
            return 17;
        }
        if (minor == 16) {
            // 1.16 → Java 11
            return 11;
        }
        // 1.15.x 及以下 → Java 8
        return 8;
    }

    /** 可手动预装的 Java 主版本（覆盖目前所有 Minecraft 版本的实际需求）。 */
    public static final int[] INSTALLABLE_MAJORS = {8, 11, 17, 21};

    /**
     * 归一化到实际可用的 LTS 版本。
     * <p>Java 16 已停止维护，且 1.17 可在 Java 17 上正常运行，
     * 因此把 16 提升到 17，避免要求用户额外下载一个已 EOL 的运行时。</p>
     */
    private static int normalize(int majorVersion) {
        return majorVersion == 16 ? 17 : majorVersion;
    }

    /** 解析 {@code 1.20.1} / {@code 1.20} 形式的版本号为数组。 */
    static int[] parseVersion(String value) {
        String cleaned = value;
        int dash = cleaned.indexOf('-');
        if (dash > 0) {
            cleaned = cleaned.substring(0, dash);
        }
        String[] segments = cleaned.split("\\.");
        int[] parts = new int[segments.length];
        for (int i = 0; i < segments.length; i++) {
            try {
                parts[i] = Integer.parseInt(segments[i].replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return parts;
    }

    /** 面向界面的描述，例如 {@code Java 17}。 */
    public static String describe(int majorVersion) {
        return "Java " + majorVersion;
    }

    /**
     * 判断候选主版本能否满足所需主版本。
     * <p>规则：</p>
     * <ul>
     *   <li>版本一致 → 满足；</li>
     *   <li>所需版本 ≥ 16 → 允许使用更高的版本（17/21 之间向前兼容性良好）；</li>
     *   <li>所需版本为 8 → 只接受 8：那个时代的模组与 Forge 大量依赖反射访问
     *       内部 API，在 Java 9+ 上会直接崩溃，不能放宽。</li>
     * </ul>
     */
    public static boolean accepts(int requiredMajor, int candidateMajor) {
        if (candidateMajor <= 0) {
            return false;
        }
        if (candidateMajor == requiredMajor) {
            return true;
        }
        return requiredMajor >= 16 && candidateMajor > requiredMajor;
    }
}
