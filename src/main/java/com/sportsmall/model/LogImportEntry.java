package com.sportsmall.model;

import java.time.Instant;

/** 已通过后端校验的导入行。source/type由后端固定，文件不能伪造真实审计。 */
public record LogImportEntry(Long userId, Instant timestamp, String level, String message, String operation) {}
