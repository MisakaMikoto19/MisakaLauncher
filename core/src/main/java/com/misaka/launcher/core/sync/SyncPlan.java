package com.misaka.launcher.core.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 同步计划（纯数据）。
 * <p>由 {@link SyncPlanner} 生成，{@link SyncEngine} 负责执行。
 * 计划与执行分离使状态判定逻辑可以被离线单测完整覆盖。</p>
 *
 * @param statuses       每个本地/远端文件的状态
 * @param toDelete       需要删除的本地文件键（先执行）
 * @param toDownload     需要下载的文件（后执行）
 * @param upToDateCount  已是最新的数量
 * @param hardPrefixes   被识别为硬同步的客户端目录前缀（诊断用）
 * @param redirected     重定向映射（诊断用）
 */
public record SyncPlan(Map<String, SyncStatus> statuses,
                       List<String> toDelete,
                       List<SyncDownload> toDownload,
                       int upToDateCount,
                       List<String> hardPrefixes,
                       Map<String, String> redirected) {

    public SyncPlan {
        statuses = Map.copyOf(new LinkedHashMap<>(statuses));
        toDelete = List.copyOf(toDelete);
        toDownload = List.copyOf(toDownload);
        hardPrefixes = List.copyOf(hardPrefixes);
        redirected = Map.copyOf(new LinkedHashMap<>(redirected));
    }

    /** 是否有任何需要执行的操作。 */
    public boolean hasChanges() {
        return !toDelete.isEmpty() || !toDownload.isEmpty();
    }

    public int totalFiles() {
        return statuses.size();
    }

    /** 需要更新的数量（哈希不同）。 */
    public int needUpdateCount() {
        return count(SyncStatus.NEED_UPDATE);
    }

    /** 需要下载的数量（本地缺失）。 */
    public int needDownloadCount() {
        return count(SyncStatus.NEED_DOWNLOAD);
    }

    public int needDeleteCount() {
        return count(SyncStatus.NEED_DELETE);
    }

    private int count(SyncStatus status) {
        int total = 0;
        for (SyncStatus value : statuses.values()) {
            if (value == status) {
                total++;
            }
        }
        return total;
    }

    /** 按状态分组的文件键，供界面展示。 */
    public List<String> keysWithStatus(SyncStatus status) {
        List<String> keys = new ArrayList<>();
        statuses.forEach((key, value) -> {
            if (value == status) {
                keys.add(key);
            }
        });
        keys.sort(String::compareTo);
        return keys;
    }
}
