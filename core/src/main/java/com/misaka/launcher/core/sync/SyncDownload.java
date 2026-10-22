package com.misaka.launcher.core.sync;

import java.nio.file.Path;

/**
 * 一条下载任务。
 *
 * @param fileKey    协议键（客户端侧相对路径）
 * @param serverPath 服务端下载地址所需的路径（{@code ServerFileInfo.path}）
 * @param target     落盘目标
 * @param expectedMd5 期望的 MD5
 * @param size       期望字节数
 */
public record SyncDownload(String fileKey, String serverPath, Path target, String expectedMd5, long size) {
}
