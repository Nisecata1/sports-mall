package com.sportsmall.dao.mongo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.MongoException;
import com.mongodb.WriteConcern;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.ItemDetail;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.mongodb.client.model.Filters.eq;

/** 按item_id维护同一份MongoDB详情；仅更新受管理字段，保留_id和额外元数据。 */
public final class ItemDetailDao {
    private final MongoCollection<Document> collection;

    public ItemDetailDao(MongoDatabase database) {
        collection = Objects.requireNonNull(database).getCollection("item_details")
                .withWriteConcern(WriteConcern.ACKNOWLEDGED);
    }

    public Optional<ItemDetail> findByItemId(long itemId) {
        if (itemId < 1) {
            throw new IllegalArgumentException("商品编号必须大于0");
        }
        Document document = collection.find(eq("item_id", itemId)).first();
        return document == null ? Optional.empty() : Optional.of(mapDocument(document));
    }

    public ItemDetail save(ItemDetail detail) {
        try {
            requireUniqueItemIndex();
            Document changed = collection.findOneAndUpdate(eq("item_id", detail.itemId()), updateDocument(detail),
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
            if (changed == null || !detail.equals(mapDocument(changed))) throw unconfirmedSave();
            return mapDocument(changed);
        } catch (MongoException | IllegalArgumentException failure) {
            throw unconfirmedSave();
        }
    }

    public boolean deleteByItemId(long itemId) {
        try {
            requireUniqueItemIndex();
            var result = collection.deleteOne(eq("item_id", itemId));
            if (!result.wasAcknowledged()) throw unconfirmedDelete();
            return result.getDeletedCount() == 1;
        } catch (MongoException failure) {
            throw unconfirmedDelete();
        }
    }

    static Document updateDocument(ItemDetail detail) {
        return new Document("$set", new Document("description", detail.description())
                .append("images", detail.images()).append("metadata.language", detail.language()))
                .append("$setOnInsert", new Document("item_id", detail.itemId()));
    }

    private void requireUniqueItemIndex() {
        try (var indexes = collection.listIndexes().iterator()) {
            while (indexes.hasNext()) {
                Document index = indexes.next();
                Document key = index.get("key", Document.class);
                if (key != null && key.size() == 1 && key.get("item_id") instanceof Number direction
                        && direction.intValue() == 1 && Boolean.TRUE.equals(index.get("unique"))
                        && !Boolean.TRUE.equals(index.get("sparse")) && !index.containsKey("partialFilterExpression")) return;
            }
        }
        throw new MallDataAccessException("ITEM_DETAIL_INDEX_REQUIRED", "商品详情的唯一性约束未就绪，请联系维护者检查item_details的item_id唯一索引。");
    }

    private static MallDataAccessException unconfirmedSave() {
        return new MallDataAccessException("ITEM_DETAIL_SAVE_UNCONFIRMED", "商品详情保存结果未确认，请重新读取详情或查询item_details，再决定是否重试。");
    }
    private static MallDataAccessException unconfirmedDelete() {
        return new MallDataAccessException("ITEM_DETAIL_DELETE_UNCONFIRMED", "商品详情删除结果未确认，请重新读取详情或查询item_details，再决定是否重试。");
    }

    static ItemDetail mapDocument(Document document) {
        if (!(document.get("item_id") instanceof Long itemId)
                || !(document.get("description") instanceof String description)
                || !(document.get("images") instanceof List<?> rawImages)
                || !(document.get("metadata") instanceof Document metadata)
                || !(metadata.get("language") instanceof String language)) {
            throw new IllegalArgumentException("商品详情的 BSON 字段缺失或类型错误");
        }
        List<String> images = new ArrayList<>();
        for (Object image : rawImages) {
            if (!(image instanceof String imageText)) {
                throw new IllegalArgumentException("商品详情的图片列表必须只包含字符串");
            }
            images.add(imageText);
        }
        return new ItemDetail(itemId, description, images, language);
    }
}
