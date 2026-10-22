package com.misaka.launcher.core.auth;

/**
 * 微软设备代码流的一次会话信息。
 * <p>用户需要拿着 {@code userCode} 去 {@code verificationUri} 输入并完成授权，
 * 启动器在此期间轮询令牌接口。</p>
 *
 * @param deviceCode      启动器轮询用的设备码（不应展示给用户手工输入）
 * @param userCode        用户输入用的短码，例如 {@code ABCD-EFGH}
 * @param verificationUri 用户访问的授权地址
 * @param expiresInSeconds 会话有效期
 * @param intervalSeconds 轮询间隔
 */
public record DeviceCodeFlow(String deviceCode,
                            String userCode,
                            String verificationUri,
                            int expiresInSeconds,
                            int intervalSeconds) {
}
