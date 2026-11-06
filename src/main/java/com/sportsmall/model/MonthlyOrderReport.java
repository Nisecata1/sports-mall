package com.sportsmall.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 按订单创建月份统计本人订单，金额来自已完成订单的历史金额。 */
public record MonthlyOrderReport(long userId, LocalDate monthStart, long orderCount,
                                 long pendingCount, long completedCount, long cancelledCount,
                                 BigDecimal completedAmount) {
}
