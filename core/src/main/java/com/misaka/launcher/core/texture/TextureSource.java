package com.misaka.launcher.core.texture;

import java.io.IOException;
import java.util.List;

/**
 * 一个可枚举资源的来源：mod jar、资源包 zip、或解压出来的资源包文件夹。
 *
 * <p>三个列表在构造时一次性枚举好（zip 只遍历一遍），后续判定与读取都走这里 ——
 * 一个整合包可能有几十个 jar、上万张 PNG，逐次遍历 zip 目录会慢得离谱。</p>
 */
interface TextureSource extends AutoCloseable {

    /** 人类可读的来源名（jar 文件名或目录名），用于日志。 */
    String description();

    /** 全部 {@code assets/*&#47;textures/**&#47;*.png}（相对路径，正斜杠）。 */
    List<String> pngAssetPaths();

    /** 全部模型 JSON：{@code assets/*&#47;models/**&#47;*.json}。 */
    List<String> modelJsonPaths();

    /** 全部图集定义：{@code assets/*&#47;atlases/*&#47;.json}。 */
    List<String> atlasDefinitionPaths();

    /** 是否含指定路径。 */
    boolean has(String assetPath);

    /** 读取内容；不存在返回 {@code null}。{@code maxBytes} 用于只读文件头的场景。 */
    byte[] read(String assetPath, int maxBytes) throws IOException;

    @Override
    void close();
}
