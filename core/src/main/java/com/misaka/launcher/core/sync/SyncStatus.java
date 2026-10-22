package com.misaka.launcher.core.sync;

/**
 * 文件同步状态。
 * <p>取值与 FileSync 客户端 {@code FileStatus} 完全一致，便于与原工具的日志/界面相互对照。</p>
 */
public enum SyncStatus {

    /** 哈希一致，无需操作。 */
    UP_TO_DATE("Up to date", "sync.status.upToDate"),
    /** 本地存在但哈希与服务端不同。 */
    NEED_UPDATE("Need to update", "sync.status.needUpdate"),
    /** 本地缺失。 */
    NEED_DOWNLOAD("Need to download", "sync.status.needDownload"),
    /** 硬同步模式下本地多余的受管文件。 */
    NEED_DELETE("Need to delete", "sync.status.needDelete"),
    /** 本地多余但处于软同步目录，按原客户端语义保留。 */
    KEPT("Up to date", "sync.status.upToDate");

    private final String displayName;
    private final String i18nKey;

    SyncStatus(String displayName, String i18nKey) {
        this.displayName = displayName;
        this.i18nKey = i18nKey;
    }

    /** 与 Python 客户端一致的英文展示名（也用于日志对照）。 */
    public String displayName() {
        return displayName;
    }

    public String i18nKey() {
        return i18nKey;
    }

    public boolean requiresDownload() {
        return this == NEED_DOWNLOAD || this == NEED_UPDATE;
    }

    public boolean requiresDelete() {
        return this == NEED_DELETE;
    }
}
