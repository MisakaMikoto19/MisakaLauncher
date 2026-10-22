package com.misaka.launcher.core.update;

import com.misaka.launcher.core.util.FileUtils;

/**
 * 一次「检查更新」得到的结论。
 *
 * <p>字段命名与服务端 ZooKeeper 的 {@code /api/v1/updates/check} 返回保持一致，
 * 但只保留启动器真正需要的内容：</p>
 * <ul>
 *   <li>{@code hasUpdate} 由服务端判定（本地不重算），拿不准的服务端回什么就是什么；</li>
 *   <li>{@code downloadUrl} 已解析为绝对地址 —— ZooKeeper 在未配置 {@code base_url} 时
 *       只返回裸路径，直接用会导致「地址非法」；</li>
 *   <li>清单里可能有多个平台的包，服务端会做平台回落，所以这里保存的是<b>选中那一个文件</b>
 *       的信息（名字、大小、SHA-256、分块参数）。</li>
 * </ul>
 */
public record UpdateInfo(
        String product,
        String currentVersion,
        boolean hasUpdate,
        String latestVersion,
        String channel,
        String releasedAt,
        String notes,
        String notesUrl,
        String downloadUrl,
        String fileName,
        long fileSize,
        String sha256,
        long chunkSize,
        int chunkCount) {

    /** 没有新版时的空结论。 */
    public static UpdateInfo none(String product, String currentVersion) {
        return new UpdateInfo(product, currentVersion, false, "", "", "", "", "", "", "", 0L, "", 0L, 0);
    }

    /** 端上能不能自己装上去：必须真的拿到一个下载地址。 */
    public boolean downloadable() {
        return hasUpdate && downloadUrl != null && !downloadUrl.isBlank();
    }

    /** 最新版本的展示形式，例如 {@code 1.0.1}。 */
    public String latestLabel() {
        return LauncherVersion.display(latestVersion);
    }

    /** 当前版本的展示形式。 */
    public String currentLabel() {
        return LauncherVersion.display(currentVersion);
    }

    /** 一句话摘要，用于日志与提示。 */
    public String describe() {
        return product + " " + currentLabel() + " → " + latestLabel()
                + (channel == null || channel.isBlank() ? "" : "（" + channel + "）");
    }

    /**
     * 落盘用的文件名。
     * <p>服务端可能只给相对路径或不给名字，统一从下载地址末段推导并做安全校验 ——
     * 文件名来自网络，直接拼路径会被 {@code ../} 写到目录外。</p>
     */
    public String targetFileName() {
        String name = fileName == null ? "" : fileName.trim();
        if (name.isEmpty()) {
            name = downloadUrl == null ? "" : downloadUrl;
        }
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int query = name.indexOf('?');
        if (query >= 0) {
            name = name.substring(0, query);
        }
        if (name.isBlank() || !FileUtils.isSafeFileName(name)) {
            return "misaka-launcher-" + latestLabel() + ".update";
        }
        return name;
    }
}
