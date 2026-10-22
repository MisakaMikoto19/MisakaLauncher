package com.misaka.launcher.core.auth;

import java.io.IOException;

/**
 * 认证相关错误。
 * <p>刻意把「可操作的指引」放进异常信息：认证失败绝大多数是配置或账户状态问题，
 * 用户需要知道下一步做什么，而不是看到一串错误码。</p>
 */
public class AuthException extends IOException {

    public AuthException(String message) {
        super(message);
    }

    public AuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
