package com.sportsmall.model;

/** 管理员列表只展示身份与状态，不传输密码哈希、邮箱、电话或档案。 */
public record AdminUserSummary(long userId, String username, String role, int status) {
}
