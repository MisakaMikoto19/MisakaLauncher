package com.misaka.launcher.core.java;

import java.io.IOException;

/**
 * Java 运行时发行版提供方。
 * <p>可插拔设计的意义：不同发行版的下载渠道在国内的可达性差异很大，
 * 用户可切换发行版；同时便于后续接入更多发行版（Dragonwell、Corretto 等）。</p>
 */
public interface JavaProvider {

    /** 稳定标识，与配置中的 {@code preferredVendor} 对应。 */
    String id();

    /** 面向用户的名称。 */
    String displayName();

    /** 默认优先级，越小越优先。 */
    int priority();

    /**
     * 解析指定主版本的安装包信息。
     *
     * @param majorVersion 主版本，例如 8 / 17 / 21
     * @return 安装包信息
     * @throws IOException 该发行版无对应平台/版本，或接口不可用
     */
    JavaPackage resolve(int majorVersion) throws IOException;
}
