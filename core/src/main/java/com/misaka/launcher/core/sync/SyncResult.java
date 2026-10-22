package com.misaka.launcher.core.sync;

import java.util.List;

/**
 * 一次同步的结果。
 *
 * @param totalFiles       参与判定的文件总数
 * @param upToDate         同步后仍为「已最新」的数量
 * @param downloaded       新下载的文件数
 * @param updated          覆盖更新的文件数
 * @param deleted          实际删除的文件数
 * @param failed           失败数（下载或删除）
 * @param bytes            实际传输字节数
 * @param remainingChanges 复查后仍存在的待处理项数量；未复查时为 -1
 * @param elapsedMillis    耗时
 * @param failures         失败明细
 */
public record SyncResult(int totalFiles,
                         int upToDate,
                         int downloaded,
                         int updated,
                         int deleted,
                         int failed,
                         long bytes,
                         int remainingChanges,
                         long elapsedMillis,
                         List<Failure> failures) {

    /**
     * 失败明细。
     *
     * @param fileKey 协议键
     * @param reason  原因
     */
    public record Failure(String fileKey, String reason) {
    }

    public SyncResult {
        failures = List.copyOf(failures);
    }

    public boolean success() {
        return failed == 0;
    }

    public boolean hasChanges() {
        return downloaded > 0 || updated > 0 || deleted > 0;
    }

    /** 是否所有文件都已是最新。 */
    public boolean fullyUpToDate() {
        return remainingChanges == 0;
    }

    /** 面向界面的单行摘要（供日志与提示条使用）。 */
    public String summary() {
        return "共 " + totalFiles + " 个文件：已最新 " + upToDate
                + "，更新 " + updated + "，下载 " + downloaded + "，删除 " + deleted
                + (failed > 0 ? "，失败 " + failed : "");
    }
}
