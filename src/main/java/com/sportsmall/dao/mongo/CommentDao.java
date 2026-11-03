package com.sportsmall.dao.mongo;

import com.mongodb.MongoException;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.InsertOneResult;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.CommentView;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import static com.mongodb.client.model.Filters.eq;
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
