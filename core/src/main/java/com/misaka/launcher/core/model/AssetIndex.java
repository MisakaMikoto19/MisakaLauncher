package com.misaka.launcher.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 资源索引（{@code assets/indexes/<id>.json}）。
 * <p>对象按内容哈希寻址：路径为 {@code assets/objects/<hash前两位>/<hash>}，
 * 因此多个版本、多个实例可以安全共享同一份 {@code assets} 目录，无需重复下载。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AssetIndex {

    public Map<String, AssetObject> objects = new LinkedHashMap<>();
    public boolean virtual;
    public boolean map_to_resources;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AssetObject {
        /** SHA-1，同时作为文件名。 */
        public String hash = "";
        public long size = 0L;
    }

    public int size() {
        return objects == null ? 0 : objects.size();
    }
}
