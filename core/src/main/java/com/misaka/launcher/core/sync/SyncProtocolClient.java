package com.misaka.launcher.core.sync;

import com.misaka.launcher.core.task.ProgressListener;
import com.misaka.launcher.core.util.HttpUtils;
import com.misaka.launcher.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * FileSync 协议客户端。
 * <p>严格对应服务端 Flask 应用暴露的四个路由：</p>
 * <ul>
 *   <li>{@code GET /api/file_list} → 文件数组；</li>
 *   <li>{@code GET /api/config} → 目录/重定向/忽略清单；</li>
 *   <li>{@code GET /api/file/<path>} → 文件二进制；</li>
 *   <li>{@code GET /api/health} → 健康检查。</li>
 * </ul>
 * <p>采用 HTTP 而非 HTTPS：服务端为明文 HTTP 部署，加密会直接连不上；
 * 传输完整性由 MD5 校验保证。</p>
 */
public final class SyncProtocolClient {

    private static final Logger log = LoggerFactory.getLogger(SyncProtocolClient.class);

    private final String host;
    private final String port;
    private final Duration metaTimeout;
    private final Duration downloadTimeout;

    public SyncProtocolClient(String host, String port, int metaTimeoutSeconds, int downloadTimeoutSeconds) {
        this.host = host == null ? "" : host.trim();
        this.port = port == null ? "" : port.trim();
        this.metaTimeout = Duration.ofSeconds(Math.max(3, metaTimeoutSeconds));
        this.downloadTimeout = Duration.ofSeconds(Math.max(30, downloadTimeoutSeconds));
    }

    /** 服务根地址，例如 {@code http://127.0.0.1:10086}。 */
    public String baseUrl() {
        String normalizedHost = host;
        if (normalizedHost.startsWith("http://") || normalizedHost.startsWith("https://")) {
            // 允许用户直接粘贴完整地址
            return port.isBlank() ? normalizedHost : normalizedHost;
        }
        return "http://" + normalizedHost + (port.isBlank() ? "" : ":" + port);
    }

    /** 配置摘要，用于界面展示（不含任何敏感信息）。 */
    public String describe() {
        return baseUrl();
    }

    // ------------------------------------------------------------------
    // 接口
    // ------------------------------------------------------------------

    /** 健康检查；失败返回 false（不抛异常，便于界面做「测试连接」）。 */
    public boolean health() {
        try {
            HttpUtils.Result result = HttpUtils.send("GET", baseUrl() + "/api/health", Map.of(), null, null,
                    metaTimeout);
            return result.isSuccess();
        } catch (IOException e) {
            log.debug("同步服务器健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    /** 获取服务端文件列表。 */
    public List<ServerFileInfo> fetchFileList() throws IOException {
        HttpUtils.Result result = HttpUtils.send("GET", baseUrl() + "/api/file_list", Map.of(), null, null,
                metaTimeout);
        if (!result.isSuccess()) {
            throw new IOException("获取文件列表失败：" + result.summary());
        }
        try {
            return JsonUtils.mapper().readValue(result.body(),
                    JsonUtils.mapper().getTypeFactory().constructCollectionType(List.class, ServerFileInfo.class));
        } catch (IOException e) {
            throw new IOException("文件列表不是合法 JSON：" + e.getMessage(), e);
        }
    }

    /** 获取服务端同步配置。 */
    public ServerSyncConfig fetchConfig() throws IOException {
        HttpUtils.Result result = HttpUtils.send("GET", baseUrl() + "/api/config", Map.of(), null, null,
                metaTimeout);
        if (!result.isSuccess()) {
            throw new IOException("获取服务器配置失败：" + result.summary());
        }
        try {
            return JsonUtils.read(result.body(), ServerSyncConfig.class);
        } catch (IOException e) {
            throw new IOException("服务器配置不是合法 JSON：" + e.getMessage(), e);
        }
    }

    /**
     * 下载单个文件。
     * <p>校验由 {@link HttpUtils#download} 完成：算法按期望值长度自动识别（本协议为 MD5），
     * 校验失败会删除临时文件并抛错。</p>
     *
     * @return 是否成功
     */
    public boolean download(String serverPath, Path target, String expectedMd5, long size,
                           ProgressListener listener) {
        String url = baseUrl() + "/api/file/" + encodePath(serverPath);
        try {
            HttpUtils.download(URI.create(url), target, listener, expectedMd5, size,
                    Map.of(), downloadTimeout, null);
            return true;
        } catch (IOException e) {
            log.debug("下载失败 {}：{}", serverPath, e.getMessage());
            return false;
        }
    }

    /** 下载地址（供日志与排错）。 */
    public String fileUrl(String serverPath) {
        return baseUrl() + "/api/file/" + encodePath(serverPath);
    }

    /**
     * 对路径逐段进行百分号编码。
     * <p>必须做：模组文件名常含空格与中文，未编码会导致 URI 非法或服务端路由不匹配。</p>
     */
    static String encodePath(String serverPath) {
        String cleaned = serverPath == null ? "" : serverPath.replace('\\', '/');
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        StringBuilder sb = new StringBuilder(cleaned.length() + 16);
        String[] segments = cleaned.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }
}
