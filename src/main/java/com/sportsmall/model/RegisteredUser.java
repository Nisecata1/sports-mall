package com.sportsmall.model;

/** 注册成功后返回的账号摘要，不携带密码或密码哈希。 */
public record RegisteredUser(long userId, String username, String email) {
}
