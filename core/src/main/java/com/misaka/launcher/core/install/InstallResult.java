package com.misaka.launcher.core.install;

import com.misaka.launcher.core.download.DownloadReport;

import java.nio.file.Path;
import java.util.List;

/**
 * 安装结果。
 *
 * @param versionId      版本 id
 * @param downloadReport 下载汇总
 * @param extractedNatives 解压出的原生库文件数
 * @param assetIndexId   资源索引 id
 * @param versionJson    写入游戏目录的版本 JSON 路径
 * @param elapsedMillis  总耗时
 */
public record InstallResult(String versionId,
                            DownloadReport downloadReport,
                            int extractedNatives,
                            String assetIndexId,
                            Path versionJson,
                            long elapsedMillis) {

    public boolean success() {
        return downloadReport != null && !downloadReport.hasRequiredFailure();
    }

    /** 完整性校验结果。 */
    public record IntegrityReport(String versionId, int total, int missing, List<Path> missingFiles) {

        public boolean complete() {
            return missing == 0;
        }

        /** 缺失占比，用于界面展示「文件完整度」。 */
        public double completeness() {
            return total == 0 ? 1.0 : (total - missing) / (double) total;
        }
    }
}
