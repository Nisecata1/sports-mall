package com.sportsmall.model;

import com.sportsmall.exception.MallBusinessException;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 日期按MySQL订单的创建时间筛选；界面结束日包含当天整日。null表示该条件不限。 */
public record OrderFilter(Integer status, LocalDate startDate, LocalDate endDate) {
    private static final LocalDate MIN_DATE = LocalDate.of(1000, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);

    public OrderFilter {
        if (status != null && (status < 0 || status > 2)) {
            throw new MallBusinessException("INVALID_ORDER_FILTER", "订单状态请选择0、1、2，留空表示全部。");
        }
        if (outsideDatabaseRange(startDate) || outsideDatabaseRange(endDate)) {
            throw new MallBusinessException("INVALID_ORDER_FILTER", "创建日期应在1000-01-01至9999-12-31之间。");
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new MallBusinessException("INVALID_ORDER_FILTER", "开始日期不能晚于结束日期。");
        }
    }

    public static OrderFilter all() {
        return new OrderFilter(null, null, null);
    }

    public LocalDateTime startInclusive() {
        return startDate == null ? null : startDate.atStartOfDay();
    }

    public LocalDateTime endExclusive() {
        // DATETIME不可能超过9999年；最大日期不再加一天，避免生成超出数据库范围的值。
        return endDate == null || endDate.equals(MAX_DATE) ? null : endDate.plusDays(1).atStartOfDay();
    }

    private static boolean outsideDatabaseRange(LocalDate date) {
        return date != null && (date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE));
    }
}
