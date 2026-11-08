package com.sportsmall.dao.mongo;

import com.mongodb.MongoException;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.InsertOneResult;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.exception.MallBusinessException;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.sportsmall.model.CommentStatistics;
import com.sportsmall.model.CommentView;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Sorts.descending;

/** 评论独立写入MongoDB；MySQL引用是否存在由调用前的业务层检查。 */
public final class CommentDao {
    private final MongoCollection<Document> collection;

    public CommentDao(MongoDatabase database) {
        collection = Objects.requireNonNull(database).getCollection("comments")
                .withWriteConcern(WriteConcern.ACKNOWLEDGED);
    }

    public CommentView insert(long userId, long itemId, String content, int rating, List<String> tags) {
        Document document = commentDocument(new ObjectId(), userId, itemId, content, rating, tags, Instant.now());
        CommentView saved = mapDocument(document);
        try {
            InsertOneResult result = collection.insertOne(document);
            if (!result.wasAcknowledged()) {
                throw unconfirmed(saved.commentId());
            }
            return saved;
        } catch (MongoException exception) {
            // 网络异常可能发生在服务器已写入之后；不自动重试新增，先让用户查看最新评论。
            throw unconfirmed(saved.commentId());
        }
    }

    public List<CommentView> findLatestByItemId(long itemId, int limit) {
        if (itemId < 1 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("评论查询的商品编号或数量无效");
        }
        try (MongoCursor<Document> rows = collection.find(eq("item_id", itemId))
                .sort(descending("created_at", "_id")).limit(limit).iterator()) {
            List<CommentView> comments = new ArrayList<>();
            while (rows.hasNext()) {
                comments.add(mapDocument(rows.next()));
            }
            return List.copyOf(comments);
        } catch (MongoException exception) {
            throw new MallDataAccessException("COMMENT_QUERY_FAILED", "无法读取评论，请检查MongoDB连接与comments集合。");
        }
    }

    public CommentStatistics statisticsByItemId(long itemId) {
        if (itemId < 1) {
            throw new IllegalArgumentException("统计的商品编号必须大于0");
        }
        try {
            Document result = collection.aggregate(statisticsPipeline(itemId)).first();
            return result == null ? CommentStatistics.empty(itemId) : mapStatistics(result, itemId);
        } catch (MongoException exception) {
            throw new MallDataAccessException("COMMENT_STATISTICS_FAILED", "无法完成评论统计，请检查MongoDB连接与comments集合。");
        }
    }

