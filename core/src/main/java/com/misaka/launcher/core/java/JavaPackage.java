package com.misaka.launcher.core.java;

import java.util.List;

/**
 * 一个可下载的 Java 运行时安装包。
 *
 * @param vendor        发行版标识，例如 {@code temurin}、{@code zulu}
 * @param majorVersion  主版本，例如 17
 * @param versionString 完整版本号，例如 {@code 17.0.20.1+1}
 * @param fileName      归档文件名，镜像改写依赖它保持文件名一致
 * @param archiveType   归档类型：{@code zip} 或 {@code tar.gz}
 * @param sha256        期望的 SHA-256；为空表示不校验
 * @param size          期望字节数；未知为 -1
 * @param downloadUrls  有序候选地址（镜像优先，官方兜底）
 */
public record JavaPackage(String vendor,
                          int majorVersion,
                          String versionString,
                          String fileName,
                          String archiveType,
                          String sha256,
                          long size,
                          List<String> downloadUrls) {

    public boolean isZip() {
        return "zip".equalsIgnoreCase(archiveType);
    }

    public boolean isTarGz() {
        return "tar.gz".equalsIgnoreCase(archiveType) || "tgz".equalsIgnoreCase(archiveType);
    }

    /** 首个候选地址（官方地址）。 */
    public String primaryUrl() {
        return downloadUrls.isEmpty() ? "" : downloadUrls.get(downloadUrls.size() - 1);
    }
}
