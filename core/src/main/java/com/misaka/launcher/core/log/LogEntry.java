package com.misaka.launcher.core.log;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 一条日志记录（已格式化，供 UI 展示与导出）。
 *
 * @param timestamp   毫秒时间戳
 * @param level       级别名，例如 INFO / WARN / ERROR
 * @param loggerName  来源 logger
 * @param message     正文
 * @param throwable   异常堆栈文本；无异常时为 null
 */
public record LogEntry(long timestamp, String level, String loggerName, String message, String throwable) {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FULL_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    /** {@code HH:mm:ss.SSS}，用于控制台与列表展示。 */
    public String shortTime() {
        return TIME_FORMAT.format(Instant.ofEpochMilli(timestamp));
    }

    /** {@code yyyy-MM-dd HH:mm:ss.SSS}，用于导出。 */
    public String fullTime() {
        return FULL_FORMAT.format(Instant.ofEpochMilli(timestamp));
    }

    /** 单行文本形式，供复制与导出使用。 */
    public String toLine() {
        StringBuilder sb = new StringBuilder(128);
        sb.append('[').append(fullTime()).append("] [").append(level).append("] [")
                .append(loggerName).append("] ").append(message);
        if (throwable != null && !throwable.isBlank()) {
            sb.append(System.lineSeparator()).append(throwable);
        }
        return sb.toString();
    }

    /** 级别权重，用于「至少某个级别」的过滤。 */
    public int levelWeight() {
        return switch (level) {
            case "TRACE", "FINEST" -> 0;
            case "DEBUG", "FINE" -> 1;
            case "INFO" -> 2;
            case "WARN", "WARNING" -> 3;
            case "ERROR", "SEVERE" -> 4;
            default -> 2;
        };
    }
}
