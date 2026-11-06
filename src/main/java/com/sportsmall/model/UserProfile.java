package com.sportsmall.model;

/** profileId为空表示尚未建立档案，userId始终来自后端确认的账号。 */
public record UserProfile(long userId, Long profileId, String realName, String idCard,
                          String address, String notes) {
}
