package com.misaka.launcher.core.sync;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 服务端文件条目（{@code GET /api/file_list} 的数组元素）。
 * <p>字段与 Flask 服务端 {@code FileScanner.scan_directory} 产出的字典一一对应：
 * {@code {path, real_path, size, modified_time, hash, mode}}。</p>
 * <p>关键点：{@code path} 用于拼下载地址，{@code real_path} 用于计算客户端侧的
 * 相对路径（两者在当前服务端实现中相同，但协议上必须区分）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerFileInfo {

    /** 相对服务器工作目录的路径；用于下载地址。 */
    public String path = "";

    /** 相对服务器工作目录的路径；用于映射到客户端目录。 */
    @JsonProperty("real_path")
    public String realPath = "";

    public long size = 0L;

    @JsonProperty("modified_time")
    public double modifiedTime = 0d;

    /** MD5 十六进制小写。 */
    public String hash = "";

    /** 该文件所属服务端目录的同步模式：hard / soft。 */
    public String mode = "soft";

    /** 下载地址所需的路径：优先 path，缺失时回退 real_path。 */
    public String downloadPath() {
        return path == null || path.isBlank() ? realPath : path;
    }

    public String effectiveRealPath() {
        return realPath == null || realPath.isBlank() ? path : realPath;
    }

    @Override
    public String toString() {
        return downloadPath() + " (md5=" + hash + ", size=" + size + ")";
    }
}
