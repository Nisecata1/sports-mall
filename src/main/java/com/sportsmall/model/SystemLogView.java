package com.sportsmall.model;

import java.time.Instant;

/** 日志只读投影；导入日志与真实登录/业务日志通过type区分。 */
public record SystemLogView(String logId, Long userId, String type, String level,
                            String message, String operation, Instant timestamp) {}
