package com.sportsmall.dao.mongo;

import com.mongodb.MongoException;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.ActionLogView;
import com.sportsmall.model.HotItemStatistics;
import com.sportsmall.model.UserActivityStatistics;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/** action_logs保存实际浏览；热门排行由时间窗口内的VIEW行为计算。 */
public final class ActionLogDao {
    private final MongoCollection<Document> collection;

    public ActionLogDao(MongoDatabase database) {
        collection = Objects.requireNonNull(database).getCollection("action_logs")
                .withWriteConcern(WriteConcern.ACKNOWLEDGED);
    }

    public ActionLogView recordView(long userId, long itemId, long durationSeconds) {
        Document document = viewDocument(new ObjectId(), userId, itemId, durationSeconds, Instant.now());
        String id = document.getObjectId("_id").toHexString();
        try {
            if (!collection.insertOne(document).wasAcknowledged()) {
                throw unconfirmed(id);
            }
            return new ActionLogView(id, userId, itemId, durationSeconds, document.getDate("created_at").toInstant());
        } catch (MongoException exception) {
            throw unconfirmed(id);
        }
    }

    public List<HotItemStatistics> hotItems(Instant fromInclusive, Instant throughInclusive, int limit) {
        Objects.requireNonNull(fromInclusive);
        Objects.requireNonNull(throughInclusive);
        if (fromInclusive.isAfter(throughInclusive) || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("热门排行的时间范围或数量无效");
        }
        try (MongoCursor<Document> rows = collection.aggregate(hotPipeline(fromInclusive, throughInclusive, limit)).iterator()) {
            List<HotItemStatistics> result = new ArrayList<>();
            while (rows.hasNext()) {
                result.add(mapHotItem(rows.next()));
            }
            return List.copyOf(result);
        } catch (MongoException exception) {
            throw new MallDataAccessException("HOT_RANKING_FAILED", "无法读取热门排行，请检查MongoDB连接与action_logs集合。");
        }
    }

    public UserActivityStatistics activityForUser(long userId, Instant fromInclusive, Instant throughInclusive) {
        Objects.requireNonNull(fromInclusive);
        Objects.requireNonNull(throughInclusive);
        if (userId < 1 || fromInclusive.isAfter(throughInclusive)) {
            throw new IllegalArgumentException("用户报告的编号或时间范围无效");
        }
        try {
            Document row = collection.aggregate(activityPipeline(userId, fromInclusive, throughInclusive)).first();
            return row == null ? UserActivityStatistics.empty(userId) : mapActivity(row, userId);
        } catch (MongoException exception) {
            throw new MallDataAccessException("USER_ACTIVITY_QUERY_FAILED", "无法读取用户活动报告，请检查MongoDB连接与action_logs集合。");
        }
    }

    static List<Document> activityPipeline(long userId, Instant from, Instant through) {
        Document valid = new Document("$and", List.of(validViewExpression(),
                new Document("$eq", List.of(new Document("$type", "$user_id"), "long"))));
        Document day = new Document("$dateToString", new Document("format", "%Y-%m-%d")
                .append("date", "$created_at").append("timezone", "Asia/Shanghai"));
        return List.of(
                new Document("$match", new Document("user_id", userId).append("action_type", "VIEW")
                        .append("created_at", new Document("$gte", Date.from(from)).append("$lte", Date.from(through)))),
                new Document("$group", new Document("_id", "$user_id")
                        .append("view_count", new Document("$sum", 1))
                        .append("duration_seconds", new Document("$sum", "$duration_seconds"))
                        .append("item_ids", new Document("$addToSet", "$item_id"))
                        .append("active_days", new Document("$addToSet", day))
                        .append("last_viewed_at", new Document("$max", "$created_at"))
                        .append("invalid_count", new Document("$sum", new Document("$cond", List.of(valid, 0, 1))))),
                new Document("$project", new Document("_id", 1).append("view_count", 1).append("duration_seconds", 1)
                        .append("last_viewed_at", 1).append("invalid_count", 1)
                        .append("distinct_item_count", new Document("$size", "$item_ids"))
                        .append("active_day_count", new Document("$size", "$active_days"))));
    }

