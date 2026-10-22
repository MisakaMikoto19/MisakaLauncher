package com.misaka.launcher.core.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * 国际化。
 *
 * <p>内置语言包在 {@code core/src/main/resources/i18n/messages_<tag>.properties}（UTF-8）；
 * 此外还会扫描数据目录下的 {@code i18n/} 目录，同名标签的文件会<b>覆盖</b>内置文案，
 * 不同名标签的文件会作为<b>新语言</b>出现在设置与首页的语言切换里 ——
 * 想加一门语言或改几句措辞，不必重新构建启动器。</p>
 *
 * <p>键回落顺序：当前语言 → 英语 → 简体中文 → 键名本身。任何一层缺失都不会抛异常，
 * 所以外部翻译文件<b>可以只覆盖一部分键</b>，没翻的自动落到英语。</p>
 */
public final class I18n {

    private static final Logger log = LoggerFactory.getLogger(I18n.class);

    public static final String ZH = "zh_CN";
    public static final String EN = "en_US";
    public static final String FR = "fr_FR";

    /** 内置语言，顺序即界面展示顺序。 */
    private static final List<String> BUILT_IN = List.of(ZH, EN, FR);

    /** 回落链：当前 → 英语 → 中文。 */
    private static final List<String> FALLBACK_CHAIN = List.of(EN, ZH);

    private static final String RESOURCE_PREFIX = "/i18n/messages_";
    private static final String RESOURCE_SUFFIX = ".properties";
    private static final String OVERLAY_PREFIX = "messages_";

    /** 语言标签 → 文案表。用 {@link Properties} 而不是 {@link java.util.ResourceBundle}：
     *  后者的父级链依赖默认 Locale，行为会随操作系统变化，难以解释「为什么这句是英文」。 */
    private static final Map<String, Properties> BUNDLES = new ConcurrentHashMap<>();

    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    /** MessageFormat 的数字占位符，例如 {@code {0}}、{@code {1,number}}。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+[^}]*}");

    /** 已就「占位符没填」告警过的键：同一键只报一次，避免刷屏把日志撑爆。 */
    private static final Set<String> UNFILLED_WARNED = ConcurrentHashMap.newKeySet();

    private static volatile List<String> available = BUILT_IN;
    private static volatile String current = ZH;
    private static volatile Path overlayDir;

    private I18n() {
    }

    static {
        reload(null);
    }

    /**
     * 指定外部翻译目录并重新加载（启动时调用一次）。
     *
     * @param dir 形如 {@code <数据目录>/i18n}；为 null 或不存在时只用内置语言
     */
    public static synchronized void init(Path dir) {
        reload(dir);
    }

