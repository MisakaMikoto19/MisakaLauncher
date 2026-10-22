package com.misaka.launcher.core.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Jackson 统一入口。
 * <p>开启宽松解析：允许注释、单引号、未加引号的字段名与尾随逗号，
 * 以兼容 Mojang / Forge / 各类第三方元数据文件中不规范但常见的写法。</p>
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .disable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            // 反序列化必须容忍未知字段：配置文件里完全可能存在「本版本已移除」或
            // 「更高版本写入」的键，而 ConfigStore 把解析异常一律当成「配置损坏」——
            // 会备份并重建配置，用户的实例与账户跟着一起丢。默认的严格模式会让
            // 「删掉一个配置字段」直接变成数据事故，因此显式关掉。
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final ObjectMapper PRETTY = MAPPER.copy()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonUtils() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectNode newObject() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode newArray() {
        return MAPPER.createArrayNode();
    }

    public static JsonNode read(String json) throws IOException {
        return MAPPER.readTree(json);
    }

    public static JsonNode read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return MAPPER.readTree(in);
        }
    }

    public static <T> T read(Path file, Class<T> type) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return MAPPER.readValue(in, type);
        }
    }

    public static <T> T read(String json, Class<T> type) throws IOException {
        return MAPPER.readValue(json, type);
    }

    public static <T> T convert(JsonNode node, Class<T> type) throws IOException {
        return MAPPER.treeToValue(node, type);
    }

    public static String write(Object value) throws IOException {
        return MAPPER.writeValueAsString(value);
    }

    public static String writePretty(Object value) throws IOException {
        return PRETTY.writeValueAsString(value);
    }

    public static byte[] writePrettyBytes(Object value) throws IOException {
        return PRETTY.writeValueAsBytes(value);
    }

    // ------------------------------------------------------------------
    // 空值安全的树访问辅助方法：Mojang 元数据字段可选，缺失是常态
    // ------------------------------------------------------------------

    public static String text(JsonNode node, String field, String fallback) {
        if (node == null) {
            return fallback;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    public static long longValue(JsonNode node, String field, long fallback) {
        if (node == null) {
            return fallback;
        }
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? fallback : value.asLong();
    }

    public static int intValue(JsonNode node, String field, int fallback) {
        long v = longValue(node, field, fallback);
        return (int) v;
    }

    public static boolean boolValue(JsonNode node, String field, boolean fallback) {
        if (node == null) {
            return fallback;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asBoolean(fallback);
    }

    public static List<String> stringList(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        if (node == null) {
            return result;
        }
        JsonNode array = node.get(field);
        if (array != null && array.isArray()) {
            array.forEach(item -> result.add(item.asText()));
        }
        return result;
    }
}
