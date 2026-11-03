package com.sportsmall.model;

import java.math.BigDecimal;

/** 两个MySQL视图的本人概览，金额只累计状态为已完成的历史订单。 */
public record AccountOverview(long userId, String username, String realName, String address,
                              long orderCount, long pendingCount, long completedCount, long cancelledCount,
                              BigDecimal completedAmount) {
}
