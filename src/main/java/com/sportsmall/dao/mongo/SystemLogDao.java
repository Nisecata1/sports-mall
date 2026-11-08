package com.sportsmall.dao.mongo;

import com.mongodb.MongoException;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.model.*;
import com.sportsmall.model.*;
import com.sportsmall.exception.MallDataAccessException;
import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.descending;

/** 登录、成功写请求及明确标注的导入日志；不保存请求正文、密码或哈希。 */
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

    public List<SystemLogView> find(SystemLogFilter filter) {
        List<org.bson.conversions.Bson> conditions = new ArrayList<>();
        if (filter.userId() != null) conditions.add(eq("user_id", filter.userId()));
        if (filter.type() != null) conditions.add(eq("log_type", filter.type()));
        if (filter.level() != null) conditions.add(eq("log_level", filter.level()));
        if (filter.start() != null) conditions.add(gte("timestamp", Date.from(filter.start())));
        if (filter.end() != null) conditions.add(lt("timestamp", Date.from(filter.end())));
        try (var rows = collection.find(conditions.isEmpty() ? new Document() : and(conditions))
                .sort(descending("timestamp", "_id")).limit(200).iterator()) {
            List<SystemLogView> found = new ArrayList<>();
            while (rows.hasNext()) {
                var row = rows.next();
                Object id = row.get("_id"), userId = row.get("user_id");
                var detail = row.get("action_detail", Document.class);
                Date time = row.getDate("timestamp");
                found.add(new SystemLogView(id instanceof org.bson.types.ObjectId objectId ? objectId.toHexString() : String.valueOf(id),
                        userId instanceof Number number ? number.longValue() : null, row.getString("log_type"), row.getString("log_level"),
                        row.getString("message"), detail == null ? null : detail.getString("operation"), time == null ? null : time.toInstant()));
            }
            return List.copyOf(found);
        } catch (MongoException failure) { throw new MallDataAccessException("SYSTEM_LOG_QUERY_FAILED", "系统日志查询失败，请检查MongoDB连接。"); }
    }

    public void recordOperation(long userId, String method, String endpoint, String ip) {
        try {
            var result = collection.insertOne(operationDocument(userId, method, endpoint, ip, Instant.now()));
            if (!result.wasAcknowledged()) throw new MallDataAccessException("SYSTEM_LOG_AUDIT_FAILED", "业务请求日志未确认保存。");
        } catch (MongoException failure) { throw new MallDataAccessException("SYSTEM_LOG_AUDIT_FAILED", "业务请求日志未确认保存。"); }
    }

    static Document operationDocument(long userId, String method, String endpoint, String ip, Instant timestamp) {
        return new Document("user_id", userId).append("log_type", "OPERATION").append("log_level", "INFO")
                .append("message", "已完成商城业务写请求")
                .append("action_detail", new Document("ip", ip).append("operation", method + " " + endpoint)
                        .append("source", "HTTP_BACKEND").append("http_status", 200))
                .append("timestamp", Date.from(timestamp));
    }

    public void importRows(long actor, String batchId, List<LogImportRow> rows) {
        List<WriteModel<Document>> writes = new ArrayList<>();
        for (var row : rows) {
            String id = "import:" + batchId + ":" + row.rowNo();
            var entry = row.entry();
            var document = new Document("user_id", entry.userId()).append("log_type", "IMPORT")
                    .append("log_level", entry.level()).append("message", entry.message())
                    .append("timestamp", Date.from(entry.timestamp()))
                    .append("action_detail", new Document("operation", entry.operation()).append("source", "COURSE_IMPORT")
                            .append("imported_by", actor).append("batch_id", batchId).append("row_no", row.rowNo()));
            writes.add(new UpdateOneModel<>(eq("_id", id), new Document("$setOnInsert", document), new UpdateOptions().upsert(true)));
        }
        if (writes.isEmpty()) return;
        try {
            if (!collection.bulkWrite(writes, new BulkWriteOptions().ordered(false)).wasAcknowledged())
                throw new MallDataAccessException("SYSTEM_LOG_IMPORT_UNCONFIRMED", "MongoDB导入写入未确认。");
        } catch (MongoException failure) { throw new MallDataAccessException("SYSTEM_LOG_IMPORT_UNCONFIRMED", "MongoDB导入写入未确认。"); }
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
