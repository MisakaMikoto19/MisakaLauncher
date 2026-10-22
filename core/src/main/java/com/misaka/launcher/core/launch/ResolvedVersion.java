package com.misaka.launcher.core.launch;

import com.misaka.launcher.core.model.VersionDetail;

import java.nio.file.Path;
import java.util.List;

/**
 * 已完成「继承链合并」的版本描述。
 * <p>加载器 profile 通过 {@code inheritsFrom} 复用原版信息，合并后才是可直接启动的完整描述。</p>
 *
 * @param versionId        启动时使用的版本 id（继承链最末端，即用户选择的那一个）
 * @param detail           合并后的完整描述
 * @param inheritanceChain 继承链上的版本 id，顺序为「自身 → 基础版」
 * @param clientJar        实际使用的客户端主 jar（加载器场景下通常是基础版的 jar）
 */
public record ResolvedVersion(String versionId,
                              VersionDetail detail,
                              List<String> inheritanceChain,
                              Path clientJar) {

    public ResolvedVersion {
        inheritanceChain = List.copyOf(inheritanceChain);
    }

    public boolean hasClientJar() {
        return clientJar != null && java.nio.file.Files.isRegularFile(clientJar);
    }

    /** 是否叠加了加载器（继承链长度大于 1）。 */
    public boolean isModded() {
        return inheritanceChain.size() > 1;
    }
}
