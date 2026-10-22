package com.misaka.launcher.core.i18n;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「文案里带占位符，调用时却一个参数都没传」的自检。
 *
 * <p>这类问题不会报错，只会把 {@code {0}} 原样画到界面上。真实踩过三处：
 * 设置页「关于」的版本行（{@code Ui.settingRow} 按静态标签取文案，资源里却写着
 * {@code 版本 {0}}）、以及「安装游戏 / 安装模组」的任务标题（{@code TaskCenter} 用
 * {@code I18n.t(titleKey)} 取标题，资源里写着 {@code 正在安装 {0}}）。</p>
 *
 * <p>因此这里锁两件事：无参拿到带占位符的文案必须在日志里点名；被当作静态标签使用的键
 * 不允许再出现占位符（两个语言都要）。</p>
 */
class I18nPlaceholderTest {

    /** 资源里确实带 {@code {0}} 的键，用来模拟「漏传参数」的调用。 */
    private static final String PLACEHOLDER_KEY = "install.failed";

    /** 作为静态标签 / 任务标题取用、因此不能带占位符的键。 */
    private static final List<String> STATIC_KEYS = List.of(
            "settings.about.version", "install.progress", "download.installing");

    @Test
    void placeholderWithoutArgsShouldWarnOnce() {
        List<LogRecord> records = new ArrayList<>();
        Handler handler = captureInto(records);
        Logger jul = Logger.getLogger(I18n.class.getName());
        jul.addHandler(handler);
        try {
            String text = I18n.t(PLACEHOLDER_KEY);
            assertTrue(text.contains("{0}"), "没传参时占位符会原样输出：" + text);

            // 去重是刻意的：同一条文案可能每次刷新界面都取一次，不能把日志刷爆
            I18n.t(PLACEHOLDER_KEY);

            assertEquals(1, records.size(), "同一个键只应告警一次：" + describe(records));
            LogRecord record = records.get(0);
            assertTrue(record.getLevel().intValue() >= Level.WARNING.intValue(),
                    "应该是 WARNING 级别，否则默认日志级别下看不到：" + record.getLevel());
            assertTrue(recordText(record).contains(PLACEHOLDER_KEY),
                    "日志里要点名是哪个键漏了参数：" + recordText(record));
        } finally {
            jul.removeHandler(handler);
        }
    }

    @Test
    void formattedCallShouldReplaceWithoutWarning() {
        List<LogRecord> records = new ArrayList<>();
        Handler handler = captureInto(records);
        Logger jul = Logger.getLogger(I18n.class.getName());
        jul.addHandler(handler);
        try {
            String text = I18n.t(PLACEHOLDER_KEY, "网络断了");
            assertFalse(text.contains("{0}"), "传了参就该替换掉占位符：" + text);
            assertTrue(text.contains("网络断了"), "应带上调用方给的参数：" + text);
            assertTrue(records.isEmpty(), "正常传参的调用不该产生告警：" + describe(records));
        } finally {
            jul.removeHandler(handler);
        }
    }

    @Test
    void staticLabelKeysShouldNotContainPlaceholders() throws Exception {
        for (String bundle : List.of("messages_zh_CN.properties", "messages_en_US.properties")) {
            Properties props = new Properties();
            try (InputStream in = I18n.class.getResourceAsStream("/i18n/" + bundle)) {
                assertNotNull(in, "找不到语言资源 " + bundle);
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (String key : STATIC_KEYS) {
                String value = props.getProperty(key);
                assertNotNull(value, bundle + " 缺少键 " + key);
                // 只认 {数字} 形式：文案里出现别的花括号（例如代码示例）不算问题
                assertFalse(value.matches(".*\\{\\d+[^}]*}.*"),
                        bundle + " 的 " + key + " 是被无参取用的静态文案，不能带占位符：" + value);
            }
        }
    }

    /** 挂一个只收集记录的 handler：测试里断言日志用，不依赖具体日志实现。 */
    private static Handler captureInto(List<LogRecord> records) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    /**
     * 记录的可搜索文本。
     * <p>SLF4J 到 JUL 的桥接可能把 {@code {}} 占位符与参数分开交给 LogRecord，也可能自己
     * 先拼好；两种都兼容：模板与参数拼起来一起找。</p>
     */
    private static String recordText(LogRecord record) {
        Object[] params = record.getParameters();
        return record.getMessage() + " " + Arrays.toString(params);
    }

    private static String describe(List<LogRecord> records) {
        List<String> texts = new ArrayList<>();
        for (LogRecord record : records) {
            texts.add(record.getLevel() + " " + recordText(record));
        }
        return texts.toString();
    }
}
