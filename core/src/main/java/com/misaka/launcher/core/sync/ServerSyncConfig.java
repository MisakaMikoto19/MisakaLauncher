package com.misaka.launcher.core.sync;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端同步配置（{@code GET /api/config} 的响应）。
 * <p>注意：该接口<b>只</b>返回 {@code directories} / {@code redirections} / {@code ignored_files}，
 * 不包含 {@code port} 与 {@code mode}（这两个字段存在于服务端配置文件但不对外暴露），
 * 因此客户端不得假设它们存在。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerSyncConfig {

    public List<SyncDirectory> directories = new ArrayList<>();
    public List<Redirection> redirections = new ArrayList<>();

    @JsonProperty("ignored_files")
    public List<String> ignoredFiles = new ArrayList<>();

    /** 服务端目录及其同步模式。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SyncDirectory {
        public String path = "";
        /** {@code hard} 或 {@code soft}。 */
        public String mode = "soft";

        public boolean isHard() {
            return mode != null && "hard".equalsIgnoreCase(mode.trim());
        }
    }

    /** 目录重定向关系：服务端某目录的内容映射到客户端的另一目录。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Redirection {
        public String from = "";
        public String to = "";
    }

    public boolean isEmpty() {
        return directories.isEmpty() && redirections.isEmpty();
    }
}
