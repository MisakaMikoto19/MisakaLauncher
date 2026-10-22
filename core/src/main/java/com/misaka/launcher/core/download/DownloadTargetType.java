package com.misaka.launcher.core.download;

/**
 * 下载资源类型。
 * <p>用于两点：一是让下载源判断自己能否提供该资源；二是让改写逻辑拿到足够上下文
 * （例如版本 JSON 与客户端 jar 需要知道版本号才能映射到镜像地址）。</p>
 */
public enum DownloadTargetType {

    /** 版本清单（version_manifest）。 */
    VERSION_MANIFEST,
    /** 单个版本的 JSON 描述。 */
    VERSION_JSON,
    /** 客户端 / 服务端主 jar。 */
    GAME_JAR,
    /** 运行库（libraries）。 */
    LIBRARY,
    /** 资源索引（assetIndex）。 */
    ASSET_INDEX,
    /** 资源对象（assets/objects）。 */
    ASSET_OBJECT,
    /** 日志配置（logging config）。 */
    LOGGING_CONFIG,
    /** 模组加载器元数据接口（Fabric/Quilt 版本列表等）。 */
    LOADER_META,
    /** 模组加载器安装器（Forge/NeoForge installer）。 */
    LOADER_INSTALLER,
    /** 游戏用 Java 运行时压缩包。 */
    JAVA_RUNTIME,
    /** 模组 / 整合包 / 资源包 / 光影文件。 */
    MOD_FILE,
    /** 其他。 */
    OTHER
}
