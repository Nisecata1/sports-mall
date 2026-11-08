package com.sportsmall.service;

import com.google.gson.*;
import com.sportsmall.api.ApiJson;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.model.LogImportEntry;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** JSONL每行一个对象；整份文件通过校验后才允许首次数据库写入。 */
final class LogImportParser {
    private static final Set<String> FIELDS = Set.of("user_id", "timestamp", "level", "message", "operation");
    static List<LogImportEntry> parse(String text) {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > 65536) throw invalid("文件须为不超过64KiB的UTF-8 JSONL。");
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<LogImportEntry> result = new ArrayList<>();
        int lineNo = 0;
        for (String line : text.split("\\R", -1)) {
            ++lineNo;
            if (line.isBlank()) continue;
            try {
                JsonObject row = ApiJson.GSON.fromJson(line, JsonObject.class);
                if (row == null || !FIELDS.containsAll(row.keySet())) throw new IllegalArgumentException();
                Long userId = null;
                if (row.has("user_id") && !row.get("user_id").isJsonNull()) {
                    if (!row.get("user_id").isJsonPrimitive() || !row.get("user_id").getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
                    userId = row.get("user_id").getAsBigDecimal().longValueExact();
                    if (userId < 1) throw new IllegalArgumentException();
                }
                String level = string(row, "level", 5);
                if (!Set.of("INFO", "WARN", "ERROR").contains(level)) throw new IllegalArgumentException();
                Instant timestamp = Instant.parse(string(row, "timestamp", 40));
                if (timestamp.isBefore(Instant.parse("1900-01-01T00:00:00Z")) || !timestamp.isBefore(Instant.parse("9999-01-01T00:00:00Z"))) throw new IllegalArgumentException();
                result.add(new LogImportEntry(userId, timestamp, level, string(row, "message", 500), string(row, "operation", 100)));
                if (result.size() > 200) throw invalid("一批最多200条日志。");
            } catch (JsonParseException | IllegalArgumentException | ArithmeticException | DateTimeException failure) {
                throw invalid("第" + lineNo + "行格式错误：请检查字段、正整数用户编号、ISO时间、级别和文字长度。");
            }
        }
        if (result.isEmpty()) throw invalid("文件至少包含一条日志。");
        return List.copyOf(result);
    }
    static String batchId(long actor, List<LogImportEntry> entries) {
        try {
            byte[] canonical = (actor + "\n" + ApiJson.GSON.toJson(entries)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String string(JsonObject row, String key, int max) {
        var value = row.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
        String text = value.getAsString().strip();
        if (text.isBlank() || text.length() > max || text.chars().anyMatch(c -> c < 32)) throw new IllegalArgumentException();
        return text;
    }
    private static MallBusinessException invalid(String message) { return new MallBusinessException("LOG_IMPORT_INVALID", message); }
}