    static UserActivityStatistics mapActivity(Document document, long expectedUserId) {
        long userId = activityInteger(document.get("_id"));
        long count = activityInteger(document.get("view_count"));
        long distinct = activityInteger(document.get("distinct_item_count"));
        long days = activityInteger(document.get("active_day_count"));
        long duration = activityInteger(document.get("duration_seconds"));
        if (userId != expectedUserId || count < 1 || distinct < 1 || distinct > count || days < 1 || days > count
                || activityInteger(document.get("invalid_count")) != 0
                || !(document.get("last_viewed_at") instanceof Date last)) {
            throw invalidActivity();
        }
        return new UserActivityStatistics(userId, count, distinct, days, duration, last.toInstant());
    }

    private static long activityInteger(Object value) {
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0) {
            throw invalidActivity();
        }
        return ((Number) value).longValue();
    }

    private static MallDataAccessException invalidActivity() {
        return new MallDataAccessException("USER_ACTIVITY_DATA_INVALID", "用户活动统计含无效字段或不一致结果，请检查action_logs。");
    }

    static Document viewDocument(ObjectId id, long userId, long itemId, long durationSeconds, Instant createdAt) {
        if (userId < 1 || itemId < 1 || durationSeconds < 0) {
            throw new IllegalArgumentException("浏览用户、商品或时长无效");
        }
        return new Document("_id", Objects.requireNonNull(id)).append("user_id", userId).append("item_id", itemId)
                .append("action_type", "VIEW").append("duration_seconds", durationSeconds)
                .append("client_info", new Document("client_type", "CLI").append("ip", "127.0.0.1"))
                .append("created_at", Date.from(Objects.requireNonNull(createdAt)));
    }

    static List<Document> hotPipeline(Instant from, Instant through, int limit) {
        Document valid = validViewExpression();
        return List.of(
                new Document("$match", new Document("action_type", "VIEW")
                        .append("created_at", new Document("$gte", Date.from(from)).append("$lte", Date.from(through)))),
                new Document("$group", new Document("_id", "$item_id")
                        .append("view_count", new Document("$sum", 1))
                        .append("duration_seconds", new Document("$sum", "$duration_seconds"))
                        .append("invalid_count", new Document("$sum", new Document("$cond", List.of(valid, 0, 1))))),
                new Document("$sort", new Document("view_count", -1).append("duration_seconds", -1).append("_id", 1)),
                new Document("$limit", limit));
    }

    private static Document validViewExpression() {
        return new Document("$and", List.of(
                new Document("$eq", List.of(new Document("$type", "$item_id"), "long")),
                new Document("$gt", List.of("$item_id", 0)),
                new Document("$eq", List.of(new Document("$type", "$duration_seconds"), "long")),
                new Document("$gte", List.of("$duration_seconds", 0))));
    }

    static HotItemStatistics mapHotItem(Document document) {
        long itemId = nonnegativeInteger(document.get("_id"));
        long count = nonnegativeInteger(document.get("view_count"));
        long duration = nonnegativeInteger(document.get("duration_seconds"));
        if (itemId < 1 || count < 1 || nonnegativeInteger(document.get("invalid_count")) != 0) {
            throw invalidRanking();
        }
        return new HotItemStatistics(itemId, count, duration);
    }

    private static long nonnegativeInteger(Object value) {
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0) {
            throw invalidRanking();
        }
        return ((Number) value).longValue();
    }

    private static MallDataAccessException invalidRanking() {
        return new MallDataAccessException("HOT_RANKING_DATA_INVALID", "热门排行存在不合设计的商品编号或浏览时长，请检查action_logs。");
    }

    private static MallDataAccessException unconfirmed(String id) {
        return new MallDataAccessException("ACTION_LOG_UNCONFIRMED", "本次浏览记录保存结果未确认，未自动重试；行为编号=" + id);
    }
}
