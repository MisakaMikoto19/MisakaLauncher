package com.misaka.launcher.core.download;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个待下载条目。
 *
 * @param target   目标文件路径
 * @param url      上游给出的官方地址（下载源会把它改写成镜像地址）
 * @param checksum 期望的校验值（十六进制）；为空表示不校验。
 *                 算法由长度自动识别：64 位为 SHA-256、40 位为 SHA-1、32 位为 MD5，
 *                 因此同一套下载链路可同时支撑 Mojang（SHA-1）与 Adoptium（SHA-256）等来源
 * @param size     期望字节数；未知传 -1
 * @param type     资源类型，供下载源判断能否映射
 * @param headers  附加请求头（例如 CurseForge 的 x-api-key）
 * @param context  改写上下文（常用键：{@code versionId}）
 * @param required 是否必需；非必需项失败不影响整体判定（例如可选的语言文件）
 */
public record DownloadItem(Path target,
                           String url,
                           String checksum,
                           long size,
                           DownloadTargetType type,
                           Map<String, String> headers,
                           Map<String, String> context,
                           boolean required) {

    public DownloadItem {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        context = context == null ? Map.of() : Map.copyOf(context);
        if (type == null) {
            type = DownloadTargetType.OTHER;
        }
    }

    public static DownloadItem of(Path target, String url, String checksum, long size, DownloadTargetType type) {
        return new DownloadItem(target, url, checksum, size, type, Map.of(), Map.of(), true);
    }

    public static DownloadItem of(Path target, String url, String checksum, long size,
                                  DownloadTargetType type, String versionId) {
        return new DownloadItem(target, url, checksum, size, type, Map.of(),
                versionId == null ? Map.of() : Map.of("versionId", versionId), true);
    }

    /** 非必需条目。 */
    public DownloadItem asOptional() {
        return new DownloadItem(target, url, checksum, size, type, headers, context, false);
    }

    public DownloadItem withHeader(String key, String value) {
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.put(key, value);
        return new DownloadItem(target, url, checksum, size, type, merged, context, required);
    }

    public DownloadItem withContext(String key, String value) {
        Map<String, String> merged = new LinkedHashMap<>(context);
        merged.put(key, value);
        return new DownloadItem(target, url, checksum, size, type, headers, merged, required);
    }

    /** 展示用名称。 */
    public String displayName() {
        return target.getFileName().toString();
    }

    /** 覆盖校验信息（补齐来源未提供的哈希或长度）。 */
    public DownloadItem withChecksum(String checksum, long size) {
        return new DownloadItem(target, url, checksum, size, type, headers, context, required);
    }
}
