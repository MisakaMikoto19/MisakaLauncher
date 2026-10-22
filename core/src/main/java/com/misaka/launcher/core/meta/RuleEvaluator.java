package com.misaka.launcher.core.meta;

import com.misaka.launcher.core.model.VersionDetail;
import com.misaka.launcher.core.util.ArchUtils;
import com.misaka.launcher.core.util.OsUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 版本 JSON 的 {@code rules} 求值器。
 * <p>语义与官方启动器一致：</p>
 * <ol>
 *   <li>规则为空 → 允许；</li>
 *   <li>否则初始为「不允许」，按顺序应用每条命中规则，最后一条命中的 {@code action} 决定结果。</li>
 * </ol>
 */
public final class RuleEvaluator {

    private RuleEvaluator() {
    }

    /**
     * 默认特性开关。
     * <p>除自定义分辨率外全部关闭 —— 绝大多数规则只关心平台，
     * 与特性相关的参数由启动参数构建阶段按实际配置决定。</p>
     */
    public static Map<String, Boolean> defaultFeatures() {
        Map<String, Boolean> features = new HashMap<>();
        features.put("is_demo_user", false);
        features.put("has_custom_resolution", false);
        features.put("has_quick_plays_support", false);
        features.put("is_quick_play_singleplayer", false);
        features.put("is_quick_play_multiplayer", false);
        features.put("is_quick_play_realms", false);
        return features;
    }

    /** 按平台与特性求值；规则为空视为允许。 */
    public static boolean isAllowed(List<VersionDetail.Rule> rules, Map<String, Boolean> features) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (VersionDetail.Rule rule : rules) {
            if (rule == null) {
                continue;
            }
            if (applies(rule, features)) {
                allowed = rule.isAllow();
            }
        }
        return allowed;
    }

    /** 单条规则是否命中。 */
    public static boolean applies(VersionDetail.Rule rule, Map<String, Boolean> features) {
        if (rule.os != null) {
            if (!matchesOs(rule.os)) {
                return false;
            }
        }
        if (rule.features != null && !rule.features.isEmpty()) {
            Map<String, Boolean> effective = features == null ? Map.of() : features;
            for (Map.Entry<String, Boolean> entry : rule.features.entrySet()) {
                boolean actual = effective.getOrDefault(entry.getKey(), Boolean.FALSE);
                if (entry.getValue() == null || actual != entry.getValue()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean matchesOs(VersionDetail.OsRule os) {
        if (os.name != null && !os.name.isBlank() && !os.name.equals(OsUtils.mojangOsName())) {
            return false;
        }
        if (os.arch != null && !os.arch.isBlank()) {
            String arch = ArchUtils.mojangArch();
            // 64 位系统上，声明 x86 的规则同样适用（JVM 与游戏均可回退到 32 位），
            // 反之不成立 —— 这与官方启动器的宽松处理保持一致
            boolean archMatch = os.arch.equals(arch)
                    || ("x86".equals(os.arch) && "x86_64".equals(arch));
            if (!archMatch) {
                return false;
            }
        }
        if (os.version != null && !os.version.isBlank()) {
            String osVersion = System.getProperty("os.version", "");
            try {
                // 官方实现使用 find 语义，模式通常写作 ^10\. 之类的部分匹配
                if (!Pattern.compile(os.version).matcher(osVersion).find()) {
                    return false;
                }
            } catch (RuntimeException e) {
                // 模式非法时视为该条件不满足，避免因元数据问题整体失败
                return false;
            }
        }
        return true;
    }
}
