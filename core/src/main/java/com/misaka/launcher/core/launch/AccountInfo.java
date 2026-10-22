package com.misaka.launcher.core.launch;

/**
 * 启动游戏所需的账户信息（已解析、可直接用于命令行）。
 * <p>由认证模块产出；核心原则是<b>访问令牌只在内存与命令参数中出现，绝不进入日志</b>。</p>
 *
 * @param username    游戏内玩家名
 * @param uuid        玩家 UUID（不带连字符或带连字符均可，由构建器统一处理）
 * @param accessToken 访问令牌；只可能来自 {@code login_with_xbox} 换来的 Minecraft 令牌
 * @param userType    用户类型：恒为 {@code msa}（微软账户是唯一支持的登录方式）
 * @param xuid        微软账户的 XUID，可为空
 * @param clientId    客户端标识，可为空
 */
public record AccountInfo(String username,
                          String uuid,
                          String accessToken,
                          String userType,
                          String xuid,
                          String clientId) {

    public static final String TYPE_MSA = "msa";

    /** UUID 去掉连字符（原版命令行使用无连字符形式）。 */
    public String uuidWithoutDashes() {
        return uuid == null ? "" : uuid.replace("-", "");
    }

    /** 带连字符的标准 UUID 形式（官方会话档案接口需要）。 */
    public String uuidWithDashes() {
        String raw = uuidWithoutDashes();
        if (raw.length() != 32) {
            return raw;
        }
        return raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12, 16)
                + "-" + raw.substring(16, 20) + "-" + raw.substring(20);
    }
}
