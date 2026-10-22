package com.misaka.launcher.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个版本的完整描述（版本 JSON）。
 * <p>字段设计刻意保持「宽进」：Mojang 在不同年代使用了差异极大的结构
 * （1.13 之前用 {@code minecraftArguments} 字符串，之后改为 {@code arguments} 对象；
 * 加载器 profile 通过 {@code inheritsFrom} 复用原版信息），因此这里把两种形态都保留下来，
 * 由安装与启动逻辑按版本分别处理。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VersionDetail {

    public String id = "";
    public String type = "release";
    public String mainClass = "net.minecraft.client.main.Main";

    /** 1.12 及更早：以空格分隔的参数字符串。 */
    public String minecraftArguments;

    /** 1.13 及更新：结构化参数（元素为 String 或条件对象）。 */
    public Arguments arguments;

    /** 资源索引 id，例如 {@code 1.20}、{@code pre-1.6}。 */
    public String assets = "";

    public AssetIndexInfo assetIndex;
    public Map<String, DownloadInfo> downloads = new LinkedHashMap<>();
    public List<Library> libraries = new ArrayList<>();
    public JavaVersionInfo javaVersion;
    public LoggingInfo logging;

    /** 加载器 profile 通过该字段指向原版版本。 */
    public String inheritsFrom;
    public Integer minimumLauncherVersion;
    public int complianceLevel = 0;
    public String releaseTime = "";
    public String time = "";

    /** 该版本 JSON 自身在磁盘上的位置（不参与序列化，由安装流程回填）。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public transient java.nio.file.Path sourceJson;

    // ------------------------------------------------------------------

    /**
     * 结构化参数（1.13+）。
     * <p>元素可能是纯字符串，也可能是带 {@code rules} 的条件对象，因此这里保留为
     * {@code JsonNode}（混合类型数组无法安全映射为具体 POJO）。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Arguments {
        public List<com.fasterxml.jackson.databind.JsonNode> game = new ArrayList<>();
        public List<com.fasterxml.jackson.databind.JsonNode> jvm = new ArrayList<>();
    }

    /**
     * 通用下载条目。
     *
     * @param path 仅运行库使用：相对 {@code libraries/} 的路径
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DownloadInfo {
        public String id = "";
        public String url = "";
        public String sha1 = "";
        public long size = 0L;
        public String path = "";

        public boolean isUsable() {
            return url != null && !url.isBlank();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AssetIndexInfo extends DownloadInfo {
        public long totalSize = 0L;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JavaVersionInfo {
        public String component = "jre-legacy";
        public int majorVersion = 8;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LoggingInfo {
        public LoggingClient client;

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class LoggingClient {
            /** 形如 {@code -Dlog4j.configurationFile=${path}}。 */
            public String argument = "";
            public DownloadInfo file;
            public String type = "log4j2-xml";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Library {
        /** Maven 坐标。 */
        public String name = "";
        public LibraryDownloads downloads;
        public List<Rule> rules;
        /** 平台相关 classifier 映射，例如 {@code {"windows": "natives-windows"}}。 */
        public Natives natives;
        public Extract extract;
        /** 非官方仓库根地址（Forge / Fabric 等使用）。 */
        public String url;
        /**
         * 库级别的校验信息。
         * <p>Fabric / Quilt 的 profile 不使用 {@code downloads} 结构，而是把
         * {@code sha1} 与 {@code size} 直接放在库对象上，因此必须单独保留，
         * 否则这些运行库将失去完整性校验。</p>
         */
        public String sha1 = "";
        public String md5 = "";
        public long size = 0L;
        public String checksums;
        public String serverreq;
        public String clientreq;

        public boolean hasRules() {
            return rules != null && !rules.isEmpty();
        }

        public boolean hasNatives() {
            return natives != null && natives.hasAny();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LibraryDownloads {
        public DownloadInfo artifact;
        /** key 为平台 natives 标识，例如 {@code natives-windows}。 */
        public Map<String, DownloadInfo> classifiers;

        /** 返回实际存在且可下载的 artifact（部分元数据仅用占位符占位）。 */
        public DownloadInfo usableArtifact() {
            if (artifact == null || !artifact.isUsable()) {
                return null;
            }
            // Mojang 对无 artifact 的条目使用 "artifact": {...} 占位（url 为空）
            return artifact;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Natives {
        public String windows;
        public String linux;
        public String osx;
        public String osx_arm64;

        public boolean hasAny() {
            return windows != null || linux != null || osx != null || osx_arm64 != null;
        }

        public String forOs(String osName, boolean aarch64) {
            if ("osx".equals(osName) && aarch64 && osx_arm64 != null) {
                return osx_arm64;
            }
            return switch (osName) {
                case "windows" -> windows;
                case "linux" -> linux;
                case "osx" -> osx;
                default -> null;
            };
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Extract {
        public List<String> exclude = new ArrayList<>();
    }

    /**
     * 平台 / 特性条件规则。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Rule {
        /** {@code allow} 或 {@code disallow}。 */
        public String action = "allow";
        public OsRule os;
        /** 特性开关，例如 {@code {"is_demo_user": true}}。 */
        public Map<String, Boolean> features;

        public boolean isAllow() {
            return "allow".equals(action);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OsRule {
        public String name;
        public String arch;
        /** 正则，匹配 {@code os.version}。 */
        public String version;
    }

    /** 便捷查询：下载项。 */
    public DownloadInfo download(String key) {
        return downloads == null ? null : downloads.get(key);
    }

    public DownloadInfo clientJar() {
        return download("client");
    }

    public DownloadInfo serverJar() {
        return download("server");
    }

    /** 该版本是否使用旧式参数字符串。 */
    public boolean usesLegacyArguments() {
        return arguments == null || (arguments.game.isEmpty() && arguments.jvm.isEmpty());
    }

    /** 资源索引 id；部分非常古老的版本缺失该字段。 */
    public String assetIndexId() {
        if (assetIndex != null && assetIndex.id != null && !assetIndex.id.isBlank()) {
            return assetIndex.id;
        }
        return assets;
    }

    public String effectiveId() {
        return id == null || id.isBlank() ? "" : id;
    }
}
