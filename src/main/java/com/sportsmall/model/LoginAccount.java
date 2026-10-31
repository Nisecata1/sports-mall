package com.sportsmall.model;

/** 业务层内部使用的登录凭据；显示用户身份时使用不含哈希的 AuthenticatedUser。 */
public record LoginAccount(long userId, String username, String passwordHash, String role, int status) {
    @Override
    public String toString() {
        // 避免调试输出 record 时意外暴露哈希。
        return "LoginAccount[userId=" + userId + ", role=" + role + ", status=" + status + "]";
    }
}
