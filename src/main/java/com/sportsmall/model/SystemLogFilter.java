package com.sportsmall.model;

import com.sportsmall.exception.MallBusinessException;
import java.time.*;
import java.util.Set;

public record SystemLogFilter(Long userId, String type, String level, LocalDate from, LocalDate through) {
    public SystemLogFilter {
        type = type == null || type.isBlank() ? null : type;
        level = level == null || level.isBlank() ? null : level;
        if (userId != null && userId < 1
                || type != null && !Set.of("LOGIN", "OPERATION", "IMPORT").contains(type)
                || level != null && !Set.of("INFO", "WARN", "ERROR").contains(level)
                || from != null && (from.getYear() < 1900 || from.getYear() > 9998)
                || through != null && (through.getYear() < 1900 || through.getYear() > 9998)
                || from != null && through != null && from.isAfter(through)) {
            throw new MallBusinessException("LOG_FILTER_INVALID", "日志编号、类型、级别或日期范围不正确。");
        }
    }
    public static SystemLogFilter all() { return new SystemLogFilter(null, null, null, null, null); }
    public Instant start() { return from == null ? null : from.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(); }
    public Instant end() { return through == null ? null : through.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(); }
}
