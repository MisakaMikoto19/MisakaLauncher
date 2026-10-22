package com.misaka.launcher.core.download;

import java.nio.file.Path;
import java.util.List;

/**
 * 一批下载的结果汇总。
 *
 * @param total        条目总数
 * @param downloaded   实际下载成功的数量
 * @param skipped      因校验通过而跳过的数量
 * @param failed       失败数量
 * @param bytes        实际写入网络的字节数（不含跳过项）
 * @param elapsedMillis 耗时毫秒
 * @param failures     失败明细
 */
public record DownloadReport(int total,
                             int downloaded,
                             int skipped,
                             int failed,
                             long bytes,
                             long elapsedMillis,
                             List<Failure> failures) {

    /**
     * 失败明细。
     *
     * @param target   目标路径
     * @param url      最终尝试的地址
     * @param required 是否为必需项
     * @param reason   失败原因
     */
    public record Failure(Path target, String url, boolean required, String reason) {
    }

    public boolean allSucceeded() {
        return failed == 0;
    }

    /** 必需项是否存在失败。 */
    public boolean hasRequiredFailure() {
        return failures.stream().anyMatch(Failure::required);
    }

    public int succeeded() {
        return downloaded + skipped;
    }

    /** 平均速率（字节/秒），耗时为 0 时返回 0。 */
    public double averageBytesPerSecond() {
        return elapsedMillis <= 0 ? 0 : bytes * 1000.0 / elapsedMillis;
    }

    /**
     * 面向界面的单行摘要。
     * <p>放在模型上而不是各处界面拼接：安装、模组、Java 等多个流程都需要同样的文案，
     * 集中一处可避免口径不一致。</p>
     */
    public String summary() {
        StringBuilder sb = new StringBuilder(64);
        sb.append("共 ").append(total).append(" 个文件：下载 ").append(downloaded)
                .append("，跳过 ").append(skipped);
        if (failed > 0) {
            sb.append("，失败 ").append(failed);
        }
        if (bytes > 0) {
            sb.append("，传输 ").append(com.misaka.launcher.core.util.FileUtils.humanSize(bytes));
        }
        return sb.toString();
    }
}
