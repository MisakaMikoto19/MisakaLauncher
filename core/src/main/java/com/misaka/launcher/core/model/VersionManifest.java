package com.misaka.launcher.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 版本清单（{@code version_manifest_v2.json}）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VersionManifest {

    public Latest latest = new Latest();
    public List<VersionRef> versions = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Latest {
        public String release = "";
        public String snapshot = "";
    }

    /**
     * 单个版本引用。
     *
     * @param id   版本号，例如 {@code 1.20.1}
     * @param type {@code release} / {@code snapshot} / {@code old_beta} / {@code old_alpha}
     * @param url  该版本 JSON 的地址
     * @param sha1 该版本 JSON 的 SHA-1
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VersionRef {
        public String id = "";
        public String type = "release";
        public String url = "";
        public String time = "";
        public String releaseTime = "";
        public String sha1 = "";
        public int complianceLevel = 0;

        public boolean isRelease() {
            return "release".equals(type);
        }

        public boolean isSnapshot() {
            return "snapshot".equals(type);
        }

        /** 远古版本（Alpha/Beta）——部分启动器单独成组展示。 */
        public boolean isOld() {
            return type != null && type.startsWith("old_");
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /** 便捷查询：按 id 找版本引用。 */
    public VersionRef find(String id) {
        if (id == null) {
            return null;
        }
        for (VersionRef ref : versions) {
            if (id.equals(ref.id)) {
                return ref;
            }
        }
        return null;
    }
}
