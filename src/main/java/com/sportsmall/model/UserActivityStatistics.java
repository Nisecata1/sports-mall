package com.sportsmall.model;

import java.time.Instant;

/** 当前用户在指定时间窗的VIEW汇总，活跃日期按Asia/Shanghai去重。 */
public record UserActivityStatistics(long userId, long viewCount, long distinctItemCount,
                                     long activeDayCount, long durationSeconds, Instant lastViewedAt) {
    public static UserActivityStatistics empty(long userId) {
        return new UserActivityStatistics(userId, 0, 0, 0, 0, null);
    }
}
