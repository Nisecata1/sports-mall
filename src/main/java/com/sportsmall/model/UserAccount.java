package com.sportsmall.model;

/** 当前账号的可显示资料，不查询或携带密码哈希。 */
public record UserAccount(long userId, String username, String email, String phone, String role, int status) {
}