    public List<CommentView> findByUserId(long userId) {
        return managementList(eq("user_id",userId));
    }
    public List<CommentView> findForManagement() {
        return managementList(new Document());
    }
    private List<CommentView> managementList(org.bson.conversions.Bson filter) {
        try(var rows=collection.find(filter).sort(descending("created_at","_id")).limit(100).iterator()) {
            List<CommentView> result=new ArrayList<>();
            while(rows.hasNext())result.add(mapDocument(rows.next()));
            return List.copyOf(result);
        } catch(MongoException failure) {
            throw new MallDataAccessException("COMMENT_QUERY_FAILED","无法读取评论维护列表，请检查MongoDB连接。");
        }
    }
    public CommentView updateOwned(long owner,String id,String content,int rating,List<String> tags) {
        try {
            // 所属用户与文档编号在同一个写入条件中检查，不使用upsert、不覆盖整份文档。
            Document result=collection.findOneAndUpdate(and(eq("_id",new ObjectId(id)),eq("user_id",owner)),
                    new Document("$set",new Document("content",content).append("rating",rating)
                            .append("tags",new ArrayList<>(tags)).append("updated_at",new Date())),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            if(result==null)throw new MallBusinessException("COMMENT_NOT_FOUND","该评论已不存在或不属于当前账号，请刷新我的评论。");
            return mapDocument(result);
        } catch(MongoException failure) {
            throw new MallDataAccessException("COMMENT_UPDATE_UNCONFIRMED","评论修改结果未确认，请刷新我的评论后再决定是否重试。");
        }
    }
    public boolean delete(String id,Long owner) {
        try {
            var filter=owner==null ? eq("_id",new ObjectId(id)) : and(eq("_id",new ObjectId(id)),eq("user_id",owner));
            var result=collection.deleteOne(filter);
            if(!result.wasAcknowledged())throw new MongoException("Unacknowledged comment deletion");
            return result.getDeletedCount()==1;
        } catch(MongoException failure) {
            throw new MallDataAccessException("COMMENT_DELETE_UNCONFIRMED","评论删除结果未确认，请刷新评论列表后再决定是否重试。");
        }
    }

    /** 一条业务管道：先筛选商品，再由MongoDB计算全部评论，未使用最新评论的20条上限。 */
    static List<Document> statisticsPipeline(long itemId) {
        Document validRating = new Document("$and", List.of(
                new Document("$eq", List.of(new Document("$type", "$rating"), "int")),
                new Document("$in", List.of("$rating", List.of(1, 2, 3, 4, 5)))));
        Document group = new Document("_id", "$item_id")
                .append("comment_count", new Document("$sum", 1))
                .append("average_rating", new Document("$avg", "$rating"))
                // $avg可能忽略非数值；明确记录不合设计的评分，避免把部分均分当作完整结果。
                .append("invalid_rating_count", new Document("$sum", new Document("$cond", List.of(validRating, 0, 1))));
        for (int score = 1; score <= 5; score++) {
            group.append("rating_" + score + "_count", new Document("$sum",
                    new Document("$cond", List.of(new Document("$eq", List.of("$rating", score)), 1, 0))));
        }
        return List.of(new Document("$match", new Document("item_id", itemId)), new Document("$group", group));
    }

    static CommentStatistics mapStatistics(Document document, long expectedItemId) {
        long itemId = nonnegativeInteger(document.get("_id"));
        long count = nonnegativeInteger(document.get("comment_count"));
        if (itemId != expectedItemId || count < 1 || nonnegativeInteger(document.get("invalid_rating_count")) != 0
                || !(document.get("average_rating") instanceof Double average)
                || !Double.isFinite(average) || average < 1 || average > 5) {
            throw invalidStatistics();
        }
        List<Long> distribution = new ArrayList<>();
        long sum = 0;
        double weightedSum = 0;
        for (int score = 1; score <= 5; score++) {
            long value = nonnegativeInteger(document.get("rating_" + score + "_count"));
            distribution.add(value);
            try {
                sum = Math.addExact(sum, value);
            } catch (ArithmeticException exception) {
                throw invalidStatistics();
            }
            weightedSum += (double) value * score;
        }
        if (sum != count || Math.abs(weightedSum / count - average) > 1e-9) {
            throw invalidStatistics();
        }
        return new CommentStatistics(itemId, count, average, distribution);
    }

    private static long nonnegativeInteger(Object value) {
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0) {
            throw invalidStatistics();
        }
        return ((Number) value).longValue();
    }

    private static MallDataAccessException invalidStatistics() {
        return new MallDataAccessException("COMMENT_STATISTICS_DATA_INVALID",
                "评论统计包含不合设计的评分或不一致结果，请检查comments的评分类型及范围。");
    }

    static Document commentDocument(ObjectId id, long userId, long itemId, String content,
                                    int rating, List<String> tags, Instant createdAt) {
        return new Document("_id", Objects.requireNonNull(id))
                .append("user_id", userId)
                .append("item_id", itemId)
                .append("content", content)
                .append("rating", rating)
                .append("tags", List.copyOf(tags))
                .append("created_at", Date.from(Objects.requireNonNull(createdAt)));
    }

    static CommentView mapDocument(Document document) {
        if (!(document.get("_id") instanceof ObjectId id)
                || !(document.get("user_id") instanceof Long userId) || userId < 1
                || !(document.get("item_id") instanceof Long itemId) || itemId < 1
                || !(document.get("content") instanceof String content) || content.isBlank()
                || !(document.get("rating") instanceof Integer rating) || rating < 1 || rating > 5
                || !(document.get("tags") instanceof List<?> rawTags)
                || !(document.get("created_at") instanceof Date createdAt)) {
            throw invalidData();
        }
        List<String> tags = new ArrayList<>();
        for (Object tag : rawTags) {
            if (!(tag instanceof String text) || text.isBlank()) {
                throw invalidData();
            }
            tags.add(text);
        }
        return new CommentView(id.toHexString(), userId, itemId, content, rating, tags, createdAt.toInstant());
    }

    private static MallDataAccessException invalidData() {
        return new MallDataAccessException("COMMENT_DATA_INVALID", "评论文档字段缺失或类型不符合集合设计，请检查comments数据。");
    }

    private static MallDataAccessException unconfirmed(String id) {
        return new MallDataAccessException("COMMENT_SAVE_UNCONFIRMED",
                "评论保存结果未确认，请先查看该商品最新评论，再决定是否重试。此次评论编号=" + id);
    }
}
