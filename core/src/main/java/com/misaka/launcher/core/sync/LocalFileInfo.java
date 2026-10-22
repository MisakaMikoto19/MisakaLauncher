package com.misaka.launcher.core.sync;

import java.nio.file.Path;

/**
 * 本地文件条目（扫描结果）。
 *
 * @param fileKey 协议键，形如 {@code ./mods/xxx.jar}（相对同步根目录，正斜杠，带 {@code ./} 前缀）
 * @param path    实际本地路径
 * @param size    字节数
 * @param modifiedTime 最后修改时间（毫秒）
 * @param md5     MD5 十六进制小写
 */
public record LocalFileInfo(String fileKey, Path path, long size, long modifiedTime, String md5) {
}
