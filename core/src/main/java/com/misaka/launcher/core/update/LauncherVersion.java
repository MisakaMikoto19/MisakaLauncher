package com.misaka.launcher.core.update;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动器版本号语义。
 *
 * <p>规则必须与服务端 ZooKeeper 的 {@code internal/version} 完全一致，否则会出现
 * 「服务端说有新版、本地按自己的规则算成没新版」这类谁也说不清的分歧：</p>
 * <ul>
 *   <li>从任意字符串里抽出第一段版本号（容忍 {@code v1.2.3}、{@code 1.2.3-beta}）；</li>
 *   <li>补齐／截断为 4 段：{@code 1.2.3} → {@code 1.2.3.0}；</li>
 *   <li>按段位做数值比较，而不是字典序：{@code 1.10.0 > 1.9.0}。</li>
 * </ul>
 *
 * <p>是否要更新<b>以服务端返回的 {@code has_update} 为准</b>；本类用于服务端没给结论时的
 * 兜底判断，以及界面展示前的格式化。</p>
 */
public final class LauncherVersion {

    /** 版本号形态：至少两段数字。与服务端正则一致。 */
    private static final Pattern VERSION = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)*)");

    /** 固定 4 段，与服务端一致。 */
    private static final int SEGMENTS = 4;

    private LauncherVersion() {
    }

    /**
     * 抽出第一段版本号。
     *
     * @return 形如 {@code 1.2.3}；抽不到时返回空串
     */
    public static String extract(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        Matcher matcher = VERSION.matcher(raw);
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * 规格化为 4 段版本号。
     *
     * @return 形如 {@code 1.2.3.0}；无法识别时返回空串（调用方据此放弃比较，而不是当成 0.0.0.0）
     */
    public static String normalize(String raw) {
        String extracted = extract(raw);
        if (extracted.isEmpty()) {
            return "";
        }
        String[] parts = extracted.split("\\.", -1);
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < SEGMENTS; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(i < parts.length && !parts[i].isBlank() ? parts[i] : "0");
        }
        return sb.toString();
    }

    /**
     * 段位数值比较。
     *
     * @return 负数表示 {@code left} 更旧，0 表示同版本
     */
    public static int compare(String left, String right) {
        String[] a = normalize(left).split("\\.", -1);
        String[] b = normalize(right).split("\\.", -1);
        for (int i = 0; i < SEGMENTS; i++) {
            long x = i < a.length ? parse(a[i]) : 0L;
            long y = i < b.length ? parse(b[i]) : 0L;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }

    /**
     * {@code candidate} 是否比 {@code current} 新。
     * <p>任一侧拿不到版本号时一律返回 {@code false}：宁可漏提示一次，也不要在版本号
     * 形如 {@code dev} 的开发构建上反复弹窗。</p>
     */
    public static boolean isNewer(String candidate, String current) {
        if (normalize(candidate).isEmpty() || normalize(current).isEmpty()) {
            return false;
        }
        return compare(candidate, current) > 0;
    }

    /** 展示用：去掉末尾多余的 {@code .0}，例如 {@code 1.2.3.0} → {@code 1.2.3}。 */
    public static String display(String raw) {
        String normalized = normalize(raw);
        if (normalized.isEmpty()) {
            return raw == null ? "" : raw.trim();
        }
        while (normalized.endsWith(".0")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        return normalized;
    }

    private static long parse(String text) {
        String digits = text == null ? "" : text.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            // 段位长到溢出，视为极大值即可，不必精确
            return Long.MAX_VALUE;
        }
    }
}
