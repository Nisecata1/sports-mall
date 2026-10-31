package com.sportsmall.model;

/** 通过本次登录校验的身份，不携带密码或哈希；后续业务可用此身份检查权限。 */
public record AuthenticatedUser(long userId, String username, String role) {
}
