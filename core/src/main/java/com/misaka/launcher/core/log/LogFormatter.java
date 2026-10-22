package com.misaka.launcher.core.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.LogRecord;

/**
 * 统一的日志文本格式：
 * {@code [2026-10-06 12:00:00.123] [INFO ] [logger.name] message}
 */
public final class LogFormatter {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private LogFormatter() {
    }

    /** 单行日志正文（不含换行符）。 */
    public static String formatLine(LogRecord record) {
        String level = levelName(record);
        return "[" + FORMAT.format(Instant.ofEpochMilli(record.getMillis())) + "] ["
                + padLevel(level) + "] [" + record.getLoggerName() + "] " + record.getMessage();
    }

    /** 被滚动/启动标记使用的原始行（已自带前缀，不再加时间戳）。 */
    public static String formatRawLine(String message) {
        return "------ " + message + " ------";
    }

    /** 异常堆栈文本；无异常返回 null。 */
    public static String formatThrown(LogRecord record) {
        Throwable thrown = record.getThrown();
        if (thrown == null) {
            return null;
        }
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            pw.println(thrown);
            thrown.printStackTrace(pw);
        }
        return sw.toString().stripTrailing();
    }

    /** 供 UI 列表使用的级别名（与 LogEntry 保持一致）。 */
    public static String levelName(LogRecord record) {
        int value = record.getLevel().intValue();
        if (value >= java.util.logging.Level.SEVERE.intValue()) {
            return "ERROR";
        }
        if (value >= java.util.logging.Level.WARNING.intValue()) {
            return "WARN";
        }
        if (value >= java.util.logging.Level.INFO.intValue()) {
            return "INFO";
        }
        if (value >= java.util.logging.Level.FINE.intValue()) {
            return "DEBUG";
        }
        return "TRACE";
    }

    private static String padLevel(String level) {
        return switch (level) {
            case "INFO" -> "INFO ";
            case "WARN" -> "WARN ";
            default -> level;
        };
    }
}
