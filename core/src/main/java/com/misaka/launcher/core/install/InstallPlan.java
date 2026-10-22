package com.misaka.launcher.core.install;

import com.misaka.launcher.core.download.DownloadItem;
import com.misaka.launcher.core.model.VersionDetail;

import java.nio.file.Path;
import java.util.List;

/**
 * 一次安装的完整计划（纯数据，不做 IO）。
 * <p>把「计划」与「执行」分离有两个好处：安装前可以先展示将要下载的内容与体积；
 * 完整性校验可以复用同一份计划，只比对本就不需要重新计算。</p>
 *
 * @param versionId     目标版本 id
 * @param detail        该版本的描述（原版或加载器合并后的结果）
 * @param coreItems     启动必需文件：客户端 jar、运行库、日志配置
 * @param assetItems    资源对象（体积占大头，单独成批以便分阶段展示进度）
 * @param natives       需要解压到版本隔离目录的原生库
 * @param assetIndexId  资源索引 id；为空表示该版本无资源索引
 * @param virtualAssets 是否需要构建 legacy 虚拟资源目录
 * @param mapToResources 是否需要把资源映射到 {@code resources/}（1.6 以前的旧行为）
 */
public record InstallPlan(String versionId,
                          VersionDetail detail,
                          List<DownloadItem> coreItems,
                          List<DownloadItem> assetItems,
                          List<NativeArchive> natives,
                          String assetIndexId,
                          boolean virtualAssets,
                          boolean mapToResources) {

    /** 全部下载条目。 */
    public List<DownloadItem> allItems() {
        List<DownloadItem> all = new java.util.ArrayList<>(coreItems.size() + assetItems.size());
        all.addAll(coreItems);
        all.addAll(assetItems);
        return all;
    }

    /** 计划下载的总字节数（长度未知的条目按 0 计）。 */
    public long totalBytes() {
        return allItems().stream().mapToLong(item -> Math.max(0L, item.size())).sum();
    }

    /**
     * 需要解压的原生库。
     *
     * @param archive  已下载的 jar 路径
     * @param excludes 解压时排除的前缀（对应元数据 {@code extract.exclude}）
     */
    public record NativeArchive(Path archive, List<String> excludes) {
    }
}