    private static synchronized void reload(Path dir) {
        overlayDir = dir;
        BUNDLES.clear();
        for (String tag : BUILT_IN) {
            loadBundled(tag);
        }
        List<String> tags = new ArrayList<>(BUILT_IN);
        if (dir != null && Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                    String name = file.getFileName().toString();
                    if (!name.startsWith(OVERLAY_PREFIX) || !name.endsWith(RESOURCE_SUFFIX)) {
                        continue;
                    }
                    String tag = normalize(name.substring(OVERLAY_PREFIX.length(),
                            name.length() - RESOURCE_SUFFIX.length()));
                    if (tag.isBlank()) {
                        continue;
                    }
                    if (!tags.contains(tag)) {
                        tags.add(tag);
                    }
                    loadOverlay(file, tag);
                }
            } catch (IOException e) {
                log.warn("扫描外部翻译目录失败 {}：{}", dir, e.getMessage());
            }
        }
        List<String> ordered = new ArrayList<>(BUILT_IN);
        tags.stream().filter(tag -> !ordered.contains(tag)).sorted().forEach(ordered::add);
        available = List.copyOf(ordered);
    }

    private static void loadBundled(String tag) {
        try (InputStream in = I18n.class.getResourceAsStream(RESOURCE_PREFIX + tag + RESOURCE_SUFFIX)) {
            if (in == null) {
                log.warn("缺少内置语言资源：{}", tag);
                return;
            }
            Properties properties = new Properties();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            BUNDLES.put(tag, properties);
        } catch (IOException e) {
            log.warn("读取内置语言资源失败 {}：{}", tag, e.getMessage());
        }
    }

    private static void loadOverlay(Path file, String tag) {
        Properties merged = new Properties();
        Properties base = BUNDLES.get(tag);
        if (base != null) {
            merged.putAll(base);
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Properties extra = new Properties();
            extra.load(reader);
            merged.putAll(extra);
        } catch (IOException e) {
            log.warn("读取外部翻译失败 {}：{}", file.getFileName(), e.getMessage());
            return;
        }
        BUNDLES.put(tag, merged);
        log.info("已加载外部翻译 {}（{} 条）", file.getFileName(), merged.size());
    }

    /** 把 {@code zh}、{@code zh-CN}、{@code ZH_cn} 这类写法归一成内置标签。 */
    public static String normalize(String tag) {
        if (tag == null || tag.isBlank()) {
            return "";
        }
        String value = tag.trim().replace('-', '_');
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("zh") || lower.startsWith("zh_")) {
            return ZH;
        }
        if (lower.equals("en") || lower.startsWith("en_")) {
            return EN;
        }
        if (lower.equals("fr") || lower.startsWith("fr_")) {
            return FR;
        }
        return value;
    }

    /** 当前语言标签，例如 {@code zh_CN}。 */
    public static String languageTag() {
        return current;
    }

    /** 当前语言。 */
    public static Locale locale() {
        return Locale.forLanguageTag(current.replace('_', '-'));
    }

    /** 可选语言（内置在前，外部追加在后）。 */
    public static List<String> availableLanguages() {
        return available;
    }

    /**
     * 切换语言并通知监听者。
     *
     * @param tag 形如 {@code zh_CN}、{@code en}、{@code fr_FR}；为空或未知时回落到中文
     */
    public static void setLanguage(String tag) {
        String target = normalize(tag);
        if (target.isBlank() || !available.contains(target)) {
            target = ZH;
        }
        if (target.equals(current)) {
            return;
        }
        current = target;
        log.info("界面语言切换为 {}", target);
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.warn("语言切换监听器执行失败: {}", e.getMessage());
            }
        }
    }

    /** 首次启动时按系统语言猜一个（识别不了就给英语，比给中文更通用）。 */
    public static String detectSystemLanguage() {
        for (String tag : available) {
            Locale candidate = Locale.forLanguageTag(tag.replace('_', '-'));
            if (Locale.getDefault().getLanguage().equals(candidate.getLanguage())) {
                return tag;
            }
        }
        return available.contains(EN) ? EN : ZH;
    }

    /** 注册语言切换监听（UI 用于刷新文案）。 */
    public static void addListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /**
     * 取文本并按 {@link MessageFormat} 格式化。
     *
     * @param key  资源键
     * @param args 格式化参数，可为空
     */
    public static String t(String key, Object... args) {
        String pattern = lookup(key);
        if (pattern == null) {
            pattern = key;
        }
        if (args == null || args.length == 0) {
            warnUnfilledPlaceholder(key, pattern);
            return pattern;
        }
        try {
            return MessageFormat.format(pattern, args);
        } catch (IllegalArgumentException e) {
            return pattern;
        }
    }

    /** 当前语言 → 英语 → 中文，逐层找。 */
    private static String lookup(String key) {
        String value = valueOf(current, key);
        if (value != null) {
            return value;
        }
        for (String tag : FALLBACK_CHAIN) {
            if (tag.equals(current)) {
                continue;
            }
            value = valueOf(tag, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String valueOf(String tag, String key) {
        Properties properties = BUNDLES.get(tag);
        return properties == null ? null : properties.getProperty(key);
    }

    /**
     * 无参调用却拿到带占位符的文案时告警。
     *
     * <p>这类键分两拨：文案本来就该带参（调用处漏传，界面显示「正在安装 {0}」这种字面占位符），
     * 或者文案里的占位符纯属多余（该键被当成静态标签用，例如 {@code Ui.settingRow} 的标题）。
     * 两种情况都只在日志里报一次，不抛异常 —— 文案出问题不应该把启动器拦下来。</p>
     */
    private static void warnUnfilledPlaceholder(String key, String pattern) {
        // indexOf 先做廉价短路：绝大多数文案没有花括号，不必进正则
        if (pattern == null || pattern.indexOf('{') < 0 || !PLACEHOLDER.matcher(pattern).find()) {
            return;
        }
        if (UNFILLED_WARNED.add(key)) {
            log.warn("文案 {} 含占位符但调用时没传参数，界面会原样显示「{}」："
                    + "要么改成 I18n.t(key, ...) 传参，要么从资源文件里删掉占位符", key, pattern);
        }
    }

    /** 取文本，缺失时使用给定兜底值（用于由外部数据驱动的可选项）。 */
    public static String tOr(String key, String fallback, Object... args) {
        String value = t(key, args);
        return value.equals(key) ? fallback : value;
    }

    /**
     * 面向界面的语言显示名。
     *
     * <p>优先用语言包里的 {@code language.name}（这样外部翻译可以自己决定写法），
     * 没有则退回 JDK 的本地化名称。</p>
     */
    public static String displayName(String tag) {
        String normalized = normalize(tag);
        String fromBundle = valueOf(normalized, "language.name");
        if (fromBundle != null && !fromBundle.isBlank()) {
            return fromBundle;
        }
        Locale target = Locale.forLanguageTag(normalized.replace('_', '-'));
        String name = target.getDisplayLanguage(target);
        return name == null || name.isBlank() ? normalized : name;
    }

    /**
     * 标题栏上的极简标识：{@code 中} / {@code EN} / {@code FR}。
     *
     * <p>用两个字符而不是国旗：国旗与语言不是一一对应（英语/法语都不是单一国家的语言），
     * 而且小尺寸下国旗辨识度远不如文字。</p>
     */
    public static String shortLabel(String tag) {
        String normalized = normalize(tag);
        return switch (normalized) {
            case ZH -> "中";
            case EN -> "EN";
            case FR -> "FR";
            default -> normalized.length() >= 2
                    ? normalized.substring(0, 2).toUpperCase(Locale.ROOT)
                    : normalized.toUpperCase(Locale.ROOT);
        };
    }

    /** 兼容旧调用：按 {@link Locale} 取显示名。 */
    public static String displayName(Locale target) {
        return target == null ? displayName(current) : displayName(target.toString());
    }
}
