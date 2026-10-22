package com.misaka.launcher.core.java;

import com.fasterxml.jackson.databind.JsonNode;
import com.misaka.launcher.core.download.DownloadTargetType;
import com.misaka.launcher.core.meta.MetaFetcher;
import com.misaka.launcher.core.util.ArchUtils;
import com.misaka.launcher.core.util.JsonUtils;
import com.misaka.launcher.core.util.OsUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Azul Zulu Java 运行时提供方（备用发行版）。
 * <p>Azul 的 CDN（{@code cdn.azul.com}）在国内可达性通常优于 GitHub，
 * 因此作为备选很有价值。</p>
 * <p>其元数据接口的字段命名在不同版本间有过调整，这里对校验值、体积等字段做
 * 容错查找；任一必需字段缺失时抛出异常，交由上层的发行版回退逻辑处理。</p>
 */
public final class ZuluJavaProvider implements JavaProvider {

    public static final String ID = "zulu";

    private static final Logger log = LoggerFactory.getLogger(ZuluJavaProvider.class);

    private static final String API_TEMPLATE =
            "https://api.azul.com/metadata/v1/zulu/packages/"
                    + "?java_version=%d&os=%s&arch=%s&archive_type=%s&java_package_type=jdk"
                    + "&latest=true&release_status=ga&availability_types=CA&page_size=10";

    private final MetaFetcher fetcher;

    public ZuluJavaProvider(MetaFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Azul Zulu";
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public JavaPackage resolve(int majorVersion) throws IOException {
        String os = zuluOs();
        String arch = ArchUtils.zuluArch();
        String archiveType = "windows".equals(os) ? "zip" : "tar.gz";

        String url = API_TEMPLATE.formatted(majorVersion, os, arch, archiveType);
        JsonNode root = fetcher.fetchJson(url, DownloadTargetType.JAVA_RUNTIME, Map.of(), Map.of());
        if (root == null || !root.isArray() || root.isEmpty()) {
            throw new IOException("Zulu 未提供 Java " + majorVersion + " 的 " + os + "/" + arch + " 构建");
        }

        for (JsonNode entry : root) {
            String downloadUrl = firstNonBlank(entry, "download_url");
            if (downloadUrl.isBlank()) {
                continue;
            }
            String fileName = firstNonBlank(entry, "name");
            if (fileName.isBlank()) {
                fileName = downloadUrl.substring(downloadUrl.lastIndexOf('/') + 1);
            }
            String checksum = firstNonBlank(entry, "sha256_hash", "sha256", "checksum", "hash");
            long size = JsonUtils.longValue(entry, "size", -1L);
            if (size <= 0) {
                size = JsonUtils.longValue(entry, "file_size", -1L);
            }
            String versionString = firstNonBlank(entry, "distro_version", "java_version");
            if (versionString.isBlank()) {
                versionString = String.valueOf(majorVersion);
            }
            return new JavaPackage(ID, majorVersion, versionString, fileName,
                    fileName.endsWith(".tar.gz") ? "tar.gz" : "zip",
                    checksum, size, List.of(downloadUrl));
        }
        throw new IOException("Zulu 响应中没有可用的下载地址（Java " + majorVersion + "）");
    }

    /** Zulu 的 macOS 标识为 {@code macos}，其余与我们的内部标识一致。 */
    private static String zuluOs() {
        String id = OsUtils.id();
        return "mac".equals(id) ? "macos" : id;
    }

    /** 依次尝试多个候选字段名，返回首个非空值；全部缺失返回空串。 */
    private static String firstNonBlank(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            String value = JsonUtils.text(node, field, "");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /** 便于排查：列出该发行版是否可用（不抛异常）。 */
    public boolean isAvailable(int majorVersion) {
        try {
            resolve(majorVersion);
            return true;
        } catch (IOException e) {
            log.debug("Zulu 不可用于 Java {}：{}", majorVersion, e.getMessage());
            return false;
        }
    }

    /** 返回候选地址列表（当前实现只有官方 CDN）。 */
    public List<String> candidates(int majorVersion) throws IOException {
        return new ArrayList<>(resolve(majorVersion).downloadUrls());
    }
}
