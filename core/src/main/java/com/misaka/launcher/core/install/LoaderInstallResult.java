package com.misaka.launcher.core.install;

import com.misaka.launcher.core.download.DownloadReport;
import com.misaka.launcher.core.model.LoaderType;

/**
 * 加载器安装结果。
 *
 * @param versionId     安装后生成的版本 id（即实例应指向的版本）
 * @param loaderType    加载器类型
 * @param loaderVersion 加载器版本
 * @param mode          安装方式：{@code profile}（直接合并 profile）或 {@code installer}（执行官方安装器）
 * @param downloadReport 本流程自身产生的下载统计（Forge 安装器内部下载不计入）
 * @param exitCode      安装器退出码；profile 方式固定为 0
 * @param elapsedMillis 耗时
 */
public record LoaderInstallResult(String versionId,
                                  LoaderType loaderType,
                                  String loaderVersion,
                                  String mode,
                                  DownloadReport downloadReport,
                                  int exitCode,
                                  long elapsedMillis) {

    public static final String MODE_PROFILE = "profile";
    public static final String MODE_INSTALLER = "installer";
    /** 需要用户手动执行安装器（例如 OptiFine）。 */
    public static final String MODE_MANUAL = "manual";

    public boolean success() {
        return exitCode == 0;
    }
}
