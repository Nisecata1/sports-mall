package com.sportsmall.dao.mongo;

import com.mongodb.MongoException;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.InsertOneResult;
import com.sportsmall.exception.MallDataAccessException;
import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.Objects;

/** 把业务层给出的登录结果写为 BSON 日志；不接收用户名、密码或哈希。 */
public final class SystemLogDao {
    private final MongoCollection<Document> collection;

    public SystemLogDao(MongoDatabase database) {
        collection = Objects.requireNonNull(database).getCollection("system_logs")
                .withWriteConcern(WriteConcern.ACKNOWLEDGED);
    }

    public void recordLogin(Long userId, boolean succeeded, String sourceIp) {
        Document document = loginDocument(userId, succeeded, sourceIp, Instant.now());
        try {
            InsertOneResult result = collection.insertOne(document);
            if (!result.wasAcknowledged()) {
                throw new MallDataAccessException("LOGIN_LOG_SAVE_FAILED", "登录日志未确认保存，请检查MongoDB后重试。");
            }
        } catch (MongoException exception) {
            throw new MallDataAccessException("LOGIN_LOG_SAVE_FAILED",
                    "登录日志未确认保存，请确认MongoDB服务正在运行且system_logs可写，再重试登录。");
        }
    }

    static Document loginDocument(Long userId, boolean succeeded, String sourceIp, Instant timestamp) {
        return new Document("user_id", userId)
                .append("log_type", "LOGIN")
                .append("log_level", succeeded ? "INFO" : "WARN")
                .append("message", succeeded ? "商城用户登录成功" : "商城用户登录失败")
                .append("action_detail", new Document("ip", Objects.requireNonNull(sourceIp))
                        .append("operation", succeeded ? "LOGIN_SUCCESS" : "LOGIN_FAILED"))
                .append("timestamp", Date.from(Objects.requireNonNull(timestamp)));
    }
}
