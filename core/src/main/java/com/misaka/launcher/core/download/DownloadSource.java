package com.misaka.launcher.core.download;

import java.util.Map;

/**
 * 可插拔的下载源。
 * <p>核心思路是 <b>URL 改写</b>：上游（版本元数据、模组 API）给出的永远是官方地址，
 * 下载源负责把官方地址映射为等价镜像地址；无法映射时返回 {@code null}，
 * 表示该源不提供此资源，由 {@link SourceResolver} 跳过并尝试下一个源。</p>
 * <p>这一设计让「同一份安装逻辑适配所有镜像」成为可能，也让新增镜像只需实现本接口。</p>
 */
public interface DownloadSource {

    /** 稳定标识，例如 {@code official} / {@code bmclapi} / {@code custom}。 */
    String id();

    /** 面向用户的名称。 */
    String name();

    /** 默认优先级，数值越小越优先；{@code auto} 模式下的初始排序依据。 */
    int priority();

    /**
     * 用于测速与可用性探测的 URL；返回 {@code null} 表示该源不参与测速
     * （例如自定义源未配置时）。
     */
    String probeUrl();

    /**
     * 把官方 URL 改写为本源的等价 URL。
     *
     * @param officialUrl 上游给出的官方地址
     * @param type        资源类型
     * @param context     可选上下文，常见键：{@code versionId}、{@code hash}
     * @return 改写后的地址；本源码无法提供时返回 {@code null}
     */
    String rewrite(String officialUrl, DownloadTargetType type, Map<String, String> context);
}
