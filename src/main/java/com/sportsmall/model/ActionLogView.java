package com.sportsmall.model;

import java.time.Instant;

/** 一次已确认保存的商品浏览行为，时长是本次详情页停留的整秒数。 */
public record ActionLogView(String actionId, long userId, long itemId, long durationSeconds, Instant createdAt) {
}
