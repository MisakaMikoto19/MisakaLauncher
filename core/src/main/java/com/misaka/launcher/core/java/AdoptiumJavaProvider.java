package com.misaka.launcher.core.java;

import com.fasterxml.jackson.databind.JsonNode;
import com.misaka.launcher.core.download.DownloadTargetType;
import com.misaka.launcher.core.meta.MetaFetcher;
import com.misaka.launcher.core.util.ArchUtils;
import com.misaka.launcher.core.util.JsonUtils;
import com.misaka.launcher.core.util.OsUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Eclipse Temurin（Adoptium）Java 运行时提供方。
 * <p>选择它作为首选的原因：接口稳定、按平台与架构返回单一归档包（zip / tar.gz）、
 * 且附带 SHA-256 校验值，安装逻辑最简单可靠。</p>
 * <p>关于国内速度：Adoptium 的正式下载地址指向 GitHub Releases，在国内往往很慢，
 * 因此这里同时给出清华大学 TUNA 镜像的等价地址（镜像按「主版本/类型/架构/系统/文件名」
 * 组织，文件名与官方完全一致），由 {@link JavaRuntimeManager} 实测延迟后择优。</p>
 */
public final class AdoptiumJavaProvider implements JavaProvider {

    public static final String ID = "adoptium";

    private static final String API_TEMPLATE =
            "https://api.adoptium.net/v3/assets/latest/%d/hotspot?architecture=%s&image_type=jdk&os=%s&vendor=eclipse";

    /** 清华 TUNA 镜像；路径结构与 Adoptium 发布目录一致。 */
    private static final String TUNA_TEMPLATE =
            "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/%d/jdk/%s/%s/%s";

    private final MetaFetcher fetcher;

    public AdoptiumJavaProvider(MetaFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Eclipse Temurin (Adoptium)";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public JavaPackage resolve(int majorVersion) throws IOException {
        String os = OsUtils.id();
        String arch = ArchUtils.adoptiumArch();
        String url = API_TEMPLATE.formatted(majorVersion, arch, os);

        JsonNode root = fetcher.fetchJson(url, DownloadTargetType.JAVA_RUNTIME, Map.of(), Map.of());
        if (root == null || !root.isArray() || root.isEmpty()) {
            throw new IOException("Adoptium 未提供 Java " + majorVersion + " 的 "
                    + os + "/" + arch + " 构建");
        }

        JsonNode first = root.get(0);
        JsonNode binary = first.get("binary");
        JsonNode pkg = binary == null ? null : binary.get("package");
        if (pkg == null) {
            throw new IOException("Adoptium 响应缺少安装包信息（Java " + majorVersion + "）");
        }

        String fileName = JsonUtils.text(pkg, "name", "");
        String officialUrl = JsonUtils.text(pkg, "link", "");
        String sha256 = JsonUtils.text(pkg, "checksum", "");
        long size = JsonUtils.longValue(pkg, "size", -1L);
        String semver = JsonUtils.text(first.get("version"), "semver", "");
        if (semver.isBlank()) {
            semver = JsonUtils.text(first, "release_name", String.valueOf(majorVersion));
        }

        List<String> urls = new ArrayList<>();
        if (!fileName.isBlank()) {
            // 镜像优先：实测不可达时由管理器自动跳过
            urls.add(TUNA_TEMPLATE.formatted(majorVersion, arch, os, fileName));
        }
        if (!officialUrl.isBlank()) {
            // 官方地址指向 GitHub Releases，国内直连很慢：展开加速镜像候选，
            // 由下载环节实测择优；拿不到哈希时不启用第三方加速
            urls.addAll(GithubMirrors.expand(officialUrl, !sha256.isBlank()));
        }
        if (urls.isEmpty()) {
            throw new IOException("Adoptium 未返回可用的下载地址（Java " + majorVersion + "）");
        }

        return new JavaPackage(ID, majorVersion, semver, fileName, archiveType(fileName, os),
                sha256, size, List.copyOf(urls));
    }

    /** 归档类型：Windows 为 zip，其余平台为 tar.gz（镜像与官方一致）。 */
    private static String archiveType(String fileName, String os) {
        if (fileName.endsWith(".tar.gz")) {
            return "tar.gz";
        }
        if (fileName.endsWith(".zip")) {
            return "zip";
        }
        return "windows".equals(os) ? "zip" : "tar.gz";
    }
}
